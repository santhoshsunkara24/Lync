package app.wallhop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import app.wallhop.ui.design.Wallhop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Liftoff: Lync's link taking off while files go across, shared with the Mac.
 * Waiting it hovers. When the send starts it crouches, then hovers on its thrust while speed lines
 * stream back past it. When the send lands it blasts off along its own axis and drops back in,
 * with a glint running along the tube. The link is the app icon's: two hooks turned 45°, in a
 * 256 box, so "forward" is up and to the right.
 */
private val LinkA = PathParser().parsePathString("M 120.22 91.94 L 62.24 149.92 A 31 31 0 0 0 106.08 193.76 L 116.69 183.15").toPath()
private val LinkB = PathParser().parsePathString("M 135.78 164.06 L 193.76 106.08 A 31 31 0 0 0 149.92 62.24 L 139.31 72.85").toPath()
private const val R2 = 0.70710677f
/** Forward, along the link: up and to the right. */
private val Ux = R2
private val Uy = -R2
/** Across it. */
private val Px = R2
private val Py = R2

private const val LaunchMs = 260f
private const val DoneMs = 1150f

private enum class Stage { Idle, Launch, Send, Done }

private class Streak(var off: Float = 0f, var d: Float = 0f, var len: Float = 0f, var w: Float = 0f, var v: Float = 0f, var life: Float = 0f)

/**
 * The animation's state between frames. Speed lines are recycled through [spare] and the glow's
 * brush is kept per size, so a long send draws every frame without making garbage (no GC pauses).
 */
private class Engine {
    var stage = Stage.Idle
    var tp = 0f
    var vt = 0f
    var acc = 0f
    /** The last frame's length, so the speed lines move at real speed whatever the refresh rate. */
    var dt = 16f
    val lines = ArrayList<Streak>(64)
    val spare = ArrayList<Streak>(64)
    var glow: Brush? = null
    var glowRadius = -1f

    fun streak(): Streak = if (spare.isEmpty()) Streak() else spare.removeAt(spare.lastIndex)

    fun glowBrush(r: Float): Brush {
        if (r != glowRadius || glow == null) {
            glow = Brush.radialGradient(listOf(Mint, Color.Transparent), Offset(r, r), r)
            glowRadius = r
        }
        return glow!!
    }
}

private val Mint = Color(0xFF5FD9A0)
private val StreakColor = Color(0xFFE2ECE7)
private val EdgeA = Color(0xFF9FE6C3)
private val EdgeB = Color(0xFF7FD5AE)
private val TubeA = Brush.verticalGradient(listOf(Color.White, Color(0xFFE8FFF4), Color(0xFFB9EED3)), startY = 60f, endY = 196f)
private val TubeB = Brush.verticalGradient(listOf(Color(0xFFD6FBE9), Color(0xFF8FDDB8)), startY = 60f, endY = 196f)
private val EdgeStroke = Stroke(19f, cap = StrokeCap.Round)
private val TubeStroke = Stroke(16f, cap = StrokeCap.Round)
private val HighlightStroke = Stroke(3f, cap = StrokeCap.Round)

private fun clamp(v: Float) = v.coerceIn(0f, 1f)
private fun easeOut(t: Float) = 1f - (1f - clamp(t)).pow(3)
private fun easeIn(t: Float) = clamp(t).pow(2.4f)
/** Apple-style spring (duration + bounce), as on the Mac. */
private fun spring(tSec: Float, dur: Float, bounce: Float): Float {
    if (tSec <= 0f) return 0f
    val z = 1f - bounce
    val w = 2f * PI.toFloat() / dur
    val wd = w * sqrt(1f - z * z)
    return 1f - exp(-z * w * tSec) * (cos(wd * tSec) + (z * w / wd) * sin(wd * tSec))
}

/**
 * The link taking off. [sending] while a send is under way; [done] once it has landed (the finale
 * plays once). Neither: it hovers, waiting. Draws in whatever size it's given.
 */
@Composable
fun Liftoff(sending: Boolean, done: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val view = LocalView.current
    val engine = remember { Engine() }
    val currentSending by rememberUpdatedState(sending)
    val currentDone by rememberUpdatedState(done)
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000f).coerceAtMost(50f)
            last = now
            engine.tp += dt; engine.vt += dt; engine.dt = dt
            when {
                currentDone && (engine.stage == Stage.Launch || engine.stage == Stage.Send) -> { engine.stage = Stage.Done; engine.tp = 0f }
                currentSending && !currentDone && engine.stage == Stage.Idle -> {
                    engine.stage = Stage.Launch; engine.tp = 0f
                    LinkHaptics.windUp(view)
                }
                engine.stage == Stage.Launch && engine.tp >= LaunchMs -> { engine.stage = Stage.Send; engine.tp = 0f }
                engine.stage == Stage.Done && engine.tp >= DoneMs -> { engine.stage = Stage.Idle; engine.tp = 0f }
            }
            frame.longValue = now
        }
    }

    Canvas(modifier) {
        frame.longValue // Redraw every frame while it animates.
        if (reduceMotion) {
            drawLinkTube(size.width / 2, size.height / 2, size.minDimension * 0.5f)
            return@Canvas
        }
        drawLiftoff(engine)
    }
}

private fun DrawScope.drawLiftoff(e: Engine) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    val linkSize = minOf(w, h) * 0.5f
    val unit = minOf(w, h)
    var back = 0f
    var stretch = 1f
    var alpha = 1f
    var glint = -1f
    var hover = 0f
    var rate = 0f
    var speed = unit * 2.4f
    val dt = e.dt
    when (e.stage) {
        Stage.Idle -> hover = sin(e.vt / 900f) * 2f
        Stage.Launch -> { val t = e.tp / LaunchMs; back = -10f * easeOut(t); stretch = 1f - 0.07f * easeOut(t) }
        Stage.Send -> {
            back = -10f + 16f * easeOut(e.tp / 220f); stretch = 1.03f
            hover = sin(e.vt / 70f) * 0.9f
            rate = 26f; speed = unit * (2.2f + 1.2f * (0.5f + 0.5f * sin(e.vt / 900f)))
        }
        Stage.Done -> {
            val t = e.tp
            if (t < 420f) {
                val k = easeIn(t / 420f); back = 6f + k * unit * 1.2f; stretch = 1f + 0.4f * k; rate = 40f; speed = unit * 4f
            } else {
                val r = spring((t - 420f) / 1000f, 0.55f, 0.32f)
                back = -unit * 0.55f * (1f - r); alpha = clamp((t - 420f) / 160f); glint = (t - 560f) / 520f
            }
        }
    }
    // Speed lines, emitted just behind the link and streaming back.
    e.acc += rate * dt / 1000f
    while (e.acc >= 1f) {
        e.acc -= 1f
        val l = e.streak()
        l.off = (Random.nextFloat() - 0.5f) * linkSize * 0.9f; l.d = linkSize * 0.42f
        l.len = linkSize * (0.18f + Random.nextFloat() * 0.22f); l.w = linkSize * (0.032f + Random.nextFloat() * 0.02f)
        l.v = speed * (0.8f + Random.nextFloat() * 0.4f); l.life = 1f
        e.lines += l
    }
    // Move them on, recycling the ones that have faded (in place: no iterator, no garbage).
    var kept = 0
    for (i in e.lines.indices) {
        val l = e.lines[i]
        l.d += l.v * dt / 1000f; l.life -= dt / 520f
        if (l.life > 0f) e.lines[kept++] = l else e.spare += l
    }
    while (e.lines.size > kept) e.lines.removeAt(e.lines.lastIndex)
    val bx = cx + Ux * back + Px * hover
    val by = cy + Uy * back + Py * hover
    for (i in e.lines.indices) {
        val l = e.lines[i]
        val x = bx - Ux * l.d + Px * l.off
        val y = by - Uy * l.d + Py * l.off
        drawLine(
            StreakColor, Offset(x, y), Offset(x - Ux * l.len, y - Uy * l.len), strokeWidth = l.w, cap = StrokeCap.Round,
            alpha = 0.85f * minOf(1f, l.life * 1.6f),
        )
    }
    drawLinkTube(bx, by, linkSize, stretch, alpha, glint, glow = if (e.stage == Stage.Send) 0.9f else 0.6f, glowBrush = e.glowBrush(linkSize * 0.75f))
}

/** The link as a lit mint tube, like the app icon, centred at ([x], [y]) and [s] px tall. */
private fun DrawScope.drawLinkTube(
    x: Float, y: Float, s: Float, stretch: Float = 1f, alpha: Float = 1f, glint: Float = -1f, glow: Float = 0.6f, glowBrush: Brush? = null,
) {
    if (alpha <= 0.002f) return
    // A soft mint glow under it (the brush is centred on its own box, so it can be reused).
    val r = s * 0.75f
    val brush = glowBrush ?: Brush.radialGradient(listOf(Mint, Color.Transparent), Offset(r, r), r)
    translate(x - r, y - r) { drawCircle(brush, radius = r, center = Offset(r, r), alpha = 0.32f * glow * alpha) }
    val k = s / 256f
    withTransform({
        translate(x, y)
        if (stretch != 1f) {
            rotate(-45f, Offset.Zero)
            scale(stretch, 1f / sqrt(stretch), Offset.Zero)
            rotate(45f, Offset.Zero)
        }
        scale(k, k, Offset.Zero)
        translate(-128f, -128f)
    }) {
        tubeHook(LinkA, EdgeA, TubeA, 0.9f, alpha, glint)
        tubeHook(LinkB, EdgeB, TubeB, 0.6f, alpha, glint)
    }
}

private fun DrawScope.tubeHook(path: Path, edge: Color, tube: Brush, highlight: Float, alpha: Float, glint: Float) {
    drawPath(path, edge, alpha = alpha, style = EdgeStroke)
    translate(-1f, -1f) {
        drawPath(path, tube, alpha = alpha, style = TubeStroke)
    }
    translate(-3f, -3f) {
        drawPath(path, Color.White, alpha = highlight * alpha, style = HighlightStroke)
    }
    if (glint > 0f && glint < 1f) {
        drawPath(
            path, Color.White.copy(alpha = 0.95f * sin(glint * PI.toFloat()) * alpha),
            style = Stroke(7f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 600f), 24f - glint * 230f)),
        )
    }
}
