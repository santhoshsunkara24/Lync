package app.wallhop.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.tappable
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.animateColorAsState

data class TabItem(val label: String, val icon: ImageVector, val badge: Boolean = false)

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
            // Not stretched across a tablet: the same 640 dp column as the content.
            .widthIn(max = app.wallhop.ui.screens.MaxContentWidth)
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
