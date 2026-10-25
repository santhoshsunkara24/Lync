package app.wallhop.ui.screens

import androidx.compose.runtime.mutableStateOf

import app.wallhop.ui.components.MenuItem

import app.wallhop.ui.components.SwipeToDelete

import androidx.compose.ui.layout.boundsInRoot

import androidx.compose.ui.layout.onGloballyPositioned

import app.wallhop.ui.design.WIcons
import android.text.format.DateUtils
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
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

private val filters = listOf("All", "Codes", "Files", "Calls")

/**
 * Everything that went between phone and Mac (DESIGN.md §6.5). The filters are pages: swipe
 * sideways between them, and the segmented control follows the finger.
 */
@Composable
fun ActivityTab(
    activity: List<ActivityEntry>,
    overlays: Overlays,
    /** A tap on a row: copy a code, open a file or link, or the actions. */
    onTap: (ActivityEntry) -> Unit,
    /** Long press: the actions (Copy code, Send again, Delete), as a menu from the row. */
    onActions: (ActivityEntry, androidx.compose.ui.geometry.Rect?) -> Unit,
    /** "Clear older than a week" in the ⋯ menu; asks first, and offers Undo. */
    onClearOld: () -> Unit,
    /** A row swiped away: deleted, with Undo. */
    onDelete: (ActivityEntry) -> Unit = {},
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { filters.size }
    // One tick the moment a new filter is chosen (a tap, or a swipe let go past halfway), not
    // when the list finishes settling: the feel belongs to the decision.
    var lastPage by remember { mutableIntStateOf(pager.targetPage) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.targetPage }.collect { page ->
            if (page != lastPage) haptics.perform(Haptic.Selection)
            lastPage = page
        }
    }

    // The pages fill the rest of the screen, so a swipe works anywhere below the filters, even
    // on an empty filter. Measured once with the list at the top; kept while it scrolls.
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    app.wallhop.ui.design.ScrollToTopOnReselect(list)
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

    Page(
        title = "Activity",
        state = list,
        // Its only item is "Clear older than a week", so it's there only when that has something to clear.
        trailing = if (app.wallhop.core.Wording.olderThan(activity, System.currentTimeMillis(), 7) { it.time }.isEmpty()) null else {
            {
                app.wallhop.ui.components.CircleButton(WIcons.More, "More", onClick = { anchor ->
                    overlays.showMenu(anchor, listOf(app.wallhop.ui.components.MenuItem("Clear older than a week", destructive = false) { onClearOld() }))
                })
            }
        },
    ) {
        item(key = "filter") {
            Column(Modifier.staggerIn(1)) {
            SegmentedControl(
                filters,
                selected = pager.targetPage,
                onSelect = { scope.launch { pager.animateScrollToPage(it, animationSpec = androidx.compose.animation.core.spring(1f, 500f)) } },
                position = { pager.currentPage + pager.currentPageOffsetFraction },
            )
            Spacer(Modifier.height(24.dp))
            }
        }
        item(key = "pages") {
            // The history is capped at 30 entries, so each page is a plain column.
            HorizontalPager(
                pager,
                modifier = Modifier
                    .staggerIn(2)
                    .heightIn(min = pagesMin)
                    // Out past the list's 20 dp margins, so a page slides off the screen's edge
                    // rather than being cut at the margin; the padding puts the content back in line.
                    .layout { measurable, constraints ->
                        val extra = 40.dp.roundToPx()
                        val width = constraints.maxWidth + extra
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
                    },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                verticalAlignment = Alignment.Top,
                pageSpacing = 16.dp,
                beyondViewportPageCount = 1,
            ) { page ->
                Box(Modifier.fillMaxWidth().heightIn(min = pagesMin)) {
                    ActivityPage(activity.filter { matches(it, page) }, page, onTap, onActions, onDelete)
                }
            }
        }
    }
}

private fun matches(entry: ActivityEntry, filter: Int) = when (filter) {
    1 -> entry.kind == ActivityEntry.Kind.Otp
    // Files and photos, both ways. Text and links sent stay under All.
    2 -> entry.kind == ActivityEntry.Kind.FilesSent || (entry.kind == ActivityEntry.Kind.FromMac && entry.uris.isNotEmpty())
    3 -> entry.kind == ActivityEntry.Kind.Call
    else -> true
}

@Composable
private fun ActivityPage(
    entries: List<ActivityEntry>,
    filter: Int,
    onTap: (ActivityEntry) -> Unit,
    onActions: (ActivityEntry, androidx.compose.ui.geometry.Rect?) -> Unit,
    onDelete: (ActivityEntry) -> Unit,
) {
    val context = LocalContext.current
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
                    2 -> "Files and photos sent to or from your Mac show up here."
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
                            // Tied to the entry, not the slot: when a row above leaves, the next one
                            // mustn't take over its swipe (or anything else it remembered).
                            key(entry.key) {
                                AnimatedVisibility(entry.visible, enter = rowEnter(reduceMotion), exit = rowExit(reduceMotion)) {
                                    SwipeToDelete(onDelete = { onDelete(entry.item) }) {
                                        ActivityRow(entry.item, onLongClick = { onActions(entry.item, it) }, onClick = { onTap(entry.item) })
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** Actions for one entry (long press); the same on Home and in Activity. */
internal fun showEntryActions(
    overlays: Overlays,
    context: android.content.Context,
    entry: ActivityEntry,
    haptics: Haptics? = null,
    onDelete: (String) -> Unit,
    /** The row that was long-pressed: the menu grows from it. Without one, a sheet from the bottom. */
    anchor: androidx.compose.ui.geometry.Rect? = null,
    /** Files sent to the Mac: send them again. */
    onSendAgain: ((ActivityEntry) -> Unit)? = null,
) {
    val actions = buildList {
            if (entry.kind == ActivityEntry.Kind.Otp) {
                add(AlertAction("Copy code") { copyEntryCode(overlays, context, entry, haptics) })
            }
            if (entry.kind == ActivityEntry.Kind.FromMac && entry.uris.isNotEmpty()) {
                add(AlertAction(if (entry.uris.size == 1) "Open" else "Open in Downloads") { openReceived(overlays, context, entry) })
                add(AlertAction("Share") {
                    val uris = entry.uris.map(android.net.Uri::parse)
                    runCatching {
                        context.startActivity(app.wallhop.link.Notifications.shareChooser(uris, uris.map { context.contentResolver.getType(it) ?: "*/*" }))
                    }
                })
            }
            if (entry.kind == ActivityEntry.Kind.FilesSent && entry.uris.isNotEmpty() && onSendAgain != null) {
                add(AlertAction("Send again") { onSendAgain(entry) })
            }
            linkOf(entry)?.let { url -> add(AlertAction("Open link") { app.wallhop.core.Open.link(context, url) }) }
            add(AlertAction("Delete", destructive = true) { onDelete(entry.id) })
        }
    if (anchor != null) {
        // From the row, like a context menu: the finger is already there.
        overlays.showMenu(anchor, actions.map { action ->
            MenuItem(action.label, icon = entryActionIcon(action.label), destructive = action.destructive) { action.onClick() }
        })
    } else {
        overlays.showActionSheet(ActionSheetSpec(message = null, actions = actions))
    }
}

private fun entryActionIcon(label: String) = when (label) {
    "Copy code" -> WIcons.Clipboard
    "Open", "Open in Downloads" -> WIcons.File
    "Open link" -> WIcons.Link
    "Send again" -> WIcons.Retry
    "Delete" -> WIcons.XCircle
    else -> null
}

/** The link a "Link opened on your Mac" or "Link from your Mac" entry is about, if any. */
private fun linkOf(entry: ActivityEntry): String? = entry.detail.trim().takeIf {
    (entry.kind == ActivityEntry.Kind.TextSent || (entry.kind == ActivityEntry.Kind.FromMac && entry.uris.isEmpty())) &&
        app.wallhop.extras.isSingleUrl(it)
}

/** Copies a code, marked sensitive, with "Code copied". */
private fun copyEntryCode(overlays: Overlays, context: android.content.Context, entry: ActivityEntry, haptics: Haptics?) {
    copyCode(context, entry.title)
    haptics?.confirm()
    overlays.toast("Code copied", WIcons.CheckCircle)
}

/** Files from the Mac: one opens in its app; several open Downloads, where Lync's folder is. */
private fun openReceived(overlays: Overlays, context: android.content.Context, entry: ActivityEntry) {
    val uris = entry.uris.map(android.net.Uri::parse)
    // Moved or deleted since (Downloads tidied up): say so, rather than an app opening nothing.
    if (uris.size == 1 && !exists(context, uris.first())) {
        overlays.toast(app.wallhop.core.Problems.FILE_GONE, WIcons.Warning)
        return
    }
    val intent = if (uris.size == 1) {
        app.wallhop.link.Notifications.viewIntent(uris.first(), context.contentResolver.getType(uris.first()))
    } else {
        app.wallhop.link.Notifications.downloadsIntent()
    }
    runCatching { context.startActivity(intent) }.onFailure { overlays.toast("No app here can open this") }
}

/** The file is still there (its Downloads entry answers a query). */
private fun exists(context: android.content.Context, uri: android.net.Uri): Boolean = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { it.moveToFirst() } ?: false
}.getOrDefault(false)

/**
 * A tap on an entry, on Home and in Activity: a code is copied; files from the Mac open; a link
 * opens on the phone; anything else shows its actions (long press always does).
 */
internal fun tapEntry(
    overlays: Overlays,
    context: android.content.Context,
    entry: ActivityEntry,
    haptics: Haptics?,
    onActions: (ActivityEntry) -> Unit,
) {
    val link = linkOf(entry)
    when {
        entry.kind == ActivityEntry.Kind.Otp -> copyEntryCode(overlays, context, entry, haptics)
        entry.kind == ActivityEntry.Kind.FromMac && entry.uris.isNotEmpty() -> openReceived(overlays, context, entry)
        link != null -> app.wallhop.core.Open.link(context, link)
        else -> onActions(entry)
    }
}

/**
 * One entry. Tap does the obvious thing ([tapEntry]); long press offers the actions.
 * [showDay]: lists that aren't grouped by day (Home's Recent) say "Yesterday" or the date.
 */
@Composable
internal fun ActivityRow(entry: ActivityEntry, onLongClick: (androidx.compose.ui.geometry.Rect?) -> Unit, onClick: (() -> Unit)? = null, showDay: Boolean = false) {
    val colors = Wallhop.colors
    // Where the row is, so a long press's menu can grow out of it.
    var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val context = LocalContext.current
    val (icon, color) = when (entry.kind) {
        ActivityEntry.Kind.Otp -> WIcons.Code to colors.green
        ActivityEntry.Kind.TextSent -> WIcons.Text to colors.tint
        ActivityEntry.Kind.FilesSent -> WIcons.File to colors.tint
        ActivityEntry.Kind.FromMac -> WIcons.Laptop to colors.gray
        ActivityEntry.Kind.Call -> WIcons.CallMissed to colors.red
    }
    val title = if (entry.kind == ActivityEntry.Kind.Otp) app.wallhop.core.Wording.spaced(entry.title) else entry.title
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
                if (showDay) rowTime(context, entry.time) else DateUtils.formatDateTime(context, entry.time, DateUtils.FORMAT_SHOW_TIME),
                style = Type.caption1,
                color = colors.labelSecondary,
            )
        },
        onLongClickLabel = "Show actions",
        onClickLabel = when {
            entry.kind == ActivityEntry.Kind.Otp -> "Copy code"
            entry.kind == ActivityEntry.Kind.FromMac && entry.uris.isNotEmpty() -> "Open"
            linkOf(entry) != null -> "Open link"
            else -> null
        },
        onLongClick = { onLongClick(bounds) },
        onClick = onClick,
        modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
    )
}

/** The time today, otherwise "Yesterday" or a short date. */
private fun rowTime(context: android.content.Context, time: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    return when (java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()) {
        today -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_TIME)
        today.minusDays(1) -> "Yesterday"
        else -> DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
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
