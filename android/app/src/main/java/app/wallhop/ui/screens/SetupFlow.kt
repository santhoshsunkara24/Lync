package app.wallhop.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wallhop.core.Features
import app.wallhop.ui.components.ButtonStyle
import app.wallhop.ui.components.IconTile
import app.wallhop.ui.components.TextAction
import app.wallhop.ui.components.WButton
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.WIcons
import app.wallhop.ui.design.Wallhop

/** One step of the guided setup: what the feature does and why, which doubles as Play's prominent disclosure. */
class SetupStep(val key: Features.Key, val icon: ImageVector, val title: String, val message: String)

/** The guided setup's steps, in order: Codes, Phone notifications on Mac, Calls, Texts, Run in background, Alerts. */
val SetupSteps = listOf(
    SetupStep(
        Features.Key.Codes, WIcons.Code, "Codes",
        "Lync reads incoming texts only to find one-time codes, and sends just the code to your paired Mac, ready to paste.",
    ),
    SetupStep(
        Features.Key.AppNotifications, WIcons.BellRinging, "Phone notifications on Mac",
        "Lync reads notifications from the apps you pick and shows them only on your paired Mac, so you can read and answer them there. It also finds codes that arrive in other apps.",
    ),
    SetupStep(
        Features.Key.Calls, WIcons.Call, "Calls",
        "Lync shows who's calling on your paired Mac so you can answer or decline there. Call details only go to your Mac. Contacts is optional: without it you'll see numbers instead of names.",
    ),
    SetupStep(
        Features.Key.Texts, WIcons.Chats, "Texts",
        "Lync reads and sends your text messages so you can use them on your paired Mac. They only go to your Mac. Contacts is optional.",
    ),
    SetupStep(
        Features.Key.Background, WIcons.BatteryCharging, "Run in background",
        "Lets Lync stay connected while it's closed, so codes and files reach your Mac without opening the app.",
    ),
    SetupStep(
        Features.Key.Notifications, WIcons.Bell, "Alerts on this phone",
        "Lync lets you know when files or text arrive from your Mac, and shows progress while it sends.",
    ),
)

/**
 * The guided setup (DESIGN.md §6.6): one full-screen step per feature, "2 of 6", Turn on or
 * Skip. [extra] adds step-specific help under the message (notification access steps).
 */
@Composable
fun GuidedSetup(
    index: Int,
    onTurnOn: (SetupStep) -> Unit,
    onSkip: (SetupStep) -> Unit,
    onClose: () -> Unit,
    extra: @Composable (SetupStep) -> Unit = {},
    turnOnLabel: (SetupStep) -> String = { "Turn on" },
) {
    val home = Wallhop.home
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    BackHandler(onBack = onClose)
    val step = SetupSteps.getOrNull(index) ?: return
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to home.top, 0.4f to home.mid, 0.75f to colors.background))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} of ${SetupSteps.size}", style = Type.subheadline, color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.weight(1f))
                TextAction("Not now", onClick = onClose, color = Color.White)
            }
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    if (reduceMotion) fadeIn(tween(Durations.Quick)) togetherWith fadeOut(tween(Durations.Quick))
                    else (slideInHorizontally(Springs.smooth()) { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally(Springs.smooth()) { -it / 3 } + fadeOut(tween(Durations.Quick)))
                },
                label = "setupStep",
            ) { shown ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    IconTile(shown.icon, home.pale, size = 72.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(shown.title, style = Type.largeTitle, color = Color.White, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(12.dp))
                    Text(shown.message, style = Type.body, color = Color.White.copy(alpha = 0.78f))
                    extra(shown)
                }
            }
            WButton(turnOnLabel(step), { onTurnOn(step) }, style = ButtonStyle.Filled, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            WButton("Skip", { onSkip(step) }, style = ButtonStyle.Glass, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Numbered help lines, e.g. the notification access steps. */
@Composable
fun SetupHelp(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, line ->
            Row {
                Text("${i + 1}.", style = Type.subheadline, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.widthIn(min = 22.dp))
                Text(line, style = Type.subheadline, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Start)
            }
        }
    }
}
