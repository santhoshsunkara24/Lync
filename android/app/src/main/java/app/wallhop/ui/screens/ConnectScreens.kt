package app.wallhop.ui.screens

import app.wallhop.ui.design.WIcons
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wallhop.link.DiscoveredMac
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkState
import app.wallhop.ui.components.ActivityIndicator
import app.wallhop.ui.components.AlertAction
import app.wallhop.ui.components.AlertSpec
import app.wallhop.ui.components.ButtonStyle
import app.wallhop.ui.components.CodeDisplay
import app.wallhop.ui.components.GlassStage
import app.wallhop.ui.components.HopDots
import app.wallhop.ui.components.IconTile
import app.wallhop.ui.components.InsetSection
import app.wallhop.ui.components.ListRow
import app.wallhop.ui.components.Overlays
import app.wallhop.ui.components.TextAction
import app.wallhop.ui.components.WButton
import app.wallhop.ui.components.WTextField
import app.wallhop.ui.design.DrawnCheck
import app.wallhop.ui.design.Fades
import app.wallhop.ui.design.Radius
import app.wallhop.ui.design.confirm
import app.wallhop.ui.design.rememberAnimatedRows
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.design.staggerIn
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Type
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.capsule
import app.wallhop.ui.design.rounded
import kotlinx.coroutines.delay

/** First run: find a Mac on this Wi-Fi (DESIGN.md §6.2). */
@Composable
fun ConnectScreen(
    macs: List<DiscoveredMac>,
    problem: LinkState?,
    overlays: Overlays,
    onPair: (host: String, port: Int, name: String) -> Unit,
    onDismissProblem: () -> Unit,
    onScan: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val colors = Wallhop.colors
    val address = rememberSaveable { mutableStateOf("") }
    val addressError = remember { mutableStateOf<String?>(null) }
    fun enterAddress() = showAddressSheet(overlays, address, addressError) { host, port -> onPair(host, port, host) }

    // A failed pairing needs an answer: try again, or put it away.
    LaunchedEffect(problem) {
        if (problem is LinkState.PairingFailed) {
            overlays.showAlert(AlertSpec(
                title = "Couldn't pair with ${problem.macName}",
                message = problem.reason,
                actions = buildList {
                    if (onRetry != null) add(AlertAction("Try again", default = true) { onRetry() })
                    add(AlertAction("Dismiss") { onDismissProblem() })
                },
                onBack = onDismissProblem,
            ))
        }
    }
    // Nothing found for a while: say what usually helps, and offer the address.
    var searchingLong by remember { mutableStateOf(false) }
    LaunchedEffect(macs.isEmpty()) {
        searchingLong = false
        if (macs.isEmpty()) {
            delay(15_000)
            searchingLong = true
        }
    }

    val reduceMotion = Wallhop.a11y.reduceMotion
    val macRows = rememberAnimatedRows(macs) { "${it.host}:${it.port}" }
    Page(
        title = "Connect your Mac",
        subtitle = "Open Lync on your Mac and keep both on the same Wi-Fi. Pair once and they stay connected.",
        bottomClearance = 32.dp,
    ) {
        item(key = "hero") {
            Column(Modifier.staggerIn(1)) {
                HeroIllustration(active = true)
                Spacer(Modifier.height(28.dp))
            }
        }
        item(key = "scan") {
            Column(Modifier.staggerIn(2)) {
                // The quickest way: scan the code in the Mac's menu bar panel.
                WButton("Pair with QR code", onScan, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Filled, icon = WIcons.QrCode)
                Spacer(Modifier.height(28.dp))
            }
        }
        item(key = "macs") {
            Column(Modifier.staggerIn(3)) {
                SectionHeader("Macs on this Wi-Fi") { ActivityIndicator(size = 14.dp) }
                InsetSection(dividerInset = 57.dp) {
                    if (macRows.isEmpty()) {
                        row {
                            ListRow(
                                title = "Looking for Macs…",
                                // The line above the list already says to open Lync on the Mac; after a
                                // while without results, say what usually fixes it.
                                subtitle = if (searchingLong) "Check both are on the same Wi-Fi, or enter the address below." else null,
                                leading = { IconTile(WIcons.Laptop, colors.gray) },
                            )
                        }
                        if (searchingLong) {
                            row {
                                ListRow(
                                    title = "Enter the Mac's address",
                                    leading = { IconTile(WIcons.Wifi, colors.indigo) },
                                    chevron = true,
                                    onClick = { enterAddress() },
                                )
                            }
                        }
                    } else {
                        // Macs come and go as they're found; each one slides in or out.
                        macRows.forEach { entry ->
                            val mac = entry.item
                            row {
                                AnimatedVisibility(entry.visible, enter = rowEnter(reduceMotion), exit = rowExit(reduceMotion)) {
                                    ListRow(
                                        title = mac.name,
                                        subtitle = mac.host,
                                        leading = { IconTile(WIcons.Laptop, colors.gray) },
                                        chevron = true,
                                        onClick = { onPair(mac.host, mac.port, mac.name) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "manual") {
            Column(Modifier.staggerIn(4)) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                    TextAction("Enter the Mac's address instead", onClick = { enterAddress() })
                }
            }
        }
    }
}

private fun showAddressSheet(
    overlays: Overlays,
    address: MutableState<String>,
    error: MutableState<String?>,
    onConnect: (String, Int) -> Unit,
) {
    error.value = null
    overlays.showSheet(
        title = "Mac's address",
        leading = { TextAction("Cancel", onClick = { overlays.dismissSheet() }) },
        trailing = {
            TextAction("Connect", bold = true, enabled = address.value.isNotBlank(), onClick = {
                when (val result = parseMacAddress(address.value)) {
                    is AddressInput.Valid -> {
                        overlays.dismissSheet(cancelled = false)
                        onConnect(result.host, result.port)
                    }
                    is AddressInput.Invalid -> error.value = result.message
                }
            })
        },
    ) {
        Text(
            "On your Mac, open Lync › Settings › Add a phone. The address is under the QR code.",
            style = Type.footnote,
            color = Wallhop.colors.labelSecondary,
        )
        Spacer(Modifier.height(12.dp))
        WTextField(
            address.value, { address.value = it; error.value = null }, "192.168.1.20",
            keyboardType = KeyboardType.Uri, label = "Mac's address", error = error.value,
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

/** Phone · hopping dots · laptop, on a large rounded card. */
@Composable
fun HeroIllustration(active: Boolean, modifier: Modifier = Modifier) {
    val colors = Wallhop.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(rounded(Radius.xl))
            .background(colors.backgroundElevated)
            .padding(horizontal = 28.dp, vertical = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WIcons.Phone, null, tint = colors.label, modifier = Modifier.size(34.dp))
        HopDots(active = active, modifier = Modifier.weight(1f).padding(horizontal = 18.dp))
        Icon(WIcons.Laptop, null, tint = colors.label, modifier = Modifier.size(40.dp))
    }
}

/**
 * The 6-digit code to compare with the Mac (DESIGN.md §6.3). With [success] the Mac said yes:
 * the capsule turns green and a check draws in, just before the app moves on to the tabs.
 */
@Composable
fun PairingScreen(state: LinkState.Pairing, onCancel: () -> Unit, success: Boolean = false) {
    val colors = Wallhop.colors
    val reduceMotion = Wallhop.a11y.reduceMotion
    val haptics = rememberHaptics()
    LaunchedEffect(success) { if (success) haptics.confirm() }
    // Back stops the pairing rather than leaving it running behind the home screen.
    BackHandler(enabled = !success, onBack = onCancel)
    GlassStage(
        modifier = Modifier.fillMaxSize(),
        background = colors.background,
        content = {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AnimatedContent(
                    targetState = state.code,
                    transitionSpec = { fadeIn(Fades.fadeIn()) togetherWith fadeOut(Fades.fadeOut()) },
                    label = "pairing",
                ) { code ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (code == null) {
                            // QR pairing: no code to compare, so success is just the check.
                            Box(Modifier.size(width = 140.dp, height = 44.dp), contentAlignment = Alignment.Center) {
                                if (success) DrawnCheck(colors.green, Modifier.size(44.dp), strokeWidth = 4.dp)
                                else HopDots(active = true, modifier = Modifier.size(width = 140.dp, height = 10.dp))
                            }
                            Spacer(Modifier.height(28.dp))
                            Text(
                                if (success) "Paired with ${state.macName}" else "Connecting to ${state.macName}…",
                                style = Type.title2, color = colors.label, textAlign = TextAlign.Center,
                            )
                        } else {
                            Text("Pairing code", style = Type.title1, color = colors.label)
                            Spacer(Modifier.height(24.dp))
                            // The capsule settles in as the digits drop, and goes green on success.
                            val appear = remember { Animatable(if (reduceMotion) 1f else 0.92f) }
                            LaunchedEffect(Unit) { appear.animateTo(1f, Springs.bouncy()) }
                            val capsuleColor by animateColorAsState(
                                if (success) colors.green.copy(alpha = 0.28f) else colors.backgroundElevated,
                                Springs.smooth(), label = "capsule",
                            )
                            Box(
                                Modifier
                                    .graphicsLayer { scaleX = appear.value; scaleY = appear.value }
                                    .clip(capsule())
                                    .background(capsuleColor)
                                    .padding(horizontal = 28.dp, vertical = 16.dp),
                            ) {
                                CodeDisplay(code)
                            }
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "Check ${state.macName} shows the same code, then click Pair on the Mac.",
                                style = Type.body,
                                color = colors.labelSecondary,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(28.dp))
                            AnimatedContent(
                                targetState = success,
                                transitionSpec = { fadeIn(Fades.fadeIn()) togetherWith fadeOut(Fades.fadeOut()) },
                                label = "pairingStatus",
                            ) { paired ->
                                Row(
                                    Modifier.height(24.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (paired) {
                                        DrawnCheck(colors.green, Modifier.size(22.dp))
                                        Text("Paired", style = Type.headline, color = colors.green)
                                    } else {
                                        ActivityIndicator(size = 16.dp)
                                        Text("Waiting for your Mac", style = Type.footnote, color = colors.labelSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        chrome = {
            WButton(
                "Cancel",
                onClick = onCancel,
                enabled = !success,
                style = ButtonStyle.Glass,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .size(width = 200.dp, height = 50.dp),
            )
        },
    )
}

/** A row joining a plain list: it opens up and fades in; leaving reverses it (DESIGN.md §7.2). */
internal fun rowEnter(reduceMotion: Boolean) =
    if (reduceMotion) fadeIn(Fades.fadeIn()) else expandVertically(Springs.smooth()) + fadeIn(Fades.fadeIn())

internal fun rowExit(reduceMotion: Boolean) =
    if (reduceMotion) fadeOut(Fades.fadeOut()) else shrinkVertically(Springs.smooth()) + fadeOut(Fades.fadeOut())
