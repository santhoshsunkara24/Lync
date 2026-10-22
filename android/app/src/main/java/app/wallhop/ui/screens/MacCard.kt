package app.wallhop.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wallhop.link.ActivityEntry
import app.wallhop.link.LinkState
import app.wallhop.link.MacInfo
import app.wallhop.link.TransferPhase
import app.wallhop.link.TransferState
import app.wallhop.ui.components.spacedCode
import app.wallhop.ui.design.LocalLightAngle
import app.wallhop.ui.design.Radius
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.rounded
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.sin

/** Live details about the Mac for [MacCard]. */
class MacCardData(
    val info: MacInfo,
    val latencyMs: Int?,
    val connectedSince: Long?,
    /** The newest Activity entry: it hops into the little Mac's notch when it arrives. */
    val latest: ActivityEntry?,
    val transfer: TransferState?,
    /** The last few Activity entries, for Home's Recent section. */
    val recent: List<ActivityEntry> = emptyList(),
)

/** What the little Mac's notch is showing. */
private class NotchEvent(val icon: ImageVector, val text: String, val progress: Float? = null)

/**
 * The paired Mac as a living miniature (DESIGN.md §6.4): your real wallpaper on its screen,
 * its lock screen when it's locked, and a notch that does what the real one does — whatever
 * you send hops up into it.
 */
@Composable
fun LiveMac(connected: Boolean, data: MacCardData, modifier: Modifier = Modifier) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    // Events: a dot hops between the phone (bottom) and the notch, then the notch shows what
    // happened. Nothing animates between events, so an idle card costs nothing.
    val hop = remember { Animatable(0f) }
    var hopUp by remember { mutableStateOf(true) }
    var flash by remember { mutableStateOf<NotchEvent?>(null) }
    val seen = remember { mutableStateOf(data.latest?.id) }
    // Plays when the Mac tab is on screen: something sent from another tab hops in on return.
    val active = app.wallhop.ui.components.LocalScreenActive.current
    LaunchedEffect(data.latest?.id, active) {
        val entry = data.latest ?: return@LaunchedEffect
        if (entry.id == seen.value || !active) return@LaunchedEffect
        seen.value = entry.id
        if (System.currentTimeMillis() - entry.time > 10_000) return@LaunchedEffect
        val event = notchEvent(entry)
        hopUp = entry.kind != ActivityEntry.Kind.FromMac
        if (hopUp) {
            if (!reduceMotion) hop.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            hop.snapTo(0f)
            flash = event
            delay(2800)
            flash = null
        } else {
            // From the Mac: the notch announces it, then it hops down to the phone.
            flash = event
            delay(1400)
            if (!reduceMotion) hop.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
            hop.snapTo(0f)
            delay(800)
            flash = null
        }
    }
    val transfer = data.transfer
    val event = when {
        transfer != null && transfer.phase == TransferPhase.Sending ->
            NotchEvent(WIcons.Image, "${(transfer.progress * 100).toInt()}%", transfer.progress)
        transfer != null && transfer.phase == TransferPhase.Waiting -> NotchEvent(WIcons.Image, "Waiting…", 0f)
        else -> flash
    }
    // On connect the little Mac lifts a touch and settles, once.
    val settle = remember { Animatable(0f) }
    val wasConnected = remember { booleanArrayOf(connected) }
    LaunchedEffect(connected) {
        val justConnected = connected && !wasConnected[0]
        wasConnected[0] = connected
        if (justConnected && !reduceMotion && active) {
            settle.animateTo(-1f, Springs.snappy())
            settle.animateTo(0f, Springs.bouncy())
        }
    }
    Box(modifier.graphicsLayer { translationY = settle.value * 5.dp.toPx() }) {
        MiniMac(data.info, connected, event, hop = { hop.value }, hopUp = hopUp)
    }
}

/**
 * The miniature MacBook. Proportions follow a 13" MacBook Air: 16:10.4 screen, notch about 15%
 * of the width. Screen glare and wallpaper parallax follow the same tilt light as the glass.
 */
@Composable
private fun MiniMac(info: MacInfo, connected: Boolean, event: NotchEvent?, hop: () -> Float, hopUp: Boolean) {
    val colors = Wallhop.colors
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val width = minOf(maxWidth * 0.8f, 300.dp)
        val bezel = width * 0.028f
        val lidHeight = width / 1.54f + bezel
        val baseWidth = width * 1.13f
        val baseHeight = width * 0.045f
        val shell = if (colors.dark) Color(0xFF5B5D63) else Color(0xFFC9CBD0)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Lid: aluminium edge, black bezel, the screen.
            Box(
                Modifier
                    .size(width, lidHeight)
                    .clip(RoundedCornerShape(topStart = width * 0.05f, topEnd = width * 0.05f, bottomStart = 2.dp, bottomEnd = 2.dp))
                    .background(shell)
                    .padding(start = 1.dp, end = 1.dp, top = 1.dp)
                    .clip(RoundedCornerShape(topStart = width * 0.047f, topEnd = width * 0.047f))
                    .background(Color(0xFF0B0B0D))
                    .padding(start = bezel, end = bezel, top = bezel, bottom = bezel * 0.6f),
            ) {
                Screen(info, connected, width)
                Notch(event, connected, width, Modifier.align(Alignment.TopCenter).offset(y = -bezel))
            }
            // Hinge and base, with a soft contact shadow.
            Canvas(Modifier.size(baseWidth, baseHeight + width * 0.05f)) {
                val w = size.width
                val h = baseHeight.toPx()
                val lid = width.toPx()
                drawOval(
                    Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = if (colors.dark) 0.5f else 0.16f), Color.Transparent),
                        center = Offset(w / 2, h * 1.1f), radius = w * 0.5f,
                    ),
                    topLeft = Offset(w * 0.02f, h * 0.4f), size = Size(w * 0.96f, h * 1.6f),
                )
                val metal = if (colors.dark) listOf(Color(0xFF6A6C72), Color(0xFF2E2F33)) else listOf(Color(0xFFEDEEF1), Color(0xFFA4A7AE))
                drawRoundRect(
                    Brush.verticalGradient(metal, startY = 0f, endY = h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(h * 0.5f, h * 0.5f),
                )
                drawRect(Color.Black.copy(alpha = 0.22f), Offset((w - lid * 0.97f) / 2, 0f), Size(lid * 0.97f, h * 0.22f))
                val lip = lid * 0.16f
                drawRoundRect(
                    Color.Black.copy(alpha = if (colors.dark) 0.35f else 0.12f),
                    Offset((w - lip) / 2, 0f), Size(lip, h * 0.42f), CornerRadius(h * 0.3f),
                )
            }
        }

        // The hop between phone and notch: a lit dot on a little arc, drawn above everything.
        val bodyHeight = lidHeight + baseHeight
        Canvas(Modifier.size(maxWidth, bodyHeight + 28.dp)) {
            val t = hop()
            if (t <= 0f || t >= 1f) return@Canvas
            val p = if (hopUp) t else 1f - t
            val bottom = Offset(size.width / 2, size.height - 6.dp.toPx())
            val top = Offset(size.width / 2, with(density) { (width * 0.028f + width * 0.018f).toPx() })
            val x = bottom.x + sin(p * PI).toFloat() * width.toPx() * 0.22f
            val y = bottom.y + (top.y - bottom.y) * FastOutSlowInEasing.transform(p)
            val alpha = sin(t * PI).toFloat().coerceIn(0f, 1f)
            val r = 4.dp.toPx() * (1f - 0.35f * p)
            drawCircle(
                Brush.radialGradient(listOf(colors.tint.copy(alpha = 0.55f * alpha), Color.Transparent), Offset(x, y), r * 4),
                radius = r * 4, center = Offset(x, y),
            )
            drawCircle(Color.White.copy(alpha = alpha), r, Offset(x, y))
        }
    }
}

/** The Mac's screen: wallpaper, menu bar, and the lock screen while it's locked. */
@Composable
private fun Screen(info: MacInfo, connected: Boolean, width: Dp) {
    val light = LocalLightAngle.current
    val density = LocalDensity.current
    val awake by animateFloatAsState(if (connected) 1f else 0f, Springs.smooth(), label = "awake")
    val locked by animateFloatAsState(if (info.locked && connected) 1f else 0f, Springs.smooth(), label = "locked")
    val wallpaper = remember(info.wallpaper) { info.wallpaper?.asImageBitmap() }
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.15f) }) }

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(width * 0.02f)).background(Color.Black)) {
        val parallax = Modifier.graphicsLayer {
            // Tilt shifts the wallpaper a touch, as if it sat behind the glass.
            translationX = (light.value - 45f) / 20f * size.width * 0.02f
            scaleX = 1.05f
            scaleY = 1.05f
            alpha = 0.22f + 0.78f * awake
        }
        if (wallpaper != null) {
            Image(
                wallpaper, null, parallax.fillMaxSize(), contentScale = ContentScale.Crop,
                colorFilter = if (connected) null else grey,
            )
        } else {
            // A moving or unreadable wallpaper: a calm stand-in.
            Box(
                parallax.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF15245E), Color(0xFF4A2D86), Color(0xFFD06A6F))),
                ),
            )
        }
        // Menu bar.
        Box(
            Modifier
                .fillMaxWidth()
                .height(width * 0.034f)
                .graphicsLayer { alpha = awake * (1f - locked) }
                .background(Color.White.copy(alpha = 0.18f)),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = width * 0.03f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(width * 0.018f),
            ) {
                listOf(0.05f, 0.035f, 0.03f, 0.04f).forEach { Box(Modifier.size(width * it, width * 0.008f).clip(capsule()).background(Color.White.copy(alpha = 0.8f))) }
                Spacer(Modifier.weight(1f))
                listOf(0.02f, 0.02f, 0.06f).forEach { Box(Modifier.size(width * it, width * 0.008f).clip(capsule()).background(Color.White.copy(alpha = 0.8f))) }
            }
        }
        // Lock screen: dimmed wallpaper, a lock and the time.
        if (locked > 0.01f) {
            val time by produceState(now()) {
                while (true) {
                    delay(60_000 - System.currentTimeMillis() % 60_000)
                    value = now()
                }
            }
            Column(
                Modifier.fillMaxSize().graphicsLayer { alpha = locked }.background(Color.Black.copy(alpha = 0.28f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(width * 0.08f))
                Icon(WIcons.Lock, null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(width * 0.04f))
                Text(
                    time,
                    color = Color.White,
                    style = Type.largeTitle.copy(
                        fontSize = with(density) { (width * 0.12f).toSp() },
                        lineHeight = with(density) { (width * 0.14f).toSp() },
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
        // Glare across the glass, following the tilt light.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                val shift = (light.value - 45f) / 20f
                val start = Offset(size.width * (0.15f + 0.25f * shift), 0f)
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0f),
                        0.45f to Color.White.copy(alpha = 0.07f),
                        0.55f to Color.White.copy(alpha = 0f),
                        start = start, end = start + Offset(size.width * 0.6f, size.height),
                    ),
                )
            },
        )
    }
}

/**
 * The notch. Idle it's the plain black notch with the green "camera" light when connected;
 * for an event it grows into a pill with an icon and a few words, like Wallhop's notch on the Mac.
 */
@Composable
private fun Notch(event: NotchEvent?, connected: Boolean, width: Dp, modifier: Modifier) {
    val open = event != null
    val notchWidth by animateDpAsState(if (open) width * 0.5f else width * 0.155f, Springs.bouncy(), label = "notchW")
    val notchHeight by animateDpAsState(if (open) width * 0.082f else width * 0.046f, Springs.bouncy(), label = "notchH")
    val content by animateFloatAsState(if (open) 1f else 0f, Springs.snappy(), label = "notchContent")
    val density = LocalDensity.current
    // Keep the last event on screen while the pill closes.
    var shown by remember { mutableStateOf(event) }
    if (event != null) shown = event
    val green = Wallhop.colors.green

    Box(
        modifier
            .size(notchWidth, notchHeight)
            .clip(RoundedCornerShape(bottomStart = notchHeight * 0.45f, bottomEnd = notchHeight * 0.45f))
            .background(Color(0xFF0B0B0D)),
        contentAlignment = Alignment.Center,
    ) {
        if (content < 0.99f && connected) {
            // The camera light: green while the link is up.
            Box(
                Modifier
                    .offset(x = width * 0.025f, y = width * 0.006f)
                    .size(width * 0.011f)
                    .graphicsLayer { alpha = 1f - content }
                    .clip(capsule())
                    .background(green),
            )
        }
        val current = shown
        if (current != null && content > 0.01f) {
            Row(
                Modifier
                    .padding(top = width * 0.022f, start = width * 0.035f, end = width * 0.035f)
                    .graphicsLayer { alpha = content },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(width * 0.02f),
            ) {
                if (current.progress != null) {
                    ProgressRing(current.progress, Modifier.size(width * 0.038f))
                } else {
                    Icon(current.icon, null, tint = green, modifier = Modifier.size(width * 0.038f))
                }
                Text(
                    current.text,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = Type.caption2.copy(
                        fontSize = with(density) { (width * 0.036f).toSp() },
                        lineHeight = with(density) { (width * 0.042f).toSp() },
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, modifier: Modifier) {
    val track = Color.White.copy(alpha = 0.25f)
    val tint = Wallhop.colors.green
    // Progress arrives up to 10 times a second; the ring glides between updates.
    val reduceMotion = Wallhop.a11y.reduceMotion
    val shown by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        if (reduceMotion) androidx.compose.animation.core.snap() else Springs.smooth(),
        label = "notchRing",
    )
    Canvas(modifier) {
        val stroke = size.minDimension * 0.18f
        drawCircle(track, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
        drawArc(tint, -90f, 360f * shown, false, style = Stroke(stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke))
    }
}

private fun notchEvent(entry: ActivityEntry): NotchEvent = when (entry.kind) {
    ActivityEntry.Kind.Otp -> NotchEvent(WIcons.Code, spacedCode(entry.title))
    ActivityEntry.Kind.FilesSent -> NotchEvent(WIcons.Image, entry.title)
    ActivityEntry.Kind.TextSent -> NotchEvent(WIcons.Text, entry.title)
    ActivityEntry.Kind.Call -> NotchEvent(WIcons.Call, entry.title)
    ActivityEntry.Kind.FromMac -> NotchEvent(WIcons.Laptop, entry.title)
}

private fun now(): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())

internal fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "Now"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} m"
    }
}

/**
 * The Bridge (Home's Mac card): your phone stands in front of the big Mac, and a mint link arcs
 * from it to the notch with a light that travels along it while they're linked. Fill the
 * picture area with it, on top of [LiveMac].
 */
@Composable
fun BridgeLink(connected: Boolean, home: app.wallhop.ui.design.HomeColors, modifier: Modifier = Modifier) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val active = app.wallhop.ui.components.LocalScreenActive.current
    val mint = app.wallhop.ui.design.HomeColors.CodeMint
    // One slow loop drives both the dotted link's drift and the travelling light.
    val flow = remember { Animatable(0f) }
    LaunchedEffect(connected, reduceMotion, active) {
        if (!connected || reduceMotion || !active) return@LaunchedEffect
        while (true) {
            flow.snapTo(0f)
            flow.animateTo(1f, tween(4200, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    val linked by animateFloatAsState(if (connected) 1f else 0f, Springs.smooth(), label = "linked")
    val time by produceState(now()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = now()
        }
    }
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val lid = minOf(w * 0.8f, 300.dp)
        val phoneW = 52.dp
        val phoneH = 108.dp
        val phoneX = w * 0.86f - phoneW / 2
        val phoneY = 52.dp
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = linked }) {
            val start = Offset((phoneX + phoneW / 2 + 3.dp).toPx(), (phoneY - 3.dp).toPx())
            val end = Offset(size.width / 2 + (lid * 0.155f / 2 + 4.dp).toPx(), 21.dp.toPx())
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(start.x, start.y)
                cubicTo(start.x + 10.dp.toPx(), 6.dp.toPx(), end.x + 34.dp.toPx(), 4.dp.toPx(), end.x, end.y)
            }
            drawPath(path, mint.copy(alpha = 0.22f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            val dash = 10.dp.toPx()
            drawPath(
                path, mint,
                style = Stroke(
                    2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.1f, dash), -flow.value * dash * 6),
                ),
            )
            // The light: travels phone → notch in the first 60 % of each loop.
            val p = flow.value / 0.6f
            if (p in 0.01f..1f) {
                val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
                val at = measure.getPosition(measure.length * FastOutSlowInEasing.transform(p))
                val a = sin(p * PI).toFloat()
                drawCircle(Brush.radialGradient(listOf(mint.copy(alpha = 0.7f * a), Color.Transparent), at, 12.dp.toPx()), 12.dp.toPx(), at)
                drawCircle(Color.White.copy(alpha = a), 3.dp.toPx(), at)
            }
        }
        // The phone: dark frame, punch-hole camera, a lock screen in Home's own colours.
        Box(
            Modifier
                .offset(phoneX, phoneY)
                .size(phoneW, phoneH)
                .graphicsLayer { rotationZ = 6f; shadowElevation = 14.dp.toPx(); shape = RoundedCornerShape(13.dp); clip = true }
                .background(Color(0xFF18181A))
                .padding(2.5.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Brush.verticalGradient(listOf(home.top, home.mid, Color(0xFF0B0B0C)))),
        ) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(4.5.dp).clip(capsule()).background(Color.Black))
            Text(
                time,
                color = Color.White,
                maxLines = 1,
                style = Type.caption2.copy(fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.Light),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 15.dp),
            )
        }
    }
}
