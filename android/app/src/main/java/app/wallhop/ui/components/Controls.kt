package app.wallhop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.border
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
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
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val track by animateColorAsState(if (checked) home.pale else Color.Transparent, Springs.snappy(), label = "track")
    val outline by animateColorAsState(if (checked) home.pale else Color.White.copy(alpha = 0.45f), Springs.snappy(), label = "outline")
    val thumbColor by animateColorAsState(if (checked) home.onPale else Color.White.copy(alpha = 0.6f), Springs.snappy(), label = "thumb")
    val thumb by animateDpAsState(
        when {
            pressed -> 28.dp
            checked -> 24.dp
            else -> 16.dp
        },
        Springs.snappy(), label = "thumbSize",
    )
    val travel by animateFloatAsState(if (checked) 1f else 0f, Springs.snappy(), label = "travel")
    Box(
        Modifier
            .size(52.dp, 32.dp)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .clip(capsule())
            .background(track)
            .border(2.dp, outline, capsule())
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
        contentAlignment = Alignment.CenterStart,
    ) {
        // Thumb centre runs from 16 dp to 36 dp.
        Box(
            Modifier
                .offset(x = 16.dp + 20.dp * travel - thumb / 2)
                .size(thumb)
                .clip(capsule())
                .background(thumbColor),
        )
    }
}

/**
 * Filter chips in a row (like the reference's Episodes / In Progress / Liked): see-through
 * pills, with a pale pill that slides to the selected one. Pass [position] (fractional index,
 * e.g. a pager's) to have the selection follow a swipe live.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    position: (() -> Float)? = null,
    haptic: Boolean = true,
) {
    val colors = Wallhop.colors
    val home = Wallhop.home
    val haptics = rememberHaptics()
    BoxWithConstraints(modifier.fillMaxWidth().height(44.dp)) {
        val gap = 8.dp
        val segment = (maxWidth - gap * (options.size - 1)) / options.size
        val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { (segment + gap).toPx() }
        val sprung = animateFloatAsState(selected.toFloat(), Springs.snappy(), label = "segment")
        val at = position ?: { sprung.value }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEach { _ -> Box(Modifier.weight(1f).fillMaxHeight().clip(capsule()).background(Color.White.copy(alpha = 0.12f))) }
        }
        Box(
            Modifier
                .offset { androidx.compose.ui.unit.IntOffset((stepPx * at()).roundToInt(), 0) }
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
                        .clip(capsule())
                        .tappable(source, role = Role.Tab) {
                            if (index != selected) {
                                if (haptic) haptics.perform(Haptic.Selection)
                                onSelect(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val tint by animateColorAsState(if (on) home.onPale else colors.label, Springs.snappy(), label = "chipText")
                    Text(label, style = Type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = tint, maxLines = 1)
                }
            }
        }
    }
}

/** iOS-style spinner: 8 spokes fading in turn, one turn per second. */
@Composable
fun ActivityIndicator(color: Color = Wallhop.colors.labelSecondary, size: Dp = 20.dp) {
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
    val value by animateFloatAsState(progress ?: 0f, Springs.smooth(), label = "progress")
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(capsule())
            .background(colors.fill),
    ) {
        if (progress != null) {
            Box(Modifier.fillMaxHeight().width(maxWidth * value.coerceIn(0f, 1f)).clip(capsule()).background(color))
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
