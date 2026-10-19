package app.wallhop.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.wallhop.link.ActivityEntry
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkState
import app.wallhop.link.TransferPhase
import app.wallhop.link.TransferState
import app.wallhop.ui.components.LocalOverlays
import app.wallhop.ui.components.LocalScreenActive
import app.wallhop.ui.components.DisabledAlpha
import app.wallhop.ui.components.ProgressBar
import app.wallhop.ui.components.copyCode
import app.wallhop.ui.components.spacedCode
import app.wallhop.ui.design.Figtree
import app.wallhop.ui.design.HomeColors
import app.wallhop.ui.design.Ink
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.tappable
import kotlinx.coroutines.delay

class MacActions(
    val sendPhotos: () -> Unit,
    val sendFiles: () -> Unit,
    val sendText: () -> Unit,
    val sendClipboard: () -> Unit,
    val cancelTransfer: () -> Unit,
    val dismissTransfer: () -> Unit,
    /** Sends a failed transfer's files again. */
    val retryTransfer: (TransferState) -> Unit,
    val openSettings: () -> Unit,
    val openActivity: () -> Unit,
    /** The Mac details sheet (DESIGN.md §6.4). */
    val macDetails: () -> Unit,
    /** Copy code / Delete for a Recent entry. */
    val entryActions: (ActivityEntry) -> Unit,
    val more: (Rect) -> Unit,
)

// The home screen's type: calm medium weights, like the reference (DESIGN.md §6.4).
private fun style(size: Int, weight: Int, tracking: Float = 0f, line: Int = (size * 1.25f).toInt()) = TextStyle(
    fontFamily = Figtree, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), letterSpacing = tracking.sp,
)
private val Title = style(30, 500, -0.6f)
private val Section = style(22, 500, -0.2f)
private val CardTitle = style(19, 600, -0.1f, 24)
private val Overline = style(12, 600, 0.1f)
private val Body = style(13, 500)
private val Label = style(14, 600)
private val Meta = style(12, 500)
private val TransferTitle = style(22, 600, -0.2f, 27)
private val CodeStyle = style(40, 600, 2.4f, 46)

/**
 * Home (DESIGN.md §6.4). The whole screen takes its colour from the phone's Material You
 * palette (your phone wallpaper) and fades
 * to near-black. A row of cards — the Mac, then the latest thing that went across — and the
 * ways to send below.
 */
@Composable
fun MacTab(
    state: LinkState,
    macName: String,
    transfer: TransferState?,
    needsSetup: Boolean,
    actions: MacActions,
    card: MacCardData,
) {
    val connected = state is LinkState.Connected
    val home = Wallhop.home
    val overlays = LocalOverlays.current
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Landscape and camera cutouts: keep content clear of the sides.
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val sidesPx = sides.getLeft(density, LocalLayoutDirection.current) + sides.getRight(density, LocalLayoutDirection.current)
    val screen = with(density) { (LocalWindowInfo.current.containerSize.width - sidesPx).toDp() }
    // Text and Clipboard need the Mac now; photos and files wait for it, like the share sheet.
    fun needsMac(action: () -> Unit): () -> Unit = {
        if (connected) action() else overlays?.toast("Your Mac isn't connected", WIcons.Warning)
    }
    val since by produceState<Long?>(null, card.connectedSince) {
        val start = card.connectedSince ?: run { value = null; return@produceState }
        while (true) {
            value = System.currentTimeMillis() - start
            delay(60_000)
        }
    }
    val meta = heroMeta(card.info, connected, since)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to home.top, 0.46f to home.mid, 0.72f to Ink, 1f to Ink)),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(sides),
            contentPadding = PaddingValues(top = statusBar + 18.dp, bottom = navBar + 112.dp),
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Home", style = Title, color = Color.White, modifier = Modifier.weight(1f))
                    RoundButton(WIcons.More, "More", Color.White.copy(alpha = 0.16f), onClick = actions.more)
                }
            }
            item(key = "mac-title") {
                SectionTitle(
                    "Your Mac",
                    when {
                        !connected -> "Looking for your Mac…"
                        card.info.locked -> "Connected · Mac is locked"
                        else -> "Connected over Wi‑Fi" + (card.latencyMs?.let { " · $it ms" } ?: "")
                    },
                    onClick = actions.macDetails,
                    modifier = Modifier.padding(top = 22.dp),
                )
            }
            item(key = "cards") {
                // The Mac card fills the width. When there's more (a transfer, the latest item),
                // it narrows so the next card peeks in from the right and the row snaps card by card.
                val others = (if (transfer != null) 1 else 0) + (if (card.latest != null) 1 else 0)
                val heroWidth = (screen - 40.dp - if (others > 0) 64.dp else 0.dp).coerceAtMost(520.dp)
                val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyRow(
                    state = rowState,
                    flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(rowState),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // One height for the row, so every card lines up top and bottom: the tallest
                    // card's content, which grows with a two-line Mac name or a larger font.
                    modifier = Modifier.padding(top = 16.dp).height(cardRowHeight(macName, meta, heroWidth, transfer, card.latest)),
                ) {
                    if (transfer != null) item(key = "transfer") { TransferCard(transfer, home, actions) }
                    item(key = "mac") { MacHero(macName, connected, meta, card, home, actions, heroWidth, needsMac(actions.sendClipboard)) }
                    card.latest?.let { latest -> item(key = "latest") { LatestCard(latest, home, actions) } }
                }
            }
            item(key = "send-title") {
                SectionTitle(
                    "Send to Mac",
                    if (connected) null else "Your Mac isn't connected. Photos and files go across once it's back.",
                    onClick = null,
                    modifier = Modifier.padding(top = 26.dp),
                )
            }
            item(key = "send") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    item { Chip(WIcons.Image, "Photos", true, actions.sendPhotos) }
                    item { Chip(WIcons.File, "Files", true, actions.sendFiles) }
                    item { Chip(WIcons.Text, "Text", connected, needsMac(actions.sendText)) }
                    item { Chip(WIcons.Clipboard, "Clipboard", connected, needsMac(actions.sendClipboard)) }
                }
            }
            if (card.recent.isNotEmpty()) {
                item(key = "recent-title") {
                    SectionTitle("Recent", null, onClick = actions.openActivity, modifier = Modifier.padding(top = 26.dp))
                }
                item(key = "recent") {
                    app.wallhop.ui.components.InsetSection(
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                        dividerInset = 64.dp,
                    ) {
                        card.recent.forEach { entry ->
                            row {
                                ActivityRow(entry, onLongClick = { actions.entryActions(entry) }, onClick = { actions.entryActions(entry) })
                            }
                        }
                    }
                }
            }
            if (needsSetup) {
                item(key = "setup") {
                    SectionTitle(
                        "Finish setup",
                        "Codes, calls and texts reach your Mac once these are allowed.",
                        onClick = actions.openSettings,
                        modifier = Modifier.padding(top = 26.dp),
                    )
                }
            }
        }
    }
}

/** The cards' height at the default font with a one-line Mac name; they grow from here. */
private val CardHeight = 330.dp
private val SmallCardWidth = 204.dp

/**
 * The row's height: the tallest card's content. The cards' text is measured at their width,
 * so a two-line Mac name or a large font size never pushes a button out of a card.
 */
@Composable
private fun cardRowHeight(name: String, meta: String, heroWidth: Dp, transfer: TransferState?, latest: ActivityEntry?): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    fun text(value: String, style: TextStyle, width: Dp, maxLines: Int = Int.MAX_VALUE): Dp = with(density) {
        if (value.isEmpty()) return 0.dp
        measurer.measure(value, style, maxLines = maxLines, constraints = Constraints(maxWidth = width.roundToPx().coerceAtLeast(1)))
            .size.height.toDp()
    }
    val inner = heroWidth - 32.dp
    // Picture, padding, gaps and the button row, plus the text.
    val hero = 170.dp + 30.dp + 12.dp + 14.dp + 48.dp +
        text(kind(name, true), Overline, inner) + text(name, CardTitle, inner, 2) + text(meta, Meta, inner, 2)
    val small = SmallCardWidth - 32.dp
    val transferCard = transfer?.let {
        val failed = it.phase == TransferPhase.Failed
        val done = it.phase == TransferPhase.Done || failed
        32.dp + 8.dp + (if (done) 0.dp else 16.dp) + (if (failed) 104.dp else 48.dp) +
            text(transferStatus(it), Overline, small) + text(LinkManager.summary(it.items), TransferTitle, small, 3) +
            text(transferMessage(it), Meta, small, 4)
    } ?: 0.dp
    val latestCard = latest?.let {
        val title = if (it.kind == ActivityEntry.Kind.Otp) text(spacedCode(it.title).replace(" ", "\n"), CodeStyle, small)
            else text(it.title, TransferTitle, small, 3)
        32.dp + 8.dp + 48.dp + text(latestLabel(it), Overline, small) + title + text(it.detail, Meta, small, 2)
    } ?: 0.dp
    return maxOf(CardHeight, hero, transferCard, latestCard)
}

/** "82% battery, charging · on for 2 h 14 m". */
private fun heroMeta(info: app.wallhop.link.MacInfo, connected: Boolean, since: Long?): String = buildList {
    if (info.laptop && info.battery != null) add("${info.battery}% battery" + if (info.charging) ", charging" else "")
    when {
        !connected -> add("away")
        since == null -> Unit
        since < 60_000 -> add("just connected")
        else -> add("on for ${duration(since)}")
    }
}.joinToString(" · ")

private fun transferStatus(transfer: TransferState) = when (transfer.phase) {
    TransferPhase.Waiting -> "Waiting for your Mac"
    TransferPhase.Sending -> "Sending"
    TransferPhase.Done -> "On your Mac"
    TransferPhase.Failed -> "Didn’t send"
}

private fun transferMessage(transfer: TransferState) = transfer.message ?: when (transfer.phase) {
    TransferPhase.Done -> "Copied to your Mac's clipboard. Paste it, or save it there."
    else -> "${app.wallhop.ui.components.formatBytes(transfer.sentBytes)} of ${app.wallhop.ui.components.formatBytes(transfer.totalBytes)}"
}

private fun latestLabel(entry: ActivityEntry) = when (entry.kind) {
    ActivityEntry.Kind.Otp -> "Latest code"
    ActivityEntry.Kind.FilesSent -> "Latest files"
    ActivityEntry.Kind.TextSent -> "Latest text"
    ActivityEntry.Kind.Call -> "Missed call"
    ActivityEntry.Kind.FromMac -> "From your Mac"
}

/** "Title ›" with a quiet line under it. */
@Composable
private fun SectionTitle(title: String, subtitle: String?, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.tappable(source, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Section, color = Color.White)
            if (onClick != null) Icon(WIcons.ChevronRight, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        if (subtitle != null) {
            Text(subtitle, style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The Mac: its live miniature on its own wallpaper, then its name and a quick action. */
@Composable
private fun MacHero(
    name: String,
    connected: Boolean,
    meta: String,
    card: MacCardData,
    home: HomeColors,
    actions: MacActions,
    width: Dp,
    sendClipboard: () -> Unit,
) {
    val info = card.info
    val wallpaper = remember(info.wallpaper) { info.wallpaper?.asImageBitmap() }

    CardBox(width = width, color = home.card, modifier = Modifier.fillMaxHeight()) {
        Box(Modifier.fillMaxWidth().height(170.dp).background(home.top), contentAlignment = Alignment.Center) {
            if (wallpaper != null) {
                // Blurred, so the little Mac (which shows the same wallpaper) stands out.
                Image(wallpaper, null, Modifier.fillMaxSize().blur(28.dp).alpha(0.6f), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, home.card.copy(alpha = 0.55f)))))
            LiveMac(connected, card, Modifier.width((width * 0.6f).coerceIn(196.dp, 228.dp)).padding(top = 12.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            Text(kind(name, info.laptop), style = Overline, color = Color.White.copy(alpha = 0.72f))
            Text(name, style = CardTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            Text(meta, style = Meta, color = Color.White.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(WIcons.Clipboard, "Send clipboard", home.pale, home.onPale, connected, sendClipboard, Modifier.weight(1f))
                RoundButton(WIcons.More, "More", Color.White.copy(alpha = 0.12f), onClick = actions.more)
            }
        }
    }
}

/** The newest thing that went across, in a contrasting colour. A code can be copied here. */
@Composable
private fun LatestCard(entry: ActivityEntry, home: HomeColors, actions: MacActions) {
    val context = LocalContext.current
    val overlays = LocalOverlays.current
    val isCode = entry.kind == ActivityEntry.Kind.Otp
    CardBox(width = SmallCardWidth, color = home.peek, modifier = Modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
            Text(latestLabel(entry), style = Overline, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.weight(1f))
            if (isCode) {
                Text(spacedCode(entry.title).replace(" ", "\n"), style = CodeStyle, color = Color.White)
            } else {
                Text(entry.title, style = TransferTitle, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(entry.detail, style = Meta, color = Color.White.copy(alpha = 0.78f), maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            if (isCode) {
                PillButton(WIcons.Clipboard, "Copy", home.peekPale, home.onPeekPale, true, onClick = {
                    copyCode(context, entry.title)
                    overlays?.toast("Code copied", WIcons.CheckCircle)
                }, modifier = Modifier.fillMaxWidth())
            } else {
                PillButton(WIcons.Activity, "Activity", home.peekPale, home.onPeekPale, true, actions.openActivity, Modifier.fillMaxWidth())
            }
        }
    }
}

/** A transfer in progress takes the first spot in the row until it's dismissed. */
@Composable
private fun TransferCard(transfer: TransferState, home: HomeColors, actions: MacActions) {
    val title = LinkManager.summary(transfer.items)
    val failed = transfer.phase == TransferPhase.Failed
    val done = transfer.phase == TransferPhase.Done || failed
    CardBox(width = SmallCardWidth, color = home.card, modifier = Modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (failed) Icon(WIcons.Warning, null, tint = Wallhop.colors.red, modifier = Modifier.size(14.dp))
                Text(transferStatus(transfer), style = Overline, color = Color.White.copy(alpha = 0.72f))
            }
            Spacer(Modifier.weight(1f))
            Text(title, style = TransferTitle, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                transferMessage(transfer),
                style = Meta, color = Color.White.copy(alpha = 0.72f), maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!done) {
                ProgressBar(if (transfer.phase == TransferPhase.Waiting) null else transfer.progress,
                    Modifier.padding(top = 12.dp), color = home.pale)
            }
            Spacer(Modifier.weight(1f))
            if (failed) {
                PillButton(WIcons.Retry, "Try again", home.pale, home.onPale, true, { actions.retryTransfer(transfer) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PillButton(WIcons.XCircle, "Dismiss", Color.White.copy(alpha = 0.12f), Color.White, true,
                    actions.dismissTransfer, Modifier.fillMaxWidth())
            } else {
                PillButton(
                    if (done) WIcons.CheckCircle else WIcons.XCircle, if (done) "Done" else "Cancel",
                    home.pale, home.onPale, true,
                    if (done) actions.dismissTransfer else actions.cancelTransfer,
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CardBox(width: Dp, color: Color, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier.width(width).clip(RoundedCornerShape(28.dp)).background(color)) { content() }
}

/** Pale pill button, the card's main action. Not [enabled]: dimmed, but a tap still explains why. */
@Composable
private fun PillButton(
    icon: ImageVector, label: String, container: Color, content: Color, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(48.dp)
            .pressScale(source)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .tappable(source, onClick = onClick)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Label, color = content, maxLines = 1)
    }
}

/** See-through round icon button. Reports where it is, for menus that grow out of it. */
@Composable
private fun RoundButton(icon: ImageVector, description: String, container: Color, onClick: (Rect) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val bounds = remember { arrayOf(Rect.Zero) }
    Box(
        Modifier
            .size(48.dp)
            .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
            .pressScale(source)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .tappable(source, onClick = { onClick(bounds[0]) }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/** See-through chip: a way to send. Not [enabled]: dimmed, but a tap still explains why. */
@Composable
private fun Chip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .height(48.dp)
            .pressScale(source)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.13f))
            .tappable(source, onClick = onClick)
            .alpha(if (enabled) 1f else DisabledAlpha)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = Label, color = Color.White)
    }
}

/** "MacBook Air", "MacBook Pro", or just "Mac" for desktops. */
private fun kind(name: String, laptop: Boolean): String = when {
    name.contains("MacBook Air", ignoreCase = true) -> "MacBook Air"
    name.contains("MacBook Pro", ignoreCase = true) -> "MacBook Pro"
    laptop -> "MacBook"
    else -> "Mac"
}

/**
 * The Mac details sheet (DESIGN.md §6.4): how the Mac is connected, its battery, and Forget.
 * Opened from "Your Mac ›", the ⋯ menu and the Mac row in Settings.
 */
@Composable
fun MacDetails(
    state: LinkState,
    info: app.wallhop.link.MacInfo,
    latencyMs: Int?,
    connectedSince: Long?,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Wallhop.colors
    val connected = state as? LinkState.Connected
    app.wallhop.ui.components.InsetSection(dividerInset = 57.dp) {
        row {
            app.wallhop.ui.components.ListRow(
                title = "Status",
                leading = { app.wallhop.ui.components.IconTile(WIcons.Wifi, if (connected != null) colors.green else colors.gray) },
                value = when {
                    connected == null -> "Looking for it…"
                    info.locked -> "Connected, locked"
                    else -> "Connected"
                },
            )
        }
        if (connected != null) {
            row {
                app.wallhop.ui.components.ListRow(
                    title = "Address",
                    leading = { app.wallhop.ui.components.IconTile(WIcons.Laptop, colors.gray) },
                    value = connected.host,
                )
            }
            if (latencyMs != null) {
                row {
                    app.wallhop.ui.components.ListRow(
                        title = "Response time",
                        leading = { app.wallhop.ui.components.IconTile(WIcons.Gauge, colors.indigo) },
                        value = "$latencyMs ms",
                    )
                }
            }
        }
        if (info.laptop && info.battery != null) {
            row {
                app.wallhop.ui.components.ListRow(
                    title = "Battery",
                    leading = { app.wallhop.ui.components.IconTile(WIcons.BatteryCharging, colors.orange) },
                    value = "${info.battery}%" + if (info.charging) ", charging" else "",
                )
            }
        }
        if (connected != null && connectedSince != null) {
            row {
                app.wallhop.ui.components.ListRow(
                    title = "Connected since",
                    leading = { app.wallhop.ui.components.IconTile(WIcons.Clock, colors.gray) },
                    value = android.text.format.DateUtils.formatDateTime(context, connectedSince, android.text.format.DateUtils.FORMAT_SHOW_TIME),
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    app.wallhop.ui.components.InsetSection(dividerInset = 57.dp) {
        row {
            app.wallhop.ui.components.ListRow(
                title = "Forget this Mac",
                leading = { app.wallhop.ui.components.IconTile(WIcons.LinkBreak, colors.red) },
                destructive = true,
                onClick = onForget,
            )
        }
    }
}
