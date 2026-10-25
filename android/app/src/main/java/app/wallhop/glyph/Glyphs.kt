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
