package app.wallhop.ui.design

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The home screen's colours, from your wallpaper (DESIGN.md §6.4) — see [themeSeeds].
 * The screen runs from [top] through [mid] to near-black; cards and buttons are tones of the
 * same hue, and [peek] is a contrasting hue for the card beside the Mac.
 */
@Immutable
data class HomeColors(
    val top: Color,
    val mid: Color,
    val card: Color,
    val pale: Color,
    val onPale: Color,
    val peek: Color,
    val peekPale: Color,
    val onPeekPale: Color,
    /** Red for destructive text, light enough to read on [card] and on lists over [mid]. */
    val red: Color = Color(0xFFFF453A),
) {
    companion object {
        /** Violet, for phones older than Android 12 (no Material You). */
        val Default = from(0xFF4B2BC9.toInt(), null)

        /** One-time codes always use Lync's own colours, never the wallpaper's (shared with the Mac). */
        val CodeForest = Color(0xFF0F2E24)
        val CodeMint = Color(0xFF7FE3B0)
        /** Secondary text on [CodeForest]. */
        const val CodeSecondaryAlpha = 0.75f

        /**
         * [seed] is the primary hue; [accent] the tertiary one for the contrasting card. The
         * lightness of [top], [mid] and [peek] drops per hue until white and white at 78 %
         * read at 4.5:1 (yellows and greens get darker than blues).
         */
        fun from(seed: Int, accent: Int?): HomeColors {
            val hsl = Contrast.argbToHsl(seed)
            // A monochrome Material You palette (common on Nothing phones) stays monochrome:
            // greys from charcoal to ink, pale grey buttons, a lighter grey card for contrast.
            if (hsl[1] < 0.12f) return monochrome()
            val hue = hsl[0]
            // Greys and near-greys get a gentle tint, so the screen never looks washed out.
            val sat = hsl[1].coerceIn(0.45f, 0.8f)
            val other = accent?.let { Contrast.argbToHsl(it)[0] } ?: ((hue + 110f) % 360f)
            return finish(
                top = safe(hue, sat * 0.85f, 0.42f),
                mid = safe(hue, sat * 0.7f, 0.2f),
                card = hsl(hue, sat * 0.5f, 0.12f),
                pale = hsl(hue, 0.8f, 0.92f),
                onPale = hsl(hue, 0.6f, 0.22f),
                peek = safe(other, 0.62f, 0.44f),
                peekPale = hsl(other, 0.9f, 0.9f),
                onPeekPale = hsl(other, 0.6f, 0.18f),
            )
        }

        private fun monochrome() = finish(
            top = safe(0f, 0f, 0.3f),
            mid = safe(0f, 0f, 0.14f),
            card = hsl(0f, 0f, 0.12f),
            pale = hsl(0f, 0f, 0.92f),
            onPale = hsl(0f, 0f, 0.12f),
            peek = safe(0f, 0f, 0.26f),
            peekPale = hsl(0f, 0f, 0.92f),
            onPeekPale = hsl(0f, 0f, 0.12f),
        )

        private fun finish(top: Color, mid: Color, card: Color, pale: Color, onPale: Color, peek: Color, peekPale: Color, onPeekPale: Color): HomeColors {
            val cardArgb = card.toArgb()
            // Lists are white 7 % over the card (sheets) or over the gradient's mid tone (pages).
            val red = Contrast.lightenUntil(
                0xFFFF453A.toInt(),
                listOf(cardArgb, Contrast.blend(Contrast.WHITE, 0.07f, cardArgb), Contrast.blend(Contrast.WHITE, 0.07f, mid.toArgb())),
            )
            return HomeColors(top, mid, card, pale, onPale, peek, peekPale, onPeekPale, Color(red))
        }

        /** hsl with the lightness lowered until white (and white at 78 %) text reads on it. */
        private fun safe(h: Float, s: Float, l: Float) = Color(Contrast.hslToArgb(h, s, Contrast.lightnessForWhite(h, s, l)))
        private fun hsl(h: Float, s: Float, l: Float) = Color(Contrast.hslToArgb(h, s, l))
    }
}

/**
 * The two colours Wallhop themes itself with: [seed] for the screen, [accent] for the contrasting
 * card. Android's Material You palette when it follows the wallpaper; otherwise the wallpaper's own
 * colours, read directly. Phones set to a preset or monochrome style would otherwise give grey even
 * with a colourful wallpaper. Null before Android 12 (the caller falls back to violet).
 */
fun themeSeeds(context: Context): Pair<Int, Int?>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val system = context.getColor(android.R.color.system_accent1_500)
    val systemAccent = context.getColor(android.R.color.system_accent3_500)
    val wallpaper = runCatching {
        WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
    }.getOrNull()
    val colours = listOfNotNull(wallpaper?.primaryColor, wallpaper?.secondaryColor, wallpaper?.tertiaryColor)
        .map { it.toArgb() }
        .filter { chroma(it) >= 0.12f }
    if (app.wallhop.BuildConfig.STRESS_HOOKS) android.util.Log.d(
        "WallhopTheme",
        "system=${Integer.toHexString(system)} preset=${usesPresetColours(context)} wallpaper=$wallpaper usable=${colours.map(Integer::toHexString)}",
    )
    // The system palette wins when it is colourful and the user chose wallpaper colours.
    if (chroma(system) >= 0.12f && !usesPresetColours(context)) return system to systemAccent
    val seed = colours.firstOrNull() ?: return system to systemAccent
    val seedHue = hue(seed)
    val accent = colours.drop(1).firstOrNull { hueDistance(hue(it), seedHue) > 30f }
    return seed to accent
}

/** True when the phone's theme uses a preset colour rather than the wallpaper's. */
private fun usesPresetColours(context: Context): Boolean = runCatching {
    val json = android.provider.Settings.Secure.getString(
        context.contentResolver, "theme_customization_overlay_packages",
    ) ?: return false
    val obj = org.json.JSONObject(json)
    obj.optString("android.theme.customization.color_source") == "preset" ||
        obj.optString("android.theme.customization.theme_style") == "MONOCHROMATIC"
}.getOrDefault(false)

private fun chroma(argb: Int): Float {
    val r = android.graphics.Color.red(argb); val g = android.graphics.Color.green(argb); val b = android.graphics.Color.blue(argb)
    return (maxOf(r, g, b) - minOf(r, g, b)) / 255f
}

private fun hue(argb: Int) = FloatArray(3).also { ColorUtils.colorToHSL(argb, it) }[0]

private fun hueDistance(a: Float, b: Float) = minOf(kotlin.math.abs(a - b), 360f - kotlin.math.abs(a - b))

/** The last colours worked out, so a new screen (or the share sheet) starts with them. */
@Volatile private var lastHomeColors: HomeColors? = null

/**
 * Home colours from the wallpaper; violet before Android 12. Updates when the wallpaper changes.
 * Reading the wallpaper's colours can take a while, so it happens off the main thread; until then
 * the screen uses the last colours, or the system palette on first launch.
 */
@Composable
fun rememberHomeColors(): HomeColors {
    val context = LocalContext.current
    // Re-read on configuration changes (a new Material You palette) and on wallpaper colour changes.
    val configuration = LocalConfiguration.current
    var wallpaperVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val manager = WallpaperManager.getInstance(context)
        val listener = WallpaperManager.OnColorsChangedListener { _, _ -> wallpaperVersion++ }
        runCatching { manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { manager.removeOnColorsChangedListener(listener) } }
    }
    val initial = remember { lastHomeColors ?: quickHomeColors(context) }
    val colors by produceState(initial, configuration, wallpaperVersion) {
        value = withContext(Dispatchers.IO) {
            themeSeeds(context)?.let { (seed, accent) -> HomeColors.from(seed, accent) } ?: HomeColors.Default
        }.also { lastHomeColors = it }
    }
    return colors
}

/** A first guess without touching the wallpaper: the system palette, which is quick to read. */
private fun quickHomeColors(context: Context): HomeColors {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return HomeColors.Default
    return HomeColors.from(context.getColor(android.R.color.system_accent1_500), context.getColor(android.R.color.system_accent3_500))
}
