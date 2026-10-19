package app.wallhop.share

import app.wallhop.ui.design.WIcons
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wallhop.extras.isSingleUrl
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkService
import app.wallhop.link.LinkState
import app.wallhop.link.TransferPhase
import app.wallhop.ui.MainActivity
import app.wallhop.ui.components.ButtonStyle
import app.wallhop.ui.components.HopDots
import app.wallhop.ui.components.ProgressBar
import app.wallhop.ui.components.TextAction
import app.wallhop.ui.components.ThumbnailStack
import app.wallhop.ui.components.WButton
import app.wallhop.ui.components.formatBytes
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Radius
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.WallhopTheme
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.rounded
import app.wallhop.ui.design.tappable
import kotlinx.coroutines.delay

/**
 * "Send to Mac" from any app's share menu (DESIGN.md §6.8). This window floats over another
 * app, so it can't refract that app's pixels; on Android 12+ the system blurs what's behind
 * instead and the sheet is frosted. The transfer runs in the background service.
 */
class ShareActivity : ComponentActivity() {

    sealed interface Shared {
        data class Files(val uris: List<Uri>) : Shared
        data class Text(val text: String) : Shared
        data object Nothing : Shared
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager.isCrossWindowBlurEnabled
        if (blurs) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = (28 * resources.displayMetrics.density).toInt() }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.18f)

        val link = LinkManager.get(this)
        val shared = parse(intent)
        if (savedInstanceState == null && link.pairedMac != null) {
            when (shared) {
                is Shared.Files -> LinkService.sendFiles(this, shared.uris)
                else -> LinkService.start(this)
            }
        }
        setContent {
            WallhopTheme {
                ShareSheet(link, shared, frosted = blurs, onClose = { finish() }, onRetry = {
                    if (shared is Shared.Files) LinkService.sendFiles(this, shared.uris)
                })
            }
        }
    }

    private fun parse(intent: Intent): Shared {
        val uris = buildList {
            when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE ->
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            }
            if (isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add) }
        }
        if (uris.isNotEmpty()) return Shared.Files(uris)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        return if (!text.isNullOrBlank()) Shared.Text(text) else Shared.Nothing
    }
}

private enum class Outcome { Working, Done, Failed }

@Composable
private fun ShareSheet(
    link: LinkManager,
    shared: ShareActivity.Shared,
    frosted: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = Wallhop.colors
    val haptics = rememberHaptics()
    val state by link.state.collectAsStateWithLifecycle()
    val transfer by link.transfer.collectAsStateWithLifecycle()
    val macName = link.pairedMac?.name
    val isLink = shared is ShareActivity.Shared.Text && isSingleUrl(shared.text)

    var textResult by remember { mutableStateOf<Boolean?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(shared, attempt) {
        if (shared is ShareActivity.Shared.Text && macName != null) {
            textResult = null
            textResult = if (isLink) link.openOnMac(shared.text.trim()) else link.sendText(shared.text)
        }
    }

    val outcome = when (shared) {
        is ShareActivity.Shared.Files -> when (transfer?.phase) {
            TransferPhase.Done -> Outcome.Done
            TransferPhase.Failed -> Outcome.Failed
            else -> Outcome.Working
        }
        is ShareActivity.Shared.Text -> when (textResult) {
            true -> Outcome.Done
            false -> Outcome.Failed
            null -> Outcome.Working
        }
        ShareActivity.Shared.Nothing -> Outcome.Failed
    }

    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        closing = true
        visible.targetState = false
    }
    LaunchedEffect(visible.currentState, visible.isIdle) {
        if (closing && visible.isIdle && !visible.currentState) onClose()
    }
    LaunchedEffect(outcome) {
        when (outcome) {
            Outcome.Done -> {
                haptics.perform(Haptic.Success)
                delay(1_300)
                close()
            }
            Outcome.Failed -> haptics.perform(Haptic.Failure)
            Outcome.Working -> Unit
        }
    }
    BackHandler { close() }

    Box(Modifier.fillMaxSize()) {
        val outside = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize().tappable(outside) { close() })

        AnimatedVisibility(
            visibleState = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Springs.smooth()) { it },
            exit = slideOutVertically(Springs.smooth()) { it },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp)
                    .clip(rounded(Radius.sheet))
                    .background(colors.backgroundElevated.copy(alpha = if (frosted) 0.84f else 1f))
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                Box(
                    Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally).size(36.dp, 5.dp)
                        .clip(capsule()).background(colors.labelTertiary),
                )
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    Box(Modifier.align(Alignment.CenterStart)) {
                        if (outcome != Outcome.Done) TextAction("Cancel", onClick = {
                            if (shared is ShareActivity.Shared.Files && outcome == Outcome.Working) link.cancelTransfer()
                            close()
                        })
                    }
                    Text("Send to Mac", style = Type.headline, color = colors.label, modifier = Modifier.align(Alignment.Center))
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        if (outcome == Outcome.Working && shared is ShareActivity.Shared.Files) {
                            TextAction("Hide", bold = true, onClick = { close() })
                        }
                    }
                }

                if (macName == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Pair a Mac first", style = Type.title3, color = colors.label)
                    Text("Open Lync, pair it with your Mac, then share again.", style = Type.subheadline,
                        color = colors.labelSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp))
                    val context = androidx.compose.ui.platform.LocalContext.current
                    WButton("Open Lync", onClick = {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        close()
                    }, style = ButtonStyle.Filled, modifier = Modifier.fillMaxWidth())
                    return@Column
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (shared) {
                        is ShareActivity.Shared.Files -> ThumbnailStack(shared.uris)
                        else -> Box(
                            Modifier.size(52.dp).clip(rounded(Radius.s)).background(colors.backgroundElevated2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (isLink) WIcons.Link else WIcons.File, null, tint = colors.tint)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (shared) {
                                is ShareActivity.Shared.Files -> transfer?.let { LinkManager.summary(it.items) } ?: "${shared.uris.size} items"
                                is ShareActivity.Shared.Text -> if (isLink) shared.text.trim() else "Text"
                                ShareActivity.Shared.Nothing -> "Nothing to send"
                            },
                            style = Type.headline,
                            color = colors.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(WIcons.Laptop, null, tint = colors.labelSecondary, modifier = Modifier.size(15.dp))
                            Text(" $macName", style = Type.subheadline, color = colors.labelSecondary, maxLines = 1)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                AnimatedContent(
                    targetState = outcome,
                    transitionSpec = { (fadeIn() + scaleIn(Springs.bouncy(), initialScale = 0.9f)) togetherWith fadeOut() },
                    label = "outcome",
                ) { result ->
                    when (result) {
                        Outcome.Done -> Row(
                            Modifier.fillMaxWidth().height(50.dp).clip(capsule()).background(colors.green),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(WIcons.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(if (isLink) "Opened on your Mac" else "Copied to your Mac", style = Type.headline, color = Color.White)
                        }
                        Outcome.Failed -> Column {
                            Text(
                                transfer?.message ?: when {
                                    shared == ShareActivity.Shared.Nothing -> "There's nothing here Lync can send."
                                    else -> "Couldn't reach your Mac. Check it's awake and on the same Wi-Fi."
                                },
                                style = Type.footnote,
                                color = colors.red,
                            )
                            if (shared != ShareActivity.Shared.Nothing) {
                                Spacer(Modifier.height(12.dp))
                                WButton("Try again", onClick = {
                                    if (shared is ShareActivity.Shared.Text) attempt++ else onRetry()
                                }, style = ButtonStyle.Filled, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Outcome.Working -> Column {
                            val sending = transfer?.phase == TransferPhase.Sending
                            Text(
                                when {
                                    shared is ShareActivity.Shared.Text -> "Sending…"
                                    sending -> "Sending over Wi-Fi · ${formatBytes(transfer?.sentBytes ?: 0)} of ${formatBytes(transfer?.totalBytes ?: 0)}"
                                    state is LinkState.Connected -> "Starting…"
                                    else -> "Waiting for your Mac. It sends as soon as both are on the same Wi-Fi."
                                },
                                style = Type.footnote,
                                color = colors.labelSecondary,
                            )
                            Spacer(Modifier.height(12.dp))
                            if (sending) ProgressBar(transfer?.progress) else HopDots(active = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
