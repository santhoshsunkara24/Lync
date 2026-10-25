package app.wallhop.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wallhop.R
import app.wallhop.core.Features
import app.wallhop.ui.components.ButtonStyle
import app.wallhop.ui.components.IconTile
import app.wallhop.ui.components.WButton
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.Wallhop

/*
 * Asking for a permission after onboarding: one feature at a time, when it's wanted. Lync's own
 * sheet comes first (what it looks like, what Lync sees and never sees), then Android's prompt.
 * "Not now" is as easy as yes, and a feature Android won't ask about again gets the way to
 * Settings, never a repeat.
 */

/** What Lync says before Android's prompt for a feature (also Play's prominent disclosure). */
class Ask(
    val key: Features.Key,
    val title: String,
    val line: String,
    val sees: List<String>,
    val never: String,
    val icon: ImageVector,
    @DrawableRes val preview: Int? = null,
)

fun askFor(key: Features.Key): Ask? = when (key) {
    Features.Key.Codes -> Ask(
        key, "Codes on your Mac", "When a code arrives by text, it's on your Mac, ready to paste.",
        listOf("Lync reads incoming texts to find the code"), "Never the rest of your messages",
        WIcons.Code, R.drawable.onb_code,
    )
    Features.Key.Calls -> Ask(
        key, "Calls on your Mac", "When your phone rings, your Mac shows who it is.",
        listOf("The caller's name and number", "Contacts for names, if you allow it"), "Never your call audio",
        WIcons.Call, R.drawable.onb_call,
    )
    Features.Key.Texts -> Ask(
        key, "Texts on your Mac", "Read and reply to your texts from your Mac.",
        listOf("Your texts, to show and send them on your Mac", "Contacts for names, if you allow it"), "Never stored online",
        WIcons.Chats,
    )
    Features.Key.AppNotifications -> Ask(
        key, "Notifications on your Mac", "Notifications from the apps you pick show up on your Mac.",
        listOf("Only the apps you choose"), "Never sent anywhere but your Mac",
        WIcons.BellRinging,
    )
    Features.Key.Bluetooth -> Ask(
        key, "Bluetooth backup", "When your Mac is nearby but off your Wi‑Fi, codes, calls and notifications still get there.",
        listOf("Your paired Mac, when it's nearby"), "Never your location",
        WIcons.Bluetooth,
    )
    else -> null
}

/** The sheet before Android's prompt. */
@Composable
fun ColumnScope.AskSheet(ask: Ask, onContinue: () -> Unit, onLater: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    val colors = Wallhop.colors
    AskPreview(ask)
    Spacer(Modifier.height(18.dp))
    Text(ask.title, style = Type.pageTitle.copy(fontSize = 26.sp, lineHeight = 32.sp), color = colors.label,
        modifier = Modifier.semantics { heading() })
    Text(ask.line, style = Type.body, color = colors.labelSecondary, modifier = Modifier.padding(top = 6.dp))
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ask.sees.forEach { Fact(WIcons.CheckCircle, colors.tint, it, colors.label) }
        Fact(WIcons.XCircle, colors.labelTertiary, ask.never, colors.labelSecondary)
    }
    PrivateChip(Modifier.padding(top = 16.dp))
    extra?.invoke()
    Spacer(Modifier.height(20.dp))
    WButton("Continue", onContinue, style = ButtonStyle.Filled, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    WButton("Not now", onLater, style = ButtonStyle.Glass, modifier = Modifier.fillMaxWidth())
}

/** Android won't ask again: three steps to the switch in Settings, and a plain way to leave it off. */
@Composable
fun ColumnScope.BlockedSheet(feature: String, permission: String, onOpen: () -> Unit, onKeepOff: () -> Unit) {
    val colors = Wallhop.colors
    IconTile(WIcons.Lock, colors.orange, size = 56.dp)
    Spacer(Modifier.height(16.dp))
    Text("$feature is off", style = Type.pageTitle.copy(fontSize = 26.sp, lineHeight = 32.sp), color = colors.label,
        modifier = Modifier.semantics { heading() })
    Text("Android won't ask again, so it's a quick switch in Settings.", style = Type.body, color = colors.labelSecondary,
        modifier = Modifier.padding(top = 6.dp))
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Tap Open Settings", "Go to Permissions › $permission", "Choose Allow, then come back").forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(colors.fill), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = Type.footnote, color = colors.label)
                }
                Spacer(Modifier.width(12.dp))
                Text(step, style = Type.callout, color = colors.label)
            }
        }
    }
    Spacer(Modifier.height(22.dp))
    WButton("Open Settings", onOpen, style = ButtonStyle.Filled, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    WButton("Keep it off", onKeepOff, style = ButtonStyle.Glass, modifier = Modifier.fillMaxWidth())
}

/** "Only between your phone and Mac": where it goes, said once and quietly. */
@Composable
fun PrivateChip(modifier: Modifier = Modifier) {
    val colors = Wallhop.colors
    Row(
        modifier.clip(CircleShape).background(colors.tint.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WIcons.Lock, null, tint = colors.tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text("Only between your phone and Mac, encrypted", style = Type.footnote, color = colors.tint)
    }
}

@Composable
private fun Fact(icon: ImageVector, tint: Color, text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Type.callout, color = color, textAlign = TextAlign.Start)
    }
}

/** What it'll look like: Lync's real card dropping from the notch, or the feature's icon in a soft light. */
@Composable
private fun AskPreview(ask: Ask) {
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    val drop = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { drop.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 280f)) }
    val tint = colors.tint
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (ask.preview != null) 150.dp else 96.dp)
            .drawBehind {
                drawRect(Brush.radialGradient(
                    listOf(tint.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(size.width / 2, 0f), radius = size.width * 0.6f,
                ))
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.2f), Color.Transparent)),
                    Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx(),
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val layer = Modifier.graphicsLayer {
            val p = drop.value
            scaleX = 0.32f + 0.68f * p
            scaleY = 0.18f + 0.82f * p
            alpha = (p * 6f).coerceIn(0f, 1f)
            transformOrigin = TransformOrigin(0.5f, 0f)
        }
        if (ask.preview != null) {
            Image(painterResource(ask.preview), null, layer.width(300.dp))
        } else {
            Box(layer.padding(top = 20.dp)) { IconTile(ask.icon, tint, size = 56.dp) }
        }
    }
}

/** A feature Home can suggest, one at a time. */
class Suggestion(val key: Features.Key, val title: String, val message: String, val icon: ImageVector)

/** The order Home suggests features in. */
val SuggestionOrder = listOf(Features.Key.Codes, Features.Key.Calls, Features.Key.Texts, Features.Key.AppNotifications)

fun suggestionFor(key: Features.Key): Suggestion? = when (key) {
    Features.Key.Codes -> Suggestion(key, "Get codes on your Mac", "Ready to paste, without picking up your phone.", WIcons.Code)
    Features.Key.Calls -> Suggestion(key, "See calls on your Mac", "Who's calling, without picking up your phone.", WIcons.Call)
    Features.Key.Texts -> Suggestion(key, "Text from your Mac", "Read and reply without reaching for your phone.", WIcons.Chats)
    Features.Key.AppNotifications -> Suggestion(key, "Notifications on your Mac", "Only from the apps you pick.", WIcons.BellRinging)
    else -> null
}
