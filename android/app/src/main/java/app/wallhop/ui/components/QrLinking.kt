package app.wallhop.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.tappable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pairing from a scanned QR code, as one continuous moment: the code just scanned breaks into
 * mint dots that stream into the two links, held apart while the phone connects; when the Mac
 * says yes they clasp (the same spring as [LinkCatch]), light up, a glint runs along one and a
 * ring of light goes round; then "You're linked." The pattern is the real one: [qrText] encoded
 * again, so the dots start from the squares the camera just saw. Given [from] (where the code
 * was on screen, in window pixels), the code first comes forward from there to the middle,
 * turning mint on the way, before it breaks into dots.
 */
@Composable
fun QrLinking(
    qrText: String,
    macName: String,
    success: Boolean,
    onCancel: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    from: androidx.compose.ui.geometry.Rect? = null,
) {
    BackHandler(enabled = !success, onBack = onCancel)
    val reduceMotion = Wallhop.a11y.reduceMotion
    val haptics = rememberHaptics()
    val geo = remember(qrText) { LinkingGeometry(qrText) }
    // Seconds on the forming timeline, from the frame clock (below zero while the code comes
    // forward); stops once the moment is over.
    val lead = if (from != null && !reduceMotion) Lead else 0f
    var t by remember { mutableFloatStateOf(-lead) }
    var canvasAt by remember { mutableStateOf(Offset.Zero) }
    var claspAt by remember { mutableStateOf<Float?>(null) }
    val successNow by rememberUpdatedState(success)
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var done = false
        while (true) {
            withFrameNanos { now ->
                t = (if (reduceMotion) Fused else 0f) - lead + (now - start) / 1e9f
            }
            // The clasp waits for the links to be formed, and for the Mac's yes.
            if (claspAt == null && successNow && t >= Fused) claspAt = t
            val c = claspAt
            if (c != null && !done && t >= c + Done) { done = true; finished() }
            if (done && t > (c ?: 0f) + Done + 0.5f) break
        }
    }
    val clasp = claspAt
    LaunchedEffect(clasp) {
        if (clasp == null || reduceMotion) return@LaunchedEffect
        kotlinx.coroutines.delay((Contact * 1000).toLong()); haptics.perform(Haptic.Light)
        kotlinx.coroutines.delay(((Lit - Contact) * 1000).toLong()); haptics.perform(Haptic.Success)
    }
    LaunchedEffect(clasp, reduceMotion) { if (clasp != null && reduceMotion) haptics.perform(Haptic.Success) }
    val linkedText = clasp != null && (reduceMotion || t >= clasp + 0.75f)

    Column(
        modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.CenterEnd) {
            if (!success) {
                Text(
                    "Cancel", style = Type.callout.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = Color.White.copy(alpha = 0.56f),
                    modifier = Modifier.clip(capsule()).tappable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onCancel).padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(300.dp).onGloballyPositioned { canvasAt = it.positionInWindow() }) {
                drawLinking(geo, t, claspAt, reduceMotion, from?.translate(-canvasAt), lead)
            }
        }
        AnimatedContent(
            targetState = linkedText,
            transitionSpec = { fadeIn(androidx.compose.animation.core.tween(400)) togetherWith fadeOut(androidx.compose.animation.core.tween(200)) },
            label = "linkingText",
            modifier = Modifier.heightIn(min = 104.dp).padding(bottom = 84.dp),
        ) { linked ->
            Column(
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (linked) "You're linked." else "Connecting to $macName…",
                    color = if (linked) Color.White else Color.White.copy(alpha = 0.56f),
                    style = if (linked) Type.pageTitle.copy(fontSize = 32.sp, lineHeight = 38.sp) else Type.body,
                    modifier = Modifier.semantics { heading() },
                )
                if (linked) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(capsule()).background(Mint))
                        Text(macName, color = Color.White.copy(alpha = 0.56f), style = Type.body)
                    }
                }
            }
        }
    }
}

// Coming forward from where the code was scanned, before the forming starts.
// Each move is a critically damped spring (Apple's default: no bounce, settles smoothly), and
// each step starts while the last is still settling, so nothing parks between them.
private const val Lead = 0.5f
// Timeline, seconds. Forming: the code rounds into dots, they stream in, then fuse into the links.
private const val MorphFrom = -0.08f
private const val MorphTo = 0.24f
private const val TravelFrom = 0.16f
private const val TravelSpread = 0.36f
private const val Travel = 0.72f
private const val Arrive = TravelFrom + TravelSpread + Travel // 1.24
private const val Fused = Arrive + 0.28f
// The clasp, counted from when it starts.
private const val WindUp = 0.08f
private const val Contact = 0.18f
private const val Lit = 0.53f
private const val Done = 1.95f

private val Mint = Color(0xFF7FE3B0)
private val Ring = Color(0xFF5FD9A0)
private val ApartA = Color(0xFF65A184)
private val ApartB = Color(0xFF4F6B60)

/** The two hooks (LinkCatch's, turned -45°) in a 256 box; dots sampled along them; the code's squares. */
private class LinkingGeometry(qrText: String) {
    val hookA: Path = PathParser().parsePathString(HookA).toPath()
    val hookB: Path = PathParser().parsePathString(HookB).toPath()

    class Dot(val sign: Int, val x: Float, val y: Float, val cx: Float, val cy: Float, val nx: Float, val ny: Float, val bend: Float, val delay: Float)

    val dots: List<Dot>
    val spare: List<Offset>
    val cell: Float

    init {
        // The pattern the Mac drew, at the size the dots need.
        val matrix = runCatching {
            QRCodeWriter().encode(qrText, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0))
        }.getOrNull()
        val n = matrix?.width ?: 25
        cell = QrSide / n
        val origin = 128f - QrSide / 2
        val cells = buildList {
            for (j in 0 until n) for (i in 0 until n) {
                val on = matrix?.get(i, j) ?: ((i * 31 + j * 17) % 7 < 3)
                if (on) add(Offset(origin + (i + 0.5f) * cell, origin + (j + 0.5f) * cell))
            }
        }
        // Points along each hook, in four rows across its stroke.
        val targets = ArrayList<Triple<Int, Float, Float>>()
        for ((path, sign) in listOf(hookA to -1, hookB to 1)) {
            val pm = PathMeasure().apply { setPath(path, false) }
            var s = 0f
            while (s <= pm.length + 0.01f) {
                val p = pm.getPosition(min(s, pm.length))
                val tan = pm.getTangent(min(s, pm.length))
                val len = hypot(tan.x, tan.y).takeIf { it > 0f } ?: 1f
                for (lane in Lanes) targets += Triple(sign, p.x - tan.y / len * lane, p.y + tan.x / len * lane)
                s += Spacing
            }
        }
        // Squares and places matched in sweeping order, so the dots flow as streams, not a scramble.
        val key = { x: Float, y: Float -> x + y * 0.35f }
        val pts = targets.sortedBy { key(it.second, it.third) }
        val pool = cells.sortedBy { key(it.x, it.y) }
        val used = HashSet<Int>()
        val maxD = hypot(QrSide / 2, QrSide / 2)
        val step = pool.size.toFloat() / pts.size
        dots = pts.mapIndexed { k, (sign, x, y) ->
            val idx = min(pool.size - 1, (k * step).toInt()); used += idx
            val c = pool[idx]
            val ex = x + sign * Ux * Apart; val ey = y + sign * Uy * Apart
            val dx = ex - c.x; val dy = ey - c.y; val d = hypot(dx, dy).takeIf { it > 0f } ?: 1f
            val wobble = (((k * 7919) % 100) / 100f - 0.5f) * 15f
            Dot(sign, x, y, c.x, c.y, -dy / d, dx / d, sign * 30f + wobble,
                TravelFrom + TravelSpread * hypot(c.x - 128f, c.y - 128f) / maxD)
        }
        spare = pool.filterIndexed { i, _ -> i !in used }
    }

    companion object {
        const val HookA = "M120.22 91.94 L62.24 149.92 A31 31 0 0 0 106.08 193.76 L116.69 183.15"
        const val HookB = "M135.78 164.06 L193.76 106.08 A31 31 0 0 0 149.92 62.24 L139.31 72.85"
        const val QrSide = 236f
        const val Spacing = 4.4f
        val Lanes = floatArrayOf(-7.2f, -2.4f, 2.4f, 7.2f)
    }
}

private const val Apart = 46f
private val Ux = sqrt(0.5f)
private val Uy = -sqrt(0.5f)

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun eo(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
private fun eio(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
private fun lerpF(a: Float, b: Float, p: Float) = a + (b - a) * p

/** A critically damped spring from rest, [response] seconds (Apple's damping 1): 0 → 1, no overshoot. */
private fun settle(x: Float, response: Float): Float {
    if (x <= 0f) return 0f
    val w = 2f * PI.toFloat() / response
    return 1f - (1f + w * x) * exp(-w * x)
}

/** LinkCatch's spring (damping 0.58, stiffness 130): 0 → 1, a little past, settling. */
private fun springClose(x: Float): Float {
    if (x <= 0f) return 0f
    val z = 0.58f; val w = sqrt(130f); val wd = w * sqrt(1 - z * z)
    return 1f - exp(-z * w * x) * (cos(wd * x) + z * w / wd * sin(wd * x))
}

private fun DrawScope.drawLinking(geo: LinkingGeometry, t: Float, clasp: Float?, reduceMotion: Boolean, from: androidx.compose.ui.geometry.Rect?, lead: Float) {
    val k = size.minDimension / 256f * 0.86f
    translate(size.width / 2 - 128f * k, size.height / 2 - 128f * k) {
        scale(k, k, pivot = Offset.Zero) {
            // Glow behind the link, growing in as it forms.
            val glow = if (reduceMotion) 1f else eo(seg(t, Arrive - 0.4f, Arrive + 0.8f))
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(Ring.copy(alpha = 0.17f * (0.3f + 0.7f * glow)), Ring.copy(alpha = 0.04f), Color.Transparent),
                    center = Offset(128f, 128f), radius = 190f,
                ),
                radius = 190f, center = Offset(128f, 128f),
            )
            // The scanned code in link units, from this canvas's pixels.
            val start = from?.let {
                val ox = size.width / 2 - 128f * k; val oy = size.height / 2 - 128f * k
                androidx.compose.ui.geometry.Rect((it.left - ox) / k, (it.top - oy) / k, (it.right - ox) / k, (it.bottom - oy) / k)
            }
            when {
                reduceMotion || t >= Arrive -> drawLinks(geo, t, clasp, reduceMotion)
                t < MorphFrom && start != null -> drawForward(geo, 1f + t / lead, start)
                // The code is all but settled in the middle by then (over 99%): the squares round as it lands.
                else -> drawForming(geo, t, if (start != null) Mint else Color.White)
            }
        }
    }
}

/**
 * The scanned code coming forward, [p] 0 → 1: from where it was in the camera to full size in the
 * middle, squares and all, turning from white to mint on the way.
 */
private fun DrawScope.drawForward(geo: LinkingGeometry, p: Float, start: androidx.compose.ui.geometry.Rect) {
    val e = settle(p * Lead, 0.38f)
    val scale = lerpF(start.width / LinkingGeometry.QrSide, 1f, e)
    val cx = lerpF(start.center.x, 128f, e); val cy = lerpF(start.center.y, 128f, e)
    val color = lerp(Color.White, Mint, eio(seg(p, 0.3f, 0.75f)))
    val side = (geo.cell - 1.2f) * scale
    val radius = CornerRadius(0.6f * scale)
    fun square(x: Float, y: Float) {
        val sx = cx + (x - 128f) * scale; val sy = cy + (y - 128f) * scale
        drawRoundRect(color, Offset(sx - side / 2, sy - side / 2), Size(side, side), radius)
    }
    for (c in geo.spare) square(c.x, c.y)
    for (d in geo.dots) square(d.cx, d.cy)
}

private fun DrawScope.drawForming(geo: LinkingGeometry, t: Float, from: Color) {
    val morph = eio(seg(t, MorphFrom, MorphTo))
    val color = lerp(from, Mint, morph)
    val side = lerpF(geo.cell - 1.2f, 4.4f, morph)
    val radius = CornerRadius(lerpF(0.6f, side / 2, morph))
    // Squares that aren't needed fade as the rest round into dots.
    val fade = 1f - seg(t, MorphFrom, MorphTo + 0.1f)
    if (fade > 0f) for (c in geo.spare) {
        drawRoundRect(color.copy(alpha = fade), Offset(c.x - side / 2, c.y - side / 2), Size(side, side), radius)
    }
    for (d in geo.dots) {
        val p = settle(t - d.delay, Travel * 0.7f)
        val b = sin(PI.toFloat() * p) * d.bend
        val ex = d.x + d.sign * Ux * Apart; val ey = d.y + d.sign * Uy * Apart
        val x = lerpF(d.cx, ex, p) + d.nx * b; val y = lerpF(d.cy, ey, p) + d.ny * b
        drawRoundRect(color, Offset(x - side / 2, y - side / 2), Size(side, side), radius)
    }
}

private fun DrawScope.drawLinks(geo: LinkingGeometry, t: Float, clasp: Float?, reduceMotion: Boolean) {
    val fuse = if (reduceMotion) 1f else settle(t - Arrive, 0.3f)
    val u = clasp?.let { t - it } ?: -1f
    // Apart, breathing gently while the phone connects; then the wind-up and the spring shut.
    val breathe = if (reduceMotion) 0f else 3f * sin(2f * PI.toFloat() * (t - Fused) / 3.2f) * seg(t, Fused, Fused + 0.8f)
    val gap = when {
        clasp == null -> Apart + breathe
        reduceMotion -> 0f
        u < WindUp -> Apart + 8f * eo(seg(u, 0f, WindUp))
        else -> (Apart + 8f) * (1f - springClose(u - WindUp))
    }
    val lit = if (clasp == null) 0f else if (reduceMotion) 1f else eo(seg(u, Contact, Lit))
    val squash = if (clasp == null || reduceMotion) 0f else max(0f, 1f - abs(u - Contact) / 0.12f)
    if (fuse < 1f) {
        for (d in geo.dots) {
            val r = 2.2f + 3.5f * fuse
            drawCircle(Mint.copy(alpha = 0.9f * (1f - fuse)), r, Offset(d.x + d.sign * Ux * Apart, d.y + d.sign * Uy * Apart))
        }
    }
    val center = Offset(128f, 128f)
    rotate(-45f, center) {
        scale(1f + 0.05f * squash, 1f - 0.06f * squash, center) {
            rotate(45f, center) {
                for ((path, sign) in listOf(geo.hookB to 1, geo.hookA to -1)) {
                    translate(sign * Ux * gap, sign * Uy * gap) {
                        val c = lerp(if (sign > 0) ApartB else ApartA, Mint, lit)
                        drawPath(path, c.copy(alpha = fuse), style = Stroke(21f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        if (sign < 0 && clasp != null && !reduceMotion) {
                            val gp = seg(u, 0.28f, 0.73f)
                            if (gp > 0f && gp < 1f) {
                                val at = gp * 194f - 22f
                                drawPath(
                                    path, Color.White.copy(alpha = 0.9f * sin(PI.toFloat() * gp)),
                                    style = Stroke(6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, 400f), -at)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // One ring of light round the link as it clasps.
    if (clasp != null && !reduceMotion) {
        val rp = seg(u, 0.2f, 1.28f)
        if (rp > 0f && rp < 1f) {
            val start = -90f + rp * 396f
            for (i in 0 until 28) {
                val f = i / 27f
                drawArc(
                    Ring.copy(alpha = 0.9f * sin(PI.toFloat() * rp) * sin(PI.toFloat() * f)),
                    startAngle = start - 140f * f - 5.7f, sweepAngle = 5.7f, useCenter = false,
                    topLeft = Offset(128f - 150f, 128f - 150f), size = Size(300f, 300f),
                    style = Stroke(3f, cap = StrokeCap.Round),
                )
            }
        }
    }
}
