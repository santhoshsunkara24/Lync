package app.wallhop.ui.screens

import app.wallhop.ui.design.WIcons
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.wallhop.link.ActivityEntry
import app.wallhop.ui.components.ActionSheetSpec
import app.wallhop.ui.components.AlertAction
import app.wallhop.ui.components.EmptyState
import app.wallhop.ui.components.IconTile
import app.wallhop.ui.components.InsetSection
import app.wallhop.ui.components.ListRow
import app.wallhop.ui.components.Overlays
import app.wallhop.ui.components.SegmentedControl
import app.wallhop.ui.components.copyCode
import app.wallhop.ui.components.spacedCode
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Haptics
import app.wallhop.ui.design.RollingCode
import app.wallhop.ui.design.confirm
import app.wallhop.ui.design.rememberAnimatedRows
import app.wallhop.ui.design.staggerIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val filters = listOf("All", "Codes", "Files & text", "Calls")

/**
 * Everything that went between phone and Mac (DESIGN.md §6.5). The filters are pages: swipe
 * sideways between them, and the segmented control follows the finger.
 */
@Composable
fun ActivityTab(activity: List<ActivityEntry>, overlays: Overlays, onDelete: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { filters.size }
    // One tick each time the list settles on a new filter, whether swiped or tapped.
    var lastPage by remember { mutableIntStateOf(pager.settledPage) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page != lastPage) haptics.perform(Haptic.Selection)
            lastPage = page
        }
    }

    // The pages fill the rest of the screen, so a swipe works anywhere below the filters, even
    // on an empty filter. Measured once with the list at the top; kept while it scrolls.
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val measuredMin by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == "pages" }
            if (item != null && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) {
                info.viewportEndOffset - info.afterContentPadding - item.offset
            } else {
                -1
            }
        }
    }
    var pagesMinPx by remember { mutableIntStateOf(0) }
    if (measuredMin > 0 && measuredMin != pagesMinPx) pagesMinPx = measuredMin
    val pagesMin = with(androidx.compose.ui.platform.LocalDensity.current) { pagesMinPx.toDp() }

    Page(title = "Activity", state = list) {
        item(key = "filter") {
            Column(Modifier.staggerIn(1)) {
            SegmentedControl(
                filters,
                selected = pager.targetPage,
                onSelect = { scope.launch { pager.animateScrollToPage(it, animationSpec = Springs.smooth()) } },
                position = { pager.currentPage + pager.currentPageOffsetFraction },
                haptic = false,
            )
            Spacer(Modifier.height(24.dp))
            }
        }
        item(key = "pages") {
            // The history is capped at 30 entries, so each page is a plain column.
            HorizontalPager(
                pager,
                modifier = Modifier.staggerIn(2).heightIn(min = pagesMin),
                verticalAlignment = Alignment.Top,
                pageSpacing = 16.dp,
                beyondViewportPageCount = 1,
            ) { page ->
                Box(Modifier.fillMaxWidth().heightIn(min = pagesMin)) {
                    ActivityPage(activity.filter { matches(it, page) }, page, overlays, onDelete)
                }
            }
        }
    }
}

private fun matches(entry: ActivityEntry, filter: Int) = when (filter) {
    1 -> entry.kind == ActivityEntry.Kind.Otp
    2 -> entry.kind in setOf(ActivityEntry.Kind.FilesSent, ActivityEntry.Kind.TextSent, ActivityEntry.Kind.FromMac)
    3 -> entry.kind == ActivityEntry.Kind.Call
    else -> true
}

@Composable
private fun ActivityPage(entries: List<ActivityEntry>, filter: Int, overlays: Overlays, onDelete: (String) -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val reduceMotion = Wallhop.a11y.reduceMotion
    val groups = entries.groupBy { dayLabel(context, it.time) }
    // Days already here when the page opened; a day that turns up later animates its first row in.
    val initialDays = remember { groups.keys.toSet() }
    Column(Modifier.fillMaxWidth()) {
        if (entries.isEmpty()) {
            EmptyState(
                WIcons.Activity,
                title = if (filter == 0) "Nothing yet" else "Nothing here yet",
                message = when (filter) {
                    1 -> "Codes from your messages show up here."
                    2 -> "Files, photos and text you share show up here."
                    3 -> "Missed calls show up here."
                    else -> "Codes from your messages, files you share and calls show up here."
                },
            )
        }
        groups.forEach { (day, items) ->
            key(day) {
                SectionHeader(day)
                // New entries open up into place; deleted ones fold away (DESIGN.md §7.2).
                val rows = rememberAnimatedRows(items, enterInitially = day !in initialDays) { it.id }
                InsetSection(dividerInset = 57.dp) {
                    rows.forEach { entry ->
                        row {
                            AnimatedVisibility(entry.visible, enter = rowEnter(reduceMotion), exit = rowExit(reduceMotion)) {
                                ActivityRow(entry.item, onLongClick = { showEntryActions(overlays, context, entry.item, haptics, onDelete) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** Copy code / Delete for one entry; the same on Home and in Activity. */
internal fun showEntryActions(
    overlays: Overlays,
    context: android.content.Context,
    entry: ActivityEntry,
    haptics: Haptics? = null,
    onDelete: (String) -> Unit,
) {
    overlays.showActionSheet(ActionSheetSpec(
        message = null,
        actions = buildList {
            if (entry.kind == ActivityEntry.Kind.Otp) {
                add(AlertAction("Copy code") {
                    copyCode(context, entry.title)
                    haptics?.confirm()
                    overlays.toast("Code copied", WIcons.CheckCircle)
                })
            }
            add(AlertAction("Delete", destructive = true) { onDelete(entry.id) })
        },
    ))
}

/** One entry. Long press (or [onClick], where given) offers Copy code / Delete. */
@Composable
internal fun ActivityRow(entry: ActivityEntry, onLongClick: () -> Unit, onClick: (() -> Unit)? = null) {
    val colors = Wallhop.colors
    val context = LocalContext.current
    val (icon, color) = when (entry.kind) {
        ActivityEntry.Kind.Otp -> WIcons.Lock to colors.green
        ActivityEntry.Kind.TextSent -> WIcons.Text to colors.tint
        ActivityEntry.Kind.FilesSent -> WIcons.File to colors.tint
        ActivityEntry.Kind.FromMac -> WIcons.Laptop to colors.gray
        ActivityEntry.Kind.Call -> WIcons.CallMissed to colors.red
    }
    val title = if (entry.kind == ActivityEntry.Kind.Otp) spacedCode(entry.title) else entry.title
    // A code that just arrived rolls in digit by digit; older ones simply show.
    val fresh = remember(entry.id) { System.currentTimeMillis() - entry.time < 15_000 }
    ListRow(
        title = title,
        titleContent = if (entry.kind == ActivityEntry.Kind.Otp && fresh) {
            {
                RollingCode(
                    title, Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), colors.label,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = title },
                )
            }
        } else null,
        subtitle = entry.detail,
        leading = { IconTile(icon, color) },
        trailing = {
            Text(
                DateUtils.formatDateTime(context, entry.time, DateUtils.FORMAT_SHOW_TIME),
                style = Type.caption1,
                color = colors.labelSecondary,
            )
        },
        onLongClickLabel = "Show actions",
        onLongClick = onLongClick,
        onClick = onClick,
    )
}

private fun dayLabel(context: android.content.Context, time: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val day = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
    }
}
