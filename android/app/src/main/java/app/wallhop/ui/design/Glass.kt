package app.wallhop.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

fun capsule() = Capsule()
fun rounded(radius: Dp) = RoundedRectangle(radius)

/**
 * The surface behind menus and sheets. The approved design has no glass, so this is the frosted
 * solid (DESIGN.md §4.4).
 */
@Composable
fun Modifier.glass(shape: Shape = Capsule()): Modifier {
    val colors = Wallhop.colors
    // Fully opaque: at 96 % the rows and tab bar behind showed through action sheets.
    val fill = lerp(colors.backgroundElevated, colors.backgroundElevated2, 0.3f)
    return this
        .shadow(if (colors.dark) 0.dp else 10.dp, shape, clip = false)
        .clip(shape)
        .background(fill)
        .border(0.5.dp, colors.separator.copy(alpha = 0.6f), shape)
}
