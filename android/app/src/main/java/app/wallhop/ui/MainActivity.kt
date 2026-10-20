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
        val demo = (app.wallhop.BuildConfig.DEBUG || app.wallhop.BuildConfig.STRESS_HOOKS) && intent.getBooleanExtra("demo", false)
        handleIntent(intent)
        setContent {
            WallhopTheme { WallhopRoot(link, demo) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** A notification can ask for a tab (Activity, for files from the Mac). */
    private fun handleIntent(intent: android.content.Intent?) {
        val tab = intent?.getIntExtra(app.wallhop.link.Notifications.EXTRA_TAB, -1) ?: -1
        if (tab in 0..2) AppNav.tab.value = tab
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

/** Asks the app root for a tab; cleared once it's shown. */
internal object AppNav {
    val tab = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
}
