package app.wallhop.ui

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
import app.wallhop.ui.components.FloatingTabBar
import app.wallhop.ui.components.GlassStage
import app.wallhop.ui.components.MenuItem
import app.wallhop.ui.components.OverlayHost
import app.wallhop.ui.components.Overlays
import app.wallhop.ui.components.TabItem
import app.wallhop.ui.components.PillNavBar
import app.wallhop.ui.components.TextAction
import app.wallhop.ui.components.WTextField
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
    var showApps by rememberSaveable { mutableStateOf(false) }
    var minimized by remember { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val screen = when (state) {
        is LinkState.NotPaired, is LinkState.PairingFailed, is LinkState.Forgotten -> 0
        is LinkState.Pairing -> 1
        else -> 2
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
    GlassStage(
        modifier = Modifier.fillMaxSize(),
        background = colors.background,
        content = {
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.nestedScroll(minimizer),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "root",
            ) { screen ->
                when (screen) {
                    0 -> if (scanning) {
                        app.wallhop.ui.screens.QrScanScreen(
                            onScanned = { invite -> scanning = false; link.pair(invite) },
                            onClose = { scanning = false },
                        )
                    } else {
                        ConnectScreen(
                            macs = macs,
                            problem = state.takeIf { it is LinkState.PairingFailed || it is LinkState.Forgotten },
                            overlays = overlays,
                            onPair = { host, port, name -> link.pair(host, port, name) },
                            onDismissProblem = { link.dismissPairingProblem() },
                            onScan = { scanning = true },
                        )
                    }
                    1 -> (state as? LinkState.Pairing)?.let { PairingScreen(it) { link.cancelPairing() } }
                    else -> Tabs(
                        link, state, setup, overlays, demo,
                        tab = tab, showApps = showApps,
                        onTab = { tab = it; showApps = false; minimized = false },
                        onShowApps = { showApps = it },
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
                exit = fadeOut(tween(150)),
            ) {
                PillNavBar(
                    tabs = listOf(
                        TabItem("Mac", WIcons.Laptop),
                        TabItem("Activity", WIcons.Activity),
                        TabItem("Settings", WIcons.Settings, badge = !setup.essentialsDone),
                    ),
                    selected = tab,
                    onSelect = { tab = it; showApps = false; minimized = false },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                )
            }
            OverlayHost(overlays)
        },
    )
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
    onTab: (Int) -> Unit,
    onShowApps: (Boolean) -> Unit,
    onPermissionChange: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Wallhop.colors
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val transfer by link.transfer.collectAsStateWithLifecycle()
    val realActivity by link.activity.collectAsStateWithLifecycle()
    val macInfo by link.macInfo.collectAsStateWithLifecycle()
    val latency by link.latencyMs.collectAsStateWithLifecycle()
    val connectedSince by link.connectedSince.collectAsStateWithLifecycle()
    val activity = if (demo) remember { demoActivity() } else realActivity
    val card = if (demo) {
        app.wallhop.ui.screens.MacCardData(
            macInfo.copy(battery = 82, charging = true, laptop = true), latencyMs = 9,
            connectedSince = remember { System.currentTimeMillis() - 134 * 60_000L }, latest = activity.firstOrNull(), transfer = transfer,
            recent = activity.take(4),
        )
    } else {
        app.wallhop.ui.screens.MacCardData(macInfo, latency, connectedSince, activity.firstOrNull(), transfer, activity.take(4))
    }
    val mirrored by remember { MirroredApps.get(context) }.enabled.collectAsStateWithLifecycle()

    val draft = remember { mutableStateOf("") }

    val macName = when (state) {
        is LinkState.Connected -> state.macName
        is LinkState.Searching -> state.macName
        else -> link.pairedMac?.name ?: "Your Mac"
    }

    // Permission requests.
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPermissionChange() }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionChange() }
    // Android's "allow in background?" dialog. Some phones close it at once without showing it;
    // then the app's battery page is the way, so a tap never does nothing.
    var batteryAskedAt by remember { mutableStateOf(0L) }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPermissionChange()
        val instant = android.os.SystemClock.elapsedRealtime() - batteryAskedAt < 800
        if (instant && !isBackgroundAllowed(context)) openAppBatterySettings(context)
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) LinkService.sendFiles(context, uris)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) LinkService.sendFiles(context, uris)
    }

    fun report(ok: Boolean, what: String) {
        if (ok) {
            haptics.perform(Haptic.Success)
            overlays.toast("$what sent to your Mac", WIcons.CheckCircle, colors.green)
        } else {
            overlays.toast("Couldn't reach your Mac", WIcons.Warning, colors.red)
        }
    }

    fun confirmForget() {
        overlays.showActionSheet(ActionSheetSpec(
            message = "Codes and files stop going to $macName until you pair again.",
            actions = listOf(AlertAction("Forget $macName", destructive = true) { link.forgetMac() }),
        ))
    }

    LaunchedEffect(transfer?.phase) {
        when (transfer?.phase) {
            TransferPhase.Done -> {
                haptics.perform(Haptic.Success)
                delay(4_000)
                link.clearFinishedTransfer()
            }
            TransferPhase.Failed -> haptics.perform(Haptic.Failure)
            else -> Unit
        }
    }

    val macActions = MacActions(
        sendPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        sendFiles = { pickFiles.launch(arrayOf("*/*")) },
        sendText = {
            overlays.showSheet(
                title = "Send text",
                leading = { TextAction("Cancel", onClick = { overlays.dismissSheet() }) },
                trailing = {
                    TextAction("Send", bold = true, enabled = draft.value.isNotBlank(), onClick = {
                        val text = draft.value
                        overlays.dismissSheet()
                        draft.value = ""
                        scope.launch { report(link.sendText(text), "Text") }
                    })
                },
            ) {
                WTextField(draft.value, { draft.value = it }, "It lands on your Mac's clipboard", singleLine = false, minLines = 5)
            }
        },
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
        cancelTransfer = { link.cancelTransfer() },
        dismissTransfer = { link.clearFinishedTransfer() },
        openSettings = { onTab(2) },
        openActivity = { onTab(1) },
        more = { anchor ->
            overlays.showMenu(anchor, listOf(
                MenuItem("Settings", WIcons.Settings) { onTab(2) },
                MenuItem("Forget this Mac", WIcons.LinkBreak, destructive = true) { confirmForget() },
            ))
        },
    )

    val settingsActions = SettingsActions(
        allowSms = { single.launch(Manifest.permission.RECEIVE_SMS) },
        allowCalls = { multiple.launch(CallControl.permissions) },
        allowTexts = { multiple.launch(SmsStore.permissions) },
        allowNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) single.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        openNotificationAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        allowBackground = {
            if (!isBackgroundAllowed(context)) {
                batteryAskedAt = android.os.SystemClock.elapsedRealtime()
                runCatching {
                    battery.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }.onFailure { openAppBatterySettings(context) }
            }
        },
        setFeature = { key, on ->
            app.wallhop.core.Features.set(context, key, on)
            if (key == app.wallhop.core.Features.Key.Background && on) LinkService.start(context)
            onPermissionChange()
        },
        forgetMac = { confirmForget() },
        sendTestCode = { link.sendOtp((100000..999999).random().toString(), "Lync test", "debug") },
    )

    // All three tabs stay alive: switching is instant and each keeps its scroll position, like iOS.
    // Hidden tabs aren't drawn and their animations pause.
    val visited = remember { mutableStateListOf(tab) }
    LaunchedEffect(tab) { if (tab !in visited) visited.add(tab) }
    Box(Modifier.fillMaxSize()) {
        for (index in 0..2) {
            if (index !in visited) continue
            key(index) {
                TabSlot(visible = index == tab && !showApps) {
                    when (index) {
                        0 -> MacTab(state, macName, transfer, needsSetup = !setup.essentialsDone, actions = macActions, card = card)
                        1 -> ActivityTab(activity, overlays, onDelete = { link.removeActivity(it) })
                        else -> SettingsTab(setup, link.pairedMac?.name, mirrored.size, settingsActions, openApps = { onShowApps(true) })
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
                hasNotificationAccess = setup.notificationAccess,
                onOpenAccess = settingsActions.openNotificationAccess,
                onBack = { onShowApps(false) },
            )
        }
    }
}

/** A kept-alive tab: fades and drifts in when selected; when hidden it isn't drawn or announced. */
@Composable
private fun TabSlot(visible: Boolean, content: @Composable () -> Unit) {
    val reduceMotion = Wallhop.a11y.reduceMotion
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (visible) 1f else 0f,
        if (reduceMotion) tween(0) else tween(if (visible) 200 else 120),
        label = "tab",
    )
    val drift = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .graphicsLayer {
                alpha = shown
                translationY = (1f - shown) * drift
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
        app.wallhop.link.ActivityEntry("1", app.wallhop.link.ActivityEntry.Kind.Otp, "482913", "From HDFC Bank · sent to your Mac", now - 120_000),
        app.wallhop.link.ActivityEntry("2", app.wallhop.link.ActivityEntry.Kind.FilesSent, "3 photos", "Copied on your Mac · save it there if you like", now - hour),
        app.wallhop.link.ActivityEntry("3", app.wallhop.link.ActivityEntry.Kind.Call, "Missed call", "Mom", now - 2 * hour),
        app.wallhop.link.ActivityEntry("4", app.wallhop.link.ActivityEntry.Kind.TextSent, "Link opened on your Mac", "https://www.nothing.tech", now - 3 * hour),
        app.wallhop.link.ActivityEntry("5", app.wallhop.link.ActivityEntry.Kind.Otp, "771204", "From Swiggy · sent to your Mac", now - 26 * hour),
        app.wallhop.link.ActivityEntry("6", app.wallhop.link.ActivityEntry.Kind.FromMac, "2 files from your Mac", "Saved to Downloads › Lync", now - 27 * hour),
        app.wallhop.link.ActivityEntry("7", app.wallhop.link.ActivityEntry.Kind.FilesSent, "Invoice.pdf", "Copied on your Mac · save it there if you like", now - 28 * hour),
        app.wallhop.link.ActivityEntry("8", app.wallhop.link.ActivityEntry.Kind.Otp, "903311", "From Google · sent to your Mac", now - 30 * hour),
        app.wallhop.link.ActivityEntry("9", app.wallhop.link.ActivityEntry.Kind.Call, "Missed call", "+91 98765 43210", now - 50 * hour),
    )
}

fun readSetup(context: Context): Setup {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val power = context.getSystemService(PowerManager::class.java)
    val permitted = mapOf(
        Features.Key.Codes to granted(Manifest.permission.RECEIVE_SMS),
        Features.Key.Calls to (granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS)),
        Features.Key.Texts to (granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.SEND_SMS)),
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
        // Notification access is optional, as before.
        essentialsDone = permitted.filterKeys { it != Features.Key.AppNotifications }
            .all { (key, ok) -> ok || !Features.on(context, key) },
    )
}

private fun isBackgroundAllowed(context: Context): Boolean =
    context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** Wallhop's page in Android's settings, where App battery usage › Unrestricted lives. */
private fun openAppBatterySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
