package app.wallhop.ui.components

import androidx.compose.animation.core.animate
import kotlinx.coroutines.Job
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drag-to-dismiss that feels physical, shared by sheets, toasts and popups (Apple's fluid
 * interfaces): the surface follows the finger 1:1 from where it was grabbed, resists softly when
 * pulled the wrong way, and on release a flick decides, by where its momentum would carry it,
 * rather than how far it went. Either way it carries on at the finger's speed, so there is no
 * seam between dragging and animating, and it can be grabbed again mid-flight.
 *
 * [direction] is the way it leaves: 1 down (sheets, toasts), -1 up (things at the top).
 */
@Stable
class SwipeDismiss internal constructor(
    private val scope: CoroutineScope,
    private val density: Float,
    private val direction: Int,
) {
    /**
     * Where the surface is drawn, in px along the drag axis. A plain value, written straight from
     * the finger, so a late finger update can never interrupt the spring that follows a release
     * (that left surfaces stuck part-way).
     */
    var offset by mutableFloatStateOf(0f)
        private set
    /** The spring running after a release; a new touch stops it where it is. */
    private var settling: Job? = null
    /** Where the finger would put it without resistance. */
    private var raw = 0f
    /** The surface's size along the axis, so the decision is relative to it. */
    var extent by mutableFloatStateOf(1f)
    /** Set once it has been thrown off screen: whatever shows it shouldn't animate it out again. */
    var gone by mutableStateOf(false)
        private set

    /** 0 at rest, 1 off screen: what's dimmed behind follows this. */
    val progress: Float get() = (offset * direction / extent.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** Shown again after being thrown away: back at rest. */
    fun reset() {
        settling?.cancel()
        raw = 0f
        gone = false
        offset = 0f
    }

    /** A new drag starts from where the surface is now, even mid-animation. */
    fun grab() {
        settling?.cancel()
        val shown = offset * direction
        raw = if (shown >= 0f) offset else -direction * unRubber(-shown)
    }

    fun drag(delta: Float) {
        settling?.cancel()
        raw += delta
        val along = raw * direction
        offset = if (along >= 0f) raw else -direction * rubber(-along)
    }

    /**
     * The finger let go at [velocity] px/s: thrown far enough (by its projected resting place,
     * Apple's ~0.2 s of momentum) or fast enough, it leaves and [onDismiss] runs once it's off;
     * otherwise it springs back. A flick back the other way always keeps it.
     */
    suspend fun release(velocity: Float, onDismiss: () -> Unit, commitAt: Float = 0.5f) {
        val along = velocity * direction
        val dpPerSec = along / density
        val projected = offset * direction + along * 0.2f
        val leave = dpPerSec > FLICK_DP || (dpPerSec > -KEEP_DP && projected > extent * commitAt)
        val target = if (leave) direction * (extent + 48 * density) else 0f
        if (leave) gone = true else raw = 0f
        settling?.cancel()
        val job = scope.launch {
            animate(offset, target, initialVelocity = velocity, animationSpec = spring(1f, if (leave) 400f else 500f)) { value, _ ->
                offset = value
            }
        }
        settling = job
        job.join()
        // Only once it's actually off screen (not if a new touch caught it on the way).
        if (leave && !job.isCancelled) onDismiss() else if (leave) gone = false
    }

    /** Pulled the wrong way: the further it goes, the less it follows. */
    private fun rubber(x: Float): Float {
        // However big the surface, the wrong way gives a short stretch, never a reveal.
        val d = minOf(extent, STRETCH_DP * density).coerceAtLeast(1f)
        return (1f - 1f / (x * 0.55f / d + 1f)) * d
    }

    private fun unRubber(y: Float): Float {
        val d = minOf(extent, STRETCH_DP * density).coerceAtLeast(1f)
        val k = (1f - (y / d)).coerceAtLeast(0.01f)
        return (1f / k - 1f) * d / 0.55f
    }

    /**
     * Lets scrolling content inside hand over to the drag: once it's scrolled to the top, pulling
     * further moves the surface; pushing it back first returns the surface before scrolling.
     */
    val nestedScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            // Moving back toward rest: the surface goes first, then the content scrolls.
            if (source == NestedScrollSource.UserInput && offset * direction > 0f && dy * direction < 0f) {
                val take = if (abs(dy) > abs(offset)) -offset else dy
                drag(take)
                return Offset(0f, take)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Content can't scroll any further this way: the rest moves the surface.
            if (source == NestedScrollSource.UserInput && available.y * direction > 0f) {
                drag(available.y)
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset * direction <= 0f) return Velocity.Zero
            release(available.y, pendingDismiss)
            return available
        }
    }

    /** What [nestedScroll] calls when a fling from scrolling content throws the surface away. */
    internal var pendingDismiss: () -> Unit = {}

    private companion object {
        /** Fast enough to leave whatever the distance. */
        const val FLICK_DP = 1000f
        /** Moving back this fast always keeps it. */
        const val KEEP_DP = 300f
        /** How far, at most, it stretches when pulled the wrong way. */
        const val STRETCH_DP = 120f
    }
}

@Composable
fun rememberSwipeDismiss(key: Any?, direction: Int = 1, onDismiss: () -> Unit): SwipeDismiss {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val state = remember(key) { SwipeDismiss(scope, density, direction) }
    state.pendingDismiss = onDismiss
    return state
}

/** Hands scrolling content's overscroll to [state] (see [SwipeDismiss.nestedScroll]). */
fun Modifier.swipeDismissScroll(state: SwipeDismiss): Modifier = nestedScroll(state.nestedScroll)
