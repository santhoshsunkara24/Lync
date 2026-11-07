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

    @Test fun liftoffKeepsTheLinkAndAddsSpeedLinesBehindIt() {
        for (n in listOf(13, 25)) {
            val link = Glyphs.smallLink(n)
            val frame = Glyphs.liftoff(n, 0.5f, 600)
            // Every pixel of the link is lit (dim or bright)…
            assertTrue(link.indices.filter { link[it] }.all { frame[it] > 0 })
            // …and some speed lines are lit outside it, all behind it (down and to the left).
            val extra = frame.indices.filter { frame[it] > 0 && !link[it] }
            assertTrue(extra.isNotEmpty())
            val c = n - 1
            // Behind the link: further down and left than its centre line.
            val linkMid = link.indices.filter { link[it] }.map { (it % n) - (it / n) }.average()
            assertTrue(extra.all { i -> (i % n) - (i / n) < linkMid + 1 })
            assertTrue(c > 0)
        }
    }

    @Test fun liftoffShiftMovesTheLinkForward() {
        val n = 13
        val base = Glyphs.liftoff(n, 1f, 0, shift = 0, streaks = false)
        val ahead = Glyphs.liftoff(n, 1f, 0, shift = 1, streaks = false)
        for (i in base.indices) if (base[i] > 0) {
            val x = i % n + 1; val y = i / n - 1
            if (x in 0 until n && y in 0 until n) assertEquals(base[i], ahead[y * n + x])
        }
        // Shifted right out of sight, nothing is left.
        assertTrue(Glyphs.liftoff(n, 1f, 0, shift = n, streaks = false).all { it == 0 })
    }

    @Test fun smallLinkLeavesRoomBehind() {
        val n = 13
        val link = Glyphs.smallLink(n)
        assertTrue(link.count { it } in 12..20)
        // The lower-left corner (where the trail goes) is clear.
        for (y in n - 4 until n) for (x in 0 until 3) assertTrue(!link[y * n + x])
    }

    @Test fun exhaustGlidesAndNeverTouchesTheLink() {
        val n = 13
        val link = Glyphs.smallLink(n)
        fun exhaust(t: Long) = Glyphs.liftoff(n, 1f, t).let { f -> IntArray(f.size) { if (link[it]) 0 else f[it] } }
        // Smooth: from one frame to the next (40 ms) no light jumps by more than a quarter.
        for (t in 0L..2000L step 40) {
            val a = exhaust(t); val b = exhaust(t + 40)
            for (i in a.indices) assertTrue(kotlin.math.abs(a[i] - b[i]) < 64)
        }
        // A pixel of dark all round the link.
        val f = exhaust(300)
        for (i in f.indices) if (f[i] > 0) {
            val x = i % n; val y = i / n
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until n && ny in 0 until n) assertTrue(!link[ny * n + nx])
            }
        }
    }

    @Test fun smallLinkStaysInsideTheRoundMatrix() {
        for (n in listOf(13, 25)) {
            val link = Glyphs.smallLink(n)
            val c = (n - 1) / 2.0
            val r = n / 2.0 // the round display's lights: 137 of them on the 13 x 13 Matrix
            for (i in link.indices) if (link[i]) {
                val dx = i % n - c; val dy = i / n - c
                assertTrue("pixel ${i % n},${i / n} on $n", kotlin.math.sqrt(dx * dx + dy * dy) <= r)
            }
        }
    }
}
