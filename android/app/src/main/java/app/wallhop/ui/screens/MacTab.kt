package app.wallhop.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import app.wallhop.core.Features
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wallhop.link.ActivityEntry
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkState
import app.wallhop.link.TransferPhase
import app.wallhop.link.TransferState
import app.wallhop.ui.components.LocalOverlays
import app.wallhop.ui.components.DisabledAlpha
import app.wallhop.ui.components.ProgressBar
import app.wallhop.ui.components.copyCode
import app.wallhop.ui.design.Figtree
import app.wallhop.ui.design.Radius
import app.wallhop.ui.design.Space
import app.wallhop.ui.design.HomeColors
import app.wallhop.ui.design.Ink
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.AnimatedChangeText
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Fades
import app.wallhop.ui.design.LocalStagger
import app.wallhop.ui.design.RollingCode
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.confirm
import app.wallhop.ui.design.pressDim
import app.wallhop.ui.design.rememberAnimatedRows
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.rememberStaggerClock
import app.wallhop.ui.design.shake
import app.wallhop.ui.design.staggerIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
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
    val openActivity: () -> Unit,
    /** The Mac details sheet (DESIGN.md §6.4). */
    val macDetails: () -> Unit,
    /** Copy code / Delete for a Recent entry (long press). */
    val entryActions: (ActivityEntry, androidx.compose.ui.geometry.Rect?) -> Unit,
    /** A Recent row swiped away: deleted, with Undo. */
    val deleteEntry: (ActivityEntry) -> Unit,
    /** A tap on a Recent entry: copy the code, open the file or link, or the actions. */
    val entryTap: (ActivityEntry) -> Unit,
    val more: (Rect) -> Unit,
    /** The Home suggestion's Set up: the ask sheet for that feature. */
    val setUpSuggestion: (Features.Key) -> Unit,
    /** The Home suggestion's ×: gone for good. */
    val dismissSuggestion: (Features.Key) -> Unit,
    /** Stops "Ring phone". */
    val stopRinging: () -> Unit,
    /** The Mac forgot this phone: drop the old pairing and pair again. */
    val pairAgain: () -> Unit,
    /** No Mac yet: the scanner, to pair one. */
    val pairMac: () -> Unit = {},
    /** No Mac yet: pair with one found on this Wi-Fi (both show the same code). */
    val pairWith: (app.wallhop.link.DiscoveredMac) -> Unit = {},
    /** Tries to reach the Mac now, then says connected or why not. */
    val retryConnect: () -> Unit,
    /** Does what a problem's button says (Wi‑Fi settings, Pair again, Try again…). */
    val runFix: (app.wallhop.core.Fix) -> Unit = {},
    /** A problem as a toast, with its button. */
    val showProblem: (app.wallhop.core.Problem) -> Unit = {},
)

// The home screen's type: calm medium weights, like the reference (DESIGN.md §6.4).
private fun style(size: Int, weight: Int, tracking: Float = 0f, line: Int = (size * 1.25f).toInt()) = TextStyle(
    fontFamily = Figtree, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), letterSpacing = tracking.sp,
)
// Titles and section titles in Galindo, the wordmark's face, like every page's big title.
private val Title = app.wallhop.ui.design.Type.pageTitle
private val Section = TextStyle(fontFamily = app.wallhop.ui.design.Galindo, fontSize = 22.sp, lineHeight = 28.sp)
private val CardTitle = style(19, 600, -0.1f, 24)
private val Overline = style(12, 600, 0.1f)
private val Body = style(13, 500)
private val Label = style(14, 600)
private val Meta = style(12, 500)
private val TransferTitle = style(22, 600, -0.2f, 27)
private val HeroName = style(24, 500, -0.2f, 30)
private val HeroMeta = style(14, 500)
private val CodeStyle = style(30, 600, 1.2f, 36)

/** The Latest card's code grows with the font only up to 1.3×, so six digits always fit the card. */
private fun codeStyle(fontScale: Float): TextStyle {
    val k = minOf(fontScale, 1.3f) / fontScale.coerceAtLeast(0.01f)
    return CodeStyle.copy(fontSize = CodeStyle.fontSize * k, lineHeight = CodeStyle.lineHeight * k)
}

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
    /** At most one feature to suggest, or null. */
    suggestion: Suggestion?,
    actions: MacActions,
    card: MacCardData,
    ringing: Boolean = false,
    /** Wi-Fi can't reach the Mac but the Bluetooth backup can: codes, calls and notifications still go. */
    bluetooth: Boolean = false,
    /** The Bluetooth backup is on in Lync but this phone's Bluetooth is off: a quiet line says so. */
    bluetoothOff: Boolean = false,
    /** Lync's Bluetooth backup switch is off: away from Wi‑Fi, codes can't reach the Mac. */
    backupOff: Boolean = false,
    /** Macs seen on this Wi-Fi, offered when none is paired. */
    found: List<app.wallhop.link.DiscoveredMac> = emptyList(),
) {
    val connected = state is LinkState.Connected
    val forgotten = state is LinkState.Forgotten
    // No Mac paired: Home invites pairing instead of showing a Mac.
    val unpaired = state is LinkState.NotPaired
    val home = Wallhop.home
    val overlays = LocalOverlays.current
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Landscape and camera cutouts: keep content clear of the sides.
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val sidesPx = sides.getLeft(density, LocalLayoutDirection.current) + sides.getRight(density, LocalLayoutDirection.current)
    val window = with(density) { (LocalWindowInfo.current.containerSize.width - sidesPx).toDp() }
    // Tablets and landscape: one centred column, like the other pages.
    val screen = window.coerceAtMost(MaxContentWidth)
    // Why Text and Clipboard can't go now, for the toast (with its button) and for TalkBack.
    val problem = when {
        unpaired -> app.wallhop.core.Problems.NO_MAC_YET
        forgotten -> app.wallhop.core.Problems.FORGOTTEN
        // Over Bluetooth text and clipboard still go (photos and files wait for Wi‑Fi).
        connected || bluetooth -> null
        else -> app.wallhop.core.Problems.away((state as? LinkState.Searching)?.reason ?: app.wallhop.core.Away.MacNotAnswering)
    }
    val reason = problem?.title
    // Text and Clipboard need the Mac now; photos and files wait for it, like the share sheet.
    fun needsMac(action: () -> Unit): () -> Unit = {
        if (problem == null) action() else actions.showProblem(problem)
    }
    fun needsPairing(action: () -> Unit): () -> Unit = {
        when {
            unpaired -> actions.showProblem(app.wallhop.core.Problems.NO_MAC_YET)
            forgotten -> actions.showProblem(app.wallhop.core.Problems.FORGOTTEN)
            else -> action()
        }
    }
    val since by produceState<Long?>(null, card.connectedSince) {
        val start = card.connectedSince ?: run { value = null; return@produceState }
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            value = elapsed
            // Next update exactly when the next whole minute of connection is reached, so
            // "just connected" never outlasts the first minute.
            delay(60_000 - elapsed.mod(60_000L))
        }
    }
    val meta = heroMeta(card.info, connected, bluetooth, since)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to home.top, 0.46f to home.mid, 0.72f to Ink, 1f to Ink)),
        contentAlignment = Alignment.TopCenter,
    ) {
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        app.wallhop.ui.design.ScrollToTopOnReselect(list)
        CompositionLocalProvider(LocalStagger provides rememberStaggerClock()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxHeight().width(screen + with(density) { sidesPx.toDp() }).windowInsetsPadding(sides),
            contentPadding = PaddingValues(top = statusBar + Space.m, bottom = navBar + 112.dp),
        ) {
            item(key = "header") {
                // Connected, the page is the Mac: its name is the title, the link clasped in front,
                // and ⋯ is that Mac's menu. Otherwise it's Home, with the away card below.
                Column(Modifier.itemMotion(this, 0).fillMaxWidth().padding(horizontal = ScreenGutter)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val source = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .weight(1f)
                                .then(if (connected) Modifier.pressDim(source, 0.7f).tappable(source, onClick = actions.macDetails) else Modifier)
                                .semantics(mergeDescendants = true) { heading() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // A steady title: the Mac's name is on its own section below, so the
                            // page doesn't change its heading every time Wi‑Fi comes and goes.
                            androidx.compose.foundation.text.BasicText(
                                "Home",
                                style = Title.copy(color = Color.White),
                                maxLines = 1,
                                // Still too long at the smallest size: it ends in "…", never cut mid-letter.
                                overflow = TextOverflow.Ellipsis,
                                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = Title.fontSize),
                            )
                        }
                        // Mac details and Forget only mean something once a Mac is paired.
                        if (!unpaired) {
                            Spacer(Modifier.width(Space.s))
                            RoundButton(WIcons.More, "More", Color.White.copy(alpha = 0.16f), onClick = actions.more)
                        }
                    }
                    AnimatedVisibility(
                        visible = connected && card.info.locked,
                        enter = fadeIn(Fades.fadeIn()) + expandVertically(Springs.snappy()),
                        exit = fadeOut(Fades.fadeOut()) + shrinkVertically(Springs.snappy()),
                    ) {
                        Text("Mac is locked", style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = Space.xxs))
                    }
                }
            }
            // Ringing comes first: it's loud, and Stop should be one tap away.
            if (ringing) {
                item(key = "ringing") {
                    Banner(
                        icon = WIcons.BellRinging,
                        title = "Your Mac is ringing this phone",
                        message = null,
                        action = "Stop",
                        onAction = actions.stopRinging,
                        modifier = Modifier.itemMotion(this, -1).padding(start = ScreenGutter, end = ScreenGutter, top = Space.m),
                        live = true,
                    )
                }
            }
            if (forgotten) {
                item(key = "forgotten") {
                    Banner(
                        icon = WIcons.LinkBreak,
                        title = "$macName doesn't recognise this phone",
                        message = "Pair again to keep codes and files going across.",
                        action = "Pair again",
                        onAction = actions.pairAgain,
                        modifier = Modifier.itemMotion(this, -1).padding(start = ScreenGutter, end = ScreenGutter, top = Space.m),
                    )
                }
            }
            // One quiet suggestion at a time, only while linked; × hides it for good.
            if (suggestion != null && connected) {
                item(key = "suggest-${suggestion.key}") {
                    Banner(
                        icon = suggestion.icon,
                        title = suggestion.title,
                        message = suggestion.message,
                        action = "Set up",
                        onAction = { actions.setUpSuggestion(suggestion.key) },
                        onDismiss = { actions.dismissSuggestion(suggestion.key) },
                        modifier = Modifier.itemMotion(this, 1).padding(start = ScreenGutter, end = ScreenGutter, top = Space.m),
                    )
                }
            }
            // Connected, the title says it all; forgotten, the banner above does. Otherwise, why it's away.
            if (unpaired) {
                item(key = "mac-title") {
                    ConnectCard(actions, found, Modifier.itemMotion(this, 1).padding(start = ScreenGutter, end = ScreenGutter, top = SectionGap))
                }
            } else if (!connected && !forgotten && !bluetooth) {
                // Over Bluetooth nothing's wrong: no card. The Mac card says "Connected over
                // Bluetooth" and Send to Mac says what waits for Wi‑Fi.
                item(key = "mac-title") {
                    AwayCard(macName, false, (state as? LinkState.Searching)?.reason, actions,
                        Modifier.itemMotion(this, 1).padding(start = ScreenGutter, end = ScreenGutter, top = SectionGap))
                }
            }
            if (!unpaired && !forgotten && !connected && backupOff) {
                item(key = "backup-off") {
                    BluetoothOffLine("Bluetooth backup is off · codes need Wi‑Fi", { actions.setUpSuggestion(Features.Key.Bluetooth) },
                        Modifier.itemMotion(this, 1).padding(start = ScreenGutter, end = ScreenGutter, top = Space.m))
                }
            } else if (!unpaired && !forgotten && bluetoothOff) {
                item(key = "bluetooth-off") {
                    BluetoothOffLine("Bluetooth is off · codes need Wi‑Fi", { actions.runFix(app.wallhop.core.Fix.TurnOnBluetooth) },
                        Modifier.itemMotion(this, 1).padding(start = ScreenGutter, end = ScreenGutter, top = Space.m))
                }
            }
            if (!unpaired) item(key = "your-mac") {
                // The Mac by its own name ("Santhosh's MacBook Air"), like the Mac shows the phone's.
                SectionTitle(macName.ifBlank { "Your Mac" }, null, onClick = actions.macDetails, modifier = Modifier.itemMotion(this, 2).padding(top = SectionGap))
            }
            if (!unpaired) item(key = "cards") {
                // The Mac card fills the width. When there's more (a transfer, the latest item),
                // it narrows so the next card peeks in from the right and the row snaps card by card.
                val others = (if (transfer != null) 1 else 0) + (if (card.latest != null) 1 else 0)
                val heroWidth = (screen - ScreenGutter * 2 - if (others > 0) 64.dp else 0.dp).coerceAtMost(520.dp)
                val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
                val fadeEdge = with(density) { 40.dp.toPx() }
                LazyRow(
                    state = rowState,
                    flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(rowState),
                    contentPadding = PaddingValues(horizontal = ScreenGutter),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    // One height for the row, so every card lines up top and bottom: the tallest
                    // card's content, which grows with a two-line Mac name or a larger font.
                    modifier = Modifier.itemMotion(this, 2).padding(top = TitleGap).height(cardRowHeight(macName, meta, heroWidth, transfer, card.latest))
                        // The card peeking in from the right fades out at the edge, so it never
                        // shows half a code; it comes clear as it scrolls in.
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            if (rowState.canScrollForward) {
                                drawRect(
                                    Brush.horizontalGradient(
                                        0f to Color.Black, 1f to Color.Transparent,
                                        startX = size.width - fadeEdge * 1.4f, endX = size.width,
                                    ),
                                    topLeft = androidx.compose.ui.geometry.Offset(size.width - fadeEdge * 1.4f, 0f),
                                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                                )
                            }
                        },
                ) {
                    // A transfer card arriving pushes the others along rather than jumping them.
                    if (transfer != null) item(key = "transfer") { Box(Modifier.itemMotion(this, -1)) { TransferCard(transfer, home, actions, heroWidth) } }
                    item(key = "mac") { Box(Modifier.itemMotion(this, -1)) { MacHero(macName, connected || bluetooth, meta, card, home, actions, heroWidth) } }
                    // The latest thing sent, whatever it is (a code too), sits to the right of the Mac.
                    card.latest?.let { latest -> item(key = "latest") { Box(Modifier.itemMotion(this, -1)) { LatestCard(latest, home, actions) } } }
                }
            }
            item(key = "send-title") {
                SectionTitle(
                    "Send to Mac",
                    when {
                        unpaired -> "Pair your Mac to send photos, files and text."
                        forgotten -> "Pair again to send to your Mac."
                        connected -> null
                        bluetooth -> "Text and clipboard go over Bluetooth. Photos and files wait for the same Wi‑Fi."
                        else -> "Photos and files go across once your Mac is back. Text and clipboard need your Mac now."
                    },
                    onClick = null,
                    modifier = Modifier.itemMotion(this, 3).padding(top = SectionGap),
                )
            }
            item(key = "send") {
                val pairReason = when {
                    unpaired -> "Pair your Mac to send"
                    forgotten -> "Pair again to send to your Mac"
                    else -> null
                }
                val chips = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyRow(
                    state = chips,
                    contentPadding = PaddingValues(horizontal = ScreenGutter),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    // A soft fade at an edge that has more chips past it, so a cut-off chip reads as "scroll".
                    modifier = Modifier.itemMotion(this, 4).padding(top = TitleGap).scrollEdgeFade(chips),
                ) {
                    item { Chip(WIcons.Image, "Photos", pairReason, needsPairing(actions.sendPhotos)) }
                    item { Chip(WIcons.File, "Files", pairReason, needsPairing(actions.sendFiles)) }
                    item { Chip(WIcons.Text, "Text", reason, needsMac(actions.sendText)) }
                    item { Chip(WIcons.Clipboard, "Clipboard", reason, needsMac(actions.sendClipboard)) }
                }
            }
            if (card.recent.isEmpty() && card.latest == null) {
                item(key = "recent-empty") {
                    Column(Modifier.itemMotion(this, 5).padding(top = SectionGap)) {
                        SectionTitle("Recent", null, onClick = null)
                        Text(
                            "Codes, files and text that go between your phone and Mac show up here.",
                            style = app.wallhop.ui.design.Type.subheadline, color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(start = ScreenGutter, end = ScreenGutter, top = TitleGap)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.l))
                                .background(home.card.copy(alpha = 0.6f))
                                .padding(Space.m),
                        )
                    }
                }
            }
            if (card.recent.isNotEmpty()) {
                item(key = "recent-title") {
                    SectionTitle("Recent", null, onClick = actions.openActivity, modifier = Modifier.itemMotion(this, 5).padding(top = SectionGap))
                }
                item(key = "recent") {
                    // The newest entry opens up at the top; the oldest folds away at the bottom.
                    val reduceMotion = Wallhop.a11y.reduceMotion
                    val rows = rememberAnimatedRows(card.recent) { it.id }
                    app.wallhop.ui.components.InsetSection(
                        modifier = Modifier.itemMotion(this, 6).padding(start = ScreenGutter, end = ScreenGutter, top = TitleGap),
                        dividerInset = app.wallhop.ui.components.IconRowTextInset,
                    ) {
                        rows.forEach { entry ->
                            row {
                                // Tied to the entry, not the slot (see Activity).
                                androidx.compose.runtime.key(entry.key) {
                                    AnimatedVisibility(entry.visible, enter = rowEnter(reduceMotion), exit = rowExit(reduceMotion)) {
                                        app.wallhop.ui.components.SwipeToDelete(onDelete = { actions.deleteEntry(entry.item) }) {
                                            ActivityRow(entry.item, onLongClick = { actions.entryActions(entry.item, it) }, onClick = { actions.entryTap(entry.item) }, showDay = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        // Scrolled past "Home": a small title (tap it to go back up) and the ⋯ button stay at the
        // top, so the menu never scrolls out of reach.
        val collapseAt = with(density) { 56.dp.toPx() }
        val collapsed by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > collapseAt } }
        val bar by androidx.compose.animation.core.animateFloatAsState(if (collapsed) 1f else 0f, Springs.smooth(), label = "homeBar")
        if (bar > 0.01f) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val solid = statusBar + 60.dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(solid + 24.dp)
                    .graphicsLayer { alpha = bar }
                    .background(Brush.verticalGradient(0f to home.top, solid / (solid + 24.dp) to home.top, 1f to home.top.copy(alpha = 0f))),
            ) {
                Text(
                    "Home", style = app.wallhop.ui.design.Type.headline, color = Color.White,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = statusBar + Space.xs)
                        .clickable(interactionSource = null, indication = null, onClickLabel = "Scroll to top") {
                            scope.launch { list.animateScrollToItem(0) }
                        }
                        .padding(horizontal = Space.m, vertical = Space.s),
                )
                if (!unpaired) Box(Modifier.align(Alignment.TopEnd).padding(top = statusBar + Space.xs, end = ScreenGutter)) {
                    RoundButton(WIcons.More, "More", Color.White.copy(alpha = 0.16f), onClick = actions.more)
                }
            }
        }
    }
}

/** A tinted card with an icon, a line or two and one action: setup, ringing, a lost pairing. */
@Composable
private fun Banner(
    icon: ImageVector,
    title: String,
    message: String?,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    val home = Wallhop.home
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(home.card)
            .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Assertive } else Modifier)
            // With ×, its glyph (not its touch target) ends on the card's 16 dp padding.
            .padding(start = Space.m, end = if (onDismiss != null) 0.dp else Space.m, top = Space.m, bottom = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        app.wallhop.ui.components.IconTile(icon, home.pale)
        Column(Modifier.weight(1f)) {
            Text(title, style = Label, color = Color.White)
            if (message != null) Text(message, style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = Space.xxs))
        }
        PillButton(null, action, home.pale, home.onPale, onAction)
        if (onDismiss != null) {
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.size(48.dp).clip(CircleShape).pressDim(source, 0.6f).tappable(source, onClick = onDismiss)
                    .semantics { contentDescription = "Not now" },
                contentAlignment = Alignment.Center,
            ) { Icon(WIcons.Close, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp)) }
        }
    }
}

/**
 * The Mac is away: why (once a round of attempts has failed), what to do, and the buttons that
 * do it. Try again tries now instead of waiting out the backoff, and ends with a result.
 */
/** No Mac yet: what pairing gives, and the way to it. Everything else in the app works meanwhile. */
@Composable
private fun ConnectCard(actions: MacActions, found: List<app.wallhop.link.DiscoveredMac>, modifier: Modifier = Modifier) {
    val home = Wallhop.home
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(home.card)
            .padding(Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.wallhop.ui.components.LinkCatch(false, Modifier.size(CardGlyph), shaded = false)
            Spacer(Modifier.width(Space.xs))
            Text("Connect your Mac", style = Label, color = Color.White, modifier = Modifier.semantics { heading() })
        }
        // Text and buttons start where the title's text does, not under the link.
        Column(Modifier.padding(start = CardGlyph + Space.xs)) {
            Text(
                "Codes, calls and files from this phone show up on your Mac. Open Lync on the Mac and scan its code.",
                style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = Space.xs),
            )
            Spacer(Modifier.height(Space.m))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                // A Mac already seen on this Wi-Fi is one tap away; otherwise, scan its code.
                found.take(2).forEach { mac ->
                    PillButton(WIcons.Laptop, "Pair with ${mac.name}", home.pale, home.onPale, { actions.pairWith(mac) })
                }
                PillButton(WIcons.QrCode, "Scan Mac's code",
                    if (found.isEmpty()) home.pale else Color.White.copy(alpha = 0.12f), if (found.isEmpty()) home.onPale else Color.White, actions.pairMac)
                if (found.isEmpty()) PillButton(WIcons.Laptop, "Get Lync for Mac", Color.White.copy(alpha = 0.12f), Color.White, { shareMacLink(context) })
            }
        }
    }
}

@Composable
private fun AwayCard(macName: String, bluetooth: Boolean, reason: app.wallhop.core.Away?, actions: MacActions, modifier: Modifier = Modifier) {
    val home = Wallhop.home
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(home.card)
            .padding(Space.m),
    ) {
        Row(
            Modifier.pressDim(source, 0.7f).tappable(source, onClick = actions.macDetails),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.wallhop.ui.components.LinkCatch(false, Modifier.size(CardGlyph), shaded = false)
            Spacer(Modifier.width(Space.xs))
            Text(if (bluetooth) "$macName is connected over Bluetooth" else "$macName isn't connected", style = Label, color = Color.White, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
        }
        val problem = if (bluetooth) null else reason?.let { app.wallhop.core.Problems.away(it) }
        // Everything under the title starts where its text does, not under the link.
        Column(Modifier.padding(start = CardGlyph + Space.xs)) {
            Spacer(Modifier.height(Space.xs))
            if (problem != null) {
                // Why, in a few words, and what to do, in one line.
                Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text(problem.title, style = Label, color = Color.White)
                    Text(problem.action, style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = Space.xxs))
                }
            } else {
                val checks = if (bluetooth) listOf("Codes, calls and notifications go across", "Files and text wait for the same Wi‑Fi")
                    else listOf("Mac awake and Lync open", "Same Wi‑Fi as this phone")
                checks.forEach { check ->
                    Row(Modifier.padding(vertical = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                        Icon(WIcons.CheckCircle, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Space.xs))
                        Text(check, style = Body, color = Color.White.copy(alpha = 0.78f))
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        run {
            // The problem's buttons (Wi‑Fi settings, Pair again…); Try again always. They span the
            // card from its own edge, so they line up with the card, not half-way in under the text.
            val fixes = (problem?.fixes.orEmpty() + if (bluetooth) listOf(app.wallhop.core.Fix.WifiSettings) else emptyList())
                .let { if (app.wallhop.core.Fix.TryAgain in it || app.wallhop.core.Fix.PairAgain in it) it else it + app.wallhop.core.Fix.TryAgain }
                .take(2)
            // Side by side, sharing the width; stacked at large text sizes.
            val stacked = LocalDensity.current.fontScale > 1.3f
            val buttons: @Composable (Modifier) -> Unit = { m ->
                fixes.forEachIndexed { index, fix ->
                    val first = index == 0
                    PillButton(
                        fixIcon(fix), fix.label,
                        if (first) home.pale else Color.White.copy(alpha = 0.12f), if (first) home.onPale else Color.White,
                        { if (fix == app.wallhop.core.Fix.TryAgain) actions.retryConnect() else actions.runFix(fix) },
                        m,
                    )
                }
            }
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) { buttons(Modifier.fillMaxWidth()) }
            else Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) { buttons(Modifier.weight(1f)) }
        }
    }
}

/** Bluetooth (or Lync's backup) is off: one quiet line, its button a link at the end. */
@Composable
private fun BluetoothOffLine(text: String, onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    val home = Wallhop.home
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WIcons.Bluetooth, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Space.xs))
        Text(text, style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(Space.s))
        Text(
            "Turn on", style = Label, color = home.pale,
            modifier = Modifier.pressDim(source, 0.6f).tappable(source, onClick = onTurnOn).padding(vertical = Space.xs),
        )
    }
}

/** The icon on a problem's button. */
private fun fixIcon(fix: app.wallhop.core.Fix): ImageVector? = when (fix) {
    app.wallhop.core.Fix.TryAgain -> WIcons.Retry
    app.wallhop.core.Fix.WifiSettings -> WIcons.Wifi
    app.wallhop.core.Fix.PairAgain -> WIcons.Link
    app.wallhop.core.Fix.PairMac -> WIcons.QrCode
    app.wallhop.core.Fix.ChooseAgain -> WIcons.Image
    app.wallhop.core.Fix.TurnOnBluetooth -> WIcons.Bluetooth
    app.wallhop.core.Fix.UpdateMac -> WIcons.Laptop
    else -> null
}

/**
 * A Home item's motion: added or removed items fade while the rest slide (spring.smooth), and
 * the first [index]es fade up in turn when Home comes on show. -1: no stagger.
 */
@Composable
private fun Modifier.itemMotion(scope: androidx.compose.foundation.lazy.LazyItemScope, index: Int): Modifier {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val placed = with(scope) { this@itemMotion.animateItem(
        fadeInSpec = if (reduceMotion) null else Fades.fadeIn(),
        placementSpec = if (reduceMotion) null else Springs.smooth(),
        fadeOutSpec = if (reduceMotion) null else Fades.fadeOut(),
    ) }
    return if (index < 0) placed else placed.staggerIn(index)
}

/** The cards' least height: the Mac card (picture, padding, one status line). Taller content grows the row. */
private val CardHeight = 228.dp
/** The little Mac's stage on the hero card. The Mac is drawn big and the card's details cut it off. */
private val HeroPictureHeight = 172.dp
private val SmallCardWidth = 204.dp
/** Padding round the hero card's details. */
private val HeroPadding = Space.m
/** The link beside a card's title; the card's text lines up after it. */
private val CardGlyph = 20.dp
/** The clasped link beside the Mac's name at the top of Home. */
private val HeaderGlyph = 24.dp

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
    val inner = heroWidth - HeroPadding * 2
    // Picture, padding and the gap, plus the text (the meta line beside its 20 dp icon).
    val hero = HeroPictureHeight + HeroPadding * 2 +
        maxOf(text(meta, HeroMeta, inner - 20.dp - Space.xs, 2), 20.dp)
    val small = SmallCardWidth - Space.m * 2
    val transferCard = transfer?.let {
        val failed = it.phase == TransferPhase.Failed
        // Padding, the message's gap, the progress bar and its gap, then one button or two.
        val wide = heroWidth - Space.m * 2
        Space.m * 2 + Space.s + Space.xs + (if (it.phase == TransferPhase.Sending) Space.s + 4.dp else 0.dp) + Space.m +
            (if (failed) 48.dp * 2 + Space.xs else 48.dp) +
            text(transferStatus(it), Overline, wide) + text(LinkManager.summary(it.items), TransferTitle, wide, 3) +
            text(transferMessage(it), Meta, wide, 4)
    } ?: 0.dp
    val latestCard = latest?.let {
        val title = if (it.kind == ActivityEntry.Kind.Otp) text(app.wallhop.core.Wording.spaced(it.title), codeStyle(density.fontScale), small)
            else text(it.shownTitle, TransferTitle, small, 3)
        Space.m * 2 + Space.xs + 48.dp + text(latestLabel(it), Overline, small) + title + (it.shownDetail?.let { d -> text(d, Meta, small, 2) } ?: 0.dp)
    } ?: 0.dp
    return maxOf(CardHeight, hero, transferCard, latestCard)
}

/** "Connected for 15 min  •  Mac 82% charging". */
private fun heroMeta(info: app.wallhop.link.MacInfo, connected: Boolean, bluetooth: Boolean, since: Long?): String = buildList {
    add(
        when {
            // Off Wi-Fi but linked over Bluetooth: connected, just not for files.
            !connected && bluetooth -> "Connected over Bluetooth"
            !connected -> "Not connected"
            since == null -> "Connected"
            since < 60_000 -> "Just connected"
            else -> "Connected for ${duration(since)}"
        },
    )
    if (info.laptop && info.battery != null) add("Mac ${info.battery}%" + if (info.charging) " charging" else "")
}.joinToString("  •  ")

private fun transferStatus(transfer: TransferState) = when (transfer.phase) {
    TransferPhase.Waiting -> "Waiting for your Mac"
    TransferPhase.Sending -> "Sending"
    TransferPhase.Done -> app.wallhop.core.Wording.READY_SHORT
    TransferPhase.Failed -> "Didn’t send"
}

/** Why it failed and what to do ("Mac is out of space. Free up space…"), or how far it's got. */
private fun transferMessage(transfer: TransferState) = transfer.problem?.sentence ?: transfer.message ?: when (transfer.phase) {
    TransferPhase.Done -> app.wallhop.core.Wording.READY
    // Nothing has gone yet, so no "0 B of …": the size, and when it goes.
    TransferPhase.Waiting -> "${app.wallhop.ui.components.formatBytes(transfer.totalBytes)} · sends when your Mac is back"
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
            .then(if (onClick != null) Modifier.pressDim(source, 0.7f).tappable(source, onClick = onClick) else Modifier)
            .padding(horizontal = ScreenGutter),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(title, style = Section, color = Color.White, modifier = Modifier.semantics { heading() })
            if (onClick != null) Icon(WIcons.ChevronRight, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        if (subtitle != null) {
            Text(subtitle, style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = Space.xxs))
        }
    }
}

/** The Mac: its live miniature on its own wallpaper, then its name and status. Tap for details. */
@Composable
private fun MacHero(
    name: String,
    connected: Boolean,
    meta: String,
    card: MacCardData,
    home: HomeColors,
    actions: MacActions,
    width: Dp,
) {
    val info = card.info
    val wallpaper = remember(info.wallpaper) { info.wallpaper?.asImageBitmap() }
    val source = remember { MutableInteractionSource() }
    CardBox(width = width, color = home.card, modifier = Modifier.fillMaxHeight().pressScale(source, 0.98f).tappable(source, onClick = actions.macDetails)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(HeroPictureHeight)
                .clipToBounds()
                .background(home.top)
                // One description for the whole picture: "MacBook Air, connected, 82% battery".
                .clearAndSetSemantics {
                    contentDescription = app.wallhop.core.Wording.miniMac(
                        kind(name, info.laptop), connected, info.locked, if (info.laptop) info.battery else null, info.charging,
                    )
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            if (wallpaper != null) {
                // Blurred, so the little Mac (which shows the same wallpaper) stands out.
                Image(wallpaper, null, Modifier.fillMaxSize().blur(28.dp).alpha(0.6f), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, home.card.copy(alpha = 0.55f)))))
            // Big and anchored to the top: only the top of the screen shows, like a Mac peeking up.
            LiveMac(connected, card, Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).padding(top = 16.dp))
        }
        // The details sit on a darker panel that cuts the little Mac off at its lid.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.lerp(home.card, Ink, 0.62f))
                .padding(HeroPadding),
        ) {
            // The name is the section's title just above, so the card only says how the Mac is.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (connected) WIcons.Link else WIcons.LinkBreak, null,
                    tint = if (connected) home.codeAccent else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Space.xs))
                // "Connected for …" and the battery roll when they change.
                AnimatedChangeText(meta, style = HeroMeta, color = Color.White.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The newest thing that went across, in a contrasting colour. A code can be copied here. */
@Composable
private fun LatestCard(entry: ActivityEntry, home: HomeColors, actions: MacActions) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val isCode = entry.kind == ActivityEntry.Kind.Otp
    // "Copy" turns into "Copied" with a check for a moment.
    var copied by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_600)
            copied = false
        }
    }
    // Codes get a deep card in the wallpaper's hue (shared with the Mac); other kinds the contrasting hue.
    val background = if (isCode) home.codeCard else home.peek
    val accent = if (isCode) home.codeAccent else Color.White
    val secondary = Color.White.copy(alpha = if (isCode) HomeColors.CodeSecondaryAlpha else 0.78f)
    val fontScale = LocalDensity.current.fontScale
    CardBox(width = SmallCardWidth, color = background, modifier = Modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().padding(Space.m)) {
            Text(latestLabel(entry), style = Overline, color = secondary)
            Spacer(Modifier.weight(1f))
            if (isCode) {
                // A new code rolls in like an odometer, digit by digit (an old one just shows).
                val fresh = remember(entry.id) { System.currentTimeMillis() - entry.time < 15_000 }
                val groups = app.wallhop.core.Wording.spaced(entry.title).split(' ')
                // One line, "482 913", like everywhere else the code appears.
                Row(
                    Modifier.clearAndSetSemantics { contentDescription = app.wallhop.core.Wording.spaced(entry.title) },
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    var offset = 0L
                    groups.forEach { group ->
                        RollingCode(group, codeStyle(fontScale), accent, animate = fresh, delayOffsetMs = offset)
                        offset += group.length * 30L
                    }
                }
            } else {
                Text(entry.shownTitle, style = TransferTitle, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            entry.shownDetail?.let {
                Text(it, style = Meta, color = secondary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
            }
            Spacer(Modifier.weight(1f))
            if (isCode) {
                PillButton(
                    if (copied) WIcons.CheckCircle else WIcons.Clipboard, if (copied) "Copied" else "Copy",
                    home.codeAccent, home.codeCard,
                    onClick = {
                        copyCode(context, entry.title)
                        haptics.confirm()
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    morph = true,
                    description = if (copied) "Copied" else app.wallhop.core.Wording.copyCodeLabel(entry.title),
                )
            } else {
                PillButton(WIcons.Activity, "Activity", home.peekPale, home.onPeekPale, actions.openActivity, Modifier.fillMaxWidth())
            }
        }
    }
}

/** A transfer in progress takes the first spot in the row until it's dismissed. */
@Composable
private fun TransferCard(transfer: TransferState, home: HomeColors, actions: MacActions, width: Dp) {
    val title = LinkManager.summary(transfer.items)
    val failed = transfer.phase == TransferPhase.Failed
    val done = transfer.phase == TransferPhase.Done || failed
    // A failed send gives the card a short shake (the error haptic plays with it).
    // As wide as the Mac card, so the row shows one whole card and only a sliver of the next.
    CardBox(width = width, color = home.card, modifier = Modifier.fillMaxHeight().shake(failed)) {
        Column(Modifier.fillMaxHeight().padding(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                when {
                    failed -> Icon(WIcons.Warning, null, tint = Wallhop.colors.red, modifier = Modifier.size(16.dp))
                    // Fills as it sends, then becomes a check with a pop.
                    transfer.phase != TransferPhase.Waiting -> app.wallhop.ui.components.ProgressCheck(
                        transfer.progress, done = transfer.phase == TransferPhase.Done,
                        modifier = Modifier.size(16.dp), color = Wallhop.colors.green,
                    )
                }
                Text(transferStatus(transfer), style = Overline, color = Color.White.copy(alpha = 0.72f))
            }
            // What it is sits under its status; the free space goes above the button, in one place.
            Spacer(Modifier.height(Space.s))
            Text(title, style = TransferTitle, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                transferMessage(transfer),
                style = Meta, color = Color.White.copy(alpha = 0.72f), maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Space.xs),
            )
            // A bar only once something is moving; waiting, the line above says when it goes.
            if (transfer.phase == TransferPhase.Sending) {
                ProgressBar(transfer.progress, Modifier.padding(top = Space.s), color = home.pale)
            }
            Spacer(Modifier.weight(1f))
            if (failed) {
                // Files that couldn't be opened are picked again; anything else is sent again.
                val choose = transfer.problem?.fix == app.wallhop.core.Fix.ChooseAgain
                PillButton(if (choose) WIcons.Image else WIcons.Retry, if (choose) "Choose again" else "Try again",
                    home.pale, home.onPale, { actions.retryTransfer(transfer) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.xs))
                PillButton(WIcons.XCircle, "Dismiss", Color.White.copy(alpha = 0.12f), Color.White,
                    actions.dismissTransfer, Modifier.fillMaxWidth())
            } else {
                PillButton(
                    if (done) WIcons.CheckCircle else WIcons.XCircle, if (done) "Done" else "Cancel",
                    home.pale, home.onPale,
                    if (done) actions.dismissTransfer else actions.cancelTransfer,
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CardBox(width: Dp, color: Color, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier.width(width).clip(RoundedCornerShape(Radius.l)).background(color)) { content() }
}

/** Pale pill button, the card's main action. */
@Composable
private fun PillButton(
    icon: ImageVector?, label: String, container: Color, content: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier,
    /** Cross-fade icon (with a little overshoot) and label when they change, e.g. Copy → Copied. */
    morph: Boolean = false,
    /** What TalkBack says instead of [label], e.g. "Copy code 482 913". */
    description: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val reduceMotion = Wallhop.a11y.reduceMotion
    Row(
        modifier
            .height(48.dp)
            .pressScale(source)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .tappable(source, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (!morph || icon == null) {
            if (icon != null) {
                Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.xs))
            }
            // A long label ("Pair with …" a long Mac name) truncates and keeps its icon.
            Text(label, style = Label, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        } else {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(Durations.Quick)) togetherWith fadeOut(tween(Durations.Quick))
                    else (fadeIn(Fades.fadeIn()) + scaleIn(Springs.pop(), initialScale = 0.85f)) togetherWith
                        (fadeOut(Fades.fadeOut()) + scaleOut(Springs.snappy(), targetScale = 0.85f))
                },
                label = "pillIcon",
            ) { shown -> Icon(shown, null, tint = content, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(Space.xs))
            AnimatedContent(
                targetState = label,
                transitionSpec = { fadeIn(Fades.fadeIn()) togetherWith fadeOut(Fades.fadeOut()) using SizeTransform(clip = false) },
                label = "pillLabel",
            ) { shown ->
                // Announced, so TalkBack users hear "Copied" too.
                Text(shown, style = Label, color = content, maxLines = 1,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
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

/**
 * See-through chip: a way to send. With a [disabledReason] it's dimmed and TalkBack hears it's
 * unavailable and why; a tap still explains.
 */
@Composable
private fun Chip(icon: ImageVector, label: String, disabledReason: String?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val enabled = disabledReason == null
    Row(
        Modifier
            .height(48.dp)
            .pressScale(source)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.13f))
            .tappable(source, onClick = onClick)
            // Not greyed out: it still answers a tap (with why and the fix), and the line under
            // "Send to Mac" already says what has to wait. TalkBack hears the reason.
            .then(if (enabled) Modifier else Modifier.semantics { stateDescription = disabledReason.orEmpty() })
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.xs))
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
 * The Mac details sheet (DESIGN.md §6.4): status, battery and when it connected first; the
 * address and response time are tucked under "Connection details". Then Forget.
 * Opened from "Your Mac ›", the ⋯ menu, the Mac card and the Mac row in Settings.
 */
@Composable
fun MacDetails(
    state: LinkState,
    info: app.wallhop.link.MacInfo,
    latencyMs: Int?,
    connectedSince: Long?,
    /** Not on Wi‑Fi, but the Bluetooth backup reaches the Mac. */
    bluetooth: Boolean = false,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Wallhop.colors
    val connected = state as? LinkState.Connected
    val reduceMotionDetails = Wallhop.a11y.reduceMotion
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    app.wallhop.ui.components.InsetSection(dividerInset = app.wallhop.ui.components.IconRowTextInset) {
        row {
            // Away: why, and what to do, under the row (the same words as Home's card).
            val away = (state as? LinkState.Searching)?.reason?.takeIf { !bluetooth }?.let { app.wallhop.core.Problems.away(it) }
            val overBluetooth = bluetooth && connected == null
            app.wallhop.ui.components.ListRow(
                // Long enough to need the row's width: the reason is the title, what to do under it.
                title = when {
                    overBluetooth -> "Connected over Bluetooth"
                    away != null -> away.title
                    else -> "Status"
                },
                subtitle = when {
                    overBluetooth -> "Codes, calls and notifications go across. Files and text wait for the same Wi‑Fi."
                    away != null -> away.action
                    else -> null
                },
                subtitleMaxLines = Int.MAX_VALUE,
                leading = {
                    app.wallhop.ui.components.IconTile(
                        if (overBluetooth) WIcons.Bluetooth else WIcons.Wifi,
                        if (connected != null || overBluetooth) colors.green else colors.gray,
                    )
                },
                value = when {
                    state is LinkState.Forgotten -> "Needs pairing again"
                    overBluetooth || away != null -> null
                    connected == null -> "Not connected"
                    info.locked -> "Connected, locked"
                    else -> "Connected"
                },
            )
        }
        if (info.laptop && info.battery != null) {
            row {
                app.wallhop.ui.components.ListRow(
                    title = "Battery",
                    leading = { app.wallhop.ui.components.IconTile(WIcons.BatteryCharging, colors.orange) },
                    value = "${info.battery}%" + if (info.charging) ", charging" else "",
                    animateValue = true,
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
        if (connected != null) {
            // The details open under their row in one piece (the chevron turning with them),
            // rather than two rows appearing in a single frame.
            row {
                Column {
                    val turn by androidx.compose.animation.core.animateFloatAsState(
                        if (expanded) 90f else 0f, if (reduceMotionDetails) tween(0) else Springs.snappy(), label = "detailsChevron",
                    )
                    app.wallhop.ui.components.ListRow(
                        title = "Connection details",
                        leading = { app.wallhop.ui.components.IconTile(WIcons.Gauge, colors.indigo) },
                        trailing = {
                            Icon(WIcons.ChevronRight, null, tint = colors.labelTertiary,
                                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
                        },
                        onClick = { expanded = !expanded },
                        modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        expanded,
                        enter = if (reduceMotionDetails) fadeIn(tween(Durations.Quick))
                            else androidx.compose.animation.expandVertically(Springs.snappy()) + fadeIn(Fades.fadeIn()),
                        exit = if (reduceMotionDetails) fadeOut(tween(Durations.Quick))
                            else androidx.compose.animation.shrinkVertically(Springs.snappy()) + fadeOut(Fades.fadeOut()),
                    ) {
                        Column {
                            DetailsDivider(colors.separator)
                            app.wallhop.ui.components.ListRow(title = "Address", value = connected.host)
                            if (latencyMs != null) {
                                DetailsDivider(colors.separator)
                                app.wallhop.ui.components.ListRow(title = "Response time", value = "$latencyMs ms", animateValue = true)
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(Space.m))
    app.wallhop.ui.components.InsetSection(dividerInset = app.wallhop.ui.components.IconRowTextInset) {
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

/** Fades the row's left and right edges while there's more content past them. */
private fun Modifier.scrollEdgeFade(state: androidx.compose.foundation.lazy.LazyListState): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = 40.dp.toPx()
        if (state.canScrollForward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - edge, endX = size.width),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - edge, 0f),
                size = androidx.compose.ui.geometry.Size(edge, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
        if (state.canScrollBackward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = edge),
                size = androidx.compose.ui.geometry.Size(edge, size.height),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
    }

/** The same hairline InsetSection puts between rows, for rows that open inside one. */
@Composable
private fun DetailsDivider(color: Color) {
    Box(Modifier.padding(start = app.wallhop.ui.components.IconRowTextInset).fillMaxWidth().height(0.5.dp).background(color))
}
