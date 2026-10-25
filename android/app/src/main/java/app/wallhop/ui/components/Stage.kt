package app.wallhop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A screen root: [content] on [background], with [chrome] (tab bar, overlays) floating above.
 * The approved design has no glass, so nothing is recorded for refraction.
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
        Box(Modifier.fillMaxSize(), content = chrome)
    }
}
