package app.wallhop.ui.screens



import app.wallhop.ui.design.WIcons
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.wallhop.link.DiscoveredMac
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkState
import app.wallhop.ui.components.ActivityIndicator
import app.wallhop.ui.components.AlertAction
import app.wallhop.ui.components.AlertSpec
import app.wallhop.ui.components.Overlays
import app.wallhop.ui.design.Fades
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.pressScale
import app.wallhop.ui.design.tappable
import kotlinx.coroutines.delay

/** First run: find a Mac on this Wi-Fi (DESIGN.md §6.2). */
@Composable
fun ConnectScreen(
    macs: List<DiscoveredMac>,
    problem: LinkState?,
    overlays: Overlays,
    /** [typed]: the address was entered by hand, so a failure can point at it. */
    onPair: (host: String, port: Int, name: String, typed: Boolean) -> Unit,
    onDismissProblem: () -> Unit,
    onScan: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val colors = Wallhop.colors
    val context = androidx.compose.ui.platform.LocalContext.current

    // A failed pairing needs an answer: why, what to do, and the button that does it.
    LaunchedEffect(problem) {
        if (problem is LinkState.PairingFailed) {
            val failed = problem.problem
            overlays.showAlert(AlertSpec(
                title = failed.title,
                message = failed.action,
                actions = buildList {
                    failed.fixes.forEach { fix ->
                        when (fix) {
                            app.wallhop.core.Fix.TryAgain -> if (onRetry != null) add(AlertAction(fix.label, default = isEmpty()) { onRetry() })
                            // Opening Wi‑Fi or the releases page leaves the problem up to come back to.
                            else -> add(AlertAction(fix.label, default = isEmpty()) {
                                onDismissProblem()
                                app.wallhop.core.Open.run(context, fix)
                            })
                        }
                    }
                    add(AlertAction("Dismiss") { onDismissProblem() })
                },
                onBack = onDismissProblem,
            ))
        }
    }
    // No Wi‑Fi: say so straight away, rather than searching for a Mac that can't be found.
    val wifi by produceState(app.wallhop.core.LocalNetwork.joined(context)) {
        while (true) {
            value = app.wallhop.core.LocalNetwork.joined(context)
            delay(2_000)
        }
    }
    val radioOn = remember(wifi) { app.wallhop.core.LocalNetwork.radioOn(context) }
    // Nothing found for a while: say what usually helps, and offer the address.
    var searchingLong by remember { mutableStateOf(false) }
    LaunchedEffect(macs.isEmpty()) {
        searchingLong = false
        if (macs.isEmpty()) {
            delay(15_000)
            searchingLong = true
        }
    }

    // One object (the camera finding the Mac's code), one line, one button. Macs found on this
    // Wi-Fi show as quiet chips under it; the address is a quiet link for when nothing shows.
    OnboardingFrame(
        title = when {
            wifi -> "Scan the code on your Mac."
            radioOn -> "Not on Wi‑Fi."
            else -> "Wi‑Fi is off."
        },
        body = when {
            !wifi -> if (radioOn) "Join the same Wi‑Fi as your Mac." else "Turn it on to find your Mac."
            searchingLong && macs.isEmpty() -> "Check Lync is open on your Mac and both are on the same Wi‑Fi."
            else -> "Open Lync from your Mac's notch to show it."
        },
        hero = {
            ScanHero()
            if (macs.isNotEmpty()) {
                // A Mac found on this Wi‑Fi: pair with it without the QR (both show the same code).
                // A real button (fill, edge, verb, chevron), not a status chip.
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    macs.take(2).forEach { mac ->
                        val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(
                            Modifier
                                .heightIn(min = 48.dp)
                                .pressScale(source)
                                .clip(capsule())
                                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f))
                                .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f), capsule())
                                .tappable(source) { onPair(mac.host, mac.port, mac.name, false) }
                                .semantics { role = androidx.compose.ui.semantics.Role.Button }
                                .padding(start = 16.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(WIcons.Laptop, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                            Text("Pair with ${mac.name}", style = Type.subheadline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                color = androidx.compose.ui.graphics.Color.White, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            Icon(WIcons.ChevronRight, null, tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        },
        foot = {
            if (!wifi) {
                OnboardingPill(app.wallhop.core.Fix.WifiSettings.label, { app.wallhop.core.Open.run(context, app.wallhop.core.Fix.WifiSettings) }, Modifier.fillMaxWidth())
            } else {
                OnboardingPill("Scan", onScan, Modifier.fillMaxWidth())
            }
            QuietButton("Get Lync for Mac", { shareMacLink(context) }, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
        },
    )
}

@Composable
fun PairingScreen(
    state: LinkState.Pairing,
    onCancel: () -> Unit,
    success: Boolean = false,
    /** The QR code just scanned, when this pairing came from one: the link forms out of it. */
    qrText: String? = null,
    /** Where that code was on screen (window pixels): it comes forward from there. */
    qrFrom: androidx.compose.ui.geometry.Rect? = null,
    onLinked: () -> Unit = {},
) {
    if (qrText != null && state.code == null) {
        app.wallhop.ui.components.QrLinking(qrText, state.macName, success, onCancel = onCancel, onFinished = onLinked, from = qrFrom)
        return
    }
    // Back stops the pairing rather than leaving it running behind the home screen.
    BackHandler(enabled = !success, onBack = onCancel)
    androidx.compose.animation.AnimatedContent(
        targetState = success,
        transitionSpec = { fadeIn(Fades.fadeIn()) togetherWith fadeOut(Fades.fadeOut()) },
        label = "pairing",
    ) { paired ->
        if (paired) {
            // The link snaps shut and a ring of light runs round it; the app moves on by itself.
            LinkedMoment(state.macName)
        } else {
            val code = state.code
            OnboardingFrame(
                title = if (code != null) "Same code on your Mac?" else "Connecting to ${state.macName}…",
                body = if (code != null) "If it matches, click Pair there." else null,
                onBack = onCancel,
                action = "Cancel" to onCancel,
                hero = {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (code != null) {
                            CodeTiles(code)
                            Spacer(Modifier.height(22.dp))
                            // The Mac has two minutes to say yes; show what's left.
                            val left by produceState(PairingWindowMs, code) {
                                val start = android.os.SystemClock.elapsedRealtime()
                                while (value > 0) {
                                    value = (PairingWindowMs - (android.os.SystemClock.elapsedRealtime() - start)).coerceAtLeast(0)
                                    delay(1_000 - (value % 1_000).coerceAtMost(999))
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.semantics {
                                    contentDescription = "Waiting for your Mac, about ${(left + 59_999) / 60_000} minute${if (left > 60_000) "s" else ""} left"
                                },
                            ) {
                                ActivityIndicator(size = 14.dp)
                                Text(
                                    "Waiting for your Mac · ${app.wallhop.core.Wording.countdown(left)}",
                                    style = Type.subheadline, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.56f),
                                )
                            }
                        } else {
                            app.wallhop.ui.components.LinkCatch(false, Modifier.size(130.dp), shaded = false)
                        }
                    }
                },
            )
        }
    }
}

/** How long the Mac has to accept pairing (the phone waits 130 s; the countdown shows two minutes). */
private const val PairingWindowMs = 120_000L

/** A row joining a plain list: it opens up and fades in; leaving reverses it (DESIGN.md §7.2). */
internal fun rowEnter(reduceMotion: Boolean) =
    if (reduceMotion) fadeIn(Fades.fadeIn()) else expandVertically(Springs.smooth()) + fadeIn(Fades.fadeIn())

internal fun rowExit(reduceMotion: Boolean) =
    // Quick and without bounce: a removed row closes up before attention lands on it.
    if (reduceMotion) fadeOut(Fades.fadeOut()) else shrinkVertically(androidx.compose.animation.core.spring(1f, 500f)) + fadeOut(Fades.fadeOut())

/** Shares the Mac app's download link, so it can be opened on the Mac. */
internal fun shareMacLink(context: android.content.Context) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Lync for Mac")
        .putExtra(android.content.Intent.EXTRA_TEXT, "Get Lync for your Mac: ${app.wallhop.core.Wording.RELEASES_URL}")
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Send the Mac link")) }
        .onFailure { app.wallhop.core.Open.link(context, app.wallhop.core.Wording.RELEASES_URL) }
}
