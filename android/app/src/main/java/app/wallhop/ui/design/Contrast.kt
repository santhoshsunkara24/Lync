package app.wallhop.ui.design

import kotlin.math.abs
import kotlin.math.pow

/**
 * WCAG contrast maths, in plain Kotlin so it runs in unit tests. Colours are ARGB ints.
 * Used to keep white text readable on colours that come from the wallpaper (DESIGN.md §3.3).
 */
object Contrast {
    /** WCAG AA for normal text. */
    const val AA = 4.5

    /** HSL (h 0–360, s and l 0–1) to an opaque ARGB int. Same maths as ColorUtils.HSLToColor. */
    fun hslToArgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val m = l - 0.5f * c
        val x = c * (1f - abs((h / 60f % 2f) - 1f))
        val (r, g, b) = when (((h % 360f + 360f) % 360f / 60f).toInt()) {
            0 -> Triple(c + m, x + m, m)
            1 -> Triple(x + m, c + m, m)
            2 -> Triple(m, c + m, x + m)
            3 -> Triple(m, x + m, c + m)
            4 -> Triple(x + m, m, c + m)
            else -> Triple(c + m, m, x + m)
        }
        fun channel(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
        return argb(255, channel(r), channel(g), channel(b))
    }

    /** ARGB to HSL (h 0–360, s and l 0–1). */
    fun argbToHsl(color: Int): FloatArray {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val l = (max + min) / 2f
        if (delta == 0f) return floatArrayOf(0f, 0f, l)
        val s = delta / (1f - abs(2f * l - 1f))
        val h = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } * 60f
        return floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    /** Relative luminance (WCAG 2.x). */
    fun luminance(color: Int): Double {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(red(color)) + 0.7152 * lin(green(color)) + 0.0722 * lin(blue(color))
    }

    /** Contrast ratio between two opaque colours, 1–21. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [fg] drawn at [alpha] over the opaque [bg]. */
    fun blend(fg: Int, alpha: Float, bg: Int): Int {
        fun mix(f: Int, b: Int) = (f * alpha + b * (1f - alpha)).toInt().coerceIn(0, 255)
        return argb(255, mix(red(fg), red(bg)), mix(green(fg), green(bg)), mix(blue(fg), blue(bg)))
    }

    /** Contrast of white at [alpha] on [bg]. */
    fun whiteOn(bg: Int, alpha: Float = 1f): Double = ratio(blend(WHITE, alpha, bg), bg)

    /**
     * The lightest lightness at or below [start] where white text at every alpha in [alphas]
     * reaches [min] on hsl(h, s, l). Steps down 1 % at a time.
     */
    fun lightnessForWhite(h: Float, s: Float, start: Float, alphas: FloatArray = floatArrayOf(1f, 0.78f), min: Double = AA): Float {
        var l = start
        while (l > 0.02f) {
            val bg = hslToArgb(h, s, l)
            if (alphas.all { whiteOn(bg, it) >= min }) return l
            l -= 0.01f
        }
        return 0.02f
    }

    /** [color] mixed toward white until it reaches [min] against every background. */
    fun lightenUntil(color: Int, backgrounds: List<Int>, min: Double = AA): Int {
        var t = 0f
        var c = color
        while (t <= 1f) {
            c = blend(WHITE, t, color)
            if (backgrounds.all { ratio(c, it) >= min }) return c
            t += 0.02f
        }
        return c
    }

    const val WHITE = -0x1 // 0xFFFFFFFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
}
