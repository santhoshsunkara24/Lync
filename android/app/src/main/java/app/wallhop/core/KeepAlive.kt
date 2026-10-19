package app.wallhop.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Some phone makers close background apps even when Android allows them to run (see
 * dontkillmyapp.com). For those brands Settings shows one extra step, and this opens the maker's
 * own screen for it when the phone has one, or Wallhop's app page otherwise.
 */
object KeepAlive {
    class Brand(val name: String, val steps: String, val screens: List<ComponentName>)

    private fun c(pkg: String, cls: String) = ComponentName(pkg, cls)

    /** The extra step for this phone's maker; null when Android's own setting is enough. */
    val brand: Brand? by lazy {
        when (Build.MANUFACTURER.lowercase()) {
            "xiaomi", "redmi", "poco" -> Brand(
                "Xiaomi", "Turn on Autostart for Lync, and set Battery saver to No restrictions.",
                listOf(c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            )
            "samsung" -> Brand(
                "Samsung", "Battery › Unrestricted, and keep Lync out of Sleeping apps.",
                emptyList(),
            )
            "oppo", "realme", "oneplus" -> Brand(
                Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                "Allow Auto launch for Lync, and set Battery usage to Allow background activity.",
                listOf(
                    c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    c("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                    c("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                ),
            )
            "vivo", "iqoo" -> Brand(
                "vivo", "Allow Background power consumption, and turn on Auto-start for Lync.",
                listOf(
                    c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                ),
            )
            "huawei", "honor" -> Brand(
                Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                "In App launch, set Lync to Manage manually and turn on all three switches.",
                listOf(
                    c("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    c("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ),
            )
            else -> null
        }
    }

    /** Opens the maker's screen, falling back to Wallhop's app page. */
    fun open(context: Context) {
        val screens = brand?.screens.orEmpty().map { Intent().setComponent(it) } +
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        for (intent in screens) {
            val opened = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (opened) return
        }
    }
}
