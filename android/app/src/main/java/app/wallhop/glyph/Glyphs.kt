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
     * The Lync droid for the Glyph Matrix, drawn pixel by pixel from the logo (brand/kit): the bar,
     * the head hanging from it, two eyes and the antennae leaning out. `#` is lit.
     */
    internal val droid13 = listOf(
        "###########",
        ".#########.",
        ".##.###.##.",
        "..#######..",
        "...#####...",
        "...#...#...",
        "..#.....#..",
    )
    internal val droid25 = listOf(
        ".#####################.",
        "#######################",
        "...#################...",
        "...#################...",
        "...####..#####..####...",
        "....###..#####..###....",
        ".....#############.....",
        "......###########......",
        ".......#########.......",
        ".......#.......#.......",
        "......#.........#......",
        "......#.........#......",
    )

    /** [art] centred in an [n] x [n] grid, moved down by [dy] rows; [blink] fills the eyes. */
    internal fun matrix(n: Int, dy: Int = 0, blink: Boolean = false): BooleanArray {
        val art = if (n >= 25) droid25 else droid13
        val grid = BooleanArray(n * n)
        val top = (n - art.size) / 2 + dy
        val left = (n - art[0].length) / 2
        val eyeRows = if (n >= 25) 4..5 else 2..2
        art.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                val lit = ch == '#' || (blink && r in eyeRows && ch == '.' &&
                    line.substring(0, c).contains('#') && line.substring(c).contains('#'))
                val y = top + r; val x = left + c
                if (lit && y in 0 until n && x in 0 until n) grid[y * n + x] = true
            }
        }
        return grid
    }
}
