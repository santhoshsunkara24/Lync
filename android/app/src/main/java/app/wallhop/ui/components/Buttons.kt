package app.wallhop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.GlassVariant
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.glass
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.pressedFraction
import app.wallhop.ui.design.rememberGlassTouch
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.tappable
import app.wallhop.ui.design.trackGlassTouch

// Buttons. See docs/DESIGN.md §5.3.

enum class ButtonStyle {
    /** Pale Material You pill with dark text: the screen's main action. */
    Prominent,
    /** See-through white pill: secondary actions. */
    Glass,
    /** Pale pill, same as [Prominent], for actions inside content. */
    Filled,
    /** Text only. */
    Plain,
}

@Composable
fun WButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Filled,
    destructive: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = Wallhop.colors
    val home = Wallhop.home
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val pressed = source.pressedFraction()
    val isGlass = false
    val labelColor = when (style) {
        ButtonStyle.Prominent, ButtonStyle.Filled -> if (destructive) Color.White else home.onPale
        ButtonStyle.Glass -> if (destructive) colors.red else colors.label
        ButtonStyle.Plain -> if (destructive) colors.red else colors.tint
    }

    val surface = when (style) {
        ButtonStyle.Prominent, ButtonStyle.Filled -> Modifier.pressScale(source, 0.96f).clip(capsule())
            .background(if (destructive) colors.red else home.pale)
        ButtonStyle.Glass -> Modifier.pressScale(source, 0.96f).clip(capsule()).background(Color.White.copy(alpha = 0.13f))
        ButtonStyle.Plain -> Modifier
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else DisabledAlpha)
            .then(surface)
            .tappable(source, enabled = enabled && !loading) {
                if (style == ButtonStyle.Prominent) haptics.perform(Haptic.Light)
                onClick()
            }
            .defaultMinSize(minHeight = if (style == ButtonStyle.Plain) 44.dp else 52.dp)
            .padding(horizontal = if (style == ButtonStyle.Plain) 4.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = loading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "loading") { busy ->
            if (busy) {
                ActivityIndicator(color = labelColor, size = 20.dp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) Icon(icon, null, tint = labelColor, modifier = Modifier.size(20.dp))
                    Text(
                        text,
                        style = if (style == ButtonStyle.Plain) Type.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else Type.headline,
                        color = labelColor,
                        modifier = Modifier.alpha(if (pressed > 0f && !isGlass) 0.7f else 1f),
                    )
                }
            }
        }
    }
}

/**
 * 48 dp round see-through button for top actions (back, more). [onClick] receives the button's
 * bounds so a menu can grow out of it (DESIGN.md §5.12).
 */
@Composable
fun CircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val source = remember { MutableInteractionSource() }
    val bounds = remember { arrayOf(Rect.Zero) }
    Box(
        modifier = modifier
            .size(48.dp)
            .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
            .pressScale(source, 0.92f)
            .clip(capsule())
            .background(Color.White.copy(alpha = 0.14f))
            .tappable(source) { onClick(bounds[0]) }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint ?: Wallhop.colors.label, modifier = Modifier.size(20.dp))
    }
}

/** Small text-only action, e.g. "Cancel" in a sheet header. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, bold: Boolean = false, enabled: Boolean = true, color: Color? = null) {
    val source = remember { MutableInteractionSource() }
    val pressed = source.pressedFraction()
    Text(
        text,
        style = if (bold) Type.headline else Type.body,
        color = (color ?: Wallhop.colors.tint).copy(alpha = if (!enabled) DisabledAlpha else if (pressed > 0f) 0.5f else 1f),
        // A 48 dp target at least, growing with the font.
        modifier = Modifier
            .tappable(source, enabled = enabled, onClick = onClick)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 4.dp, vertical = 13.dp),
    )
}
