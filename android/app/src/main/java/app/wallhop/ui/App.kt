package app.wallhop.ui

import androidx.core.content.edit
import androidx.core.net.toUri
import app.wallhop.core.Features
import app.wallhop.ui.design.WIcons
import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wallhop.Shortcuts
import app.wallhop.calls.CallControl
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkService
import app.wallhop.link.LinkState
import app.wallhop.link.TransferPhase
import app.wallhop.notify.MirroredApps
import app.wallhop.sms.SmsStore
import app.wallhop.ui.components.ActionSheetSpec
import app.wallhop.ui.components.AlertAction
import app.wallhop.ui.components.AlertSpec
import app.wallhop.ui.components.ButtonStyle
import app.wallhop.ui.components.LocalOverlays
import app.wallhop.ui.components.ToastAction
import app.wallhop.ui.components.WButton
import app.wallhop.ui.design.Type
import app.wallhop.ui.screens.LicencesScreen
import app.wallhop.ui.screens.MacDetails
import app.wallhop.ui.screens.openAppDetails
import app.wallhop.ui.screens.openLink
import app.wallhop.ui.screens.showEntryActions
import app.wallhop.ui.screens.tapEntry
import app.wallhop.ui.screens.GuidedSetup
import app.wallhop.ui.screens.SetupSteps
import app.wallhop.ui.components.FloatingTabBar
import app.wallhop.ui.components.GlassStage
import app.wallhop.ui.components.MenuItem
import app.wallhop.ui.components.OverlayHost
import app.wallhop.ui.components.ReceivedPopup
import app.wallhop.ui.components.Overlays
import app.wallhop.ui.components.TabItem
import app.wallhop.ui.components.PillNavBar
import app.wallhop.ui.components.TextAction
import app.wallhop.ui.components.WTextField
import app.wallhop.ui.design.Durations
import app.wallhop.ui.design.Haptic
import app.wallhop.ui.design.Springs
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.rememberHaptics
import app.wallhop.ui.screens.ActivityTab
import app.wallhop.ui.screens.AppsScreen
import app.wallhop.ui.screens.ConnectScreen
import app.wallhop.ui.screens.MacActions
import app.wallhop.ui.screens.MacTab
import app.wallhop.ui.screens.PairingScreen
import app.wallhop.ui.screens.SettingsActions
import app.wallhop.ui.screens.SettingsTab
import app.wallhop.ui.screens.Setup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** App root: connect flow, or the three tabs, with sheets and alerts floating on top. */
@Composable
fun WallhopRoot(link: LinkManager, demo: Boolean = false) {
    val colors = Wallhop.colors
    val context = LocalContext.current
    val overlays = remember { Overlays() }
    val realState by link.state.collectAsStateWithLifecycle()
    val state = if (demo) LinkState.Connected("Santhosh's MacBook Air", "192.168.0.4") else realState
    val macs by link.discovered.collectAsStateWithLifecycle()

    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        link.start()
        onPauseOrDispose { }
    }
    val setup = remember(resumes) { readSetup(context) }

    LaunchedEffect(state) {
        when (val s = state) {
            is LinkState.Connected -> Shortcuts.publish(context, s.macName)
            is LinkState.NotPaired -> Shortcuts.remove(context)
            else -> Unit
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // A notification asked for a tab (e.g. Activity for files from the Mac).
    val requestedTab by AppNav.tab.collectAsStateWithLifecycle()
    LaunchedEffect(requestedTab) {
        requestedTab?.let { tab = it; AppNav.tab.value = null }
    }
    // Files that just arrived from the Mac while Lync is open: the popup above everything.
    var received by remember { mutableStateOf<app.wallhop.link.ReceivedBatch?>(null) }
    LaunchedEffect(link) { link.received.collect { received = it } }
    var showApps by rememberSaveable { mutableStateOf(false) }
    var showLicences by rememberSaveable { mutableStateOf(false) }
    var minimized by remember { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    // The last pairing attempt, for "Try again" when it fails.
    var retryPair by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Pairing just succeeded (the Mac said yes): the pairing screen stays a moment to show it.
    // Cancelling also leaves Pairing (for Searching, if a Mac was already paired): not a success.
    var lastPairing by remember { mutableStateOf<LinkState.Pairing?>(null) }
    var pairingCancelled by remember { mutableStateOf(false) }
    val pairedNow = lastPairing != null && !pairingCancelled && (state is LinkState.Searching || state is LinkState.Connected)
    LaunchedEffect(state) {
        when {
            state is LinkState.Pairing -> lastPairing = state
            pairedNow -> {
                delay(1_300)
                lastPairing = null
            }
            else -> {
                lastPairing = null
                pairingCancelled = false
            }
        }
    }
    val screen = when {
        state is LinkState.NotPaired || state is LinkState.PairingFailed -> 0
        state is LinkState.Pairing || pairedNow -> 1
        else -> 2
    }
    // The Mac says it no longer knows this phone. The pairing is kept (and retried) until the
    // user chooses to pair again.
    LaunchedEffect(state is LinkState.Forgotten) {
        if (state is LinkState.Forgotten) {
            overlays.showAlert(AlertSpec(
                title = "Your Mac forgot this phone",
                message = "${state.macName} no longer recognises this phone. Pair again to keep codes and files going across.",
                actions = listOf(
                    AlertAction("Pair again", default = true) { link.dismissPairingProblem() },
                    AlertAction("Not now"),
                ),
            ))
        }
    }
    // Scrolling down tucks the tab bar away; scrolling up brings it back (DESIGN.md §5.2).
    val minimizer = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Only when content actually moved, so drags on screens that don't scroll leave the bar alone.
                if (consumed.y < -6f) minimized = true else if (consumed.y > 6f) minimized = false
                return Offset.Zero
            }
        }
    }

    // One recorded content layer for the whole app; the tab bar and overlays float above it.
    CompositionLocalProvider(LocalOverlays provides overlays) {
    GlassStage(
        modifier = Modifier.fillMaxSize(),
        background = colors.background,
        content = {
            AnimatedContent(
                targetState = screen,
                // While a sheet, alert or menu is up, TalkBack only sees that.
                modifier = Modifier.nestedScroll(minimizer).then(if (overlays.modal) Modifier.clearAndSetSemantics { } else Modifier),
                transitionSpec = { fadeIn(tween(Durations.Fade + 20)) togetherWith fadeOut(tween(Durations.Fade - 20)) },
                label = "root",
            ) { screen ->
                when (screen) {
                    0 -> if (scanning) {
                        app.wallhop.ui.screens.QrScanScreen(
                            onScanned = { invite ->
                                scanning = false
                                retryPair = { scanning = true } // A QR code is used once: scan the new one.
                                link.pair(invite)
                            },
                            onClose = { scanning = false },
                        )
                    } else {
                        ConnectScreen(
                            macs = macs,
                            problem = state.takeIf { it is LinkState.PairingFailed },
                            overlays = overlays,
                            onPair = { host, port, name ->
                                retryPair = { link.pair(host, port, name) }
                                link.pair(host, port, name)
                            },
                            onDismissProblem = { link.dismissPairingProblem() },
                            onScan = { scanning = true },
                            onRetry = retryPair,
                        )
                    }
                    1 -> {
                        // Held while this screen fades out, so it doesn't blank mid-transition.
                        val held = remember { arrayOfNulls<LinkState.Pairing>(1) }
                        ((state as? LinkState.Pairing) ?: lastPairing)?.let { held[0] = it }
                        held[0]?.let { PairingScreen(it, onCancel = { pairingCancelled = true; link.cancelPairing() }, success = state !is LinkState.Pairing) }
                    }
                    else -> Tabs(
                        link, state, setup, overlays, demo,
                        tab = tab, showApps = showApps, showLicences = showLicences,
                        onTab = { tab = it; showApps = false; showLicences = false; minimized = false },
                        onShowApps = { showApps = it },
                        onShowLicences = { showLicences = it },
                        onPermissionChange = { resumes++ },
                    )
                }
            }
        },
        chrome = {
            androidx.compose.animation.AnimatedVisibility(
                visible = screen == 2,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(Springs.smooth()) { it } + fadeIn(),
                exit = fadeOut(tween(Durations.Quick)),
            ) {
                PillNavBar(
                    tabs = listOf(
                        TabItem("Home", WIcons.Laptop),
                        TabItem("Activity", WIcons.Activity),
                        TabItem("Settings", WIcons.Settings, badge = !setup.essentialsDone),
                    ),
                    selected = tab,
                    onSelect = { tab = it; showApps = false; showLicences = false; minimized = false },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(bottom = 12.dp)
                        .then(if (overlays.modal) Modifier.clearAndSetSemantics { } else Modifier),
                )
            }
            ReceivedPopup(received, onDismiss = { received = null }, bottom = if (screen == 2) 88.dp else 16.dp)
            OverlayHost(overlays)
        },
    )
    }
}

@Composable
private fun Tabs(
    link: LinkManager,
    state: LinkState,
    setup: Setup,
    overlays: Overlays,
    demo: Boolean,
    tab: Int,
    showApps: Boolean,
    showLicences: Boolean,
    onTab: (Int) -> Unit,
    onShowApps: (Boolean) -> Unit,
    onShowLicences: (Boolean) -> Unit,
    onPermissionChange: () -> Unit,
) {
    val context = LocalContext.current
    val activityHost = LocalActivity.current
    val colors = Wallhop.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val realActivity by link.activity.collectAsStateWithLifecycle()
    // Deleted entries wait here while the Undo toast is up; then they're removed for good.
    val deleting = remember { mutableStateListOf<String>() }
    val activity = (if (demo) remember { demoActivity() } else realActivity).filterNot { it.id in deleting }
    val mirrored by remember { MirroredApps.get(context) }.enabled.collectAsStateWithLifecycle()

    // Kept across rotation, and the sheet reopens with it.
    val draft = rememberSaveable { mutableStateOf("") }
    val textSheetOpen = rememberSaveable { mutableStateOf(false) }
    val sendingText = remember { mutableStateOf(false) }

    val macName = when (state) {
        is LinkState.Connected -> state.macName
        is LinkState.Searching -> state.macName
        else -> link.pairedMac?.name ?: "Your Mac"
    }
    // The actions below are made once, so a tab doesn't recompose for nothing; they read
    // whatever changes through these.
    val currentMacName by rememberUpdatedState(macName)
    val currentColors by rememberUpdatedState(colors)
    val currentOnTab by rememberUpdatedState(onTab)
    val currentOnShowApps by rememberUpdatedState(onShowApps)
    val currentOnShowLicences by rememberUpdatedState(onShowLicences)
    val currentOnPermissionChange by rememberUpdatedState(onPermissionChange)
    val currentSetup by rememberUpdatedState(setup)

    // Permission requests. [asking] is the feature waiting for Android's answer; [askingFor] the
    // permissions asked, required ones first, then "|" and the optional ones.
    val asking = rememberSaveable { mutableStateOf<String?>(null) }
    val askingFor = rememberSaveable { mutableStateOf("") }
    // The guided setup moves on once Android has answered.
    val afterAnswer = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val asked = remember { context.getSharedPreferences("permissions", Context.MODE_PRIVATE) }
    /** A permission Android won't ask for again (denied twice, or "Don't ask again"): the first such, or null. */
    fun blocked(permissions: List<String>): String? = permissions.firstOrNull { permission ->
        activityHost != null && asked.getBoolean(permission, false) && !granted(permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activityHost, permission)
    }
    fun answered() {
        val key = asking.value?.let { runCatching { Features.Key.valueOf(it) }.getOrNull() }
        val (requiredText, optionalText) = askingFor.value.split('|').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val required = requiredText.split(',').filter { it.isNotEmpty() }
        val optional = optionalText.split(',').filter { it.isNotEmpty() }
        asking.value = null
        // Only the required ones decide: Contacts, say, is a nice extra for Calls and Texts.
        val ok = required.all(::granted)
        if (!ok && key != null) {
            // Denied: the switch goes off, so the setup badge doesn't nag about it.
            Features.set(context, key, false)
            val missing = required.firstOrNull { !granted(it) }.orEmpty()
            val stuck = blocked(required.filter { asked.getBoolean(it, false) })
            if (stuck != null) showBlockedAlert(overlays, context, key, stuck)
            else overlays.toast(app.wallhop.core.Wording.deniedToast(key, missing), WIcons.Warning)
        }
        asked.edit { (required + optional).forEach { putBoolean(it, true) } }
        currentOnPermissionChange()
        afterAnswer.value?.invoke(ok)
        afterAnswer.value = null
    }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answered() }
    fun request(key: Features.Key, required: Array<String>, optional: Array<String> = emptyArray()) {
        Features.set(context, key, true)
        val missingRequired = required.filterNot(::granted)
        val missingOptional = optional.filterNot(::granted)
        if (missingRequired.isEmpty() && missingOptional.isEmpty()) {
            currentOnPermissionChange()
            afterAnswer.value?.invoke(true)
            afterAnswer.value = null
            return
        }
        asking.value = key.name
        askingFor.value = required.joinToString(",") + "|" + optional.joinToString(",")
        // Optional ones Android won't ask about again are left out, so they can't block the rest.
        val ask = missingRequired + missingOptional.filter { blocked(listOf(it)) == null }
        runCatching { multiple.launch(ask.toTypedArray()) }.onFailure { answered() }
    }
    // Android's "allow in background?" dialog. Some phones close it at once without showing it;
    // then the app's battery page is the way, so a tap never does nothing.
    var batteryAskedAt by remember { mutableStateOf(0L) }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        currentOnPermissionChange()
        val instant = android.os.SystemClock.elapsedRealtime() - batteryAskedAt < 800
        if (instant && !isBackgroundAllowed(context)) openAppBatterySettings(context)
        afterAnswer.value?.invoke(isBackgroundAllowed(context))
        afterAnswer.value = null
    }
    fun askBackground() {
        Features.set(context, Features.Key.Background, true)
        LinkService.start(context)
        if (isBackgroundAllowed(context)) {
            currentOnPermissionChange()
            afterAnswer.value?.invoke(true)
            afterAnswer.value = null
            return
        }
        batteryAskedAt = android.os.SystemClock.elapsedRealtime()
        runCatching {
            battery.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
        }.onFailure { openAppBatterySettings(context) }
    }
    // The last picker used, so a failed transfer with nothing to resend can reopen it.
    var lastPicker by remember { mutableIntStateOf(0) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        Pickers.open = false
        if (uris.isNotEmpty()) LinkService.sendFiles(context, uris)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        Pickers.open = false
        if (uris.isNotEmpty()) LinkService.sendFiles(context, uris)
    }
    fun launchPicker(which: Int) {
        lastPicker = which
        Pickers.open = true
        runCatching {
            if (which == 0) pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            else pickFiles.launch(arrayOf("*/*"))
        }.onFailure { Pickers.open = false }
    }

    fun report(ok: Boolean, what: String) {
        if (ok) {
            haptics.perform(Haptic.Success)
            overlays.toast(if (what == "Link") "Opened on your Mac" else app.wallhop.core.Wording.READY, WIcons.CheckCircle, currentColors.green)
        } else {
            overlays.toast("Couldn't reach your Mac", WIcons.Warning, currentColors.red)
        }
    }

    fun confirmForget() {
        val name = currentMacName
        overlays.showActionSheet(ActionSheetSpec(
            message = "Codes and files stop going to $name until you pair again.",
            actions = listOf(AlertAction("Forget $name", destructive = true) { link.forgetMac() }),
        ))
    }

    fun showMacDetails() {
        overlays.showSheet(title = currentMacName) {
            val liveState by link.state.collectAsStateWithLifecycle()
            val info by link.macInfo.collectAsStateWithLifecycle()
            val latency by link.latencyMs.collectAsStateWithLifecycle()
            val since by link.connectedSince.collectAsStateWithLifecycle()
            MacDetails(if (demo) state else liveState, info, latency, since, onForget = {
                overlays.dismissSheet(cancelled = false)
                confirmForget()
            })
        }
    }

    /** Hides entries at once, with Undo; they're removed for good when the toast has gone. */
    fun deleteEntries(ids: List<String>, message: String) {
        if (ids.isEmpty()) return
        deleting += ids
        overlays.toast(message, action = ToastAction("Undo") { deleting -= ids.toSet() })
        scope.launch {
            // A little longer than the toast (which lasts as long as accessibility settings ask), so Undo is always in time.
            delay(app.wallhop.ui.components.toastDuration(context, hasAction = true) + 500)
            val gone = ids.filter { it in deleting }
            link.removeActivity(gone)
            deleting -= gone.toSet()
        }
    }
    fun deleteEntry(id: String) = deleteEntries(listOf(id), "Deleted")

    fun clearOlderThanAWeek() {
        val old = app.wallhop.core.Wording.olderThan(activity, System.currentTimeMillis(), 7) { it.time }
        if (old.isEmpty()) {
            overlays.toast("Nothing older than a week")
            return
        }
        overlays.showAlert(AlertSpec(
            title = "Clear older than a week?",
            message = "${old.size} ${if (old.size == 1) "entry" else "entries"} from before last week will be removed from Activity.",
            actions = listOf(
                AlertAction("Clear", destructive = true, default = true) { deleteEntries(old.map { it.id }, "Cleared ${old.size}") },
                AlertAction("Cancel"),
            ),
        ))
    }

    fun entryActions(entry: app.wallhop.link.ActivityEntry) = showEntryActions(overlays, context, entry, haptics, { deleteEntry(it) }) { sent ->
        LinkService.sendFiles(context, sent.uris.map(android.net.Uri::parse))
    }
    fun entryTap(entry: app.wallhop.link.ActivityEntry) = tapEntry(overlays, context, entry, haptics) { entryActions(it) }

    fun showTextSheet() {
        textSheetOpen.value = true
        overlays.showSheet(
            title = "Send text",
            leading = { TextAction("Cancel", onClick = { overlays.dismissSheet() }) },
            trailing = {
                TextAction(
                    if (sendingText.value) "Sending…" else "Send",
                    bold = true,
                    enabled = draft.value.isNotBlank() && !sendingText.value,
                    onClick = {
                        val text = draft.value
                        sendingText.value = true
                        scope.launch {
                            val ok = link.sendText(text)
                            sendingText.value = false
                            // Only a sent text is cleared; after a failure it's still there to try again.
                            if (ok) {
                                draft.value = ""
                                if (textSheetOpen.value) {
                                    textSheetOpen.value = false
                                    overlays.dismissSheet(cancelled = false)
                                }
                            }
                            report(ok, "Text")
                        }
                    },
                )
            },
            onDismiss = { textSheetOpen.value = false },
        ) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            WTextField(
                draft.value, { draft.value = it }, "It's ready to paste on your Mac",
                singleLine = false, minLines = 5, label = "Text for your Mac", focusRequester = focus,
            )
        }
    }
    // Rotated with the sheet open: open it again, draft and all.
    LaunchedEffect(Unit) { if (textSheetOpen.value) showTextSheet() }

    // Before Android's own prompt: what Lync does with this, in a sentence (Play's prominent disclosure).
    fun disclose(
        key: Features.Key,
        title: String,
        message: String,
        extra: (@Composable () -> Unit)? = null,
        onContinue: () -> Unit,
    ) {
        overlays.showSheet(
            title = title,
            onDismiss = {
                Features.set(context, key, false)
                currentOnPermissionChange()
            },
        ) {
            Text(message, style = Type.body, color = Wallhop.colors.labelSecondary)
            extra?.invoke()
            Spacer(Modifier.height(20.dp))
            WButton("Continue", {
                overlays.dismissSheet(cancelled = false)
                onContinue()
            }, style = ButtonStyle.Filled, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            WButton("Not now", { overlays.dismissSheet() }, style = ButtonStyle.Glass, modifier = Modifier.fillMaxWidth())
        }
    }

    /**
     * A feature that needs permissions: Android won't ask again → straight to "Allow X in
     * Settings" (no disclosure first, it would lead nowhere); otherwise the disclosure, then Android.
     */
    fun ask(key: Features.Key, title: String, message: String, required: Array<String>, optional: Array<String> = emptyArray()) {
        val stuck = blocked(required.toList())
        if (stuck != null) {
            Features.set(context, key, false)
            currentOnPermissionChange()
            showBlockedAlert(overlays, context, key, stuck)
            return
        }
        disclose(key, title, message) { request(key, required, optional) }
    }

    // Notification access is a trip to Android's settings. When the user comes back without it on a
    // sideloaded Android 13+ install, Android's "restricted setting" block is the likely reason.
    val accessPending = rememberSaveable { mutableStateOf(false) }
    fun openAccessList() {
        Features.set(context, Features.Key.AppNotifications, true)
        accessPending.value = true
        openNotificationAccess(context, overlays, currentColors.red)
    }
    LifecycleResumeEffect(Unit) {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        if (accessPending.value && !granted && app.wallhop.core.KeepAlive.restrictedSettingsLikely(context)) {
            overlays.showAlert(AlertSpec(
                title = "Allow restricted settings",
                message = "Android blocks this for apps installed from a file. Open App info, tap ⋮ (top right) › Allow restricted settings, then come back and allow Lync in Notification access.",
                actions = listOf(
                    AlertAction("Open App info", default = true) { app.wallhop.core.KeepAlive.openAppInfo(context) },
                    AlertAction("Not now"),
                ),
            ))
        }
        accessPending.value = false
        onPauseOrDispose { }
    }

    fun requestNotificationAccess() = disclose(
        Features.Key.AppNotifications,
        "Phone notifications on Mac",
        "Lync reads notifications from the apps you choose and shows them only on your paired Mac. They never go anywhere else, and you can turn this off at any time.",
        extra = { NotificationAccessSteps(context) },
    ) { openAccessList() }

    // The guided setup: after pairing (once) and from "Finish setup".
    val showSetup = rememberSaveable { mutableStateOf(false) }
    val setupIndex = rememberSaveable { mutableIntStateOf(0) }
    val setupPrefs = remember { context.getSharedPreferences("setup", Context.MODE_PRIVATE) }
    fun openSetup() {
        setupIndex.intValue = 0
        showSetup.value = true
    }
    fun closeSetup() {
        showSetup.value = false
        setupPrefs.edit { putBoolean("guided_done", true) }
        currentOnTab(0)
    }
    fun nextStep() {
        if (setupIndex.intValue + 1 >= SetupSteps.size) closeSetup() else setupIndex.intValue++
    }
    LaunchedEffect(Unit) { if (!demo && !setupPrefs.getBoolean("guided_done", false)) openSetup() }

    val macActions = remember {
        MacActions(
            sendPhotos = { launchPicker(0) },
            sendFiles = { launchPicker(1) },
            sendText = { showTextSheet() },
            sendClipboard = {
                val text = context.getSystemService(ClipboardManager::class.java).primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) {
                    overlays.toast("Your clipboard is empty")
                } else {
                    scope.launch {
                        val isLink = app.wallhop.extras.isSingleUrl(text)
                        report(if (isLink) link.openOnMac(text.trim()) else link.sendText(text), if (isLink) "Link" else "Clipboard")
                    }
                }
            },
            cancelTransfer = {
                link.cancelTransfer()
                overlays.toast("Cancelled")
            },
            dismissTransfer = { link.clearFinishedTransfer() },
            retryTransfer = { transfer ->
                LinkService.start(context)
                if (!link.retryTransfer(transfer.id)) {
                    link.clearFinishedTransfer()
                    launchPicker(lastPicker)
                }
            },
            openSettings = { currentOnTab(2) },
            openActivity = { currentOnTab(1) },
            macDetails = { showMacDetails() },
            entryActions = { entry -> entryActions(entry) },
            entryTap = { entry -> entryTap(entry) },
            more = { anchor ->
                overlays.showMenu(anchor, listOf(
                    MenuItem("Mac details", WIcons.Laptop) { showMacDetails() },
                    MenuItem("Forget this Mac", WIcons.LinkBreak, destructive = true) { confirmForget() },
                ))
            },
            finishSetup = { openSetup() },
            stopRinging = { app.wallhop.extras.PhoneFinder.stop(context) },
            pairAgain = { link.dismissPairingProblem() },
            retryConnect = {
                link.start()
                link.poke()
                overlays.toast("Looking for your Mac…")
            },
        )
    }

    val settingsActions = remember {
        SettingsActions(
            allowSms = {
                ask(Features.Key.Codes, "Codes",
                    "Lync reads incoming texts only to find one-time codes, and sends just the code to your paired Mac.",
                    arrayOf(Manifest.permission.RECEIVE_SMS))
            },
            allowCalls = {
                ask(Features.Key.Calls, "Calls",
                    "Lync shows who's calling on your paired Mac, so you can answer there; call details only go to your Mac. Contacts is optional: without it you'll see numbers.",
                    CallControl.required, CallControl.optional)
            },
            allowTexts = {
                ask(Features.Key.Texts, "Texts",
                    "Lync reads and sends your text messages so you can use them on your paired Mac; they only go to your Mac. Contacts is optional.",
                    SmsStore.required, SmsStore.optional)
            },
            allowNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val stuck = blocked(listOf(Manifest.permission.POST_NOTIFICATIONS))
                    if (stuck != null) {
                        Features.set(context, Features.Key.Notifications, false)
                        currentOnPermissionChange()
                        showBlockedAlert(overlays, context, Features.Key.Notifications, stuck)
                    } else {
                        request(Features.Key.Notifications, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }
            },
            openNotificationAccess = { requestNotificationAccess() },
            allowBackground = { askBackground() },
            setFeature = { key, on ->
                Features.set(context, key, on)
                if (key == Features.Key.Background && on) LinkService.start(context)
                currentOnPermissionChange()
            },
            forgetMac = { confirmForget() },
            sendTestCode = { link.sendOtp((100000..999999).random().toString(), "Lync test", "debug") },
            macDetails = { showMacDetails() },
            openLicences = { currentOnShowLicences(true) },
            openPrivacy = { openLink(context, PRIVACY_URL) },
            finishSetup = { openSetup() },
            addTile = { addClipboardTile(context, overlays) },
        )
    }

    TransferEffects(link)

    // Back on Activity or Settings goes to Home first, then leaves the app.
    BackHandler(enabled = tab != 0 && !showApps && !showLicences) { onTab(0) }

    // All three tabs stay alive: switching is instant and each keeps its scroll position, like iOS.
    // Hidden tabs aren't drawn and their animations pause.
    val visited = remember { mutableStateListOf(tab) }
    LaunchedEffect(tab) { if (tab !in visited) visited.add(tab) }
    Box(Modifier.fillMaxSize()) {
        for (index in 0..2) {
            if (index !in visited) continue
            key(index) {
                TabSlot(index, tab, visible = index == tab && !showApps && !showLicences) {
                    when (index) {
                        0 -> HomeSlot(link, state, macName, !setup.essentialsDone, macActions, activity, demo)
                        1 -> ActivityTab(
                            activity, overlays,
                            onDelete = { deleteEntry(it) },
                            onTap = { entryTap(it) },
                            onActions = { entryActions(it) },
                            onClearOld = { clearOlderThanAWeek() },
                        )
                        else -> SettingsTab(
                            setup, link.pairedMac?.name,
                            macStatus = when (state) {
                                is LinkState.Connected -> "Connected"
                                is LinkState.Searching -> "Not connected"
                                is LinkState.Forgotten -> "Your Mac no longer recognises this phone"
                                else -> null
                            },
                            mirroredCount = mirrored.size,
                            actions = settingsActions,
                            openApps = { currentOnShowApps(true) },
                        )
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showApps,
            modifier = Modifier.zIndex(2f),
            enter = slideInHorizontally(Springs.smooth()) { it },
            exit = slideOutHorizontally(Springs.smooth()) { it },
        ) {
            AppsScreen(
                // Android's access, whatever Lync's own switch says: no trip to Settings when it's already on.
                hasNotificationAccess = Features.Key.AppNotifications in currentSetup.granted,
                switchedOn = currentSetup.notificationAccess,
                onOpenAccess = { requestNotificationAccess() },
                onSwitchOn = { settingsActions.setFeature(Features.Key.AppNotifications, true) },
                onBack = { onShowApps(false) },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showLicences,
            modifier = Modifier.zIndex(2f),
            enter = slideInHorizontally(Springs.smooth()) { it },
            exit = slideOutHorizontally(Springs.smooth()) { it },
        ) {
            LicencesScreen(overlays, onBack = { onShowLicences(false) })
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showSetup.value,
            modifier = Modifier.zIndex(3f),
            enter = fadeIn(tween(Durations.Fade)),
            exit = fadeOut(tween(Durations.Quick)),
        ) {
            // Steps already done are passed over.
            LaunchedEffect(setupIndex.intValue, setup) {
                while (setupIndex.intValue < SetupSteps.size && SetupSteps[setupIndex.intValue].key.let { it in setup.granted && Features.on(context, it) }) {
                    setupIndex.intValue++
                }
                if (setupIndex.intValue >= SetupSteps.size) closeSetup()
            }
            GuidedSetup(
                index = setupIndex.intValue,
                onTurnOn = { step ->
                    afterAnswer.value = { nextStep() }
                    when (step.key) {
                        Features.Key.Codes -> request(step.key, arrayOf(Manifest.permission.RECEIVE_SMS))
                        Features.Key.Calls -> request(step.key, CallControl.required, CallControl.optional)
                        Features.Key.Texts -> request(step.key, SmsStore.required, SmsStore.optional)
                        Features.Key.Notifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            request(step.key, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        } else {
                            Features.set(context, step.key, true)
                            afterAnswer.value = null
                            nextStep()
                        }
                        Features.Key.Background -> askBackground()
                        Features.Key.AppNotifications -> {
                            // Comes back here; the step moves on once access is on (see the effect above).
                            afterAnswer.value = null
                            openAccessList()
                        }
                        else -> nextStep()
                    }
                },
                onSkip = { step ->
                    Features.set(context, step.key, false)
                    currentOnPermissionChange()
                    nextStep()
                },
                onClose = { closeSetup() },
                extra = { step -> if (step.key == Features.Key.AppNotifications) NotificationAccessSteps(context) },
            )
        }
    }
}

/** The order that works, including Android 13's restricted-setting detour for sideloaded installs. */
@Composable
private fun NotificationAccessSteps(context: Context) {
    val restricted = remember { app.wallhop.core.KeepAlive.restrictedSettingsLikely(context) }
    app.wallhop.ui.screens.SetupHelp(buildList {
        add("Open the Notification access list.")
        add("Tap Lync and allow it.")
        if (restricted) {
            add("If Android says it's a restricted setting: open App info › ⋮ › Allow restricted settings.")
            add("Come back to the list and allow Lync.")
        }
    })
    if (restricted) {
        Spacer(Modifier.height(12.dp))
        WButton("Open App info", { app.wallhop.core.KeepAlive.openAppInfo(context) },
            style = ButtonStyle.Glass, modifier = Modifier.fillMaxWidth())
    }
}

/** Adds the "Clipboard to Mac" Quick Settings tile: Android 13 asks in one tap; before that, how to. */
private fun addClipboardTile(context: Context, overlays: Overlays) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = context.getSystemService(android.app.StatusBarManager::class.java)
        val added = runCatching {
            manager.requestAddTileService(
                android.content.ComponentName(context, app.wallhop.extras.ClipboardTileService::class.java),
                "Clipboard to Mac",
                android.graphics.drawable.Icon.createWithResource(context, app.wallhop.R.drawable.ic_stat_wallhop),
                ContextCompat.getMainExecutor(context),
            ) { result ->
                when (result) {
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        overlays.toast("The tile is already in Quick Settings")
                    android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                        overlays.toast("Added to Quick Settings", WIcons.CheckCircle)
                    else -> Unit
                }
            }
        }.isSuccess
        if (added) return
    }
    overlays.showAlert(AlertSpec(
        title = "Add the clipboard tile",
        message = "Swipe down twice from the top of the screen, tap the pencil (Edit), then drag “Clipboard to Mac” into your tiles.",
        actions = listOf(AlertAction("Got it", default = true)),
    ))
}

/** Home, with the things that change often (transfer progress, the Mac's battery) read here only. */
@Composable
private fun HomeSlot(
    link: LinkManager,
    state: LinkState,
    macName: String,
    needsSetup: Boolean,
    actions: MacActions,
    activity: List<app.wallhop.link.ActivityEntry>,
    demo: Boolean,
) {
    val transfer by link.transfer.collectAsStateWithLifecycle()
    val macInfo by link.macInfo.collectAsStateWithLifecycle()
    val latency by link.latencyMs.collectAsStateWithLifecycle()
    val connectedSince by link.connectedSince.collectAsStateWithLifecycle()
    val card = if (demo) {
        app.wallhop.ui.screens.MacCardData(
            macInfo.copy(battery = 82, charging = true, laptop = true), latencyMs = 9,
            connectedSince = remember { System.currentTimeMillis() - 134 * 60_000L }, latest = activity.firstOrNull(), transfer = transfer,
            recent = activity.drop(1).take(4),
        )
    } else {
        // The newest entry has its own card beside the Mac, so Recent starts with the one after it.
        app.wallhop.ui.screens.MacCardData(macInfo, latency, connectedSince, activity.firstOrNull(), transfer, activity.drop(1).take(4))
    }
    val ringing by app.wallhop.extras.PhoneFinder.ringing.collectAsStateWithLifecycle()
    MacTab(state, macName, transfer, needsSetup, actions = actions, card = card, ringing = ringing)
}

/** Haptics when a transfer ends; a finished one clears itself after a moment. */
@Composable
private fun TransferEffects(link: LinkManager) {
    val haptics = rememberHaptics()
    // Only the phase: this doesn't recompose for every progress update.
    val phase by remember(link) { link.transfer.map { it?.phase }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(null)
    LaunchedEffect(phase) {
        when (phase) {
            TransferPhase.Done -> {
                haptics.perform(Haptic.Success)
                delay(4_000)
                link.clearFinishedTransfer()
            }
            TransferPhase.Failed -> haptics.perform(Haptic.Failure)
            else -> Unit
        }
    }
}

private const val PRIVACY_URL = "https://github.com/santhoshsunkara24/Lync/blob/main/docs/PRIVACY.md"

/** Android said no and won't ask again: explain, naming the permission that's blocked, and offer Lync's page in Settings. */
private fun showBlockedAlert(overlays: Overlays, context: Context, key: Features.Key, blockedPermission: String) {
    val permission = app.wallhop.core.Wording.permissionName(blockedPermission)
    val feature = when (key) {
        Features.Key.Codes -> "Codes"
        Features.Key.Calls -> "Calls"
        Features.Key.Texts -> "Texts"
        Features.Key.Notifications -> "alerts on this phone"
        else -> return
    }
    overlays.showAlert(AlertSpec(
        title = "Allow $permission in Settings",
        message = "Android won't ask again. To use $feature, open Lync's settings, tap Permissions and allow $permission.",
        actions = listOf(
            AlertAction("Open Settings", default = true) { openAppDetails(context) },
            AlertAction("Not now"),
        ),
    ))
}

/** Android's notification access page; Lync's app page if a phone doesn't have it. */
private fun openNotificationAccess(context: Context, overlays: Overlays, red: androidx.compose.ui.graphics.Color) {
    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        .recoverCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }
        .onFailure { overlays.toast("Couldn't open Settings. Allow Lync under Notification access.", WIcons.Warning, red) }
}

/**
 * A kept-alive tab: cross-fades in when selected, drifting a little in the direction of travel
 * (from the right when moving to a tab on the right). When hidden it isn't drawn or announced.
 */
@Composable
private fun TabSlot(index: Int, selected: Int, visible: Boolean, content: @Composable () -> Unit) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (visible) 1f else 0f,
        if (reduceMotion) tween(0) else tween(if (visible) Durations.Fade else Durations.Quick - 30),
        label = "tab",
    )
    // Which side this tab sits on, fixed while it's shown: a hidden tab right of the selected
    // one waits on the right; once shown, it keeps the side it came in from.
    val side = remember { intArrayOf(0) }
    if (!visible) side[0] = (index - selected).coerceIn(-1, 1)
    val drift = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .graphicsLayer {
                alpha = shown
                translationX = (1f - shown) * drift * side[0]
            }
            .drawWithContent { if (shown > 0.001f) drawContent() }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
        CompositionLocalProvider(app.wallhop.ui.components.LocalScreenActive provides visible) { content() }
    }
}

private fun demoActivity(): List<app.wallhop.link.ActivityEntry> {
    val now = System.currentTimeMillis()
    val hour = 3_600_000L
    return listOf(
        app.wallhop.link.ActivityEntry("1", app.wallhop.link.ActivityEntry.Kind.Otp, "482913", "From HDFC Bank · ready to paste", now - 120_000),
        app.wallhop.link.ActivityEntry("2", app.wallhop.link.ActivityEntry.Kind.FilesSent, "3 photos", "Ready to paste on your Mac", now - hour),
        app.wallhop.link.ActivityEntry("3", app.wallhop.link.ActivityEntry.Kind.Call, "Missed call", "Mom", now - 2 * hour),
        app.wallhop.link.ActivityEntry("4", app.wallhop.link.ActivityEntry.Kind.TextSent, "Link opened on your Mac", "https://www.nothing.tech", now - 3 * hour),
        app.wallhop.link.ActivityEntry("5", app.wallhop.link.ActivityEntry.Kind.Otp, "771204", "From Swiggy · ready to paste", now - 26 * hour),
        app.wallhop.link.ActivityEntry("6", app.wallhop.link.ActivityEntry.Kind.FromMac, "2 files from your Mac", "Saved to Downloads › Lync", now - 27 * hour),
        app.wallhop.link.ActivityEntry("7", app.wallhop.link.ActivityEntry.Kind.FilesSent, "Invoice.pdf", "Ready to paste on your Mac", now - 28 * hour),
        app.wallhop.link.ActivityEntry("8", app.wallhop.link.ActivityEntry.Kind.Otp, "903311", "From Google · ready to paste", now - 30 * hour),
        app.wallhop.link.ActivityEntry("9", app.wallhop.link.ActivityEntry.Kind.Call, "Missed call", "+91 98765 43210", now - 50 * hour),
    )
}

fun readSetup(context: Context): Setup {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(PowerManager::class.java)
    val permitted = mapOf(
        Features.Key.Codes to granted(Manifest.permission.RECEIVE_SMS),
        Features.Key.Calls to CallControl.required.all(::granted),
        Features.Key.Texts to SmsStore.required.all(::granted),
        Features.Key.Notifications to
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)),
        Features.Key.AppNotifications to NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        Features.Key.Background to power.isIgnoringBatteryOptimizations(context.packageName),
    )
    // A switch shows on when Wallhop's own switch is on and Android allows it.
    fun on(key: Features.Key) = permitted.getValue(key) && Features.on(context, key)
    return Setup(
        sms = on(Features.Key.Codes),
        calls = on(Features.Key.Calls),
        texts = on(Features.Key.Texts),
        notifications = on(Features.Key.Notifications),
        notificationAccess = on(Features.Key.AppNotifications),
        background = on(Features.Key.Background),
        granted = permitted.filterValues { it }.keys,
        // Done when nothing the user wants (switch on) is still waiting for Android. Skipping a
        // step in the guided setup turns its switch off, so it stops asking.
        essentialsDone = permitted.all { (key, ok) -> ok || !Features.on(context, key) },
    )
}

private fun isBackgroundAllowed(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** Wallhop's page in Android's settings, where App battery usage › Unrestricted lives. */
private fun openAppBatterySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }.recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
