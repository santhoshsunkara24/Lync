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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
    val openSettings: () -> Unit,
    val openActivity: () -> Unit,
    /** The Mac details sheet (DESIGN.md §6.4). */
    val macDetails: () -> Unit,
    /** Copy code / Delete for a Recent entry (long press). */
    val entryActions: (ActivityEntry) -> Unit,
    /** A tap on a Recent entry: copy the code, open the file or link, or the actions. */
    val entryTap: (ActivityEntry) -> Unit,
    val more: (Rect) -> Unit,
    /** The guided setup, from the "Finish setup" card. */
    val finishSetup: () -> Unit,
    /** Stops "Ring phone". */
    val stopRinging: () -> Unit,
    /** The Mac forgot this phone: drop the old pairing and pair again. */
    val pairAgain: () -> Unit,
    /** Pokes the reconnect loop now. */
    val retryConnect: () -> Unit,
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
    needsSetup: Boolean,
    actions: MacActions,
    card: MacCardData,
    ringing: Boolean = false,
) {
    val connected = state is LinkState.Connected
    val forgotten = state is LinkState.Forgotten
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
    // Why sending is unavailable, for the toast and for TalkBack.
    val reason = when {
        forgotten -> "Pair again to send to your Mac"
        !connected -> "Your Mac isn't connected"
        else -> null
    }
    // Text and Clipboard need the Mac now; photos and files wait for it, like the share sheet.
    fun needsMac(action: () -> Unit): () -> Unit = {
        if (reason == null) action() else overlays?.toast(reason, WIcons.Warning)
    }
    fun needsPairing(action: () -> Unit): () -> Unit = {
        if (!forgotten) action() else overlays?.toast("Pair again to send to your Mac", WIcons.Warning)
    }
    val since by produceState<Long?>(null, card.connectedSince) {
        val start = card.connectedSince ?: run { value = null; return@produceState }
        while (true) {
            value = System.currentTimeMillis() - start
            delay(60_000)
        }
    }
    val batteryContext = LocalContext.current
    val phoneBattery by produceState<Int?>(null) {
        while (true) {
            value = batteryContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?.let { b ->
                    val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                    val scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                    if (level >= 0 && scale > 0) level * 100 / scale else null
                }
            delay(60_000)
        }
    }
    val meta = heroMeta(card.info, connected, since, phoneBattery)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to home.top, 0.46f to home.mid, 0.72f to Ink, 1f to Ink)),
        contentAlignment = Alignment.TopCenter,
    ) {
        CompositionLocalProvider(LocalStagger provides rememberStaggerClock()) {
        LazyColumn(
            Modifier.fillMaxHeight().width(screen + with(density) { sidesPx.toDp() }).windowInsetsPadding(sides),
            contentPadding = PaddingValues(top = statusBar + 18.dp, bottom = navBar + 112.dp),
        ) {
            item(key = "header") {
                Row(Modifier.itemMotion(this, 0).fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Home", style = Title, color = Color.White, modifier = Modifier.weight(1f).semantics { heading() })
                    RoundButton(WIcons.More, "More", Color.White.copy(alpha = 0.16f), onClick = actions.more)
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
                        modifier = Modifier.itemMotion(this, -1).padding(start = 20.dp, end = 20.dp, top = 16.dp),
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
                        modifier = Modifier.itemMotion(this, -1).padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    )
                }
            }
            // Setup that's still to do sits at the top, not below the fold.
            if (needsSetup && !forgotten) {
                item(key = "setup") {
                    Banner(
                        icon = WIcons.Settings,
                        title = "Finish setup",
                        message = "Codes, calls and texts reach your Mac once they're allowed.",
                        action = "Continue",
                        onAction = actions.finishSetup,
                        modifier = Modifier.itemMotion(this, 1).padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    )
                }
            }
            item(key = "mac-title") {
                if (connected || forgotten) {
                    MacStatusTitle(
                        connected = connected,
                        locked = card.info.locked,
                        onClick = actions.macDetails,
                        modifier = Modifier.itemMotion(this, 1).padding(top = 22.dp),
                    )
                } else {
                    AwayCard(macName, actions, Modifier.itemMotion(this, 1).padding(start = 20.dp, end = 20.dp, top = 22.dp))
                }
            }
            item(key = "cards") {
                // The Mac card fills the width. When there's more (a transfer, the latest item),
                // it narrows so the next card peeks in from the right and the row snaps card by card.
                val others = (if (transfer != null) 1 else 0) + (if (card.latest != null) 1 else 0)
                val heroWidth = (screen - 40.dp - if (others > 0) 64.dp else 0.dp).coerceAtMost(520.dp)
                val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
                val fadeEdge = with(density) { 40.dp.toPx() }
                LazyRow(
                    state = rowState,
                    flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(rowState),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    // One height for the row, so every card lines up top and bottom: the tallest
                    // card's content, which grows with a two-line Mac name or a larger font.
                    modifier = Modifier.itemMotion(this, 2).padding(top = 16.dp).height(cardRowHeight(macName, meta, heroWidth, transfer, card.latest))
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
                    if (transfer != null) item(key = "transfer") { Box(Modifier.itemMotion(this, -1)) { TransferCard(transfer, home, actions) } }
                    item(key = "mac") { Box(Modifier.itemMotion(this, -1)) { MacHero(macName, connected, meta, card, home, actions, heroWidth) } }
                    card.latest?.let { latest -> item(key = "latest") { Box(Modifier.itemMotion(this, -1)) { LatestCard(latest, home, actions) } } }
                }
            }
            item(key = "send-title") {
                SectionTitle(
                    "Send to Mac",
                    when {
                        forgotten -> "Pair again to send to your Mac."
                        connected -> null
                        else -> "Photos and files go across once your Mac is back."
                    },
                    onClick = null,
                    modifier = Modifier.itemMotion(this, 3).padding(top = 26.dp),
                )
            }
            item(key = "send") {
                val pairReason = if (forgotten) "Pair again to send to your Mac" else null
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.itemMotion(this, 4).padding(top = 12.dp),
                ) {
                    item { Chip(WIcons.Image, "Photos", pairReason, needsPairing(actions.sendPhotos)) }
                    item { Chip(WIcons.File, "Files", pairReason, needsPairing(actions.sendFiles)) }
                    item { Chip(WIcons.Text, "Text", reason, needsMac(actions.sendText)) }
                    item { Chip(WIcons.Clipboard, "Clipboard", reason, needsMac(actions.sendClipboard)) }
                }
            }
            if (card.recent.isNotEmpty()) {
                item(key = "recent-title") {
                    SectionTitle("Recent", null, onClick = actions.openActivity, modifier = Modifier.itemMotion(this, 5).padding(top = 26.dp))
                }
                item(key = "recent") {
                    // The newest entry opens up at the top; the oldest folds away at the bottom.
                    val reduceMotion = Wallhop.a11y.reduceMotion
                    val rows = rememberAnimatedRows(card.recent) { it.id }
                    app.wallhop.ui.components.InsetSection(
                        modifier = Modifier.itemMotion(this, 6).padding(start = 20.dp, end = 20.dp, top = 12.dp),
                        dividerInset = 64.dp,
                    ) {
                        rows.forEach { entry ->
                            row {
                                AnimatedVisibility(entry.visible, enter = rowEnter(reduceMotion), exit = rowExit(reduceMotion)) {
                                    ActivityRow(entry.item, onLongClick = { actions.entryActions(entry.item) }, onClick = { actions.entryTap(entry.item) }, showDay = true)
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
) {
    val home = Wallhop.home
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(home.card)
            .then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Assertive } else Modifier)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        app.wallhop.ui.components.IconTile(icon, home.pale)
        Column(Modifier.weight(1f)) {
            Text(title, style = Label, color = Color.White)
            if (message != null) Text(message, style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = 2.dp))
        }
        PillButton(null, action, home.pale, home.onPale, true, onAction)
    }
}

/**
 * The Mac is away: what to check, and Try again (which wakes the reconnect loop now instead of
 * waiting out its backoff).
 */
@Composable
private fun AwayCard(macName: String, actions: MacActions, modifier: Modifier = Modifier) {
    val home = Wallhop.home
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(home.card)
            .padding(16.dp),
    ) {
        Row(
            Modifier.pressDim(source, 0.7f).tappable(source, onClick = actions.macDetails),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(false)
            Spacer(Modifier.width(6.dp))
            Text("$macName isn't connected", style = Label, color = Color.White, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
        }
        Spacer(Modifier.height(10.dp))
        listOf("Mac awake and Lync open", "Same Wi‑Fi as this phone").forEach { check ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(WIcons.CheckCircle, null, tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(check, style = Body, color = Color.White.copy(alpha = 0.78f))
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton(WIcons.Retry, "Try again", home.pale, home.onPale, true, actions.retryConnect)
    }
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

/**
 * "Your Mac ›" and how it's connected. The line under it slides up as the state changes, after
 * a small dot that pops green when the Mac connects. Response time lives in Mac details.
 */
@Composable
private fun MacStatusTitle(connected: Boolean, locked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val reduceMotion = Wallhop.a11y.reduceMotion
    val rise = with(LocalDensity.current) { 6.dp.roundToPx() }
    val status = when {
        !connected -> 0
        locked -> 1
        else -> 2
    }
    Column(
        modifier
            .fillMaxWidth()
            .pressDim(source, 0.7f)
            .tappable(source, onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your Mac", style = Section, color = Color.White, modifier = Modifier.semantics { heading() })
            Icon(WIcons.ChevronRight, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(connected)
            Spacer(Modifier.width(7.dp))
            AnimatedContent(
                targetState = status,
                transitionSpec = {
                    if (reduceMotion) {
                        fadeIn(tween(Durations.Quick)) togetherWith fadeOut(tween(Durations.Quick))
                    } else {
                        (slideInVertically(Springs.snappy()) { rise } + fadeIn(Fades.fadeIn())) togetherWith
                            (slideOutVertically(Springs.snappy()) { -rise } + fadeOut(Fades.fadeOut())) using
                            SizeTransform(clip = false)
                    }
                },
                contentAlignment = Alignment.CenterStart,
                label = "macStatus",
            ) { shown ->
                val color = Color.White.copy(alpha = 0.78f)
                when (shown) {
                    0 -> Text("Not connected", style = Body, color = color)
                    1 -> Text("Connected · Mac is locked", style = Body, color = color)
                    else -> Text("Connected", style = Body, color = color)
                }
            }
        }
    }
}

/**
 * The connection dot. While looking it breathes once every 1.6 s (only then, and only while
 * Home is on screen); when the Mac connects it turns green with a single pop.
 */
@Composable
private fun StatusDot(connected: Boolean) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val active = LocalScreenActive.current
    val green = Wallhop.colors.green
    val color by animateColorAsState(if (connected) green else Color.White.copy(alpha = 0.55f), Springs.smooth(), label = "dot")
    val pulse = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    val wasConnected = remember { booleanArrayOf(connected) }
    LaunchedEffect(connected, active, reduceMotion) {
        val justConnected = connected && !wasConnected[0]
        wasConnected[0] = connected
        if (connected) {
            pulse.snapTo(0f)
            if (justConnected && !reduceMotion && active) {
                pop.animateTo(1.4f, tween(120))
                pop.animateTo(1f, Springs.bouncy())
            }
            return@LaunchedEffect
        }
        if (reduceMotion || !active) {
            pulse.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(900, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
            kotlinx.coroutines.delay(700)
        }
    }
    Canvas(Modifier.size(14.dp)) {
        val r = 3.5.dp.toPx() * pop.value
        val p = pulse.value
        if (p > 0f && p < 1f) {
            drawCircle(color.copy(alpha = 0.45f * (1f - p)), radius = 3.5.dp.toPx() * (1f + 1f * p))
        }
        drawCircle(color, radius = r)
    }
}

/** The cards' height at the default font with a one-line Mac name; they grow from here. */
private val CardHeight = 240.dp
/** The little Mac's stage on the hero card. The Mac is drawn big and the card's details cut it off. */
private val HeroPictureHeight = 132.dp
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
    val hero = HeroPictureHeight + 30.dp + 12.dp +
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
        val title = if (it.kind == ActivityEntry.Kind.Otp) text(spacedCode(it.title), codeStyle(density.fontScale), small)
            else text(it.title, TransferTitle, small, 3)
        32.dp + 8.dp + 48.dp + text(latestLabel(it), Overline, small) + title + text(it.detail, Meta, small, 2)
    } ?: 0.dp
    return maxOf(CardHeight, hero, transferCard, latestCard)
}

/** "82% battery, charging · on for 2 h 14 m". */
private fun heroMeta(info: app.wallhop.link.MacInfo, connected: Boolean, since: Long?, phoneBattery: Int? = null): String = buildList {
    if (info.laptop && info.battery != null) add("Mac ${info.battery}%" + if (info.charging) ", charging" else "")
    if (connected && phoneBattery != null) add("phone $phoneBattery%")
    when {
        !connected -> add("not connected")
        since == null -> Unit
        since < 60_000 -> add("just connected")
        else -> add("on for ${duration(since)}")
    }
}.joinToString(" · ")

private fun transferStatus(transfer: TransferState) = when (transfer.phase) {
    TransferPhase.Waiting -> "Waiting for your Mac"
    TransferPhase.Sending -> "Sending"
    TransferPhase.Done -> app.wallhop.core.Wording.READY_SHORT
    TransferPhase.Failed -> "Didn’t send"
}

private fun transferMessage(transfer: TransferState) = transfer.message ?: when (transfer.phase) {
    TransferPhase.Done -> app.wallhop.core.Wording.READY
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
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Section, color = Color.White, modifier = Modifier.semantics { heading() })
            if (onClick != null) Icon(WIcons.ChevronRight, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        if (subtitle != null) {
            Text(subtitle, style = Body, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = 2.dp))
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
            BridgeLink(connected, home, Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            Text(kind(name, info.laptop), style = Overline, color = Color.White.copy(alpha = 0.78f))
            Text(name, style = CardTitle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            // Battery and "on for …" roll when they change.
            AnimatedChangeText(meta, style = Meta, color = Color.White.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
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
    // Codes always use Lync's own forest and mint (shared with the Mac); other kinds the wallpaper's contrasting hue.
    val background = if (isCode) HomeColors.CodeForest else home.peek
    val accent = if (isCode) HomeColors.CodeMint else Color.White
    val secondary = Color.White.copy(alpha = if (isCode) HomeColors.CodeSecondaryAlpha else 0.78f)
    val fontScale = LocalDensity.current.fontScale
    CardBox(width = SmallCardWidth, color = background, modifier = Modifier.fillMaxHeight()) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
            Text(latestLabel(entry), style = Overline, color = secondary)
            Spacer(Modifier.weight(1f))
            if (isCode) {
                // A new code rolls in like an odometer, digit by digit (an old one just shows).
                val fresh = remember(entry.id) { System.currentTimeMillis() - entry.time < 15_000 }
                val groups = spacedCode(entry.title).split(' ')
                // One line, "482 913", like everywhere else the code appears.
                Row(
                    Modifier.clearAndSetSemantics { contentDescription = spacedCode(entry.title) },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    var offset = 0L
                    groups.forEach { group ->
                        RollingCode(group, codeStyle(fontScale), accent, animate = fresh, delayOffsetMs = offset)
                        offset += group.length * 30L
                    }
                }
            } else {
                Text(entry.title, style = TransferTitle, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(entry.detail, style = Meta, color = secondary, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            if (isCode) {
                PillButton(
                    if (copied) WIcons.CheckCircle else WIcons.Clipboard, if (copied) "Copied" else "Copy",
                    HomeColors.CodeMint, HomeColors.CodeForest, true,
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
    // A failed send gives the card a short shake (the error haptic plays with it).
    CardBox(width = SmallCardWidth, color = home.card, modifier = Modifier.fillMaxHeight().shake(failed)) {
        Column(Modifier.fillMaxHeight().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    failed -> Icon(WIcons.Warning, null, tint = Wallhop.colors.red, modifier = Modifier.size(14.dp))
                    // Fills as it sends, then becomes a check with a pop.
                    transfer.phase != TransferPhase.Waiting -> app.wallhop.ui.components.ProgressCheck(
                        transfer.progress, done = transfer.phase == TransferPhase.Done,
                        modifier = Modifier.size(14.dp), color = Wallhop.colors.green,
                    )
                }
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
    icon: ImageVector?, label: String, container: Color, content: Color, enabled: Boolean,
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
            .alpha(if (enabled) 1f else DisabledAlpha)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (!morph || icon == null) {
            if (icon != null) {
                Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = Label, color = content, maxLines = 1)
        } else {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(Durations.Quick)) togetherWith fadeOut(tween(Durations.Quick))
                    else (fadeIn(Fades.fadeIn()) + scaleIn(Springs.bouncy(), initialScale = 0.5f)) togetherWith
                        (fadeOut(Fades.fadeOut()) + scaleOut(Springs.snappy(), targetScale = 0.5f))
                },
                label = "pillIcon",
            ) { shown -> Icon(shown, null, tint = content, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(8.dp))
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
            .then(if (enabled) Modifier else Modifier.semantics {
                disabled()
                stateDescription = disabledReason.orEmpty()
            })
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
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Wallhop.colors
    val connected = state as? LinkState.Connected
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    app.wallhop.ui.components.InsetSection(dividerInset = 57.dp) {
        row {
            app.wallhop.ui.components.ListRow(
                title = "Status",
                leading = { app.wallhop.ui.components.IconTile(WIcons.Wifi, if (connected != null) colors.green else colors.gray) },
                value = when {
                    state is LinkState.Forgotten -> "Needs pairing again"
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
            row {
                app.wallhop.ui.components.ListRow(
                    title = "Connection details",
                    leading = { app.wallhop.ui.components.IconTile(WIcons.Gauge, colors.indigo) },
                    trailing = {
                        Icon(WIcons.ChevronRight, null, tint = colors.labelTertiary,
                            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f })
                    },
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
                )
            }
            if (expanded) {
                row {
                    app.wallhop.ui.components.ListRow(title = "Address", value = connected.host)
                }
                if (latencyMs != null) {
                    row {
                        app.wallhop.ui.components.ListRow(title = "Response time", value = "$latencyMs ms", animateValue = true)
                    }
                }
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
