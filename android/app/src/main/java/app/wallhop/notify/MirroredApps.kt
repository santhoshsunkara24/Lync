package app.wallhop.notify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which apps' notifications go to the Mac. Off for every app until the user switches it on.
 * Also remembers which apps posted notifications recently (package name and time only),
 * so the picker can suggest them first.
 */
class MirroredApps private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("mirrored_apps", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getStringSet(KEY_ENABLED, emptySet())!!.toSet())
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    fun isEnabled(packageName: String) = packageName in _enabled.value

    fun setEnabled(packageName: String, on: Boolean) {
        val next = if (on) _enabled.value + packageName else _enabled.value - packageName
        _enabled.value = next
        prefs.edit().putStringSet(KEY_ENABLED, next).apply()
    }

    fun noteSeen(packageName: String) {
        val recent = prefs.getString(KEY_RECENT, "").orEmpty()
            .split(',').filter { it.isNotBlank() && it.substringBefore('=') != packageName }
            .take(40)
        prefs.edit().putString(KEY_RECENT, (listOf("$packageName=${System.currentTimeMillis()}") + recent).joinToString(",")).apply()
    }

    /** Packages that posted notifications recently, newest first. */
    fun recent(): List<String> = prefs.getString(KEY_RECENT, "").orEmpty()
        .split(',').filter { it.isNotBlank() }.map { it.substringBefore('=') }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RECENT = "recent"

        @Volatile private var instance: MirroredApps? = null

        fun get(context: Context): MirroredApps = instance ?: synchronized(this) {
            instance ?: MirroredApps(context.applicationContext).also { instance = it }
        }
    }
}
