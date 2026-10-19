package app.wallhop.ui.design

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot

// Liquid Glass. See docs/DESIGN.md §4 — every number here comes from §4.2.

/** The screen content that glass refracts. Provided once per screen root. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Angle in degrees of the light that rims glass; follows device tilt (DESIGN.md §4.2). */
val LocalLightAngle = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(45f) }

enum class GlassVariant {
    /** Adapts to what's behind it; works anywhere. */
    Regular,
    /** Always more transparent; only over bold media with a dimming layer. */
    Clear,
    /** Regular glass tinted with the accent, for the one primary action. */
    Tinted,
}

fun capsule() = Capsule()
fun rounded(radius: Dp) = RoundedRectangle(radius)

/**
 * Touch state for glass: whether it's pressed and where the finger is. Read at draw time, so
 * moving a finger redraws the glow without recomposing.
 */
@Stable
class GlassTouch {
    internal val press = Animatable(0f)
    internal val glow = Animatable(0f)
    var position by mutableStateOf(Offset.Unspecified)
        internal set
    val isPressed: Boolean get() = press.targetValue > 0.5f
}

@Composable
fun rememberGlassTouch(): GlassTouch = remember { GlassTouch() }

/**
 * Follows a finger on this element without consuming the gesture, so clicks and drags still
 * reach the element's own handlers.
 */
@Composable
fun Modifier.trackGlassTouch(touch: GlassTouch, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val scope = rememberCoroutineScope()
    return pointerInput(touch) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            touch.position = down.position
            scope.launch { touch.press.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 600f)) }
            scope.launch { touch.glow.animateTo(1f, tween(90)) }
            do {
                val event = awaitPointerEvent()
                event.changes.firstOrNull()?.let { touch.position = it.position }
            } while (event.changes.any { it.pressed })
            scope.launch { touch.press.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 600f)) }
            scope.launch { touch.glow.animateTo(0f, tween(300)) }
        }
    }
}

/**
 * Draws Liquid Glass behind this element.
 *
 * - Thickness scales with size: bigger glass refracts more and casts a deeper, softer shadow.
 * - [touch]: swells with a gel bounce, brightens, and glows from the finger.
 * - [appear] 0–1 materializes the glass by ramping refraction, light and tint (no plain fades).
 * - [exportTo] lets a child (e.g. the tab bar's droplet) refract this glass and its content.
 *
 * Falls back to a frosted solid when the phone can't render backdrop effects or the system
 * asks for less transparency (DESIGN.md §4.4).
 */
@Composable
fun Modifier.glass(
    shape: Shape = Capsule(),
    variant: GlassVariant = GlassVariant.Regular,
    touch: GlassTouch? = null,
    appear: () -> Float = { 1f },
    floating: Boolean = true,
    tint: Color? = null,
    backdrop: Backdrop? = null,
    exportTo: LayerBackdrop? = null,
    swell: Boolean = true,
): Modifier {
    val colors = Wallhop.colors
    val source = backdrop ?: LocalBackdrop.current
    val light = LocalLightAngle.current
    val accent = tint ?: colors.tint
    val reduceMotion = Wallhop.a11y.reduceMotion
    val sizeState = remember { mutableStateOf(IntSize.Zero) }

    val scaled = this
        .onSizeChanged { sizeState.value = it }
        .then(
            if (touch != null && swell && !reduceMotion) {
                Modifier.graphicsLayer {
                    // Gel flex: small glass swells more than large glass.
                    val small = (1f - (sizeState.value.height / (120.dp.toPx())).coerceIn(0f, 1f))
                    val amount = 1f + touch.press.value * (0.02f + 0.03f * small)
                    scaleX = amount
                    scaleY = amount
                }
            } else {
                Modifier
            },
        )

    val solid = source == null || !isRenderEffectSupported() || Wallhop.a11y.reduceTransparency || GlassDebug.solid
    if (solid) {
        val fill = when (variant) {
            GlassVariant.Tinted -> accent
            else -> lerp(colors.backgroundElevated, colors.backgroundElevated2, 0.3f).copy(alpha = 0.96f)
        }
        return scaled
            .alpha(appear().coerceIn(0f, 1f))
            .then(if (floating) Modifier.shadow(if (colors.dark) 0.dp else 10.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .background(fill)
            .border(0.5.dp, colors.separator.copy(alpha = 0.6f), shape)
    }

    val surfaceTint = when (variant) {
        GlassVariant.Regular -> colors.glassTint.copy(alpha = if (colors.dark) 0.18f else 0.14f)
        GlassVariant.Clear -> colors.glassTint.copy(alpha = if (colors.dark) 0.10f else 0.04f)
        GlassVariant.Tinted -> accent.copy(alpha = 0.72f)
    }
    val dark = colors.dark

    return scaled.drawBackdrop(
        backdrop = source!!,
        shape = { shape },
        effects = {
            val a = appear().coerceIn(0f, 1f)
            val pressed = touch?.press?.value ?: 0f
            val t = (size.minDimension / 120.dp.toPx()).coerceIn(0.35f, 1f)
            vibrancy()
            if (GlassDebug.noBlur && GlassDebug.noLens) return@drawBackdrop
            val blurDp = if (variant == GlassVariant.Clear) 1f + 2f * t else 2f + 6f * t
            if (!GlassDebug.noBlur) blur(blurDp.dp.toPx() * a)
            if (GlassDebug.noLens) return@drawBackdrop
            val height = minOf((10f + 14f * t).dp.toPx(), size.minDimension / 2f)
            val amountDp = if (variant == GlassVariant.Clear) 28f + 44f * t else 24f + 40f * t
            lens(
                refractionHeight = height * a,
                refractionAmount = amountDp.dp.toPx() * a * (1f + pressed * 0.3f),
                depthEffect = true,
                chromaticAberration = t > 0.7f,
            )
        },
        highlight = {
            val a = appear().coerceIn(0f, 1f)
            val t = (sizeState.value.height / 360f).coerceIn(0.35f, 1f)
            val pressed = touch?.press?.value ?: 0f
            Highlight(
                width = (0.5f + 0.5f * t).dp,
                alpha = ((if (variant == GlassVariant.Clear) 0.7f else 0.45f + 0.25f * t) + pressed * 0.35f).coerceAtMost(1f) * a,
                style = HighlightStyle.Default(angle = light.value),
            )
        },
        shadow = if (floating) {
            {
                val a = appear().coerceIn(0f, 1f)
                val t = (sizeState.value.height / 360f).coerceIn(0.35f, 1f)
                val radius = (12f + 20f * t).dp
                Shadow(
                    radius = radius,
                    offset = DpOffset(0.dp, radius / 5f),
                    color = Color.Black.copy(alpha = (if (dark) 0.30f + 0.20f * t else 0.08f + 0.10f * t) * a),
                )
            }
        } else {
            null
        },
        exportedBackdrop = exportTo,
        onDrawSurface = {
            drawRect(surfaceTint.copy(alpha = surfaceTint.alpha * appear().coerceIn(0f, 1f)))
        },
        onDrawFront = {
            // Illumination from within, centred on the finger.
            val glow = touch?.glow?.value ?: 0f
            val position = touch?.position ?: Offset.Unspecified
            if (glow > 0.01f && position != Offset.Unspecified) {
                val radius = hypot(size.width, size.height) * 1.2f
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.35f * glow), Color.White.copy(alpha = 0f)),
                        center = position,
                        radius = radius,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        },
    )
}

/** A box drawn in glass, with its content centred. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = Capsule(),
    variant: GlassVariant = GlassVariant.Regular,
    touch: GlassTouch? = null,
    appear: () -> Float = { 1f },
    floating: Boolean = true,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.glass(shape, variant, touch, appear, floating),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * Tilting the phone moves the light that rims glass by up to ±20° (DESIGN.md §4.2, property 6).
 * Static when Reduce Motion is on or the phone has no rotation sensor.
 */
@Composable
fun rememberLightAngle(reduceMotion: Boolean): State<Float> {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val angle = remember { mutableFloatStateOf(45f) }
    DisposableEffect(reduceMotion, lifecycle) {
        if (reduceMotion) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        var smoothed = 45f
        var lastUpdate = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Every update redraws all glass: cap it at ~10 a second.
                if (event.timestamp - lastUpdate < 100_000_000L) return
                lastUpdate = event.timestamp
                // Gravity's direction across the screen tells which way the phone leans.
                val lean = Math.toDegrees(atan2(event.values[0].toDouble(), event.values[1].toDouble())).toFloat()
                val target = 45f + lean.coerceIn(-40f, 40f) * 0.5f
                smoothed += (target - smoothed) * 0.35f
                // Every change redraws all glass, so ignore sensor jitter under a degree.
                if (kotlin.math.abs(smoothed - angle.floatValue) >= 1f) angle.floatValue = smoothed
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // Listen only while the app is in front.
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> manager?.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            manager?.unregisterListener(listener)
        }
    }
    return angle
}

/** Helper for overlays: the current value of an appear transition. */
fun MutableFloatState.reader(): () -> Float = { floatValue }
