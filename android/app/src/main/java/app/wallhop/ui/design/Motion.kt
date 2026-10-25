package app.wallhop.ui.design

import android.os.Process
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wallhop.ui.components.LocalScreenActive
import kotlinx.coroutines.delay

// Motion vocabulary on top of the spring tokens in Tokens.kt (DESIGN.md §7). Everything here
// collapses to an instant change (or a short fade) with Reduce Motion, and nothing here runs
// while the screen is idle: each animation plays once for a change, then stops.

/** Fixed durations, for the few things that are fades rather than springs. */
object Durations {
    /** A plain fade in (DESIGN.md §7.1: fades alone may use 200 ms ease-out). */
    const val Fade = 200
    /** Fading out, and the Reduce Motion stand-in for a spring (§7.3). */
    const val Quick = 150
    /** A list row's highlight fading after release (§5.5). */
    const val Highlight = 250
    /** Something small arriving: a banner, a badge swapping in. */
    const val Enter = 260
    /** The same leaving: quicker than it came. */
    const val Exit = 200
    /** A page opened from a tab arriving and leaving (slower and softer, by request). */
    const val SubPageIn = 380
    const val SubPageOut = 300
}

/**
 * The few curves the app uses, instead of hand-typed ones. Never an ease-in for things on screen:
 * it starts slowly, just when the eye is on it.
 */
object Easings {
    /** Strong ease-out: arrivals, departures, anything answering a tap. */
    val Out = androidx.compose.animation.core.CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    /** Ease-in-out: something moving from one place on screen to another. */
    val InOut = androidx.compose.animation.core.CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
}

/** Fade specs; ease-out so things arrive calmly. */
object Fades {
    fun <T> fadeIn(): TweenSpec<T> = tween(Durations.Fade, easing = Easings.Out)
    fun <T> fadeOut(): TweenSpec<T> = tween(Durations.Quick, easing = Easings.Out)
}

// MARK: - Haptic shorthands

/** Something worked: copied, sent, paired. */
fun Haptics.confirm() = perform(Haptic.Success)
/** Something failed. */
fun Haptics.reject() = perform(Haptic.Failure)

// MARK: - Rolling digits

/**
 * A code that rolls in like an odometer: each digit slides up into its slot, one after another
 * (30 ms apart). Spaces and line breaks stay put. With [animate] false (or Reduce Motion)
 * the code simply shows; a later change to [code] rolls only the digits that changed.
 */
@Composable
fun RollingCode(
    code: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    /** Added to every digit's delay, to continue a stagger from an earlier line. */
    delayOffsetMs: Long = 0,
) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val first = remember { code }
    val play = !reduceMotion && (animate || code != first)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
        var slot = 0
        code.forEach { char ->
            if (char == ' ') {
                Text(" ", style = style, color = color)
            } else {
                RollingChar(char, delayOffsetMs + slot * 30L, play, style, color)
                slot++
            }
        }
    }
}

@Composable
private fun RollingChar(
    char: Char,
    delayMs: Long,
    play: Boolean,
    style: TextStyle,
    color: Color,
) {
    val progress = remember(char) { Animatable(if (play) 0f else 1f) }
    val active = LocalScreenActive.current
    // Waits for the screen to be on show, so a code that arrives on a hidden tab rolls when you look.
    LaunchedEffect(progress, active) {
        if (progress.value < 1f && active) {
            delay(delayMs)
            progress.animateTo(1f, Springs.snappy())
        }
    }
    Box(
        Modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            char.toString(), style = style, color = color, textAlign = TextAlign.Center,
            modifier = Modifier.graphicsLayer {
                val p = progress.value
                translationY = (1f - p) * size.height
                alpha = p.coerceIn(0f, 1f)
            },
        )
    }
}

// MARK: - Text whose numbers change

/**
 * Text that rolls when it changes: up when its first differing number grew, down when it
 * shrank (battery, latency, "on for 3 min", "4 apps"). A cross-fade with Reduce Motion.
 */
@Composable
fun AnimatedChangeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(Durations.Quick)) togetherWith fadeOut(tween(Durations.Quick))
            } else {
                val up = rose(initialState, targetState)
                (slideInVertically(Springs.snappy()) { if (up) it / 2 else -it / 2 } + fadeIn(Fades.fadeIn())) togetherWith
                    (slideOutVertically(Springs.snappy()) { if (up) -it / 2 else it / 2 } + fadeOut(Fades.fadeOut())) using
                    SizeTransform(clip = false)
            }
        },
        label = "changeText",
    ) { value ->
        Text(value, style = style, color = color, maxLines = maxLines, overflow = overflow)
    }
}

private val numberPattern = Regex("\\d+")

/** True when the first number that differs between [old] and [new] went up (or none differ). */
internal fun rose(old: String, new: String): Boolean {
    val a = numberPattern.findAll(old).map { it.value.toLong() }.toList()
    val b = numberPattern.findAll(new).map { it.value.toLong() }.toList()
    for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return b[i] > a[i]
    return true
}

// MARK: - One-shot modifiers

/** A short horizontal shake (3 oscillations, 6 dp, 300 ms) each time [trigger] turns true. */
@Composable
fun Modifier.shake(trigger: Boolean): Modifier {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val offset = remember { Animatable(0f) }
    val previous = remember { booleanArrayOf(trigger) }
    LaunchedEffect(trigger) {
        val was = previous[0]
        previous[0] = trigger
        if (trigger && !was && !reduceMotion) {
            offset.animateTo(0f, keyframes {
                durationMillis = 300
                6f at 25
                -6f at 75
                6f at 125
                -6f at 175
                6f at 225
                -6f at 275
            })
        }
    }
    return graphicsLayer { translationX = offset.value.dp.toPx() }
}

/** A quick flash: scales up and glows for a moment each time [trigger] changes (not at 0). */
@Composable
fun Modifier.flashPulse(trigger: Int): Modifier {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val flash = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        flash.snapTo(0f)
        if (reduceMotion) return@LaunchedEffect
        flash.animateTo(1f, tween(110, easing = Easings.Out))
        flash.animateTo(0f, Springs.snappy())
    }
    return graphicsLayer {
        val s = 1f + 0.18f * flash.value
        scaleX = s
        scaleY = s
    }.drawWithContent {
        drawContent()
        val f = flash.value.coerceIn(0f, 1f)
        if (f > 0f) drawCircle(Color.White.copy(alpha = 0.35f * f), radius = size.minDimension / 2 * (1f + 0.35f * f))
    }
}

/** Fades in and rises 6 dp once, the first time it's on screen. */
@Composable
fun Modifier.floatIn(): Modifier {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val active = LocalScreenActive.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(active) {
        if (active && progress.value < 1f) progress.animateTo(1f, Springs.smooth())
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * 6.dp.toPx()
    }
}

// MARK: - First-appearance stagger

/** When the current visit to a screen began; null while it shouldn't stagger. */
@Immutable
class StaggerClock(val start: Long)

val LocalStagger = compositionLocalOf<StaggerClock?> { null }

/**
 * The screen's content eases in the first time it comes on show, and only then: coming back to a
 * tab, closing a page over it or hiding the sending screen finds everything already there, as on
 * iOS. Not right after the app starts either: the first screen appears as it always has.
 */
@Composable
fun rememberStaggerClock(): StaggerClock? {
    val active = LocalScreenActive.current
    val reduceMotion = Wallhop.a11y.reduceMotion
    val first = remember { arrayOfNulls<StaggerClock>(1) }
    return remember(active, reduceMotion) {
        val now = SystemClock.uptimeMillis()
        when {
            reduceMotion -> null
            first[0] != null -> first[0]
            !active -> null
            // A clock from long ago: everything is already shown.
            now - Process.getStartUptimeMillis() < 2_000 -> StaggerClock(0L).also { first[0] = it }
            else -> StaggerClock(now).also { first[0] = it }
        }
    }
}

/** Bumped when the active tab is tapped again: its screen scrolls back to the top. */
val LocalScrollToTop = compositionLocalOf { 0 }

/** Scrolls [state] to the top each time the screen's tab is tapped again. */
@Composable
fun ScrollToTopOnReselect(state: androidx.compose.foundation.lazy.LazyListState) {
    val request = LocalScrollToTop.current
    val reduceMotion = Wallhop.a11y.reduceMotion
    val handled = remember { intArrayOf(request) }
    LaunchedEffect(request) {
        if (request == handled[0]) return@LaunchedEffect
        handled[0] = request
        // From far down, jump most of the way first, so it arrives quickly instead of scrolling forever.
        if (!reduceMotion && state.firstVisibleItemIndex > 6) state.scrollToItem(6)
        if (reduceMotion) state.scrollToItem(0) else state.animateScrollToItem(0)
    }
}

private const val StaggerStepMs = 25L
private const val StaggerCap = 8

/**
 * Item [index] of a screen's list fades in and rises 8 dp, 25 ms after the one before, once per
 * visit. Only the first 8 items; anything composed later (scrolled to) just shows.
 */
@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val clock = LocalStagger.current ?: return this
    if (index >= StaggerCap) return this
    val wait = index * StaggerStepMs
    val progress = remember(clock) {
        Animatable(if (SystemClock.uptimeMillis() - clock.start > wait + 60) 1f else 0f)
    }
    LaunchedEffect(progress) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay((clock.start + wait - SystemClock.uptimeMillis()).coerceAtLeast(0))
        progress.animateTo(1f, Springs.snappy())
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}

// MARK: - Lists that aren't lazy

/** One row of [rememberAnimatedRows]: the item and whether it's (still) on show. */
class AnimatedRow<T>(val key: Any, val item: T, val visible: MutableTransitionState<Boolean>)

private class AnimatedRowsHolder<T> {
    var shown: List<Pair<Any, T>> = emptyList()
    val states = HashMap<Any, MutableTransitionState<Boolean>>()
    var first = true
}

/**
 * For short plain lists (Home Recent, Activity's day groups): the rows to draw, including ones
 * just removed while they animate out. Rows there from the start don't animate in; rows added
 * later do ([enterInitially]: the first ones too, for a group that's new itself). Pair each
 * with AnimatedVisibility(row.visible).
 */
@Composable
fun <T> rememberAnimatedRows(items: List<T>, enterInitially: Boolean = false, key: (T) -> Any): List<AnimatedRow<T>> {
    val holder = remember { AnimatedRowsHolder<T>() }
    val keys = items.map(key)
    val present = keys.toHashSet()
    items.forEachIndexed { i, _ ->
        val k = keys[i]
        val state = holder.states[k]
        if (state == null) {
            holder.states[k] = MutableTransitionState(holder.first && !enterInitially).apply { targetState = true }
        } else {
            state.targetState = true
        }
    }
    val result = keys.zip(items).toMutableList()
    holder.shown.forEachIndexed { i, (k, item) ->
        if (k in present) return@forEachIndexed
        val state = holder.states[k] ?: return@forEachIndexed
        // Reading these subscribes, so the row drops out once it has finished leaving.
        if (state.isIdle && !state.currentState) {
            holder.states.remove(k)
        } else {
            state.targetState = false
            result.add(i.coerceAtMost(result.size), k to item)
        }
    }
    holder.first = false
    holder.shown = result
    return result.map { (k, item) -> AnimatedRow(k, item, holder.states.getValue(k)) }
}
