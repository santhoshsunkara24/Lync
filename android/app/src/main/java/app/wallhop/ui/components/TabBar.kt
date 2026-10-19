package app.wallhop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.GlassVariant
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.LocalBackdrop
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.glass
import app.wallhop.ui.design.rememberGlassTouch
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.tappable
import app.wallhop.ui.design.trackGlassTouch
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.animateColorAsState

data class TabItem(val label: String, val icon: ImageVector, val badge: Boolean = false)

/**
 * Floating Liquid Glass tab bar (DESIGN.md §5.2).
 *
 * At rest the selected tab sits on a soft fill. Touching the bar lifts the selection into a
 * glass droplet that magnifies the bar's own icons; dragging moves it across tabs, stretching
 * with speed, and releasing settles it on the nearest tab. Scrolling down shrinks the bar to a
 * single button.
 */
@Composable
fun FloatingTabBar(
    tabs: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    minimized: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Wallhop.colors
    val haptics = rememberHaptics()
    val barTouch = rememberGlassTouch()
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val fullWidth = maxWidth
        val width by animateDpAsState(if (minimized) 64.dp else fullWidth, Springs.smooth(), label = "barWidth")
        Box(
            Modifier
                .width(width)
                .height(64.dp)
                .trackGlassTouch(barTouch)
                .glass(capsule(), GlassVariant.Regular, barTouch, swell = false),
        ) {
            AnimatedVisibility(minimized, enter = fadeIn(), exit = fadeOut()) {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(64.dp)
                        .tappable(source, onClick = onExpand)
                        .semantics { contentDescription = "Show tabs" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tabs[selected].icon, null, tint = colors.tint, modifier = Modifier.size(24.dp))
                }
            }
            AnimatedVisibility(!minimized, enter = fadeIn(), exit = fadeOut()) {
                TabsRow(tabs, selected, onSelect, haptics)
            }
        }
    }
}

@Composable
private fun TabsRow(
    tabs: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    haptics: app.wallhop.ui.design.Haptics,
) {
    val colors = Wallhop.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // The bar's own icons, recorded so the droplet can magnify them.
    val tabsBackdrop = rememberLayerBackdrop()
    val behind = LocalBackdrop.current ?: emptyBackdrop()
    val dropletBackdrop = rememberCombinedBackdrop(behind, tabsBackdrop)

    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp).padding(6.dp)) {
        val tabWidthPx = with(density) { (maxWidth / tabs.size).toPx() }
        val tabWidth = maxWidth / tabs.size
        val position = remember { Animatable(selected * tabWidthPx) }
        val lift = remember { Animatable(0f) }
        // Only recomposes when the droplet appears or disappears, not on every frame of the lift.
        val lifted by remember { androidx.compose.runtime.derivedStateOf { lift.value > 0.001f } }
        var stretch by remember { mutableStateOf(1f) }
        var hovered by remember { mutableIntStateOf(selected) }

        LaunchedEffect(selected, tabWidthPx) {
            if (lift.targetValue == 0f) position.animateTo(selected * tabWidthPx, Springs.snappy())
        }

        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(tabWidthPx, tabs.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val tracker = VelocityTracker()
                        fun indexAt(x: Float) = (x / tabWidthPx).toInt().coerceIn(0, tabs.lastIndex)
                        hovered = indexAt(down.position.x)
                        scope.launch { lift.animateTo(1f, Springs.gel()) }
                        scope.launch { position.animateTo(hovered * tabWidthPx, Springs.snappy()) }
                        var last = down.position
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            last = change.position
                            // The droplet follows the finger, centred under it.
                            val x = (change.position.x - tabWidthPx / 2).coerceIn(0f, tabWidthPx * tabs.lastIndex)
                            scope.launch { position.snapTo(x) }
                            val v = abs(tracker.calculateVelocity().x)
                            stretch = 1f + (v / 4000f).coerceAtMost(0.25f)
                            val index = indexAt(change.position.x)
                            if (index != hovered) {
                                hovered = index
                                haptics.perform(Haptic.Selection)
                            }
                            if (change.positionChange() != androidx.compose.ui.geometry.Offset.Zero) change.consume()
                        } while (event.changes.any { it.pressed })
                        val target = indexAt(last.x)
                        stretch = 1f
                        if (target != currentSelected) {
                            haptics.perform(Haptic.Selection)
                            currentOnSelect(target)
                        }
                        scope.launch { position.animateTo(target * tabWidthPx, Springs.snappy()) }
                        scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 400f)) }
                    }
                },
        ) {
            // Resting selection: a soft fill that fades as the droplet lifts.
            Box(
                Modifier
                    .offset { IntOffset(position.value.roundToInt(), 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer { alpha = 1f - lift.value }
                    .clip(capsule())
                    .background(colors.label.copy(alpha = if (colors.dark) 0.12f else 0.07f)),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .layerBackdrop(tabsBackdrop),
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == selected
                    val tint by animateFloatAsState(if (isSelected) 1f else 0f, Springs.snappy(), label = "tabTint")
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                this.selected = isSelected
                                onClick { currentOnSelect(index); true }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box {
                            Icon(tab.icon, null, tint = lerp(colors.label, colors.tint, tint), modifier = Modifier.size(24.dp))
                            if (tab.badge) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = (-2).dp)
                                        .size(9.dp)
                                        .clip(capsule())
                                        .background(colors.red),
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(tab.label, style = Type.caption2, color = lerp(colors.label, colors.tint, tint))
                    }
                }
            }
            // The droplet: glass that lifts out of the bar and magnifies the icons under it.
            // Not in the frame at all while resting, so an idle bar costs one glass pass, not two.
            if (lifted && !app.wallhop.ui.design.GlassDebug.noDroplet) Box(
                Modifier
                    .offset { IntOffset(position.value.roundToInt(), 0) }
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val grow = 1f + 0.15f * lift.value
                        scaleX = grow * stretch
                        scaleY = grow / kotlin.math.sqrt(stretch)
                        alpha = (lift.value * 1.5f).coerceAtMost(1f)
                    }
                    .glass(
                        capsule(),
                        GlassVariant.Clear,
                        appear = { lift.value },
                        backdrop = dropletBackdrop,
                    ),
            )
        }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChange() = position - previousPosition

/**
 * Floating dark pill (DESIGN.md §5.2, Material 3 Expressive): icons only, with a light pill
 * that slides to the selected tab. A red dot marks a tab that needs attention.
 */
@Composable
fun PillNavBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val red = Wallhop.colors.red
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(64.dp)
            .clip(capsule())
            .background(lerp(Wallhop.home.card, Color.White, 0.06f))
            .padding(6.dp),
    ) {
        val slot = maxWidth / tabs.size
        val slotPx = with(LocalDensity.current) { slot.toPx() }
        val at by animateFloatAsState(selected.toFloat(), Springs.snappy(), label = "navPill")
        Box(
            Modifier
                .offset { IntOffset((slotPx * at).roundToInt(), 0) }
                .width(slot)
                .fillMaxHeight()
                .clip(capsule())
                .background(Wallhop.home.pale),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(capsule())
                        .tappable(source, role = androidx.compose.ui.semantics.Role.Tab) {
                            if (!active) {
                                haptics.perform(Haptic.Selection)
                                onSelect(index)
                            }
                        }
                        .semantics {
                            contentDescription = if (tab.badge) "${tab.label}, needs attention" else tab.label
                            this.selected = active
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val tint by animateColorAsState(if (active) Wallhop.home.onPale else Wallhop.home.pale.copy(alpha = 0.72f), Springs.snappy(), label = "navTint")
                    Icon(tab.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                    if (tab.badge) {
                        Box(Modifier.offset(x = 12.dp, y = (-10).dp).size(8.dp).clip(capsule()).background(red))
                    }
                }
            }
        }
    }
}
