package app.wallhop.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Is the phone on a local network the Mac could be on (Wi‑Fi or Ethernet)? */
object LocalNetwork {
    /** Joined to a Wi‑Fi (or Ethernet) network, whether or not it's the default one. */
    @Suppress("DEPRECATION") // allNetworks: the default network alone hides Wi‑Fi without internet behind mobile data.
    fun joined(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } == true
            }
        }.getOrDefault(true)
    }

    /** Wi‑Fi is switched on (it may not be joined to anything). */
    fun radioOn(context: Context): Boolean =
        runCatching { context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled }
            .getOrNull() ?: true
}

/**
 * Opens the place a [Fix] points to in Android's settings (or the releases page). Fixes that
 * need the app (Try again, Pair again, Pair your Mac, Choose again) return false: the caller does
 * those. When nothing can be opened, an Android toast says where to go instead.
 */
object Open {
    fun intent(context: Context, fix: Fix): Intent? = when (fix) {
        Fix.WifiSettings -> Intent(Settings.Panel.ACTION_WIFI)
        Fix.StorageSettings -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        Fix.NotificationSettings -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        Fix.BatterySettings -> appDetails(context)
        Fix.TurnOnBluetooth -> Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
        Fix.Update -> Intent(Intent.ACTION_VIEW, Uri.parse(Wording.RELEASES_URL))
        Fix.OpenLync -> Intent(context, app.wallhop.ui.MainActivity::class.java)
        else -> null
    }

    /** Opens it. True when this fix is one [Open] handles (whether or not a screen opened). */
    @android.annotation.SuppressLint("MissingPermission") // ACTION_REQUEST_ENABLE: Nearby devices is checked by the Bluetooth switch first.
    fun run(context: Context, fix: Fix): Boolean {
        val intent = intent(context, fix) ?: return false
        if (start(context, intent)) return true
        // Fallbacks: the full Wi‑Fi page, then Lync's own page; otherwise say where to go.
        val fallback = when (fix) {
            Fix.WifiSettings -> Intent(Settings.ACTION_WIFI_SETTINGS)
            Fix.StorageSettings -> Intent(Settings.ACTION_SETTINGS)
            Fix.NotificationSettings, Fix.TurnOnBluetooth -> appDetails(context)
            else -> null
        }
        if (fallback != null && start(context, fallback)) return true
        toast(context, if (fix == Fix.Update) Problems.NO_BROWSER else Problems.NO_SETTINGS)
        return true
    }

    /** Opens a link in a browser, or says there isn't one. */
    fun link(context: Context, url: String) {
        if (!start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) toast(context, Problems.NO_BROWSER)
    }

    /** Lync's page in Android's settings (permissions, battery), or a toast saying where it is. */
    fun appSettings(context: Context) {
        if (!start(context, appDetails(context))) toast(context, Problems.NO_SETTINGS)
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
