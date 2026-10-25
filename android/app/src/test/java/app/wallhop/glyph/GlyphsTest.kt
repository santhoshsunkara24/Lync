package app.wallhop.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphsTest {
    @Test fun artIsRectangularAndTheHooksAreTheSameShape() {
        for (art in listOf(Glyphs.link13, Glyphs.link25)) {
            assertTrue(art.all { it.length == art[0].length })
            // A half turn swaps the two hooks: the upper one is the lower one turned around.
            val turned = art.reversed().map { row -> row.reversed().map { when (it) { 'A' -> 'B'; 'B' -> 'A'; else -> it } }.joinToString("") }
            assertEquals(art, turned)
        }
    }

    @Test fun linkIsCentredAndFitsTheRoundMatrix() {
        for (n in listOf(13, 25)) {
            val grid = Glyphs.matrix(n)
            val c = (n - 1) / 2.0
            for (i in grid.indices) if (grid[i]) {
                val x = i % n - c; val y = i / n - c
                assertTrue("pixel ($x, $y) outside the circle on $n", x * x + y * y <= (n / 2.0) * (n / 2.0))
            }
            val rows = grid.indices.filter { grid[it] }.map { it / n }
            assertTrue("not centred on $n", kotlin.math.abs((n - 1 - rows.max()) - rows.min()) <= 1)
        }
    }

    @Test fun apartMovesTheHooksDiagonallyAway() {
        val n = 13
        val art = Glyphs.link13
        val top = (n - art.size) / 2
        val clasped = Glyphs.matrix(n)
        val open = Glyphs.matrix(n, apart = 1)
        art.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                if (ch == '.') return@forEachIndexed
                assertTrue(clasped[(top + r) * n + c])
                val (y, x) = if (ch == 'A') top + r + 1 to c - 1 else top + r - 1 to c + 1
                if (y in 0 until n && x in 0 until n) assertTrue(open[y * n + x])
            }
        }
    }
}
