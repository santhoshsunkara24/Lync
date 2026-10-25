package app.wallhop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.tween
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.tappable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

// Switch, segmented control, activity indicator, progress. See docs/DESIGN.md §5.6, §5.7, §5.13.

/** One opacity for every disabled or unavailable control. */
const val DisabledAlpha = 0.38f

/**
 * Material 3 switch, 52 × 32 dp: pale track and a dark thumb when on; an outlined
 * track and a small thumb when off. The thumb grows while pressed. In a list, pass the
 * switch to [ListRow] instead (whole row toggles); with a null [onCheckedChange] it's only
 * drawn, and [interactionSource] lets the row's press grow the thumb.
 */
@Composable
fun WSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val home = Wallhop.home
    val haptics = rememberHaptics()
    val reduceMotion = Wallhop.a11y.reduceMotion
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Under a finger the thumb also stretches sideways, toward the middle (DESIGN.md §5.6).
    // Everything moving here is drawn, not laid out, so flicking switches costs no measuring.
    val stretch by animateFloatAsState(if (pressed && enabled && !reduceMotion) 6f else 0f, Springs.snappy(), label = "thumbStretch")
    val track by animateColorAsState(if (checked) home.pale else Color.Transparent, Springs.snappy(), label = "track")
    val outline by animateColorAsState(if (checked) home.pale else Color.White.copy(alpha = 0.45f), Springs.snappy(), label = "outline")
    val thumbColor by animateColorAsState(if (checked) home.onPale else Color.White.copy(alpha = 0.6f), Springs.snappy(), label = "thumb")
    val thumb by animateFloatAsState(
        when {
            pressed -> 28f
            checked -> 24f
            else -> 16f
        },
        Springs.snappy(), label = "thumbSize",
    )
    val travel by animateFloatAsState(if (checked) 1f else 0f, Springs.snappy(), label = "travel")
    Box(
        Modifier
            .size(52.dp, 32.dp)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                val line = 2.dp.toPx()
                drawRoundRect(
                    outline, topLeft = Offset(line / 2, line / 2), size = Size(size.width - line, size.height - line),
                    cornerRadius = CornerRadius((size.height - line) / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(line),
                )
                // Thumb centre runs from 16 dp to 36 dp; a stretch grows it toward the other end.
                val t = thumb.dp.toPx()
                val st = stretch.dp.toPx()
                val w = t + st
                val centre = 16.dp.toPx() + 20.dp.toPx() * travel + st / 2 * (1f - 2f * travel)
                drawRoundRect(thumbColor, topLeft = Offset(centre - w / 2, (size.height - t) / 2), size = Size(w, t), cornerRadius = CornerRadius(t / 2))
            }
            .then(
                if (onCheckedChange == null) Modifier else Modifier.toggleable(
                    value = checked,
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    role = Role.Switch,
                ) {
                    haptics.perform(Haptic.Selection)
                    onCheckedChange(it)
                },
            ),
    )
}

/**
 * Filter chips in a row (like the reference's Episodes / In Progress / Liked): see-through
 * pills, with a pale pill at [position] (fractional index, e.g. a pager's), so the selection
 * follows a swipe live.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    position: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colors = Wallhop.colors
    val home = Wallhop.home
    // 48 dp at the default font, taller as the font grows so labels never clip.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth().height(48.dp * fontScale.coerceAtLeast(1f))) {
        val gap = 8.dp
        val segment = (maxWidth - gap * (options.size - 1)) / options.size
        val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { (segment + gap).toPx() }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { _ -> Box(Modifier.weight(1f).fillMaxHeight().clip(capsule()).background(Color.White.copy(alpha = 0.12f))) }
        }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset((stepPx * position()).roundToInt(), 0) }
                .width(segment)
                .fillMaxHeight()
                .clip(capsule())
                .background(home.pale),
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { index, label ->
                val source = remember { MutableInteractionSource() }
                val on = index == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(source, 0.95f)
                        .clip(capsule())
                        .tappable(source, role = Role.Tab) {
                            if (index != selected) onSelect(index)
                        }
                        .semantics { this.selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    val tint by animateColorAsState(if (on) home.onPale else colors.label, Springs.snappy(), label = "chipText")
                    Text(label, style = Type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = tint, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

/** iOS-style spinner: 8 spokes fading in turn, one turn per second. Still with Reduce Motion. */
@Composable
fun ActivityIndicator(size: Dp = 20.dp) {
    val color = Wallhop.colors.labelSecondary
    if (Wallhop.a11y.reduceMotion) {
        Canvas(Modifier.size(size)) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val outer = this.size.minDimension / 2
            for (i in 0 until 8) {
                val angle = (i * 45.0 - 90.0) * PI / 180
                drawLine(
                    color = color.copy(alpha = color.alpha * (1f - i / 8f * 0.85f)),
                    start = Offset(center.x + (outer * 0.45f * cos(angle)).toFloat(), center.y + (outer * 0.45f * sin(angle)).toFloat()),
                    end = Offset(center.x + (outer * cos(angle)).toFloat() * 0.92f, center.y + (outer * sin(angle)).toFloat() * 0.92f),
                    strokeWidth = outer * 0.2f,
                    cap = StrokeCap.Round,
                )
            }
        }
        return
    }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "spinner")
    val turn = transition.animateFloat(
        0f, 8f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "spokes",
    )
    Canvas(Modifier.size(size)) {
        // Read in the draw phase, so each tick only redraws the spinner.
        val phase = turn.value.toInt() % 8
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val outer = this.size.minDimension / 2
        val inner = outer * 0.45f
        val stroke = outer * 0.2f
        for (i in 0 until 8) {
            val angle = (i * 45.0 - 90.0) * PI / 180
            val age = (phase - i + 8) % 8
            val alpha = 1f - age / 8f * 0.85f
            drawLine(
                color = color.copy(alpha = color.alpha * alpha),
                start = Offset(center.x + (inner * cos(angle)).toFloat(), center.y + (inner * sin(angle)).toFloat()),
                end = Offset(center.x + (outer * cos(angle)).toFloat() * 0.92f, center.y + (outer * sin(angle)).toFloat() * 0.92f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 4 dp capsule progress bar. Pass null for an indeterminate shimmer. */
@Composable
fun ProgressBar(progress: Float?, modifier: Modifier = Modifier, color: Color = Wallhop.colors.tint) {
    val colors = Wallhop.colors
    // Updates arrive about ten times a second; a short linear glide between them reads as one
    // steady fill. Drawn, so it never re-measures.
    val reduceMotion = Wallhop.a11y.reduceMotion
    val value by animateFloatAsState(
        progress ?: 0f,
        if (reduceMotion) androidx.compose.animation.core.snap() else tween(250, easing = androidx.compose.animation.core.LinearEasing),
        label = "progress",
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(capsule())
            .background(colors.fill),
    ) {
        if (progress != null) {
            Box(Modifier.fillMaxSize().drawBehind {
                val w = size.width * value.coerceIn(0f, 1f)
                if (w > 0f) drawRoundRect(color, size = Size(maxOf(w, size.height), size.height), cornerRadius = CornerRadius(size.height / 2))
            })
        } else {
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "indeterminate")
            val t = transition.animateFloat(
                0f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing),
                ),
                label = "sweep",
            )
            val travel = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
            Box(
                Modifier
                    .graphicsLayer { translationX = travel * (t.value * 1.4f - 0.4f) }
                    .fillMaxHeight()
                    .width(maxWidth * 0.4f)
                    .clip(capsule())
                    .background(color),
            )
        }
    }
}

/**
 * A small progress ring for transfers (DESIGN.md §5.13). Updates arrive up to 10 times a
 * second; the ring glides between them on spring.smooth. When [done], it fills, then turns
 * into a check with a pop.
 */
@Composable
fun ProgressCheck(
    progress: Float,
    done: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Wallhop.colors.green,
) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val shownState = animateFloatAsState(
        if (done) 1f else progress.coerceIn(0f, 1f),
        if (reduceMotion) androidx.compose.animation.core.snap() else tween(250, easing = androidx.compose.animation.core.LinearEasing),
        label = "ring",
    )
    // The check waits for the ring to close, then pops in as the ring fades. Watched off the
    // composition, so the ring filling never recomposes this.
    val checkAnim = remember { androidx.compose.animation.core.Animatable(if (done) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(done, reduceMotion) {
        if (!done) { checkAnim.snapTo(0f); return@LaunchedEffect }
        if (reduceMotion) { checkAnim.snapTo(1f); return@LaunchedEffect }
        androidx.compose.runtime.snapshotFlow { shownState.value }.first { it > 0.97f }
        checkAnim.animateTo(1f, Springs.pop())
    }
    val check by checkAnim.asState()
    val shown by shownState
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - check).coerceIn(0f, 1f) }) {
            val stroke = size.minDimension * 0.18f
            val inset = stroke / 2
            val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawCircle(Color.White.copy(alpha = 0.25f), radius = size.minDimension / 2 - inset, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(
                color, -90f, 360f * shown, false,
                topLeft = Offset(inset, inset), size = arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        if (done) {
            androidx.compose.material3.Icon(
                app.wallhop.ui.design.WIcons.CheckCircle, null, tint = color,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val s = 0.85f + 0.15f * check
                    scaleX = s
                    scaleY = s
                    alpha = check.coerceIn(0f, 1f)
                },
            )
        }
    }
}
