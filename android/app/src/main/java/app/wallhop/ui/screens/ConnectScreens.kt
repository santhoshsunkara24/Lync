package app.wallhop.ui.screens

import app.wallhop.ui.design.shake

import androidx.compose.runtime.mutableIntStateOf

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.wallhop.link.DiscoveredMac
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkState
import app.wallhop.ui.components.ActivityIndicator
import app.wallhop.ui.components.AlertAction
import app.wallhop.ui.components.AlertSpec
import app.wallhop.ui.components.Overlays
import app.wallhop.ui.components.TextAction
import app.wallhop.ui.components.WTextField
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
    /** The scanner asked for the address sheet (its camera couldn't start). */
    askAddress: Boolean = false,
    onAddressAsked: () -> Unit = {},
) {
    val colors = Wallhop.colors
    val address = rememberSaveable { mutableStateOf("") }
    val addressError = remember { mutableStateOf<String?>(null) }
    // A typed address isn't a name: until the Mac says its name, it's "your Mac".
    fun enterAddress() = showAddressSheet(overlays, address, addressError) { host, port -> onPair(host, port, "your Mac", true) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(askAddress) {
        if (askAddress) {
            onAddressAsked()
            enterAddress()
        }
    }

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
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    macs.take(2).forEach { mac ->
                        val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Row(
                            Modifier
                                .heightIn(min = 44.dp)
                                .pressScale(source)
                                .clip(capsule())
                                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
                                .tappable(source) { onPair(mac.host, mac.port, mac.name, false) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(WIcons.Laptop, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                            Text(mac.name, style = Type.subheadline.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                color = androidx.compose.ui.graphics.Color.White, maxLines = 1)
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
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                QuietButton("Get Lync for Mac", { shareMacLink(context) })
                QuietButton("Enter address", { enterAddress() })
            }
        },
    )
}

private fun showAddressSheet(
    overlays: Overlays,
    address: MutableState<String>,
    error: MutableState<String?>,
    onConnect: (String, Int) -> Unit,
) {
    error.value = null
    // Connect from the button or the keyboard's Go key; a bad address says why and shakes.
    val shakes = mutableIntStateOf(0)
    fun connect() {
        when (val result = parseMacAddress(address.value)) {
            is AddressInput.Valid -> {
                overlays.dismissSheet(cancelled = false)
                onConnect(result.host, result.port)
            }
            is AddressInput.Invalid -> { error.value = result.message; shakes.intValue++ }
        }
    }
    overlays.showSheet(
        title = "Mac's address",
        leading = { TextAction("Cancel", onClick = { overlays.dismissSheet() }) },
        trailing = {
            TextAction("Connect", bold = true, enabled = address.value.isNotBlank(), onClick = { connect() })
        },
    ) {
        val haptics = app.wallhop.ui.design.rememberHaptics()
        LaunchedEffect(shakes.intValue) { if (shakes.intValue > 0) haptics.perform(app.wallhop.ui.design.Haptic.Failure) }
        // The keyboard comes up once the sheet has settled, not fighting it on the way in.
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(350); runCatching { focus.requestFocus() } }
        Text(
            "On your Mac, open Lync › Settings › Add a phone. The address is under the QR code.",
            style = Type.footnote,
            color = Wallhop.colors.labelSecondary,
        )
        Spacer(Modifier.height(12.dp))
        WTextField(
            address.value, { address.value = it; error.value = null }, "192.168.1.20",
            modifier = Modifier.shake(shakes.intValue % 2 == 1),
            keyboardType = KeyboardType.Uri, label = "Mac's address", error = error.value,
            focusRequester = focus,
            imeAction = androidx.compose.ui.text.input.ImeAction.Go,
            onImeAction = { connect() },
        )
    }
}

/** What was typed in the address field: a host and port to try, or why it can't be used. */
sealed interface AddressInput {
    data class Valid(val host: String, val port: Int) : AddressInput
    data class Invalid(val message: String) : AddressInput
}

/**
 * Reads "192.168.1.20", "192.168.1.20:47474", "mac.local", "fe80::1", "[fe80::1]" or
 * "[fe80::1]:47474". The port is optional and defaults to Lync's.
 */
fun parseMacAddress(input: String): AddressInput {
    val text = input.trim()
    if (text.isEmpty()) return AddressInput.Invalid("Enter your Mac's address.")
    val host: String
    val portText: String?
    when {
        text.startsWith("[") -> {
            val close = text.indexOf(']')
            if (close < 0) return AddressInput.Invalid("That address is missing a closing ].")
            host = text.substring(1, close)
            val rest = text.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return AddressInput.Invalid("That doesn't look like an address.")
            }
            if (!isIpv6(host)) return AddressInput.Invalid("That doesn't look like an address.")
        }
        // Two or more colons and no brackets: a bare IPv6 address, no port.
        text.count { it == ':' } > 1 -> {
            if (!isIpv6(text)) return AddressInput.Invalid("That doesn't look like an address.")
            host = text
            portText = null
        }
        else -> {
            host = text.substringBefore(':')
            portText = text.substringAfter(':', "").takeIf { text.contains(':') }
            if (!isIpv4(host) && !isHostName(host)) return AddressInput.Invalid("That doesn't look like an address.")
        }
    }
    val port = if (portText == null) LinkManager.DEFAULT_PORT else portText.toIntOrNull()
    if (port == null || port !in 1..65535) return AddressInput.Invalid("The port should be a number from 1 to 65535.")
    return AddressInput.Valid(host, port)
}

private fun isIpv4(host: String): Boolean {
    val parts = host.split('.')
    return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() <= 255 }
}

private fun isIpv6(host: String): Boolean {
    val address = host.substringBefore('%') // A zone such as %wlan0 may follow.
    if (address.count { it == ':' } < 2 || address.contains(":::")) return false
    if (address.split("::").size > 2) return false
    return address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
}

private fun isHostName(host: String): Boolean =
    host.isNotEmpty() && host.length <= 253 && !host.all { it.isDigit() || it == '.' } &&
        host.split('.').all { label -> label.isNotEmpty() && label.length <= 63 && label.all { it.isLetterOrDigit() || it == '-' } }

/**
 * The 6-digit code to compare with the Mac (DESIGN.md §6.3). With [success] the Mac said yes:
 * the capsule turns green and a check draws in, just before the app moves on to the tabs.
 */
@Composable
fun PairingScreen(state: LinkState.Pairing, onCancel: () -> Unit, success: Boolean = false) {
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
private fun shareMacLink(context: android.content.Context) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Lync for Mac")
        .putExtra(android.content.Intent.EXTRA_TEXT, "Get Lync for your Mac: ${app.wallhop.core.Wording.RELEASES_URL}")
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Send the Mac link")) }
        .onFailure { app.wallhop.core.Open.link(context, app.wallhop.core.Wording.RELEASES_URL) }
}
