package app.wallhop.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** What the paired Mac looks like right now, for the live Mac card (PROTOCOL.md, mac_info). */
data class MacInfo(
    /** 0–100, or null on a desktop Mac. */
    val battery: Int? = null,
    val charging: Boolean = false,
    val locked: Boolean = false,
    val laptop: Boolean = true,
    val notch: Boolean = true,
    /** A small copy of the Mac's wallpaper, or null for moving wallpapers. */
    val wallpaper: Bitmap? = null,
)

/**
 * Keeps the last [MacInfo] on the phone, so the card still shows the Mac's wallpaper while
 * it's away. The wallpaper is a ~25 KB JPEG; the rest lives in preferences.
 */
internal class MacInfoStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("mac_info", Context.MODE_PRIVATE)
    private val file get() = File(context.filesDir, "mac_wallpaper.jpg")

    fun load(): MacInfo = MacInfo(
        battery = prefs.getInt("battery", -1).takeIf { it >= 0 },
        charging = prefs.getBoolean("charging", false),
        laptop = prefs.getBoolean("laptop", true),
        notch = prefs.getBoolean("notch", true),
        wallpaper = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull(),
    )

    fun save(info: MacInfo, wallpaperJpeg: ByteArray?) {
        if (wallpaperJpeg != null) file.writeBytes(wallpaperJpeg)
        prefs.edit()
            .putInt("battery", info.battery ?: -1)
            .putBoolean("charging", info.charging)
            .putBoolean("laptop", info.laptop)
            .putBoolean("notch", info.notch)
            .apply()
    }

    fun clear() {
        file.delete()
        prefs.edit().clear().apply()
    }
}
