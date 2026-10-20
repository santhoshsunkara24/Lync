package app.wallhop.ui.components

import app.wallhop.ui.design.WIcons
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Radius
import app.wallhop.ui.design.RollingCode
import app.wallhop.ui.design.floatIn
import app.wallhop.ui.design.pressDim
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.rounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

// Fields, code display, empty states, thumbnails and the hop-dots motif.

/** False for screens that are kept alive but not shown (e.g. other tabs); ambient animations pause. */
val LocalScreenActive = androidx.compose.runtime.compositionLocalOf { true }

/**
 * Text field (DESIGN.md §5.8): filled well, focus ring, clear button. [label] names it for
 * TalkBack only; the sheet title and placeholder already say what it's for on screen. [error]
 * shows below in red and rings the field.
 */
@Composable
fun WTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    keyboardType: KeyboardType = KeyboardType.Text,
    label: String? = null,
    error: String? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val colors = Wallhop.colors
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (focused || error != null) 1f else 0f, tween(Durations.Quick), label = "ring")
    val ringColor = if (error != null) colors.red else colors.tint
    val shape = rounded(20.dp)
    Column(modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = Type.body.copy(color = colors.label),
            cursorBrush = SolidColor(colors.tint),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .background(Color.White.copy(alpha = 0.1f))
                .border(2.dp, ringColor.copy(alpha = ring), shape)
                .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
                .then(if (error != null) Modifier.semantics { error(error) } else Modifier)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            decorationBox = { inner ->
                val clear = value.isNotEmpty() && singleLine
                // The clear button is a 44 dp target; the text's own padding keeps the field 52 dp tall.
                Row(
                    Modifier.padding(start = 18.dp, end = if (clear) 4.dp else 18.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                        // labelSecondary, not tertiary: placeholders need 4.5:1 too.
                        if (value.isEmpty()) Text(placeholder, style = Type.body, color = colors.labelSecondary)
                        inner()
                    }
                    if (clear) ClearButton { onValueChange("") }
                }
            },
        )
        if (error != null) {
            // Read out as soon as it appears.
            Text(error, style = Type.footnote, color = colors.red,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Assertive })
        }
    }
}

/** The small (x) that empties a field, with a full 44 dp touch target. */
@Composable
private fun ClearButton(onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .size(44.dp)
            .pressDim(source)
            .clip(capsule())
            .clickable(interactionSource = source, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = "Clear" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(WIcons.XCircle, null, tint = Wallhop.colors.labelTertiary, modifier = Modifier.size(18.dp))
    }
}

/** Capsule search field. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    val colors = Wallhop.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Type.body.copy(color = colors.label),
        cursorBrush = SolidColor(colors.tint),
        modifier = modifier.fillMaxWidth().height(48.dp).clip(capsule()).background(colors.fill),
        decorationBox = { inner ->
            Row(Modifier.padding(start = 12.dp, end = if (value.isNotEmpty()) 2.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(WIcons.Search, null, tint = colors.labelSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = Type.body, color = colors.labelSecondary)
                    inner()
                }
                if (value.isNotEmpty()) ClearButton { onValueChange("") }
            }
        },
    )
}

/**
 * A 6-digit code in two groups of three. On first show the digits drop in one after another
 * (DESIGN.md §5.16: 40 ms apart, spring.bouncy); with Reduce Motion they simply show.
 */
@Composable
fun CodeDisplay(code: String, modifier: Modifier = Modifier, color: Color = Wallhop.colors.label, maxWidth: Dp = 280.dp) {
    // Digits grow with the font size, but never wider than [maxWidth]: past that the whole code
    // scales down together, so nothing clips at the largest font sizes.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val scale = minOf(fontScale, maxWidth / (30.dp * 6 + 14.dp))
    val style = Type.code.copy(fontSize = Type.code.fontSize * (scale / fontScale), lineHeight = Type.code.lineHeight * (scale / fontScale))
    // One item for TalkBack, read digit by digit.
    Box(modifier.clearAndSetSemantics { contentDescription = app.wallhop.core.Wording.pairingCodeDescription(code) }, contentAlignment = Alignment.Center) {
        RollingCode(
            spacedCode(code), style, color,
            fromTop = true, staggerMs = 40, spec = Springs.bouncy(), digitWidth = 30.dp * scale, groupGap = 14.dp * scale,
        )
    }
}

/** Centred icon, title and explanation (DESIGN.md §5.15). */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    val colors = Wallhop.colors
    Column(modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            // Floats up into place once, the first time it's seen.
            Modifier.floatIn().size(72.dp).clip(capsule()).background(Wallhop.home.pale.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Wallhop.home.pale, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = Type.headline, color = colors.label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(message, style = Type.subheadline, color = colors.labelSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * The brand motif: a row of dots between phone and Mac. When [active], a bright dot hops
 * across; otherwise the row rests. Static with Reduce Motion.
 */
@Composable
fun HopDots(active: Boolean, modifier: Modifier = Modifier, dots: Int = 7, color: Color = Wallhop.colors.label) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val screenActive = LocalScreenActive.current
    // One hop, then a rest with no frames drawn at all — an idle screen shouldn't redraw forever.
    val hop = remember { androidx.compose.animation.core.Animatable(-1f) }
    LaunchedEffect(active, reduceMotion, screenActive) {
        if (!active || reduceMotion || !screenActive) {
            hop.snapTo(-1f)
            return@LaunchedEffect
        }
        // A few hops when the screen appears or the state changes, then stillness: no idle redraws.
        repeat(3) {
            hop.snapTo(0f)
            hop.animateTo(1f, tween(1100, easing = LinearEasing))
            hop.snapTo(-1f)
            kotlinx.coroutines.delay(900)
        }
    }
    Canvas(modifier.height(10.dp)) {
        val phase = hop.value
        val spacing = size.width / (dots - 1)
        val radius = 3.dp.toPx()
        for (i in 0 until dots) {
            val position = i.toFloat() / (dots - 1)
            val glow = if (phase < 0f) 0f else (1f - abs(position - phase) * 5f).coerceIn(0f, 1f)
            val lift = glow * 3.dp.toPx()
            drawCircle(
                color = color.copy(alpha = 0.22f + glow * 0.78f),
                radius = radius * (1f + glow * 0.3f),
                center = Offset(i * spacing, size.height / 2 - lift),
            )
        }
    }
}

/** Up to three thumbnails fanned out (DESIGN.md §5.17). */
@Composable
fun ThumbnailStack(uris: List<Uri>, size: Dp = 52.dp) {
    Box(Modifier.size(size + 12.dp)) {
        uris.take(3).reversed().forEachIndexed { reversedIndex, uri ->
            val index = minOf(uris.size, 3) - 1 - reversedIndex
            Thumbnail(uri, size, Modifier.offset(x = (index * 5).dp).rotate((index - 1) * 6f))
        }
    }
}

@Composable
fun Thumbnail(uri: Uri, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = Wallhop.colors
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
        }
    }
    Box(
        modifier
            .size(size)
            .clip(rounded(Radius.s))
            .background(colors.backgroundElevated2)
            .border(0.5.dp, colors.separator, rounded(Radius.s)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(WIcons.File, null, tint = colors.labelSecondary)
        }
    }
}

/** Gradient avatar with initials. */
@Composable
fun Avatar(name: String?, size: Dp = 40.dp) {
    val palette = listOf(Color(0xFF5AC8FA), Color(0xFF5856D6), Color(0xFFFF9500), Color(0xFF34C759), Color(0xFFFF2D55), Color(0xFF8E8E93))
    val color = palette[abs((name ?: "").hashCode()) % palette.size]
    val initials = name?.split(" ")?.take(2)?.mapNotNull { it.firstOrNull()?.uppercaseChar() }?.joinToString("").orEmpty()
    Box(Modifier.size(size).clip(capsule()).background(color), contentAlignment = Alignment.Center) {
        Text(initials.ifEmpty { "#" }, style = Type.headline, color = Color.White)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "Unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/**
 * Copies a one-time code. Marked sensitive, so Android hides it in the clipboard preview and
 * keyboards don't suggest it later.
 */
fun copyCode(context: android.content.Context, code: String) {
    val clip = android.content.ClipData.newPlainText("Code", code)
    clip.description.extras = android.os.PersistableBundle().apply {
        // ClipDescription.EXTRA_IS_SENSITIVE (Android 13); older versions ignore it.
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
}

/** "482913" → "482 913". */
fun spacedCode(code: String): String = app.wallhop.core.Wording.spaced(code)
