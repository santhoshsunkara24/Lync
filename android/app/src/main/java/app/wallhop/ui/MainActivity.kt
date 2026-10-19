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
        // Debug builds only: `adb shell am start -n app.wallhop/.ui.MainActivity --ez demo true`
        // shows the paired screens with sample data, for design review.
        val demo = app.wallhop.BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)
        setContent {
            WallhopTheme { WallhopRoot(link, demo) }
        }
    }

    override fun onStart() {
        super.onStart()
        LinkService.start(this)
    }

    override fun onStop() {
        super.onStop()
        // "Run in background" off in Wallhop: stay connected only while the app is open.
        if (!isChangingConfigurations && !app.wallhop.core.Features.on(this, app.wallhop.core.Features.Key.Background)) {
            LinkService.stop(this)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // In the background, or the system is short of memory: icons can be decoded again later.
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_UI_HIDDEN) app.wallhop.ui.screens.trimAppIcons()
    }
}
