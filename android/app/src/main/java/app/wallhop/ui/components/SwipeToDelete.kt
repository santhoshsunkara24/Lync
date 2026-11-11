package app.wallhop.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.tappable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Swipe a row left to delete it, as in iOS lists. The row follows the finger and a red Delete
 * opens behind it. Thrown or pulled past half its width it deletes (a tick as it crosses the
 * line, and it carries on at the finger's speed); let go after the button's width and it rests
 * open on Delete; otherwise it closes. Pulled right, it resists. TalkBack gets "Delete" as an
 * action on the row.
 */
@Composable
fun SwipeToDelete(onDelete: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val reduceMotion = Wallhop.a11y.reduceMotion
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val red = Wallhop.colors.red
    val button = with(density) { 88.dp.toPx() }
    var width by remember { mutableFloatStateOf(1f) }
    // Written straight from the finger (no coroutine in between), so a late finger update can
    // never cancel the spring that follows and leave the row stuck part-way open.
    var x by remember { mutableFloatStateOf(0f) }
    val settling = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var raw by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }

    fun shown(r: Float): Float = if (r <= 0f) r else (1f - 1f / (r * 0.55f / button + 1f)) * button * 0.5f
    var isSettling by remember { mutableStateOf(false) }
    // Let go of a drag, it lands with a slight overshoot (none with Reduce Motion, none going off).
    fun animateTo(target: Float, velocity: Float, bounce: Boolean = true) {
        settling[0]?.cancel()
        val spec = spring<Float>(if (bounce && !reduceMotion) 0.8f else 1f, 520f)
        val job = scope.launch {
            try {
                androidx.compose.animation.core.animate(x, target, velocity, spec) { value, _ -> x = value }
            } finally {
                if (settling[0] == coroutineContext[kotlinx.coroutines.Job]) isSettling = false
            }
        }
        settling[0] = job
        isSettling = true
    }
    fun settle(target: Float, velocity: Float) {
        raw = target
        animateTo(target, velocity)
    }
    // Deleted the moment it's decided: the row slides the rest of the way off while the list
    // closes the gap, in one motion, rather than a red row waiting to be removed.
    fun delete(velocity: Float = 0f) {
        onDelete()
        haptics.perform(Haptic.Medium)
        animateTo(-width, velocity, bounce = false)
    }
    // Read during composition only when it flips, not on every frame of the swipe.
    val open by remember { androidx.compose.runtime.derivedStateOf { x < -button * 0.5f } }
    val revealing by remember { androidx.compose.runtime.derivedStateOf { x < 0f } }

    Box(
        modifier
            .onSizeChanged { width = it.width.toFloat() }
            .clipToBounds()
            .semantics { customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true }) },
    ) {
        // The red behind the row, growing with the swipe; "Delete" sits in its trailing end.
        // Its width and the label's fade are read while drawing, so a swipe redraws only these
        // two layers each frame instead of recomposing the row.
        if (revealing) {
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        val w = (-x).coerceIn(0f, size.width)
                        drawRect(red, topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(with(density) { button.toDp() })
                        .tappable(source, enabled = open) { delete() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Delete", style = Type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.graphicsLayer {
                            val revealed = (-x).coerceAtLeast(0f)
                            alpha = (revealed / button).coerceIn(0f, 1f)
                            // Rides in with the red, then sits still at the end.
                            translationX = (button - revealed).coerceAtLeast(0f) * 0.35f
                        },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                .draggable(
                    rememberDraggableState { delta ->
                        settling[0]?.cancel()
                        raw += delta
                        val next = shown(raw)
                        // One tick as it crosses the line where letting go deletes.
                        val past = next < -width * 0.5f
                        if (past != armed) { armed = past; if (past) haptics.perform(Haptic.Light) }
                        x = next
                    },
                    Orientation.Horizontal,
                    // Swiping is the user's own motion, so it stays with Reduce Motion on.
                    startDragImmediately = isSettling,
                    onDragStarted = { settling[0]?.cancel(); raw = x },
                    onDragStopped = { velocity ->
                        val dpPerSec = velocity / density.density
                        armed = false
                        when {
                            x < -width * 0.5f || dpPerSec < -800f -> delete(velocity)
                            x < -button * 0.6f && dpPerSec < 300f -> settle(-button, velocity)
                            else -> settle(0f, velocity)
                        }
                    },
                ),
        ) { content() }
        // Open on Delete: a tap on the row closes it again rather than opening the entry.
        if (open) {
            val source = remember { MutableInteractionSource() }
            Box(Modifier.matchParentSize()) {
                // Everything but the Delete button.
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxHeight()
                        .width(with(density) { (width - button).coerceAtLeast(0f).toDp() })
                        .tappable(source) { settle(0f, 0f) },
                )
            }
        }
    }
}
