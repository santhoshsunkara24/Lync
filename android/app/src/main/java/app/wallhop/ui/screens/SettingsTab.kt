package app.wallhop.ui.screens

import app.wallhop.core.Features
import app.wallhop.ui.design.WIcons
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
    /** Features whose Android permission is granted, whether or not they're switched on. */
    val granted: Set<Features.Key>,
    /** Nothing the user switched on is waiting for a permission. */
    val essentialsDone: Boolean,
)

class SettingsActions(
    val allowSms: () -> Unit,
    val allowCalls: () -> Unit,
    val allowTexts: () -> Unit,
    val allowNotifications: () -> Unit,
    val openNotificationAccess: () -> Unit,
    val allowBackground: () -> Unit,
    /** Wallhop's own switch for a feature (Features); off never needs Android's settings. */
    val setFeature: (Features.Key, Boolean) -> Unit,
    val forgetMac: () -> Unit,
    val sendTestCode: () -> Unit,
    val macDetails: () -> Unit,
    val openLicences: () -> Unit,
    val openPrivacy: () -> Unit,
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
    Page(title = "Settings") {
        item(key = "mac") {
            Column(Modifier.staggerIn(1)) {
                SectionHeader("On your Mac")
                InsetSection(dividerInset = 57.dp) {
                    row {
                        ListRow(
                            title = "App notifications",
                            leading = { IconTile(WIcons.BellRinging, colors.red) },
                            value = if (mirroredCount == 0) "Off" else "$mirroredCount apps",
                        animateValue = true,
                            chevron = true,
                            onClick = openApps,
                        )
                    }
                    row {
                        ListRow(
                            title = "Codes from messages",
                            leading = { IconTile(WIcons.Code, colors.green) },
                            checked = setup.sms,
                            onCheckedChange = toggle(Features.Key.Codes, actions.allowSms),
                        )
                    }
                    row {
                        ListRow(
                            title = "Calls",
                            leading = { IconTile(WIcons.Call, colors.green) },
                            checked = setup.calls,
                            onCheckedChange = toggle(Features.Key.Calls, actions.allowCalls),
                        )
                    }
                    row {
                        ListRow(
                            title = "Texts",
                            leading = { IconTile(WIcons.Chats, colors.green) },
                            checked = setup.texts,
                            onCheckedChange = toggle(Features.Key.Texts, actions.allowTexts),
                        )
                    }
                }
                Text(
                    "Everything goes only to your paired Mac. Call audio stays on the phone.",
                    style = app.wallhop.ui.design.Type.footnote,
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
        item(key = "connected") {
            Column(Modifier.staggerIn(2)) {
                SectionHeader("Stay connected")
                InsetSection(
                    dividerInset = 57.dp,
                ) {
                    row {
                        ListRow(
                            title = "Run in background",
                            leading = { IconTile(WIcons.BatteryCharging, colors.orange) },
                            subtitle = "Stay connected when Lync is closed",
                            checked = setup.background,
                            onCheckedChange = toggle(Features.Key.Background, actions.allowBackground),
                        )
                    }
                    app.wallhop.core.KeepAlive.brand?.let { brand ->
                        row {
                            val context = LocalContext.current
                            ListRow(
                                title = "Keep running on ${brand.name}",
                                subtitle = brand.steps,
                                leading = { IconTile(WIcons.Lightning, colors.orange) },
                                chevron = true,
                                onClick = { app.wallhop.core.KeepAlive.open(context) },
                            )
                        }
                    }
                    row {
                        ListRow(
                            title = "Notifications",
                            leading = { IconTile(WIcons.Bell, colors.red) },
                            checked = setup.notifications,
                            onCheckedChange = toggle(Features.Key.Notifications, actions.allowNotifications),
                        )
                    }
                    row {
                        ListRow(
                            title = "Notification access",
                            subtitle = "Codes in other apps, and app notifications",
                            leading = { IconTile(WIcons.Shield, colors.indigo) },
                            checked = setup.notificationAccess,
                            onCheckedChange = toggle(Features.Key.AppNotifications, actions.openNotificationAccess),
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
        app.wallhop.glyph.Glyphs.hardware?.let { hardware ->
            item(key = "glyph") { Column(Modifier.staggerIn(3)) { GlyphSection(hardware) } }
        }
        if (macName != null) {
            item(key = "pairedMac") {
                Column(Modifier.staggerIn(4)) {
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
            Column(Modifier.staggerIn(5)) {
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
                    if (BuildConfig.STRESS_HOOKS) {
                        row { ListRow(title = "Send a test code", onClick = actions.sendTestCode) }
                    }
                }
            }
        }
    }
}

private data class AppEntry(val packageName: String, val label: String)

/** Pick which apps' notifications show on the Mac (DESIGN.md §6.7). */
@Composable
fun AppsScreen(
    hasNotificationAccess: Boolean,
    switchedOn: Boolean,
    onOpenAccess: () -> Unit,
    onSwitchOn: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val colors = Wallhop.colors
    val mirrored = remember { MirroredApps.get(context) }
    val enabled by mirrored.enabled.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<AppEntry>?>(null) { value = withContext(Dispatchers.IO) { launchableApps(context) } }
    val recent = remember { mirrored.recent() }

    Page(
        title = "App notifications",
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
                InsetSection(footer = "Lync needs notification access to see notifications.") {
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
        val on = filtered.filter { it.packageName in enabled }
        val recentApps = recent.mapNotNull { pkg -> filtered.firstOrNull { it.packageName == pkg } }
            .filter { it.packageName !in enabled }.take(8)
        val rest = filtered.filter { it.packageName !in enabled && it !in recentApps }

        if (filtered.isEmpty()) {
            item(key = "none") { EmptyState(WIcons.BellRinging, "No matches", "No apps match “$query”.") }
        }
        listOf("On" to on, "Recently active" to recentApps, "All apps" to rest).forEach { (header, list) ->
            if (list.isNotEmpty()) {
                item(key = "section-$header") { SectionHeader(header) }
                // One lazy item per app: only the rows on screen are built and decode icons.
                // Keyed by app alone, so switching one on slides it up into "On" rather than blinking.
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
        footer = if (matrix) "The Lync droid appears on the Glyph Matrix." else "Your Glyph lights pulse.",
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
