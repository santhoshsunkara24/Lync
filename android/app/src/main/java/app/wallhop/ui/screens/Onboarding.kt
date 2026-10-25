package app.wallhop.ui.screens

import kotlin.math.roundToInt

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wallhop.R
import app.wallhop.ui.components.LinkCatch
import app.wallhop.ui.components.Liftoff
import app.wallhop.ui.components.LocalScreenActive
import app.wallhop.ui.design.Easings
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.tappable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * First launch, the clean way: pure black, one object per screen (Lync's real notch card or the
 * link), a short title and one line in the same place every time, and one mint button. Each
 * screen's motion plays once, then rests. The tour can always be skipped.
 */

private val Mint = Color(0xFF5FD9A0)
private val OnMint = Color(0xFF04140C)
private val Muted = Color.White.copy(alpha = 0.56f)
private val Faint = Color.White.copy(alpha = 0.14f)

/** The shared frame: top bar, a hero that fills the space, then the title, one line and the buttons. */
@Composable
fun OnboardingFrame(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    action: Pair<String, () -> Unit>? = null,
    hero: @Composable BoxScope.() -> Unit,
    foot: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 20.dp),
    ) {
        OnboardingBar(onBack, action)
        Box(Modifier.weight(1f).fillMaxWidth(), content = hero)
        OnboardingText(title, body)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp), content = foot)
    }
}

@Composable
private fun OnboardingBar(onBack: (() -> Unit)?, action: Pair<String, () -> Unit>?) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.offset(x = (-12).dp).size(48.dp).clip(capsule()).tappable(source, onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { Icon(WIcons.ChevronLeft, null, tint = Muted, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.weight(1f))
        if (action != null) QuietButton(action.first, action.second)
    }
}

@Composable
private fun OnboardingText(title: String, body: String?) {
    Column(Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
        Text(
            title, color = Color.White, textAlign = TextAlign.Start,
            style = Type.pageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp),
            modifier = Modifier.semantics { heading() },
        )
        if (body != null) {
            Text(body, color = Muted, textAlign = TextAlign.Start, style = Type.body.copy(fontSize = 16.sp, lineHeight = 23.sp), modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** The one mint button. */
@Composable
fun OnboardingPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.height(56.dp).pressScale(source, 0.97f).clip(capsule()).background(Mint).tappable(source, onClick = onClick)
            .padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = OnMint, style = Type.headline.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)) }
}

/** A quiet text button: Skip, Another time, Get Lync for Mac. */
@Composable
fun QuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 48.dp).clip(capsule()).tappable(source, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Muted, style = Type.subheadline.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) }
}

/** A soft mint light behind the hero. */
private fun Modifier.mintGlow(): Modifier = drawBehind {
    val r = size.minDimension * 0.62f
    drawCircle(
        Brush.radialGradient(listOf(Mint.copy(alpha = 0.17f), Mint.copy(alpha = 0.17f * 0.25f), Color.Transparent), center, r),
        radius = r, center = center,
    )
}

/** Progress for a one-off entrance, waiting until the screen is actually on show. */
@Composable
private fun rememberEntrance(delayMs: Long): Animatable<Float, *> {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val active = LocalScreenActive.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(active) {
        if (!active || progress.value >= 1f) return@LaunchedEffect
        delay(delayMs)
        progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 280f))
    }
    return progress
}

/**
 * The top edge of a Mac screen with Lync's real card hanging from the notch. [behind] draws
 * earlier cards peeking out underneath (a stack).
 */
@Composable
private fun BoxScope.MacEdge(@DrawableRes card: Int, delayMs: Long = 300, behind: Int = 0, keys: Boolean = false) {
    val drop = rememberEntrance(delayMs)
    val keysIn = rememberEntrance(delayMs + 900)
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 64.dp)
            .requiredWidth(LocalEdgeWidth)
            .height(300.dp)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Mint.copy(alpha = 0.14f), Mint.copy(alpha = 0.03f), Color.Transparent),
                        center = Offset(size.width / 2, 0f), radius = size.width * 0.62f,
                    ),
                )
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.22f), Color.Transparent)),
                    Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        for (i in behind downTo 1) {
            Image(
                painterResource(card), null,
                Modifier.align(Alignment.TopCenter).offset(y = (13 * i).dp).width(342.dp)
                    .graphicsLayer { val s = 1f - 0.07f * i; scaleX = s; scaleY = s; alpha = (0.5f - 0.23f * (i - 1)) * drop.value.coerceIn(0f, 1f); transformOrigin = TransformOrigin(0.5f, 0f) },
            )
        }
        Image(
            painterResource(card), null,
            Modifier.align(Alignment.TopCenter).width(342.dp).graphicsLayer {
                val p = drop.value
                scaleX = 0.32f + 0.68f * p
                scaleY = 0.18f + 0.82f * p
                alpha = (p * 6f).coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        )
        if (keys) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 150.dp).graphicsLayer {
                    alpha = keysIn.value.coerceIn(0f, 1f); translationY = (1f - keysIn.value) * 8.dp.toPx()
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { KeyCap("⌘"); KeyCap("V") }
        }
    }
}

private val LocalEdgeWidth = 420.dp

@Composable
private fun KeyCap(label: String) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0F0D))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, style = Type.title3.copy(fontWeight = FontWeight.Bold)) }
}

/** The pages as dots; [position] is fractional, so the long dot slides with the finger mid-swipe. */
@Composable
private fun Dots(position: Float, count: Int) {
    val page = position.roundToInt().coerceIn(0, count - 1)
    Row(Modifier.semantics { contentDescription = "Page ${page + 1} of $count" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val near = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            Box(
                Modifier.height(6.dp).width(6.dp + 14.dp * near).clip(capsule())
                    .background(androidx.compose.ui.graphics.lerp(Faint, Color.White, near)),
            )
        }
    }
}

/**
 * The tour before pairing: who Lync is, then codes, files and calls. Skip goes straight to pairing.
 */
@Composable
fun IntroTour(onDone: () -> Unit) {
    val pager = rememberPagerState { 4 }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    // Where the swipe is, between pages: the dots and the buttons follow it.
    val position by remember { androidx.compose.runtime.derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }
    // Back steps back through the tour rather than leaving it.
    androidx.activity.compose.BackHandler(enabled = page > 0) { scope.launch { pager.animateScrollToPage(page - 1) } }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(bottom = 20.dp)) {
        Box(Modifier.padding(horizontal = 24.dp)) {
            OnboardingBar(
                onBack = if (page > 0) ({ scope.launch { pager.animateScrollToPage(page - 1) } }) else null,
                action = "Skip" to onDone,
            )
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
            CompositionLocalProvider(LocalScreenActive provides (pager.currentPage == index)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (index) {
                            0 -> IntroHero()
                            1 -> MacEdge(R.drawable.onb_code, keys = true)
                            2 -> FilesHero()
                            else -> MacEdge(R.drawable.onb_call)
                        }
                    }
                    when (index) {
                        0 -> OnboardingText("Your phone, on your Mac.", "Codes, files and calls, right where you work.")
                        1 -> OnboardingText("Codes, already copied.", "A code arrives on your phone. Press ⌘V on your Mac.")
                        2 -> OnboardingText("Send it. Paste it.", "Share anything to Lync, then ⌘V on your Mac.")
                        else -> OnboardingText("Calls, on your Mac.", "See who's calling and read messages at your desk.")
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        // "Get started" hands over to the dots and Next as the first swipe goes, not in one frame;
        // whichever is mostly showing is on top and takes the tap.
        val handover = position.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(56.dp)) {
            val start: @Composable () -> Unit = {
                OnboardingPill("Get started", { scope.launch { pager.animateScrollToPage(1) } },
                    Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - handover })
            }
            val next: @Composable () -> Unit = {
                Row(Modifier.fillMaxSize().graphicsLayer { alpha = handover }, verticalAlignment = Alignment.CenterVertically) {
                    Dots(position - 1f, 3)
                    Spacer(Modifier.weight(1f))
                    OnboardingPill(if (page == 3) "Pair my Mac" else "Next", {
                        if (page == 3) onDone() else scope.launch { pager.animateScrollToPage(page + 1) }
                    })
                }
            }
            if (handover < 0.5f) { next(); start() } else { start(); next() }
        }
    }
}

/** The link snaps shut, then the name appears under it. */
@Composable
private fun BoxScope.IntroHero() {
    var snapped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(350); snapped = true }
    val word = rememberEntrance(900)
    Box(Modifier.fillMaxSize().mintGlow())
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        LinkCatch(snapped, Modifier.size(150.dp), shaded = false)
        Text(
            "Lync", color = Color.White, style = Type.pageTitle.copy(fontSize = 40.sp, lineHeight = 46.sp),
            modifier = Modifier.padding(top = 16.dp).graphicsLayer { alpha = word.value.coerceIn(0f, 1f); translationY = (1f - word.value) * 10.dp.toPx() },
        )
    }
}

/** The link lifts off on its own speed lines, and the real "3 photos" card lands in the notch. */
@Composable
private fun BoxScope.FilesHero() {
    MacEdge(R.drawable.onb_files, delayMs = 1100)
    Liftoff(sending = true, done = false, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(200.dp))
}

/** The camera finding the code on the Mac (pairing). */
@Composable
fun BoxScope.ScanHero() {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val scan = rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = Easings.InOut), RepeatMode.Reverse), label = "scanLine",
    )
    Box(Modifier.fillMaxSize().mintGlow())
    Box(Modifier.align(Alignment.Center).size(248.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 3.dp.toPx(); val r = 26.dp.toPx(); val l = 40.dp.toPx()
            val s = Stroke(w, cap = StrokeCap.Round)
            // Four corner brackets, each a quarter arc with short legs.
            for (k in 0 until 4) {
                rotate(90f * k) {
                    drawArc(Mint, 180f, 90f, false, topLeft = Offset(w / 2, w / 2), size = Size(r * 2, r * 2), style = s)
                    drawLine(Mint, Offset(w / 2, r + w / 2), Offset(w / 2, l), w, StrokeCap.Round)
                    drawLine(Mint, Offset(r + w / 2, w / 2), Offset(l, w / 2), w, StrokeCap.Round)
                }
            }
        }
        Box(
            Modifier.align(Alignment.Center).size(168.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF4FBF7)),
            contentAlignment = Alignment.Center,
        ) { Icon(WIcons.QrCode, null, tint = Color.Black, modifier = Modifier.size(128.dp)) }
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 34.dp).width(188.dp).height(2.dp)
                .graphicsLayer { translationY = if (reduceMotion) 88.dp.toPx() else scan.value * 176.dp.toPx() }
                .background(Mint),
        )
    }
}

/** Pairing done: the link snaps and a ring of light runs once around it. */
@Composable
fun LinkedMoment(macName: String, modifier: Modifier = Modifier) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    var snapped by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(200); snapped = true }
    val ring = remember { Animatable(0f) }
    LaunchedEffect(Unit) { if (!reduceMotion) { delay(450); ring.animateTo(1f, tween(1500, easing = Easings.Out)) } }
    val text = rememberEntrance(700)
    Column(modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth().mintGlow(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(200.dp)) {
                val p = ring.value
                if (p <= 0f || p >= 1f) return@Canvas
                val fade = (if (p < 0.15f) p / 0.15f else if (p > 0.8f) (1f - p) / 0.2f else 1f).coerceIn(0f, 1f)
                val stroke = 2.dp.toPx()
                rotate(-90f + 360f * p) {
                    drawArc(
                        Brush.sweepGradient(listOf(Color.Transparent, Mint.copy(alpha = fade), Color.Transparent)),
                        0f, 140f, false, topLeft = Offset(stroke, stroke), size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
                        style = Stroke(stroke * 1.5f, cap = StrokeCap.Round),
                    )
                }
            }
            LinkCatch(snapped, Modifier.size(130.dp), shaded = false)
        }
        Column(
            Modifier.heightIn(min = 104.dp).padding(bottom = 84.dp).graphicsLayer { alpha = text.value.coerceIn(0f, 1f); translationY = (1f - text.value) * 10.dp.toPx() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("You're linked.", color = Color.White, style = Type.pageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp), modifier = Modifier.semantics { heading() })
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(capsule()).background(Mint))
                Text(macName, color = Muted, style = Type.body.copy(fontSize = 16.sp))
            }
        }
    }
}

/** Six digits in tiles, for checking the pairing code against the Mac. */
@Composable
fun CodeTiles(code: String, modifier: Modifier = Modifier) {
    val tiles = rememberEntrance(150)
    Row(
        modifier.semantics { contentDescription = "Pairing code ${code.chunked(3).joinToString(" ")}" }
            .graphicsLayer { alpha = tiles.value.coerceIn(0f, 1f); translationY = (1f - tiles.value) * 10.dp.toPx() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        code.forEachIndexed { i, c ->
            if (i == 3) Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(46.dp, 68.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B0F0D))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(c.toString(), color = Color.White, style = Type.largeTitle.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")) }
        }
    }
}

/** The one permission asked during setup: codes, shown as they'll arrive on the Mac. */
@Composable
fun AllowCodesScreen(onAllow: () -> Unit, onLater: () -> Unit) {
    OnboardingFrame(
        title = "Let Lync catch codes.",
        body = "Only the code goes to your Mac, never the message.",
        hero = { MacEdge(R.drawable.onb_code, behind = 2) },
        foot = {
            OnboardingPill("Turn on codes", onAllow, Modifier.fillMaxWidth())
            QuietButton("Another time", onLater, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        },
    )
}
