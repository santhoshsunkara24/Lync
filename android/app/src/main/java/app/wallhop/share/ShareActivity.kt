package app.wallhop.share

import app.wallhop.ui.design.WIcons
import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
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
import app.wallhop.ui.design.shake
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
        // Each send has its own id, so the sheet follows its own transfer, never an earlier one.
        // Kept across rotation, which must not send again.
        requestId.value = savedInstanceState?.getString(KEY_REQUEST) ?: UUID.randomUUID().toString()
        if (savedInstanceState == null && link.pairedMac != null) send(link, shared)
        setContent {
            WallhopTheme {
                ShareSheet(link, shared, requestId.value, frosted = blurs, onClose = { finish() }, onRetry = {
                    requestId.value = UUID.randomUUID().toString()
                    send(link, shared)
                })
            }
        }
    }

    private val requestId = mutableStateOf("")

    private fun send(link: LinkManager, shared: Shared) {
        when (shared) {
            is Shared.Files -> LinkService.sendFiles(this, shared.uris, requestId.value)
            is Shared.Text -> {
                LinkService.start(this)
                ShareSends.send(this, link, requestId.value, shared.text)
            }
            Shared.Nothing -> Unit
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_REQUEST, requestId.value)
    }

    override fun onStart() {
        super.onStart()
        LinkService.screensOpen++
    }

    override fun onStop() {
        super.onStop()
        LinkService.screensOpen = (LinkService.screensOpen - 1).coerceAtLeast(0)
        // "Run in background" off: the service stays only while a transfer is still going.
        if (!isChangingConfigurations) LinkService.stopIfIdle(this)
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

    private companion object {
        const val KEY_REQUEST = "request"
    }
}

/**
 * Shared text and links are sent here, outside the sheet: closing the sheet or rotating doesn't
 * cancel a send, and a send isn't repeated. Results by request id; null while sending.
 */
private object ShareSends {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val results = MutableStateFlow<Map<String, Boolean?>>(emptyMap())

    fun send(context: Context, link: LinkManager, id: String, text: String) {
        if (id in results.value) return
        val app = context.applicationContext
        results.update { it + (id to null) }
        scope.launch {
            val ok = if (isSingleUrl(text)) link.openOnMac(text.trim()) else link.sendText(text)
            results.update { it + (id to ok) }
            LinkService.stopIfIdle(app)
        }
    }
}

private enum class Outcome { Working, Done, Failed }

@Composable
private fun ShareSheet(
    link: LinkManager,
    shared: ShareActivity.Shared,
    requestId: String,
    frosted: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = Wallhop.colors
    val haptics = rememberHaptics()
    val state by link.state.collectAsStateWithLifecycle()
    val anyTransfer by link.transfer.collectAsStateWithLifecycle()
    // Only this share's own transfer; another one (an earlier share, a send from the app) is ignored.
    val transfer = anyTransfer?.takeIf { it.id == requestId }
    // Where it got to, in case the app clears the finished transfer while the sheet is still up.
    var lastPhase by rememberSaveable(requestId) { mutableStateOf<TransferPhase?>(null) }
    LaunchedEffect(transfer?.phase) { transfer?.phase?.let { lastPhase = it } }
    val results by ShareSends.results.collectAsStateWithLifecycle()
    val textResult = results[requestId]
    val macName = link.pairedMac?.name
    val isLink = shared is ShareActivity.Shared.Text && isSingleUrl(shared.text)

    val outcome = when (shared) {
        is ShareActivity.Shared.Files -> when (transfer?.phase ?: lastPhase) {
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
    // Some files sent, some unreadable: Done, with a note worth reading before the sheet goes.
    val partial = (shared as? ShareActivity.Shared.Files)?.let { transfer?.message }
    LaunchedEffect(outcome) {
        when (outcome) {
            Outcome.Done -> {
                haptics.perform(Haptic.Success)
                delay(if (partial != null) 4_000 else 1_300)
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
                // No grab handle: this sheet doesn't drag. Cancel, Hide, back or a tap outside close it.
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) {
                    val sendingText = shared is ShareActivity.Shared.Text && outcome == Outcome.Working
                    Box(Modifier.align(Alignment.CenterStart)) {
                        // A text that's on its way can't be called back, so it just carries on.
                        if (outcome != Outcome.Done && !sendingText) TextAction("Cancel", onClick = {
                            if (shared is ShareActivity.Shared.Files && outcome == Outcome.Working) link.cancelTransfer()
                            close()
                        })
                    }
                    Text(
                        macName ?: "Send to Mac", style = Type.headline, color = colors.label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 76.dp),
                    )
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        if (outcome == Outcome.Working && shared !is ShareActivity.Shared.Nothing) {
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
                                is ShareActivity.Shared.Files -> transfer?.takeIf { it.items.isNotEmpty() }?.let { LinkManager.summary(it.items) }
                                    ?: if (shared.uris.size == 1) "1 item" else "${shared.uris.size} items"
                                is ShareActivity.Shared.Text -> if (isLink) "Link" else "Text"
                                ShareActivity.Shared.Nothing -> "Nothing to send"
                            },
                            style = Type.headline,
                            color = colors.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // What's going across: the text itself (first two lines), or the files' size.
                        val detail = when (shared) {
                            is ShareActivity.Shared.Text -> shared.text.trim()
                            is ShareActivity.Shared.Files -> transfer?.takeIf { it.totalBytes > 0 }?.let { formatBytes(it.totalBytes) }
                            ShareActivity.Shared.Nothing -> null
                        }
                        if (detail != null) {
                            Text(detail, style = Type.subheadline, color = colors.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                AnimatedContent(
                    targetState = outcome,
                    // A failure shakes once, with the error haptic.
                    modifier = Modifier.shake(outcome == Outcome.Failed),
                    transitionSpec = { (fadeIn() + scaleIn(Springs.bouncy(), initialScale = 0.9f)) togetherWith fadeOut() },
                    label = "outcome",
                ) { result ->
                    when (result) {
                        Outcome.Done -> Column {
                            Row(
                                Modifier.fillMaxWidth().height(50.dp).clip(capsule()).background(colors.green),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(WIcons.CheckCircle, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(if (isLink) "Opened on your Mac" else "Copied to your Mac", style = Type.headline, color = Color.White)
                            }
                            if (partial != null) {
                                Text(partial, style = Type.footnote, color = colors.orange, modifier = Modifier.padding(top = 10.dp))
                            }
                        }
                        Outcome.Failed -> Column {
                            Text(
                                when (shared) {
                                    ShareActivity.Shared.Nothing -> "There's nothing here Lync can send."
                                    is ShareActivity.Shared.Files -> transfer?.message
                                        ?: "Couldn't reach your Mac. Check it's awake and on the same Wi-Fi."
                                    is ShareActivity.Shared.Text -> "Couldn't reach your Mac. Check it's awake and on the same Wi-Fi."
                                },
                                style = Type.footnote,
                                color = colors.red,
                            )
                            if (shared != ShareActivity.Shared.Nothing) {
                                Spacer(Modifier.height(12.dp))
                                WButton("Try again", onClick = onRetry, style = ButtonStyle.Filled, modifier = Modifier.fillMaxWidth())
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
