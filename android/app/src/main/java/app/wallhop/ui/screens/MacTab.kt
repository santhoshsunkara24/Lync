package app.wallhop.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.ui.platform.LocalView
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
import app.wallhop.ui.components.ProgressBar
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
    val openSettings: () -> Unit,
    val openActivity: () -> Unit,
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
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to home.top, 0.46f to home.mid, 0.72f to Ink, 1f to Ink)),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
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
                    onClick = actions.openSettings,
                    modifier = Modifier.padding(top = 22.dp),
                )
            }
            item(key = "cards") {
                // The Mac card fills the width. When there's more (a transfer, the latest item),
                // it narrows so the next card peeks in from the right and the row snaps card by card.
                val others = (if (transfer != null) 1 else 0) + (if (card.latest != null) 1 else 0)
                val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                val heroWidth = (screen - 40.dp - if (others > 0) 64.dp else 0.dp).coerceAtMost(520.dp)
                val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyRow(
                    state = rowState,
                    flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(rowState),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // One height for the row, so every card lines up top and bottom.
                    modifier = Modifier.padding(top = 16.dp).height(CardHeight),
                ) {
                    if (transfer != null) item(key = "transfer") { TransferCard(transfer, home, actions) }
                    item(key = "mac") { MacHero(macName, connected, card, home, actions, heroWidth) }
                    card.latest?.let { latest -> item(key = "latest") { LatestCard(latest, home, actions) } }
                }
            }
            item(key = "send-title") {
                SectionTitle("Send to Mac", null, onClick = null, modifier = Modifier.padding(top = 26.dp))
            }
            item(key = "send") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    item { Chip(WIcons.Image, "Photos", connected, actions.sendPhotos) }
                    item { Chip(WIcons.File, "Files", connected, actions.sendFiles) }
                    item { Chip(WIcons.Text, "Text", connected, actions.sendText) }
                    item { Chip(WIcons.Clipboard, "Clipboard", connected, actions.sendClipboard) }
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
                        card.recent.forEach { entry -> row { ActivityRow(entry, onLongClick = actions.openActivity) } }
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

private val CardHeight = 330.dp

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
private fun MacHero(name: String, connected: Boolean, card: MacCardData, home: HomeColors, actions: MacActions, width: Dp) {
    val info = card.info
    val wallpaper = remember(info.wallpaper) { info.wallpaper?.asImageBitmap() }
    val since by produceState<Long?>(null, card.connectedSince) {
        val start = card.connectedSince ?: run { value = null; return@produceState }
        while (true) {
            value = System.currentTimeMillis() - start
            delay(60_000)
        }
    }
    val meta = buildList {
        if (info.laptop && info.battery != null) add("${info.battery}% battery" + if (info.charging) ", charging" else "")
        when {
            !connected -> add("away")
            since == null -> Unit
            since!! < 60_000 -> add("just connected")
            else -> add("on for ${duration(since!!)}")
        }
    }.joinToString(" · ")

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
            Text(meta, style = style(12, 500), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(WIcons.Clipboard, "Send clipboard", home.pale, home.onPale, connected, actions.sendClipboard, Modifier.weight(1f))
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
    CardBox(width = 204.dp, color = home.peek, modifier = Modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
            Text(
                when (entry.kind) {
                    ActivityEntry.Kind.Otp -> "Latest code"
                    ActivityEntry.Kind.FilesSent -> "Latest files"
                    ActivityEntry.Kind.TextSent -> "Latest text"
                    ActivityEntry.Kind.Call -> "Missed call"
                    ActivityEntry.Kind.FromMac -> "From your Mac"
                },
                style = Overline, color = Color.White.copy(alpha = 0.8f),
            )
            Spacer(Modifier.weight(1f))
            if (isCode) {
                Text(spacedCode(entry.title).replace(" ", "\n"), style = style(40, 600, 2.4f, 46), color = Color.White)
            } else {
                Text(entry.title, style = style(22, 600, -0.2f, 27), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(entry.detail, style = style(12, 500), color = Color.White.copy(alpha = 0.78f), maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            if (isCode) {
                PillButton(WIcons.Clipboard, "Copy", home.peekPale, home.onPeekPale, true, onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Code", entry.title))
                    overlays?.toast("Copied")
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
    val done = transfer.phase == TransferPhase.Done || transfer.phase == TransferPhase.Failed
    CardBox(width = 204.dp, color = home.card, modifier = Modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
            Text(
                when (transfer.phase) {
                    TransferPhase.Waiting -> "Waiting for your Mac"
                    TransferPhase.Sending -> "Sending"
                    TransferPhase.Done -> "On your Mac"
                    TransferPhase.Failed -> "Didn’t send"
                },
                style = Overline, color = Color.White.copy(alpha = 0.72f),
            )
            Spacer(Modifier.weight(1f))
            Text(title, style = style(22, 600, -0.2f, 27), color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                transfer.message ?: when (transfer.phase) {
                    TransferPhase.Done -> "Copied to your Mac's clipboard. Paste it, or save it there."
                    else -> "${app.wallhop.ui.components.formatBytes(transfer.sentBytes)} of ${app.wallhop.ui.components.formatBytes(transfer.totalBytes)}"
                },
                style = style(12, 500), color = Color.White.copy(alpha = 0.72f), modifier = Modifier.padding(top = 8.dp),
            )
            if (!done) {
                ProgressBar(if (transfer.phase == TransferPhase.Waiting) null else transfer.progress,
                    Modifier.padding(top = 12.dp), color = home.pale)
            }
            Spacer(Modifier.weight(1f))
            PillButton(
                if (done) WIcons.CheckCircle else WIcons.XCircle, if (done) "Done" else "Cancel",
                home.pale, home.onPale, true,
                if (done) actions.dismissTransfer else actions.cancelTransfer,
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CardBox(width: Dp, color: Color, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier.width(width).clip(RoundedCornerShape(28.dp)).background(color)) { content() }
}

/** Pale pill button, the card's main action. */
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
            .tappable(source, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
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

/** See-through chip: a way to send. */
@Composable
private fun Chip(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .height(48.dp)
            .pressScale(source)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.13f))
            .tappable(source, enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
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
