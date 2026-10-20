package app.wallhop.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private val seeds = mapOf(
        "yellow" to 0xFFFFD600.toInt(),
        "green" to 0xFF34C759.toInt(),
        "orange" to 0xFFFF9500.toInt(),
        "blue" to 0xFF0A84FF.toInt(),
        "violet" to 0xFF7C4DFF.toInt(),
        "monochrome" to 0xFF808080.toInt(),
    )

    private fun Color.argb() = toArgb()

    @Test fun wcagReferenceValues() {
        assertEquals(21.0, Contrast.ratio(0xFF000000.toInt(), Contrast.WHITE), 0.01)
        assertEquals(1.0, Contrast.ratio(Contrast.WHITE, Contrast.WHITE), 0.001)
        // White on #30D158 (the old share "Done" bar) is about 2:1.
        assertTrue(Contrast.whiteOn(0xFF30D158.toInt()) < 2.2)
    }

    @Test fun hslRoundTrips() {
        val c = Contrast.hslToArgb(210f, 0.6f, 0.4f)
        val hsl = Contrast.argbToHsl(c)
        assertEquals(210f, hsl[0], 1.5f)
        assertEquals(0.6f, hsl[1], 0.02f)
        assertEquals(0.4f, hsl[2], 0.01f)
    }

    @Test fun whiteTextReadsOnEveryWallpaperColour() {
        for ((name, seed) in seeds) {
            for (accent in listOf(null, 0xFFFFEB3B.toInt())) {
                val home = HomeColors.from(seed, accent)
                for ((part, color) in listOf("top" to home.top, "mid" to home.mid, "peek" to home.peek, "card" to home.card)) {
                    for (alpha in listOf(1f, 0.78f)) {
                        val ratio = Contrast.whiteOn(color.argb(), alpha)
                        assertTrue("$name/$accent $part white@$alpha = $ratio", ratio >= Contrast.AA)
                    }
                }
            }
        }
    }

    @Test fun blueKeepsItsBrightnessYellowGetsDarker() {
        val blue = Contrast.argbToHsl(HomeColors.from(seeds.getValue("blue"), null).top.argb())[2]
        val yellow = Contrast.argbToHsl(HomeColors.from(seeds.getValue("yellow"), null).top.argb())[2]
        assertTrue("yellow $yellow should be darker than blue $blue", yellow < blue)
    }

    @Test fun destructiveRedReadsOnCardsAndLists() {
        for ((name, seed) in seeds) {
            val home = HomeColors.from(seed, null)
            val card = home.card.argb()
            val red = home.red.argb()
            assertTrue("$name red on card", Contrast.ratio(red, card) >= Contrast.AA)
            assertTrue("$name red on list over card", Contrast.ratio(red, Contrast.blend(Contrast.WHITE, 0.07f, card)) >= Contrast.AA)
            assertTrue("$name red on list over mid", Contrast.ratio(red, Contrast.blend(Contrast.WHITE, 0.07f, home.mid.argb())) >= Contrast.AA)
        }
    }

    @Test fun placeholdersRead() {
        // Placeholders are white at 68 % in a white-10 % field on a sheet (the card colour).
        for ((name, seed) in seeds) {
            val field = Contrast.blend(Contrast.WHITE, 0.1f, HomeColors.from(seed, null).card.argb())
            assertTrue(name, Contrast.whiteOn(field, 0.68f) >= Contrast.AA)
        }
    }

    @Test fun codeCardBrandColours() {
        val forest = HomeColors.CodeForest.argb()
        val mint = HomeColors.CodeMint.argb()
        assertTrue("mint digits on forest", Contrast.ratio(mint, forest) >= Contrast.AA)
        assertTrue("secondary text on forest", Contrast.whiteOn(forest, HomeColors.CodeSecondaryAlpha) >= Contrast.AA)
        assertTrue("forest label on the mint Copy button", Contrast.ratio(forest, mint) >= Contrast.AA)
    }

    @Test fun shareDoneBarInkOnGreen() {
        assertTrue(Contrast.ratio(Ink.argb(), 0xFF30D158.toInt()) >= Contrast.AA)
    }
}
