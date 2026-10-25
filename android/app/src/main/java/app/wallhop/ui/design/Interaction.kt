package app.wallhop.ui.design

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role

// Touch response and haptics. See docs/DESIGN.md §5.3 and §8.

/** Press state (0 or 1) for an interaction source, for driving highlight and scale. */
@Composable
fun MutableInteractionSource.pressedFraction(): Float {
    val pressed by collectIsPressedAsState()
    return if (pressed) 1f else 0f
}

/**
 * Scales down slightly the moment a finger lands, springs back on release. No ripple:
 * Apple-style controls respond with motion and highlight instead.
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) pressedScale else 1f,
        animationSpec = if (pressed) Springs.press() else Springs.release(),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Row-style press (DESIGN.md §5.5): the background lights to the fill colour the instant a
 * finger lands and fades back over 250 ms on release. For rows and menu items, where scaling
 * would look odd.
 */
@Composable
fun Modifier.pressHighlight(source: MutableInteractionSource): Modifier {
    val color = Wallhop.colors.fill
    val pressed by source.collectIsPressedAsState()
    val highlight by animateColorAsState(
        if (pressed) color else color.copy(alpha = 0f),
        animationSpec = tween(if (pressed) 0 else Durations.Highlight, easing = Easings.Out),
        label = "pressHighlight",
    )
    // Drawn, so the fade after release repaints without recomposing the row.
    return drawBehind { drawRect(highlight) }
}

/** Text-like press: dims while held, for titles and small glyph buttons. */
@Composable
fun Modifier.pressDim(source: MutableInteractionSource, pressedAlpha: Float = 0.55f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        if (pressed) pressedAlpha else 1f,
        animationSpec = if (pressed) tween(0) else tween(Durations.Highlight, easing = Easings.Out),
        label = "pressDim",
    )
    return graphicsLayer { this.alpha = alpha }
}

/** Clickable with no ripple; pair with [pressScale] or a pressed background. */
@Composable
fun Modifier.tappable(
    source: MutableInteractionSource,
    role: Role = Role.Button,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)

/** Haptic events from DESIGN.md §8. Shorthands (confirm, reject) are in Motion.kt. */
enum class Haptic { Selection, Light, Medium, Success, Failure }

class Haptics(private val view: View) {
    fun perform(haptic: Haptic) {
        val constant = when (haptic) {
            Haptic.Selection -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.Light -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Medium -> HapticFeedbackConstants.LONG_PRESS
            Haptic.Success -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Failure -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
