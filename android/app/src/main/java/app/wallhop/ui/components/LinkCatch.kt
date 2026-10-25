package app.wallhop.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalView
import app.wallhop.ui.design.Wallhop
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/*
 * Lync's link (the app icon's two hooks) as a live connection state, shared with the Mac.
 * Searching: the hooks wait apart and breathe, the second one dim. Connecting: a small wind-up,
 * then they slide together on a playful spring, catch with a squash on the exact frame they meet
 * (where the click haptic lands), the dim hook lights up and a glint runs along both.
 * Geometry is in a 256 box, like the icon.
 */
private const val R = 31f
private const val SW = 19f
private const val HALF = 62f
private const val LONG = 82f
private const val SHORT = 15f
private const val APART = 46f

private val hookA = Path().apply {
    val ax = 128f - HALF
    moveTo(ax + LONG, 128f - R); lineTo(ax, 128f - R)
    arcTo(Rect(Offset(ax, 128f), R), 270f, -180f, false)
    lineTo(ax + SHORT, 128f + R)
}
private val hookB = Path().apply {
    val bx = 128f + HALF
    moveTo(bx - LONG, 128f + R); lineTo(bx, 128f + R)
    arcTo(Rect(Offset(bx, 128f), R), 90f, -180f, false)
    lineTo(bx - SHORT, 128f - R)
}
private val hookLength = LONG + PI.toFloat() * R + SHORT

/**
 * The link. [connected] drives it; the catch plays (with its haptic) only on a change to
 * connected while this is on screen. [shaded] draws the rounded-tube look for larger sizes.
 */
@Composable
fun LinkCatch(connected: Boolean, modifier: Modifier = Modifier, shaded: Boolean = true) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val active = LocalScreenActive.current
    val view = LocalView.current
    val apart = remember { Animatable(if (connected) 0f else APART) }
    val squash = remember { Animatable(0f) }
    val lit = remember { Animatable(if (connected) 1f else 0f) }
    val glint = remember { Animatable(1f) }
    val breathe = remember { Animatable(0f) }
    val was = remember { booleanArrayOf(connected) }

    LaunchedEffect(connected, active, reduceMotion) {
        val justConnected = connected && !was[0]
        was[0] = connected
        // A catch cut short (screen left mid-animation) mustn't leave the link squashed or the
        // glint frozen on it.
        squash.snapTo(0f); glint.snapTo(1f)
        if (!connected) {
            lit.animateTo(0f, tween(300)); apart.animateTo(APART, tween(420, easing = FastOutSlowInEasing))
            if (reduceMotion || !active) { breathe.snapTo(0f); return@LaunchedEffect }
            while (true) {
                breathe.snapTo(0f); breathe.animateTo(1f, tween(3200, easing = LinearEasing))
            }
        }
        breathe.snapTo(0f)
        if (!justConnected || !active) { apart.snapTo(0f); lit.snapTo(1f); return@LaunchedEffect }
        if (reduceMotion) {
            apart.snapTo(0f); lit.animateTo(1f, tween(200)); LinkHaptics.click(view); return@LaunchedEffect
        }
        LinkHaptics.windUp(view)
        apart.animateTo(APART + 8f, tween(80, easing = FastOutSlowInEasing))
        coroutineScope {
            var hit = false
            apart.animateTo(0f, spring(dampingRatio = 0.58f, stiffness = 130f)) {
                if (!hit && value <= 14f) {
                    hit = true
                    LinkHaptics.click(view)
                    launch { squash.animateTo(1f, tween(65)); squash.animateTo(0f, tween(80)) }
                    launch { lit.animateTo(1f, tween(350, easing = FastOutSlowInEasing)) }
                    launch { delay(50); glint.snapTo(0f); glint.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
                }
            }
        }
    }

    Canvas(modifier) {
        val k = size.minDimension / 256f
        val b = sin(breathe.value * 2 * PI).toFloat() * 3f
        val dx = apart.value + if (connected) 0f else b
        val sq = sin(squash.value * PI).toFloat()
        withTransform({
            translate((size.width - 256f * k) / 2f, (size.height - 256f * k) / 2f)
            scale(k, k, Offset.Zero)
            rotate(-45f, Offset(128f, 128f))
            scale(1f + 0.05f * sq, 1f - 0.06f * sq, Offset(128f, 128f))
        }) {
            hook(hookB, dx, Color.Unspecified, soft = 1f - lit.value, alpha = 1f, shaded, glint.value)
            hook(hookA, -dx, Color.Unspecified, soft = if (connected) 0f else 0.55f, alpha = 1f, shaded, glint.value)
        }
    }
}

// Lync mint, as in the app icon: a deep body, a lit top, a fine highlight. A hook that hasn't
// caught yet is a muted grey-green, so lighting up reads as the moment of connection.
private val MintDeep = Color(0xFF13875A)
private val MintBody = Color(0xFF2FB57E)
private val MintLight = Color(0xFF7FE3B0)
private val MintTop = Color(0xFFB4F5D6)
private val Muted = Color(0xFF4F6B60)
private val MutedTop = Color(0xFF7D9A8E)

private fun mix(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

private fun DrawScope.hook(path: Path, dx: Float, color: Color, soft: Float, alpha: Float, shaded: Boolean, glint: Float) {
    translate(dx, 0f) {
        if (!shaded) {
            // Small sizes: one solid stroke, mint once caught, muted while waiting.
            drawPath(path, mix(MintLight, Muted, soft).copy(alpha = alpha), style = Stroke(SW + 2f, cap = StrokeCap.Round))
            return@translate
        }
        // A soft shadow below (two wide, faint strokes stand in for a blur).
        translate(3f, 5f) {
            drawPath(path, Color.Black.copy(alpha = 0.14f * alpha), style = Stroke(SW + 10f, cap = StrokeCap.Round))
            drawPath(path, Color.Black.copy(alpha = 0.22f * alpha), style = Stroke(SW + 3f, cap = StrokeCap.Round))
        }
        // The deep edge, then the body shading lighter towards the top, then a fine highlight.
        drawPath(path, mix(MintDeep, Muted, soft).copy(alpha = alpha), style = Stroke(SW + 1f, cap = StrokeCap.Round))
        val tube = Brush.verticalGradient(
            0f to mix(MintTop, MutedTop, soft).copy(alpha = alpha),
            0.5f to mix(MintLight, Muted, soft).copy(alpha = alpha),
            1f to mix(MintBody, Muted, soft).copy(alpha = alpha),
            startY = 128f - R * 1.25f, endY = 128f + R * 1.25f,
        )
        translate(-1f, -1.5f) { drawPath(path, tube, style = Stroke(SW - 4f, cap = StrokeCap.Round)) }
        translate(-3f, -3.5f) { drawPath(path, Color.White.copy(alpha = (0.75f - 0.4f * soft) * alpha), style = Stroke(2.5f, cap = StrokeCap.Round)) }
        if (glint in 0.001f..0.999f) {
            val g = sin(glint * PI).toFloat()
            drawPath(
                path, Color.White.copy(alpha = 0.9f * g),
                style = Stroke(6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(22f, hookLength * 2), -glint * (hookLength + 22f) + 22f)),
            )
        }
    }
}

/** The link's haptic: a light wind-up tick, then on impact a click and two fading taps. */
object LinkHaptics {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    private fun play(view: View, effect: VibrationEffect?, fallback: Int?) {
        val vibrator = vibrator(view.context)
        // Below 13 a direct vibration ignores the "touch feedback" setting, so check it here.
        val allowed = Build.VERSION.SDK_INT >= 33 || (view.isHapticFeedbackEnabled &&
            Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0)
        if (effect != null && vibrator != null && vibrator.hasVibrator()) {
            if (!allowed) return
            if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            else vibrator.vibrate(effect)
        } else if (fallback != null) {
            view.performHapticFeedback(fallback)
        }
    }

    private fun supportsLowTick(view: View): Boolean = Build.VERSION.SDK_INT >= 31 &&
        vibrator(view.context)?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK) == true

    private fun supportsClick(view: View): Boolean = Build.VERSION.SDK_INT >= 31 &&
        vibrator(view.context)?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK) == true

    fun windUp(view: View) {
        if (Build.VERSION.SDK_INT >= 31 && supportsLowTick(view)) {
            play(view, VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f).compose(), null)
        }
    }

    fun click(view: View) {
        if (Build.VERSION.SDK_INT >= 31 && supportsClick(view)) {
            play(
                view,
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f, 190)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.18f, 170)
                    .compose(),
                null,
            )
        } else {
            play(view, null, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
