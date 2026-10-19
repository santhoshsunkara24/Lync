package app.wallhop.ui.design

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wallhop.R

// Design tokens. See docs/DESIGN.md §3 (foundations) and §7 (motion).

/**
 * Semantic colours (DESIGN.md §3.3). The app is always dark and expressive: [expressivePalette]
 * builds these from the phone's Material You colours, so every screen shares the wallpaper's hue.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val background: Color,
    val backgroundElevated: Color,
    val backgroundElevated2: Color,
    val label: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val separator: Color,
    val fill: Color,
    val tint: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val indigo: Color,
    val gray: Color,
    /** Base colour mixed into glass. */
    val glassTint: Color,
)

val LightPalette = Palette(
    dark = false,
    background = Color(0xFFF2F2F7),
    backgroundElevated = Color(0xFFFFFFFF),
    backgroundElevated2 = Color(0xFFF2F2F7),
    label = Color(0xFF000000),
    labelSecondary = Color(0x993C3C43),
    labelTertiary = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x33787880),
    tint = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    indigo = Color(0xFF5856D6),
    gray = Color(0xFF8E8E93),
    glassTint = Color(0xFFFFFFFF),
)

val DarkPalette = Palette(
    dark = true,
    background = Color(0xFF000000),
    backgroundElevated = Color(0xFF1C1C1E),
    backgroundElevated2 = Color(0xFF2C2C2E),
    label = Color(0xFFFFFFFF),
    labelSecondary = Color(0x99EBEBF5),
    labelTertiary = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x5C787880),
    tint = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    indigo = Color(0xFF5E5CE6),
    gray = Color(0xFF8E8E93),
    glassTint = Color(0xFF000000),
)

/**
 * Figtree (SIL OFL): clean and warm, very readable at small sizes. SF Pro is licensed for
 * Apple platforms only (DESIGN.md §2).
 */
@OptIn(ExperimentalTextApi::class)
val Figtree = FontFamily(
    listOf(400, 500, 600, 700).map { weight ->
        Font(R.font.figtree, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
    },
)

/**
 * Type scale in Figtree (DESIGN.md §3.4): calm medium weights for titles, like Material 3
 * Expressive; semibold only for headlines and labels. Tracking in sp.
 */
object Type {
    private fun style(size: Int, line: Int, weight: Int, tracking: Float) = TextStyle(
        fontFamily = Figtree,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = FontWeight(weight),
        letterSpacing = tracking.sp,
    )

    val largeTitle = style(30, 38, 500, -1.2f)
    val title1 = style(26, 32, 500, -0.8f)
    val title2 = style(22, 28, 500, -0.4f)
    val title3 = style(19, 24, 600, -0.2f)
    val headline = style(16, 21, 600, 0f)
    val body = style(16, 22, 400, 0f)
    val callout = style(15, 20, 400, 0f)
    val subheadline = style(14, 19, 500, 0f)
    val footnote = style(13, 18, 500, 0f)
    val caption1 = style(12, 16, 500, 0f)
    val caption2 = style(11, 13, 600, 0.6f)
    val code = style(44, 50, 600, 1f).copy(fontFeatureSettings = "tnum")
}

/** Continuous corner radii (DESIGN.md §3.2). */
object Radius {
    val xs = 6.dp
    val s = 10.dp
    val m = 14.dp
    val l = 22.dp
    val xl = 28.dp
    val sheet = 34.dp
}

object Space {
    val margin = 16.dp
    val section = 32.dp
}

/** Springs matched to SwiftUI's presets (DESIGN.md §7.1). */
object Springs {
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 1500f)
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 400f)
    fun <T> smooth(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 160f)
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.7f, stiffness = 250f)
    fun <T> instant(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
}

/** System accessibility state that changes how things draw and move. */
@Immutable
data class Accessibility(val reduceMotion: Boolean, val reduceTransparency: Boolean)

/** Ink: the near-black every screen fades into. */
val Ink = Color(0xFF0B0B0C)

/** The app's palette from the Material You home colours: tinted dark surfaces, white text. */
fun expressivePalette(home: HomeColors) = DarkPalette.copy(
    background = Ink,
    backgroundElevated = home.card,
    backgroundElevated2 = home.mid,
    label = Color.White,
    labelSecondary = Color.White.copy(alpha = 0.68f),
    labelTertiary = Color.White.copy(alpha = 0.4f),
    separator = Color.White.copy(alpha = 0.1f),
    fill = Color.White.copy(alpha = 0.12f),
    tint = home.pale,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }
val LocalHomeColors = staticCompositionLocalOf { HomeColors.Default }
val LocalAccessibility = staticCompositionLocalOf { Accessibility(reduceMotion = false, reduceTransparency = false) }

object Wallhop {
    val colors: Palette @Composable get() = LocalPalette.current
    val a11y: Accessibility @Composable get() = LocalAccessibility.current
    /** The Material You tones: gradient, cards, pale buttons, contrasting accent. */
    val home: HomeColors @Composable get() = LocalHomeColors.current
}

fun readAccessibility(context: Context): Accessibility {
    val animationScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val highContrast = Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    return Accessibility(reduceMotion = animationScale == 0f, reduceTransparency = highContrast || powerSave)
}

@Composable
fun WallhopTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val home = rememberHomeColors()
    val palette = remember(home) { expressivePalette(home) }
    val accessibility = remember { readAccessibility(context) }
    // Material components still used under the hood (text selection, dialogs) pick up the same colours.
    val scheme = darkColorScheme().copy(
        primary = palette.tint,
        onPrimary = home.onPale,
        background = palette.background,
        onBackground = palette.label,
        surface = palette.backgroundElevated,
        onSurface = palette.label,
        onSurfaceVariant = palette.labelSecondary,
        outline = palette.separator,
        error = palette.red,
    )
    // No glass any more, so no tilt sensor: the light stays put (the little Mac's glare uses it).
    val lightAngle = remember { androidx.compose.runtime.mutableFloatStateOf(45f) }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalHomeColors provides home,
        LocalAccessibility provides accessibility,
        LocalLightAngle provides lightAngle,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
