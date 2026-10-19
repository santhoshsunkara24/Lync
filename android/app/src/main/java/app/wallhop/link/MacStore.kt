package app.wallhop.link

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** The Mac this phone is paired with. */
data class PairedMac(
    val id: String,
    val name: String,
    val publicKey: ByteArray,
    val host: String?,
    val port: Int,
) {
    override fun equals(other: Any?) = other is PairedMac && other.id == id && other.name == name &&
        other.host == host && other.port == port
    override fun hashCode() = id.hashCode()
}

/** Saves the paired Mac in app-private storage. The Mac's public key isn't secret. */
class MacStore(context: Context) {
    private val prefs = context.getSharedPreferences("paired_mac", Context.MODE_PRIVATE)

    fun get(): PairedMac? {
        val json = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            PairedMac(
                id = obj.getString("id"),
                name = obj.getString("name"),
                publicKey = Base64.decode(obj.getString("pub"), Base64.NO_WRAP),
                host = obj.optString("host").ifEmpty { null },
                port = obj.optInt("port", LinkManager.DEFAULT_PORT),
            )
        }.getOrNull()
    }

    fun save(mac: PairedMac) {
        val obj = JSONObject()
            .put("id", mac.id)
            .put("name", mac.name)
            .put("pub", Base64.encodeToString(mac.publicKey, Base64.NO_WRAP))
            .put("host", mac.host ?: "")
            .put("port", mac.port)
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    fun forget() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "mac"
    }
}
