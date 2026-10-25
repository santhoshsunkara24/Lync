package app.wallhop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
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
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.pressedFraction
import app.wallhop.ui.design.tappable

// Buttons. See docs/DESIGN.md §5.3.

enum class ButtonStyle {
    /** See-through white pill: secondary actions. */
    Glass,
    /** Pale Material You pill with dark text: the main action. */
    Filled,
}

@Composable
fun WButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Filled,
) {
    val colors = Wallhop.colors
    val home = Wallhop.home
    val source = remember { MutableInteractionSource() }
    val pressed = source.pressedFraction()
    val labelColor = when (style) {
        ButtonStyle.Filled -> home.onPale
        ButtonStyle.Glass -> colors.label
    }

    val surface = when (style) {
        ButtonStyle.Filled -> Modifier.pressScale(source, 0.96f).clip(capsule()).background(home.pale)
        ButtonStyle.Glass -> Modifier.pressScale(source, 0.96f).clip(capsule()).background(Color.White.copy(alpha = 0.13f))
    }

    Box(
        modifier = modifier
            .then(surface)
            .tappable(source, onClick = onClick)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Type.headline,
            color = labelColor,
            modifier = Modifier.alpha(if (pressed > 0f) 0.7f else 1f),
        )
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
) {
    val source = remember { MutableInteractionSource() }
    val bounds = remember { arrayOf(Rect.Zero) }
    Box(
        modifier = modifier
            .size(48.dp)
            .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
            .pressScale(source, 0.95f)
            .clip(capsule())
            .background(Color.White.copy(alpha = 0.14f))
            .tappable(source) { onClick(bounds[0]) }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Wallhop.colors.label, modifier = Modifier.size(20.dp))
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
