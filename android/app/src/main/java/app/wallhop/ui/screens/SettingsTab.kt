package app.wallhop.ui.screens

import app.wallhop.core.Features
import app.wallhop.ui.design.WIcons
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import app.wallhop.ui.design.predictiveBack
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wallhop.BuildConfig
import app.wallhop.notify.MirroredApps
import app.wallhop.ui.components.CircleButton
import app.wallhop.ui.components.EmptyState
import app.wallhop.ui.components.IconTile
import app.wallhop.ui.components.insetSectionItems
import app.wallhop.ui.components.InsetSection
import app.wallhop.ui.components.ListRow
import app.wallhop.ui.components.SearchField
import app.wallhop.ui.design.Wallhop
import app.wallhop.ui.design.flashPulse
import app.wallhop.ui.design.rounded
import app.wallhop.ui.design.staggerIn
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the phone has granted. */
data class Setup(
    val sms: Boolean,
    val calls: Boolean,
    val texts: Boolean,
    val notifications: Boolean,
    val notificationAccess: Boolean,
    val background: Boolean,
    val bluetooth: Boolean = false,
    /** Features whose Android permission is granted, whether or not they're switched on. */
    val granted: Set<Features.Key>,
    /** Bluetooth backup can't work: Bluetooth is off, or the phone can't do it. */
    val bluetoothProblem: app.wallhop.core.Problem? = null,
    /** Alerts are on in Lync, but Android blocks Lync's notifications. */
    val notificationsBlocked: Boolean = false,
    /** Run in background is on, but Android refused to keep Lync running. */
    val cantStayRunning: Boolean = false,
)

class SettingsActions(
    val allowSms: () -> Unit,
    val allowCalls: () -> Unit,
    val allowTexts: () -> Unit,
    val allowNotifications: () -> Unit,
    val allowBackground: () -> Unit,
    val allowBluetooth: () -> Unit,
    /** Wallhop's own switch for a feature (Features); off never needs Android's settings. */
    val setFeature: (Features.Key, Boolean) -> Unit,
    val forgetMac: () -> Unit,
    val sendTestCode: () -> Unit,
    val macDetails: () -> Unit,
    val openLicences: () -> Unit,
    val openPrivacy: () -> Unit,
    /** Adds the "Clipboard to Mac" Quick Settings tile (or explains how). */
    val addTile: () -> Unit,
    /** Opens the settings page a problem's button points to (Bluetooth, notifications, battery). */
    val runFix: (app.wallhop.core.Fix) -> Unit = {},
)

/** Permissions and preferences as an inset grouped list (DESIGN.md §6.6). */
@Composable
fun SettingsTab(
    setup: Setup,
    macName: String?,
    macStatus: String?,
    mirroredCount: Int,
    actions: SettingsActions,
    openApps: () -> Unit,
) {
    val colors = Wallhop.colors
    // On: Wallhop's switch goes on, and Android's permission popup appears if it's still needed.
    // Off: Wallhop stops using the feature; the permission stays, so no trip to Android's settings.
    fun toggle(key: Features.Key, request: () -> Unit): (Boolean) -> Unit = { on ->
        actions.setFeature(key, on)
        if (on && key !in setup.granted) request()
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (AppListCache.apps == null) AppListCache.load(context) }
    Page(title = "Settings") {
        item(key = "mac") {
            Column(Modifier.staggerIn(1)) {
                SectionHeader("On your Mac")
                InsetSection(dividerInset = 57.dp) {
                    row {
                        ListRow(
                            title = "Codes",
                            subtitle = "Only the code, never the message",
                            leading = { IconTile(WIcons.Code, colors.green) },
                            checked = setup.sms,
                            onCheckedChange = toggle(Features.Key.Codes, actions.allowSms),
                        )
                    }
                    row {
                        ListRow(
                            title = "Calls",
                            subtitle = "See who's calling",
                            leading = { IconTile(WIcons.Call, colors.green) },
                            checked = setup.calls,
                            onCheckedChange = toggle(Features.Key.Calls, actions.allowCalls),
                        )
                    }
                    row {
                        ListRow(
                            title = "Texts",
                            subtitle = "Read and reply from your Mac",
                            leading = { IconTile(WIcons.Chats, colors.green) },
                            checked = setup.texts,
                            onCheckedChange = toggle(Features.Key.Texts, actions.allowTexts),
                        )
                    }
                }
                Text(
                    "Turn on only what you want. Everything goes only to your paired Mac, encrypted.",
                    style = app.wallhop.ui.design.Type.footnote,
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
        item(key = "notifications") {
            Column(Modifier.staggerIn(2)) {
                SectionHeader("Notifications")
                InsetSection(dividerInset = 57.dp) {
                    row {
                        // Picking apps and granting notification access both live in this one flow.
                        ListRow(
                            title = "Phone notifications on Mac",
                            leading = { IconTile(WIcons.BellRinging, colors.red) },
                            value = if (mirroredCount == 0 || !setup.notificationAccess) "Off" else "$mirroredCount apps",
                            animateValue = true,
                            chevron = true,
                            onClick = openApps,
                        )
                    }
                    row {
                        ListRow(
                            title = "Alerts on this phone",
                            subtitle = if (setup.notificationsBlocked) app.wallhop.core.Problems.NOTIFICATIONS_BLOCKED.sentence
                                else "Files and text from your Mac, and sending progress",
                            leading = { IconTile(WIcons.Bell, colors.red) },
                            checked = setup.notifications,
                            onCheckedChange = toggle(Features.Key.Notifications, actions.allowNotifications),
                        )
                    }
                    if (setup.notificationsBlocked) fixRow(app.wallhop.core.Problems.NOTIFICATIONS_BLOCKED, actions.runFix)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        item(key = "connected") {
            Column(Modifier.staggerIn(3)) {
                SectionHeader("Stay connected")
                InsetSection(
                    dividerInset = 57.dp,
                ) {
                    row {
                        ListRow(
                            title = "Run in background",
                            leading = { IconTile(WIcons.BatteryCharging, colors.orange) },
                            subtitle = if (setup.cantStayRunning) app.wallhop.core.Problems.CANT_STAY_RUNNING.sentence
                                else "Stay connected when Lync is closed",
                            checked = setup.background,
                            onCheckedChange = toggle(Features.Key.Background, actions.allowBackground),
                        )
                    }
                    if (setup.cantStayRunning) fixRow(app.wallhop.core.Problems.CANT_STAY_RUNNING, actions.runFix)
                    val bluetoothProblem = setup.bluetoothProblem
                    row {
                        ListRow(
                            title = "Bluetooth backup",
                            leading = { IconTile(WIcons.Bluetooth, colors.indigo) },
                            subtitle = bluetoothProblem?.sentence
                                ?: "Codes, calls and notifications when your Mac is nearby but not on your Wi‑Fi",
                            subtitleMaxLines = Int.MAX_VALUE,
                            checked = setup.bluetooth,
                            onCheckedChange = toggle(Features.Key.Bluetooth, actions.allowBluetooth),
                            // A phone without Bluetooth LE can't turn it on at all.
                            switchEnabled = bluetoothProblem != app.wallhop.core.Problems.BLUETOOTH_UNSUPPORTED,
                        )
                    }
                    if (bluetoothProblem != null) fixRow(bluetoothProblem, actions.runFix)
                    app.wallhop.core.KeepAlive.brand?.let { brand ->
                        row {
                            val context = LocalContext.current
                            ListRow(
                                title = "Keep running on ${brand.name}",
                                subtitle = brand.steps,
                                subtitleMaxLines = Int.MAX_VALUE,
                                leading = { IconTile(WIcons.Lightning, colors.orange) },
                                chevron = true,
                                onClick = { app.wallhop.core.KeepAlive.open(context) },
                            )
                        }
                    }
                    row {
                        ListRow(
                            title = "Send clipboard tile",
                            subtitle = "One tap in Quick Settings sends your clipboard",
                            leading = { IconTile(WIcons.Clipboard, colors.indigo) },
                            chevron = true,
                            onClick = actions.addTile,
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        app.wallhop.glyph.Glyphs.hardware?.let { hardware ->
            item(key = "glyph") { Column(Modifier.staggerIn(4)) { GlyphSection(hardware) } }
        }
        if (macName != null) {
            item(key = "pairedMac") {
                Column(Modifier.staggerIn(5)) {
                    SectionHeader("Mac")
                    InsetSection(dividerInset = 57.dp) {
                        row {
                            ListRow(
                                title = macName,
                                subtitle = macStatus,
                                leading = { IconTile(WIcons.Laptop, colors.gray) },
                                chevron = true,
                                onClick = actions.macDetails,
                            )
                        }
                        row {
                            ListRow(
                                title = "Forget this Mac",
                                leading = { IconTile(WIcons.LinkBreak, colors.red) },
                                destructive = true,
                                onClick = actions.forgetMac,
                            )
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
        item(key = "about") {
            Column(Modifier.staggerIn(6)) {
                SectionHeader("About")
                InsetSection(
                    dividerInset = 57.dp,
                    footer = "Everything travels encrypted between your phone and Mac over your own network. No account, no cloud.",
                ) {
                    row {
                        ListRow(
                            title = "Version",
                            leading = { IconTile(WIcons.Info, colors.gray) },
                            value = BuildConfig.VERSION_NAME,
                        )
                    }
                    row {
                        ListRow(
                            title = "Open-source licences",
                            leading = { IconTile(WIcons.Scroll, colors.gray) },
                            chevron = true,
                            onClick = actions.openLicences,
                        )
                    }
                    row {
                        ListRow(
                            title = "Privacy policy",
                            leading = { IconTile(WIcons.Shield, colors.gray) },
                            chevron = true,
                            onClick = actions.openPrivacy,
                        )
                    }
                }
            }
        }
        // Test builds only.
        if (BuildConfig.STRESS_HOOKS) {
            item(key = "developer") {
                Column {
                    Spacer(Modifier.height(32.dp))
                    SectionHeader("Developer")
                    InsetSection(dividerInset = 57.dp) {
                        row {
                            ListRow(
                                title = "Send a test code",
                                leading = { IconTile(WIcons.Code, colors.gray) },
                                onClick = actions.sendTestCode,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The row under a setting that isn't working: its problem's button ("Turn on Bluetooth", "Battery settings"). */
private fun app.wallhop.ui.components.SectionScope.fixRow(problem: app.wallhop.core.Problem, runFix: (app.wallhop.core.Fix) -> Unit) {
    val fix = problem.fix ?: return
    row {
        ListRow(
            title = if (fix == app.wallhop.core.Fix.TurnOnBluetooth) "Turn on Bluetooth" else fix.label,
            leading = { Box(Modifier.size(29.dp)) },
            titleColor = Wallhop.colors.tint,
            chevron = true,
            onClick = { runFix(fix) },
        )
    }
}

private data class AppEntry(val packageName: String, val label: String)

/**
 * The phone's apps, read once and kept: Settings warms it while it's on screen, so the apps page
 * slides in already filled rather than with a spinner that the list then pops over.
 */
private object AppListCache {
    @Volatile var apps: List<AppEntry>? = null
    suspend fun load(context: Context): List<AppEntry> =
        withContext(Dispatchers.IO) { launchableApps(context) }.also { apps = it }
}

/** Pick which apps' notifications show on the Mac (DESIGN.md §6.7). */
@Composable
fun AppsScreen(
    hasNotificationAccess: Boolean,
    switchedOn: Boolean,
    onOpenAccess: () -> Unit,
    onSwitchOn: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Wallhop.colors
    val mirrored = remember { MirroredApps.get(context) }
    val enabled by mirrored.enabled.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    // Shown from the cache at once; refreshed underneath in case apps were installed or removed.
    val apps by produceState(AppListCache.apps) { value = AppListCache.load(context) }
    val recent = remember { mirrored.recent() }
    // Which section an app sits in is decided when the list opens (and on each search), not on
    // every switch: turning one on leaves its row under the finger instead of moving it away.
    // Next time, it's in "On".
    val grouped = remember(query, apps != null) { enabled }

    Page(
        modifier = Modifier.predictiveBack(onBack = onBack),
        title = "Phone notifications on Mac",
        subtitle = "Pick the apps that show on your Mac. Where an app supports quick reply, you can answer from the Mac.",
        leading = { CircleButton(WIcons.ChevronLeft, "Back", onClick = { onBack() }) },
    ) {
        // Android's access is there but Lync's own switch is off: turn it on here, no trip to Settings.
        if (hasNotificationAccess && !switchedOn) {
            item(key = "access") {
                InsetSection(footer = "Notification access is turned off in Lync, so no notifications go to your Mac.") {
                    row {
                        ListRow(
                            title = "Turn on notification access",
                            leading = { IconTile(WIcons.Shield, colors.indigo) },
                            checked = false,
                            onCheckedChange = { if (it) onSwitchOn() },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (!hasNotificationAccess) {
            item(key = "access") {
                InsetSection(footer = "Android asks you to allow Lync to see notifications. Lync also finds codes in other apps' notifications this way.") {
                    row {
                        ListRow(
                            title = "Turn on notification access",
                            leading = { IconTile(WIcons.Shield, colors.indigo) },
                            chevron = true,
                            onClick = onOpenAccess,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        item(key = "search") {
            SearchField(query, { query = it }, placeholder = "Search apps")
            Spacer(Modifier.height(24.dp))
        }
        val all = apps
        if (all == null) {
            item(key = "loading") { Box(Modifier.padding(24.dp)) { app.wallhop.ui.components.ActivityIndicator() } }
            return@Page
        }
        val filtered = all.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
        val on = filtered.filter { it.packageName in grouped }
        val recentApps = recent.mapNotNull { pkg -> filtered.firstOrNull { it.packageName == pkg } }
            .filter { it.packageName !in grouped }.take(8)
        val rest = filtered.filter { it.packageName !in grouped && it !in recentApps }

        if (filtered.isEmpty()) {
            item(key = "none") { EmptyState(WIcons.BellRinging, "No matches", "No apps match “$query”.") }
        }
        listOf("On" to on, "Recently active" to recentApps, "All apps" to rest).forEach { (header, list) ->
            if (list.isNotEmpty()) {
                item(key = "section-$header") { SectionHeader(header) }
                // One lazy item per app: only the rows on screen are built and decode icons.
                // Keyed by app alone, so a new search moves rows rather than blinking them.
                insetSectionItems(list, key = { it.packageName }, dividerInset = 57.dp) { app ->
                    ListRow(
                        title = app.label,
                        leading = { AppIcon(app.packageName) },
                        checked = app.packageName in enabled,
                        onCheckedChange = { mirrored.setEnabled(app.packageName, it) },
                    )
                }
                item(key = "gap-$header") { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

/**
 * Decoded app icons, shared across visits to the app list. Bounded to ~3 MB (about 80 icons),
 * so scrolling back is instant without the list holding every icon on the phone in memory.
 */
private val iconCache = object : android.util.LruCache<String, ImageBitmap>(3 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Frees the icon cache, e.g. when the system is low on memory. */
fun trimAppIcons() = iconCache.evictAll()

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    // 29 dp is the size drawn; decoding any bigger only wastes memory.
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 29.dp.roundToPx() }
    // Keyed by package: a reused list row must never keep showing the previous app's icon.
    var icon by remember(packageName) { mutableStateOf(iconCache.get(packageName)) }
    LaunchedEffect(packageName) {
        if (icon != null) return@LaunchedEffect
        icon = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(px, px, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }?.also { iconCache.put(packageName, it) }
    }
    Box(Modifier.size(29.dp).clip(rounded(7.dp))) {
        icon?.let { Image(it, null, Modifier.size(29.dp)) }
    }
}

private fun launchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** Nothing phones only: which events light the Glyphs. */
@Composable
private fun GlyphSection(hardware: app.wallhop.glyph.Glyphs.Hardware) {
    val context = LocalContext.current
    val overlays = app.wallhop.ui.components.LocalOverlays.current
    val colors = Wallhop.colors
    var ring by remember { mutableStateOf(Features.on(context, Features.Key.GlyphRing)) }
    var connect by remember { mutableStateOf(Features.on(context, Features.Key.GlyphConnect)) }
    val matrix = hardware == app.wallhop.glyph.Glyphs.Hardware.Matrix
    SectionHeader(if (matrix) "Glyph Matrix" else "Glyph lights")
    InsetSection(
        dividerInset = 57.dp,
        footer = if (matrix) "Lync's link lights up on the Glyph Matrix." else "Your Glyph lights pulse.",
    ) {
        row {
            ListRow(
                title = "When your Mac rings this phone",
                leading = { IconTile(WIcons.BellRinging, colors.orange) },
                checked = ring,
                onCheckedChange = { on -> ring = on; Features.set(context, Features.Key.GlyphRing, on) },
            )
        }
        row {
            ListRow(
                title = "When your Mac connects",
                leading = { IconTile(WIcons.Link, colors.indigo) },
                checked = connect,
                onCheckedChange = { on -> connect = on; Features.set(context, Features.Key.GlyphConnect, on) },
            )
        }
        row {
            var flashes by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            ListRow(
                title = "Try it",
                // The bolt flashes with the lights on the back.
                leading = { Box(Modifier.flashPulse(flashes)) { IconTile(WIcons.Lightning, colors.gray) } },
                onClick = {
                    flashes++
                    app.wallhop.glyph.Glyphs.preview(context)
                    overlays?.toast("Look at the back of your phone")
                },
            )
        }
    }
    Spacer(Modifier.height(32.dp))
}
