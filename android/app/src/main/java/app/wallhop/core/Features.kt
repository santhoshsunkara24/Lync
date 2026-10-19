package app.wallhop.core

import android.content.Context

/**
 * Wallhop's own on/off switches, separate from Android permissions. Turning a feature off here
 * stops Wallhop using it without a trip to Android's settings (apps can't revoke their own
 * permissions). Everything starts on, so a granted permission works as before.
 */
object Features {
    enum class Key(val pref: String) {
        Codes("codes"),
        Calls("calls"),
        Texts("texts"),
        Notifications("notifications"),
        AppNotifications("app_notifications"),
        Background("background"),
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("features", Context.MODE_PRIVATE)

    fun on(context: Context, key: Key): Boolean = prefs(context).getBoolean(key.pref, true)

    fun set(context: Context, key: Key, on: Boolean) {
        prefs(context).edit().putBoolean(key.pref, on).apply()
    }
}
