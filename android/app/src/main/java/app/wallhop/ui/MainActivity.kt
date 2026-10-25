package app.wallhop.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.wallhop.link.LinkManager
import app.wallhop.link.LinkService
import app.wallhop.ui.design.WallhopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Fully transparent bars, light icons: the app is always dark (DESIGN.md §3.1).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        // The foreground service keeps the link alive after the app is closed.
        LinkService.start(this)
        val link = LinkManager.get(this)
        // Test builds only (debug and benchmark): `adb shell am start -n app.wallhop/.ui.MainActivity
        // --ez demo true` shows the paired screens with sample data, for design review and screenshots.
        // The preview build (to share for a look) always shows sample data.
        val demo = app.wallhop.BuildConfig.PREVIEW ||
            (app.wallhop.BuildConfig.DEBUG || app.wallhop.BuildConfig.STRESS_HOOKS) && intent.getBooleanExtra("demo", false)
        // With sample data, `--es demo_screen intro|connect|pairing|paired|linked|allow|sending` opens one screen on
        // its own (sending files, the pairing code, pairing done, finding a Mac), for screenshots.
        val demoScreen = if (demo) intent.getStringExtra("demo_screen") else null
        handleIntent(intent)
        setContent {
            WallhopTheme(brand = demo) {
                if (demoScreen != null) DemoScreen(demoScreen) else WallhopRoot(link, demo)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * A notification can ask for a tab (Activity, for files from the Mac), to pair again (the Mac
     * forgot this phone or was reset), or for the picker (files to choose again).
     */
    private fun handleIntent(intent: android.content.Intent?) {
        val tab = intent?.getIntExtra(app.wallhop.link.Notifications.EXTRA_TAB, -1) ?: -1
        if (tab in 0..2) AppNav.tab.value = tab
        if (intent?.getBooleanExtra(app.wallhop.link.Notifications.EXTRA_PAIR_AGAIN, false) == true) {
            intent.removeExtra(app.wallhop.link.Notifications.EXTRA_PAIR_AGAIN) // Not again on rotation.
            LinkManager.get(this).pairAgain()
        }
        if (intent?.getBooleanExtra(app.wallhop.link.Notifications.EXTRA_PICK, false) == true) {
            intent.removeExtra(app.wallhop.link.Notifications.EXTRA_PICK)
            LinkManager.get(this).clearFinishedTransfer()
            AppNav.pick.value = true
        }
    }

    override fun onStart() {
        super.onStart()
        app.wallhop.link.AppVisibility.foreground = true
        LinkService.screensOpen++
        Pickers.open = false
        LinkService.start(this)
    }

    override fun onStop() {
        super.onStop()
        app.wallhop.link.AppVisibility.foreground = false
        LinkService.screensOpen = (LinkService.screensOpen - 1).coerceAtLeast(0)
        // "Run in background" off in Lync: stay connected only while the app is open. Not while
        // a photo or file picker is up (the app comes straight back), and not while a transfer
        // is waiting or sending: the service stops itself once that ends.
        if (!isChangingConfigurations && !Pickers.open) LinkService.stopIfIdle(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // In the background, or the system is short of memory: icons can be decoded again later.
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_UI_HIDDEN) app.wallhop.ui.screens.trimAppIcons()
    }
}

/** True while Lync's own photo or file picker is on screen, so leaving for it doesn't drop the link. */
internal object Pickers {
    @Volatile var open = false
}

/** Asks the app root for a tab, or the last picker; cleared once done. */
internal object AppNav {
    val tab = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val pick = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/** One screen with sample data, for screenshots (test builds and the preview build only). */
@androidx.compose.runtime.Composable
private fun DemoScreen(which: String) {
    val mac = "MacBook Air"
    when (which) {
        "sending" -> {
            val uri = android.net.Uri.parse("content://app.wallhop.demo/photo")
            val items = List(3) { app.wallhop.link.TransferItem(uri, "IMG_20261023_0${941 + it}.jpg", 2_400_000L, "image/jpeg") }
            app.wallhop.ui.screens.SendingScreen(
                app.wallhop.link.TransferState("demo", items, 4_600_000L, 7_200_000L, app.wallhop.link.TransferPhase.Sending),
                mac, onHide = {}, onCancel = {},
            )
        }
        "intro" -> app.wallhop.ui.screens.IntroTour(onDone = {})
        "allow" -> app.wallhop.ui.screens.AllowCodesScreen(onAllow = {}, onLater = {})
        "linked" -> app.wallhop.ui.screens.LinkedMoment(mac)
        "pairing", "paired" -> app.wallhop.ui.screens.PairingScreen(
            app.wallhop.link.LinkState.Pairing(mac, "305718"), onCancel = {}, success = which == "paired",
        )
        else -> {
            val overlays = androidx.compose.runtime.remember { app.wallhop.ui.components.Overlays() }
            app.wallhop.ui.screens.ConnectScreen(
                macs = listOf(app.wallhop.link.DiscoveredMac(mac, "192.168.0.4", 52700)),
                problem = null, overlays = overlays, onPair = { _, _, _, _ -> }, onDismissProblem = {}, onScan = {},
            )
        }
    }
}
