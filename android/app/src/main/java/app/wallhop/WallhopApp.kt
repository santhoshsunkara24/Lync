package app.wallhop

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.wallhop.link.Notifications
import app.wallhop.share.ShareActivity

class WallhopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

/** Puts the paired Mac in the share sheet's top row (Direct Share). */
object Shortcuts {
    private const val ID = "paired_mac"
    private const val CATEGORY = "app.wallhop.category.SEND_TO_MAC"

    fun publish(context: Context, macName: String) {
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(macName)
            .setLongLabel("Send to $macName")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }

    fun remove(context: Context) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(ID)) }
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(ID)) }
    }
}
