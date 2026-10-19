package app.wallhop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.LocalBackdrop
import app.wallhop.ui.design.Wallhop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * A screen root: [content] on [background], with [chrome] (tab bar, overlays) floating above.
 * The approved design has no glass, so nothing is recorded for refraction: [LocalBackdrop] is
 * null and any leftover glass draws as a solid tinted surface (DESIGN.md §4).
 */
@Composable
fun GlassStage(
    modifier: Modifier = Modifier,
    background: Color,
    content: @Composable BoxScope.() -> Unit,
    chrome: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(background), content = content)
        CompositionLocalProvider(LocalBackdrop provides null) {
            Box(Modifier.fillMaxSize(), content = chrome)
        }
    }
}

/**
 * Scroll edge effect (DESIGN.md §4.5): content that slides under a bar blurs and dissolves into
 * the background progressively, instead of meeting a hard edge. [visible] fades it in once
 * something has actually scrolled underneath.
 */
@Composable
fun BoxScope.ScrollEdge(visible: () -> Float, height: Dp, atTop: Boolean = true) {
    val colors = Wallhop.colors
    val backdrop = LocalBackdrop.current
    val solidEnd = if (atTop) listOf(Color.Black, Color.Transparent) else listOf(Color.Transparent, Color.Black)
    val fade = if (atTop) {
        listOf(colors.background.copy(alpha = 0.72f), colors.background.copy(alpha = 0f))
    } else {
        listOf(colors.background.copy(alpha = 0f), colors.background.copy(alpha = 0.6f))
    }
    Box(
        Modifier
            .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = visible() }
            .then(
                if (atTop && backdrop != null && isRenderEffectSupported() && !Wallhop.a11y.reduceTransparency && !app.wallhop.ui.design.GlassDebug.noEdge) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { blur(10.dp.toPx()) },
                        highlight = null,
                        shadow = null,
                        onDrawBackdrop = { drawBlurred ->
                            // Mask the blurred copy so the blur grows towards the edge.
                            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
                            drawBlurred()
                            drawRect(Brush.verticalGradient(solidEnd), blendMode = BlendMode.DstIn)
                            drawContext.canvas.restore()
                        },
                    )
                } else {
                    Modifier
                },
            )
            .background(Brush.verticalGradient(fade)),
    )
}
