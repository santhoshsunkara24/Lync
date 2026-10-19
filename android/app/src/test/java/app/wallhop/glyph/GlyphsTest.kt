package app.wallhop.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphsTest {
    @Test fun artIsRectangularAndSymmetric() {
        for (art in listOf(Glyphs.droid13, Glyphs.droid25)) {
            assertTrue(art.all { it.length == art[0].length })
            assertTrue(art.all { it == it.reversed() })
        }
    }

    @Test fun droidIsCentredAndFitsTheRoundMatrix() {
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

    @Test fun blinkFillsOnlyTheEyes() {
        assertEquals(2, Glyphs.matrix(13, blink = true).count { it } - Glyphs.matrix(13).count { it })
        assertEquals(8, Glyphs.matrix(25, blink = true).count { it } - Glyphs.matrix(25).count { it })
    }

    @Test fun bobMovesDownOneRow() {
        val n = 13
        val up = Glyphs.matrix(n); val down = Glyphs.matrix(n, dy = 1)
        for (i in 0 until n * (n - 1)) assertEquals(up[i], down[i + n])
    }
}
