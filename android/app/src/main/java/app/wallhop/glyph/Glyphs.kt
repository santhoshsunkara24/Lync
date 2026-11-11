package app.wallhop.glyph

import android.content.Context
import app.wallhop.core.Features

/**
 * Nothing phones' Glyph lights and Glyph Matrix. The device code lives in GlyphDevice, built from
 * src/glyph when Nothing's SDK is present and from src/noglyph (does nothing) when it isn't.
 */
object Glyphs {
    enum class Hardware { Lights, Matrix }
    enum class Effect { Connected, Ringing }

    /** Where a file transfer is, for the Glyphs to follow. */
    sealed interface Transfer {
        /** [fraction] 0 to 1; [outgoing] to the Mac, else from it. */
        data class Moving(val fraction: Float, val outgoing: Boolean = true) : Transfer
        data object Done : Transfer
        /** Failed or cancelled. */
        data object Stopped : Transfer
        data object Idle : Transfer
    }

    /** What this phone has; null on phones without Glyphs, or when the SDK isn't built in. */
    val hardware: Hardware? get() = GlyphDevice.hardware

    fun play(context: Context, effect: Effect) {
        val key = when (effect) {
            Effect.Connected -> Features.Key.GlyphConnect
            Effect.Ringing -> Features.Key.GlyphRing
        }
        if (hardware == null || !Features.on(context, key)) return
        GlyphDevice.play(context.applicationContext, effect)
    }

    /**
     * A transfer's progress. Steps of 1/24 so the Matrix only redraws when a pixel would change;
     * the device keeps its session open while files move and lets go once the transfer ends.
     */
    fun transfer(context: Context, state: Transfer) {
        if (hardware == null || !Features.on(context, Features.Key.GlyphTransfer)) return
        GlyphDevice.transfer(context.applicationContext, state)
    }

    /** Plays the connection effect whatever the switches say, so people can see what it looks like. */
    fun preview(context: Context) {
        if (hardware != null) GlyphDevice.play(context.applicationContext, Effect.Connected)
    }

    fun stop(context: Context) {
        if (hardware != null) GlyphDevice.stop(context.applicationContext)
    }

    /**
     * Lync's link for the Glyph Matrix, drawn pixel by pixel from the app icon: two hooks turned 45°,
     * interlocked, with a pixel of air between them so they still read as two at this size. `A` is the lower-left hook, `B` the upper-right one (the same hook turned half a
     * turn), so the two can move apart and clasp. Anything else is dark.
     */
    internal val link13 = listOf(
        "........BB...",
        ".......BBBB..",
        "..........BB.",
        ".....AA...BB.",
        "....AA...BB..",
        "...AA...BB...",
        "..AA...BB....",
        ".AA...BB.....",
        ".AA..........",
        "..AAAA.......",
        "...AA........",
    )
    internal val link25 = listOf(
        ".................BB......",
        "...............BBBBBB....",
        "..............BBBBBBBB...",
        ".............BBBB.BBBB...",
        ".............BBB...BBB...",
        "...................BBB...",
        "..........A........BBB...",
        ".........AAA......BBBB...",
        "........AAAA.....BBBB....",
        ".......AAAA.....BBBB.....",
        "......AAAA.....BBBB......",
        ".....AAAA.....BBBB.......",
        "....AAAA.....BBBB........",
        "...AAAA......BBB.........",
        "...AAA........B..........",
        "...AAA...................",
        "...AAA...AAA.............",
        "...AAAA.AAAA.............",
        "...AAAAAAAA..............",
        "....AAAAAA...............",
        "......AA.................",
    )

    /**
     * The link as brightness levels (0 to 255) in an [n] x [n] grid, filled from the lower hook's
     * open end towards the upper hook's by [fraction]: what's sent is lit full, what's still to go
     * dim, and the pixels at the edge in between, so the fill moves smoothly.
     */
    internal fun progress(n: Int, fraction: Float, dim: Int = DIM): IntArray = fillLevels(matrix(n), n, fraction, dim)

    /** [lit] pixels filled from the lower left to the upper right by [fraction], dim to full. */
    internal fun fillLevels(lit: BooleanArray, n: Int, fraction: Float, dim: Int = DIM): IntArray {
        // Along the link's own diagonal: lower left (A's open end) to upper right (B's).
        val order = lit.indices.filter { lit[it] }.sortedBy { i -> (i % n) - (i / n) }
        val levels = IntArray(n * n)
        val edge = fraction.coerceIn(0f, 1f) * order.size
        order.forEachIndexed { k, i ->
            val t = (edge - k).coerceIn(0f, 1f)
            levels[i] = (dim + (255 - dim) * t).toInt()
        }
        return levels
    }

    /**
     * The link for Liftoff, smaller than the logo so its exhaust has room: [link13] with each arm
     * two pixels shorter, so it's the same drawing (two-pixel strokes, the same curves) at 9 x 9
     * for the 13 x 13 Matrix (the 13-pixel link itself on the 25 x 25 one).
     * Each hook's short end lines up with the other hook's long arm with a pixel of air between,
     * exactly as in the icon: that gap is what makes the two read as interlocked.
     */
    internal val miniLink = listOf(
        ".....BB..",
        "....BBBB.",
        ".......BB",
        "..AA...BB",
        ".AA...BB.",
        "AA...BB..",
        "AA.......",
        ".AAAA....",
        "..AA.....",
    )

    /**
     * Where the small link sits: a pixel up and to the right of centre, so the exhaust behind it
     * has room. The Matrix is round (137 lights on the 13 x 13 one: those within 6.5 of the
     * centre), and every pixel of the link is on a light.
     */
    internal fun smallLink(n: Int): BooleanArray {
        val art = if (n >= 25) link13 else miniLink
        val ahead = if (n >= 25) 2 else 1
        val grid = BooleanArray(n * n)
        val top = (n - art.size) / 2 - ahead
        val left = (n - art[0].length) / 2 + ahead
        art.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                if (ch == '.') return@forEachIndexed
                val y = top + r; val x = left + c
                if (y in 0 until n && x in 0 until n) grid[y * n + x] = true
            }
        }
        return grid
    }

    /** Whether ([x], [y]) is one of the round Matrix's lights. */
    internal fun onMatrix(n: Int, x: Int, y: Int): Boolean {
        val c = (n - 1) / 2.0
        return kotlin.math.hypot(x - c, y - c) <= n / 2.0
    }

    /**
     * Liftoff in pixels, as the phone and Mac show it while files go across: the small link,
     * filling from dim to bright along its length with [fraction], and its exhaust behind it
     * (down and to the left): a soft glow a few pixels deep, brightest nearest the link, with
     * pulses running out through it, away from the link. The glow never touches the link (a
     * pixel of dark all round), so the link keeps its shape. Everything is worked out from [tMs],
     * the time since the send started, so it moves smoothly and a frame needs no state; [shift]
     * moves the link along its axis (forward is up and right).
     */
    internal fun liftoff(n: Int, fraction: Float, tMs: Long, shift: Int = 0, streaks: Boolean = true): IntArray {
        val link = shifted(fillLevels(smallLink(n), n, fraction, dim = 110), n, shift)
        val out = link.copyOf()
        if (!streaks) return out
        val lit = BooleanArray(n * n) { link[it] > 0 }
        val pixels = lit.indices.filter { lit[it] }
        if (pixels.isEmpty()) return out
        val scale = n / 13f
        val rear = pixels.minOf { (it % n) - (it / n) }
        val lanes = pixels.minOf { (it % n) + (it / n) } - 1..pixels.maxOf { (it % n) + (it / n) } + 1
        fun touches(x: Int, y: Int): Boolean {
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until n && ny in 0 until n && lit[ny * n + nx]) return true
            }
            return false
        }
        val phase = tMs / EXHAUST_PULSE_MS
        for (y in 0 until n) for (x in 0 until n) {
            if (x + y !in lanes || touches(x, y) || !onMatrix(n, x, y)) continue
            // How far behind the link's back end, in pixels along its axis.
            val d = (rear - (x - y)) / 2f / scale
            if (d < 0.5f || d > 4.5f) continue
            val wave = 0.5f + 0.5f * kotlin.math.cos(2 * Math.PI * (d / EXHAUST_WAVE - phase)).toFloat()
            out[y * n + x] = ((EXHAUST_PEAK - 25 * d) * (0.35f + 0.65f * wave)).toInt().coerceIn(0, 255)
        }
        return out
    }

    /** One pulse leaves the link this often, and pulses are this many pixels apart. */
    private const val EXHAUST_PULSE_MS = 520.0
    private const val EXHAUST_WAVE = 2.2f
    /** Below the link's own full brightness, so the link always reads first. */
    private const val EXHAUST_PEAK = 165


    /** [levels] moved [by] pixels along the link's axis: up and right for more, down and left for less. */
    internal fun shifted(levels: IntArray, n: Int, by: Int): IntArray {
        if (by == 0) return levels
        val out = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val v = levels[y * n + x]
            if (v == 0) continue
            val nx = x + by; val ny = y - by
            if (nx in 0 until n && ny in 0 until n) out[ny * n + nx] = v
        }
        return out
    }

    /** How bright the part of the link still to go is. */
    private const val DIM = 45

    /**
     * The link centred in an [n] x [n] grid. [apart] moves the hooks along the diagonal, a pixel per
     * step: 0 is the logo, more pulls them away from each other, -1 squeezes them together.
     */
    internal fun matrix(n: Int, apart: Int = 0): BooleanArray {
        val art = if (n >= 25) link25 else link13
        val grid = BooleanArray(n * n)
        val top = (n - art.size) / 2
        val left = (n - art[0].length) / 2
        art.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                // The lower hook moves down and left, the upper one up and right.
                val shift = when (ch) { 'A' -> apart; 'B' -> -apart; else -> return@forEachIndexed }
                val y = top + r + shift; val x = left + c - shift
                if (y in 0 until n && x in 0 until n) grid[y * n + x] = true
            }
        }
        return grid
    }
}
