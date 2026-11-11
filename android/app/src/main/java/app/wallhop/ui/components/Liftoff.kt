package app.wallhop.ui.components

import kotlin.math.ceil

import androidx.compose.ui.geometry.Size

import androidx.compose.ui.graphics.drawscope.CanvasDrawScope

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

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
import app.wallhop.ui.design.LinkColors
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
 * Waiting it hovers. When the send starts it crouches, then flies on its thrust while speed lines
 * stream back past it, for as long as the send lasts. When the send lands it blasts off along its
 * own axis and drops back in, with a glint running along the tube. If it fails, the link jolts as
 * if it hit something and its two hooks come apart, dimmed, until it's tried again; cancelled, the
 * thrust cuts out and it sinks back the way it came, loosening as it fades.
 *
 * Kept cheap enough to run for minutes: the tube (the costly part, gradient strokes along curves)
 * is drawn once into a bitmap and reused every frame, and frames are capped at 60 a second. The link is the app icon's: two hooks turned 45°, in a
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

/**
 * Speed lines run in five fixed lanes across the link, taken in this order, so two never sit side
 * by side or cross: clean, even streaks rather than a scatter. Shared with the Mac.
 */
private val Lanes = floatArrayOf(0f, -0.34f, 0.17f, -0.17f, 0.34f)
/** How far behind the link a speed line goes before it has faded out, in link heights: inside the glow. */
private const val Reach = 1.1f

private const val LaunchMs = 260f
private const val DoneMs = 1150f
/** At most this often a new frame: plenty for the motion, half the work on a 120 Hz screen. */
private const val FrameMs = 16

private enum class Stage { Idle, Launch, Send, Done, Fail, Cancel }

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
    /** The lane the next speed line takes (see [Lanes]). */
    var nextLane = 0
    var glow: Brush? = null
    var glowRadius = -1f
    /**
     * Drawn as a loop of still frames ([LiftoffFrames]): the hover then takes exactly four swings
     * per loop, so the last frame runs into the first without a jump. 0 on screen.
     */
    var loopMs = 0f
    /** The colours it draws with: the wallpaper's, from [Liftoff]'s caller. */
    var colors: LinkColors = LinkColors.Brand
    private var glowColors: LinkColors? = null

    fun streak(): Streak = if (spare.isEmpty()) Streak() else spare.removeAt(spare.lastIndex)

    fun glowBrush(r: Float): Brush {
        if (r != glowRadius || glow == null || glowColors != colors) {
            glow = Brush.radialGradient(listOf(colors.glow, Color.Transparent), Offset(r, r), r)
            glowRadius = r
            glowColors = colors
        }
        return glow!!
    }

    private var tubeImages: Pair<ImageBitmap, ImageBitmap>? = null
    private var tubeSide = -1
    private var tubeColors: LinkColors? = null

    /**
     * The two hooks, each drawn once for its size into a bitmap that every frame then simply
     * places (apart, when the link comes undone).
     */
    fun tube(scope: DrawScope, s: Float): Pair<ImageBitmap, ImageBitmap> {
        val side = ceil(s * 1.1f).toInt().coerceAtLeast(1)
        tubeImages?.takeIf { tubeSide == side && tubeColors == colors }?.let { return it }
        fun hook(path: Path, edge: Color, tube: Brush, highlight: Float): ImageBitmap {
            val image = ImageBitmap(side, side)
            CanvasDrawScope().draw(scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(image), Size(side.toFloat(), side.toFloat())) {
                val k = s / 256f
                withTransform({
                    translate(side / 2f, side / 2f)
                    scale(k, k, Offset.Zero)
                    translate(-128f, -128f)
                }) { tubeHook(path, edge, tube, highlight, 1f, -1f) }
            }
            return image
        }
        val images = hook(LinkA, colors.edgeA, colors.tubeBrushA(), 0.9f) to hook(LinkB, colors.edgeB, colors.tubeBrushB(), 0.6f)
        tubeImages = images
        tubeSide = side
        tubeColors = colors
        return images
    }
}

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
fun Liftoff(
    sending: Boolean,
    done: Boolean,
    modifier: Modifier = Modifier,
    /** The send failed: the link comes undone until it's tried again. */
    failed: Boolean = false,
    /** Cancel was pressed: the link powers down (the screen closes once it has). */
    cancelled: Boolean = false,
    /** Its colours: the wallpaper's, like the rest of Lync. */
    colors: LinkColors = LinkColors.from(Wallhop.home),
) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val view = LocalView.current
    val engine = remember { Engine() }
    engine.colors = colors
    val currentSending by rememberUpdatedState(sending)
    val currentDone by rememberUpdatedState(done)
    val currentFailed by rememberUpdatedState(failed)
    val currentCancelled by rememberUpdatedState(cancelled)
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Skip frames that come sooner than FrameMs (a 120 Hz screen): time still moves on
            // by the real gap, so the motion's speed is the same.
            if ((now - last) / 1_000_000 < FrameMs - 2) continue
            val dt = ((now - last) / 1_000_000f).coerceAtMost(50f)
            last = now
            engine.tp += dt; engine.vt += dt; engine.dt = dt
            when {
                currentCancelled && engine.stage != Stage.Cancel -> { engine.stage = Stage.Cancel; engine.tp = 0f }
                currentCancelled -> Unit
                currentFailed && engine.stage != Stage.Fail -> { engine.stage = Stage.Fail; engine.tp = 0f }
                // Tried again: the hooks clasp back together and it lifts off as before.
                !currentFailed && engine.stage == Stage.Fail -> { engine.stage = Stage.Idle; engine.tp = 0f }
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
            drawLinkTube(size.width / 2, size.height / 2, size.minDimension * 0.5f, colors = colors)
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
    // How far the two hooks have come apart along the link's axis, in px.
    var apart = 0f
    var glow = 0.6f
    val dt = e.dt
    when (e.stage) {
        Stage.Idle -> hover = sin(e.vt / 900f) * 2f
        Stage.Launch -> { val t = e.tp / LaunchMs; back = -10f * easeOut(t); stretch = 1f - 0.07f * easeOut(t) }
        Stage.Send -> {
            back = -10f + 16f * easeOut(e.tp / 220f); stretch = 1.03f
            hover = sin(if (e.loopMs > 0f) e.vt * (2f * PI.toFloat() * 4f / e.loopMs) else e.vt / 70f) * 0.9f
            rate = 9f; speed = linkSize * 2.2f
            glow = 0.9f
        }
        Stage.Fail -> {
            val t = e.tp
            // A jolt, as if it hit something: a quick shudder across the link that dies away…
            hover = sin(t / 26f) * 7f * exp(-t / 160f) + sin(e.vt / 900f) * 2f * clamp(t / 600f)
            back = -8f * easeOut(t / 90f) * exp(-t / 260f)
            // …then the hooks part and the light goes down, and it stays that way.
            apart = linkSize * 0.07f * easeOut((t - 90f) / 420f)
            glow = 0.6f - 0.4f * clamp(t / 400f)
        }
        Stage.Cancel -> {
            val t = e.tp
            // The thrust cuts out: it sinks back the way it came, loosening as it fades.
            val k = easeIn(t / 420f)
            back = -unit * 0.16f * k
            apart = linkSize * 0.05f * easeOut(t / 300f)
            alpha = 1f - clamp((t - 120f) / 320f)
            glow = 0.6f * (1f - k)
        }
        Stage.Done -> {
            val t = e.tp
            if (t < 420f) {
                val k = easeIn(t / 420f); back = 6f + k * unit * 1.2f; stretch = 1f + 0.4f * k; rate = 18f; speed = linkSize * 3.6f
            } else {
                val r = spring((t - 420f) / 1000f, 0.55f, 0.32f)
                back = -unit * 0.55f * (1f - r); alpha = clamp((t - 420f) / 160f); glint = (t - 560f) / 520f
            }
        }
    }
    // Speed lines, emitted just behind the link and streaming back, one lane after another, all
    // the same width and speed so none overtakes or crosses another.
    e.acc += rate * dt / 1000f
    while (e.acc >= 1f) {
        e.acc -= 1f
        val l = e.streak()
        l.off = Lanes[e.nextLane % Lanes.size] * linkSize; l.d = linkSize * 0.42f
        e.nextLane++
        l.len = linkSize * (0.22f + 0.05f * (e.nextLane % 3)); l.w = linkSize * 0.032f
        l.v = speed; l.life = 1f
        e.lines += l
    }
    // Move them on, recycling the ones that have reached the edge of the glow (in place: no
    // iterator, no garbage).
    var kept = 0
    for (i in e.lines.indices) {
        val l = e.lines[i]
        l.d += l.v * dt / 1000f
        l.life = minOf(l.life, 1f - (l.d - linkSize * 0.42f) / (linkSize * Reach))
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
            e.colors.streak, Offset(x, y), Offset(x - Ux * l.len, y - Uy * l.len), strokeWidth = l.w, cap = StrokeCap.Round,
            // In quickly, then fading the further it gets from the link.
            alpha = 0.7f * minOf(1f, (1f - l.life) * 8f) * l.life,
        )
    }
    drawLinkTube(bx, by, linkSize, stretch, alpha, glint, glow = glow,
        glowBrush = e.glowBrush(linkSize * 0.75f), cached = e.tube(this, linkSize), apart = apart, colors = e.colors)
}

/** The link as a lit tube in the wallpaper's colour, like the app icon, centred at ([x], [y]) and [s] px tall. */
private fun DrawScope.drawLinkTube(
    x: Float, y: Float, s: Float, stretch: Float = 1f, alpha: Float = 1f, glint: Float = -1f, glow: Float = 0.6f, glowBrush: Brush? = null,
    /** The two hooks already drawn ([Engine.tube]): placed rather than drawn again. */
    cached: Pair<ImageBitmap, ImageBitmap>? = null,
    /** The hooks pulled apart along the link by this many px (undone, failed). */
    apart: Float = 0f,
    colors: LinkColors = LinkColors.Brand,
) {
    if (alpha <= 0.002f) return
    // A soft glow under it (the brush is centred on its own box, so it can be reused).
    val r = s * 0.75f
    val brush = glowBrush ?: Brush.radialGradient(listOf(colors.glow, Color.Transparent), Offset(r, r), r)
    translate(x - r, y - r) { drawCircle(brush, radius = r, center = Offset(r, r), alpha = 0.32f * glow * alpha) }
    val k = s / 256f
    withTransform({
        translate(x, y)
        if (stretch != 1f) {
            rotate(-45f, Offset.Zero)
            scale(stretch, 1f / sqrt(stretch), Offset.Zero)
            rotate(45f, Offset.Zero)
        }
        if (cached == null) {
            scale(k, k, Offset.Zero)
            translate(-128f, -128f)
        }
    }) {
        if (cached != null) {
            val (hookA, hookB) = cached
            // The lower hook falls back, the upper one forward: the link opening along its length.
            drawImage(hookA, Offset(-hookA.width / 2f - Ux * apart, -hookA.height / 2f - Uy * apart), alpha = alpha)
            drawImage(hookB, Offset(-hookB.width / 2f + Ux * apart, -hookB.height / 2f + Uy * apart), alpha = alpha)
            // Only the landing's glint is drawn live, for the moment it runs.
            if (glint > 0f && glint < 1f) withTransform({ scale(k, k, Offset.Zero); translate(-128f, -128f) }) {
                glintHook(LinkA, alpha, glint)
                glintHook(LinkB, alpha, glint)
            }
        } else {
            tubeHook(LinkA, colors.edgeA, colors.tubeBrushA(), 0.9f, alpha, glint)
            tubeHook(LinkB, colors.edgeB, colors.tubeBrushB(), 0.6f, alpha, glint)
        }
    }
}

private fun DrawScope.glintHook(path: Path, alpha: Float, glint: Float) {
    drawPath(
        path, Color.White.copy(alpha = 0.95f * sin(glint * PI.toFloat()) * alpha),
        style = Stroke(7f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 600f), 24f - glint * 230f)),
    )
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

/**
 * Liftoff as still pictures, for the home screen widget, which can only flip between pictures.
 * Drawn by the same code as the app's screen, in the same colours. While sending, the speed lines
 * repeat every 15 lines (five lanes, three lengths, 9 a second), so [sending] is one exact cycle
 * and plays as a seamless loop.
 */
object LiftoffFrames {
    /** One full cycle of the speed lines while sending, in ms. */
    const val LOOP_MS = 15_000f / 9f

    /** [count] frames of the send loop, [sidePx] square; show each for [LOOP_MS] / [count]. */
    fun sending(sidePx: Int, colors: LinkColors, count: Int): List<android.graphics.Bitmap> {
        val e = Engine().apply { this.colors = colors; stage = Stage.Send; tp = 1_000f; loopMs = LOOP_MS }
        val step = LOOP_MS / count
        // Warm up for a whole loop first, so the trail is already full in the first frame.
        repeat(count) { draw(e, sidePx, step) }
        return List(count) { draw(e, sidePx, step) }
    }

    /** One still: landed (the link at rest), or failed (its hooks apart, dimmed). */
    fun still(sidePx: Int, colors: LinkColors, failed: Boolean): android.graphics.Bitmap {
        val e = Engine().apply { this.colors = colors; stage = if (failed) Stage.Fail else Stage.Idle; tp = 1_500f }
        return draw(e, sidePx, 0f)
    }

    private fun draw(e: Engine, sidePx: Int, stepMs: Float): android.graphics.Bitmap {
        e.dt = stepMs; e.tp += stepMs; e.vt += stepMs
        val image = ImageBitmap(sidePx, sidePx)
        CanvasDrawScope().draw(
            androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.graphics.Canvas(image), Size(sidePx.toFloat(), sidePx.toFloat()),
        ) { drawLiftoff(e) }
        return image.asAndroidBitmap()
    }
}
