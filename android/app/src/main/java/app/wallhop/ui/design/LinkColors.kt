package app.wallhop.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The send screen's colours and Liftoff's (the lit link), in the wallpaper's hue like the rest of
 * Lync. Lync's own mint when there are no wallpaper colours ([HomeColors.Default]); grey for a
 * monochrome palette. The Mac draws Liftoff with the same numbers (LiftoffView.swift, LinkPalette).
 * Failures don't use these: they stay the app's fixed red and white, whatever the wallpaper.
 */
@Immutable
data class LinkColors(
    /** The soft glow under the link, and the laptop beside "to your Mac". */
    val glow: Color,
    val streak: Color,
    val edgeA: Color,
    val edgeB: Color,
    /** The two hooks' lit tubes, top to bottom. */
    val tubeA: List<Color>,
    val tubeB: List<Color>,
    /** The send screen: its gradient, a deeper glow, and its text. */
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val deepGlow: Color,
    val ink: Color,
    val soft: Color,
    val percent: Color,
    val onDark: Color,
) {
    fun tubeBrushA() = Brush.verticalGradient(tubeA, startY = 60f, endY = 196f)
    fun tubeBrushB() = Brush.verticalGradient(tubeB, startY = 60f, endY = 196f)

    companion object {
        /** Lync's own mint: exactly the colours Liftoff and the send screen have always had. */
        val Brand = LinkColors(
            glow = Color(0xFF5FD9A0),
            streak = Color(0xFFE2ECE7),
            edgeA = Color(0xFF9FE6C3),
            edgeB = Color(0xFF7FD5AE),
            tubeA = listOf(Color.White, Color(0xFFE8FFF4), Color(0xFFB9EED3)),
            tubeB = listOf(Color(0xFFD6FBE9), Color(0xFF8FDDB8)),
            backgroundTop = Color(0xFF07100C),
            backgroundBottom = Color(0xFF050807),
            deepGlow = Color(0xFF13875A),
            ink = Color(0xFFF1FBF6),
            soft = Color(0xFF86A99A),
            percent = Color(0xFF8FBFAA),
            onDark = Color(0xFFE6F4EE),
        )

        /**
         * The same recipe in [hue]; [vivid] 1 for a colourful wallpaper, 0 for a monochrome palette
         * (greys). Saturations and lightnesses are Brand's own, so a green wallpaper looks like Brand.
         */
        fun of(hue: Float, vivid: Float): LinkColors {
            fun c(s: Float, l: Float) = Color(Contrast.hslToArgb(hue, (s * vivid).coerceIn(0f, 1f), l))
            return LinkColors(
                glow = c(0.62f, 0.61f),
                streak = c(0.15f, 0.91f),
                edgeA = c(0.58f, 0.76f),
                edgeB = c(0.52f, 0.67f),
                tubeA = listOf(Color.White, c(1f, 0.955f), c(0.61f, 0.83f)),
                tubeB = listOf(c(0.84f, 0.91f), c(0.56f, 0.71f)),
                backgroundTop = c(0.4f, 0.045f),
                backgroundBottom = c(0.25f, 0.026f),
                deepGlow = c(0.75f, 0.3f),
                ink = c(0.56f, 0.96f),
                soft = c(0.15f, 0.6f),
                percent = c(0.24f, 0.65f),
                onDark = c(0.42f, 0.93f),
            )
        }

        /** From the home colours: their hue, read off the pale buttons. */
        fun from(home: HomeColors): LinkColors {
            if (home == HomeColors.Default) return Brand
            val hsl = Contrast.argbToHsl(home.pale.toArgb())
            return of(hsl[0], if (hsl[1] < 0.05f) 0f else 1f)
        }
    }
}
