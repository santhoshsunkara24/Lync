package app.wallhop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wallhop.ui.components.GlassStage
import app.wallhop.ui.components.LargeTitle
import app.wallhop.ui.components.ScrollEdge
import app.wallhop.ui.design.GlassVariant
import app.wallhop.ui.design.LocalStagger
import app.wallhop.ui.design.rememberStaggerClock
import app.wallhop.ui.design.staggerIn
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.glass

/** Widest the content column gets on tablets and in landscape. */
val MaxContentWidth: Dp = 640.dp

/** Height of the floating tab bar plus its gap, so the last row can scroll clear of it. */
val TabBarClearance: Dp = 64.dp + 12.dp + 24.dp

/**
 * A scrolling page in the approved style (DESIGN.md §5.1): the Material You colour at the top
 * fading to ink, round see-through buttons, then a calm 30 sp title and an optional subtitle.
 * Once the title scrolls away, a small title appears over a fade under the status bar.
 */
@Composable
fun Page(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    state: LazyListState = rememberLazyListState(),
    bottomClearance: Dp = TabBarClearance,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = Wallhop.colors
    val home = Wallhop.home
    val density = LocalDensity.current
    val collapseAt = with(density) { 72.dp.toPx() }
    val collapsed by remember {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > collapseAt }
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Landscape and camera cutouts: keep content clear of the sides as well.
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val start = sides.calculateStartPadding(layoutDirection)
    val end = sides.calculateEndPadding(layoutDirection)

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to home.top, 0.34f to home.mid, 0.62f to colors.background)),
    ) {
        // Tablets and landscape: content stays a readable column, centred (the background still fills).
        val extra = ((maxWidth - start - end - MaxContentWidth) / 2).coerceAtLeast(0.dp)
        // The page's first rows fade up in turn each time it comes on show.
        androidx.compose.runtime.CompositionLocalProvider(LocalStagger provides rememberStaggerClock()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = start + extra + 20.dp, end = end + extra + 20.dp, top = statusBar + 14.dp, bottom = navBar + bottomClearance),
        ) {
            item(key = "title") {
                Column(Modifier.staggerIn(0).fillMaxWidth().padding(bottom = 22.dp)) {
                    if (leading != null) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            leading()
                            Spacer(Modifier.weight(1f))
                            trailing?.invoke()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = Type.largeTitle, color = colors.label, modifier = Modifier.weight(1f).semantics { heading() })
                        if (leading == null) trailing?.invoke()
                    }
                    if (subtitle != null) {
                        // White at 78 %: the subtitle sits on the brightest part of the gradient.
                        Text(subtitle, style = Type.subheadline, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            content()
        }
        }
        // Content fades out under the status bar once scrolled, with the title small on top.
        val fade by animateFloatAsState(if (collapsed) 1f else 0f, Springs.smooth(), label = "edge")
        // Solid behind the small title and a little below it, then a short fade, so rows never
        // show through the title as they scroll under it.
        val solid = statusBar + 64.dp
        val total = solid + 28.dp
        Box(
            Modifier
                .fillMaxWidth()
                .height(total)
                .graphicsLayer { alpha = fade }
                .background(Brush.verticalGradient(0f to home.top, solid / total to home.top, 1f to home.top.copy(alpha = 0f))),
        ) {
            Text(title, style = Type.headline, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = statusBar + 18.dp))
        }
        if (bottomClearance > 40.dp) {
            val canScroll by remember { derivedStateOf { state.canScrollForward } }
            val bottomFade by animateFloatAsState(if (canScroll) 1f else 0f, Springs.smooth(), label = "bottomEdge")
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(navBar + 72.dp)
                    .graphicsLayer { alpha = bottomFade }
                    .background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0f), colors.background))),
            )
        }
    }
}

/** Section header text with an optional trailing element (e.g. a spinner). */
@Composable
fun SectionHeader(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, style = Type.headline, color = Wallhop.colors.label, modifier = Modifier.semantics { heading() })
        trailing?.invoke()
    }
}
