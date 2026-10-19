package app.wallhop.link

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.wallhop.R
import app.wallhop.ui.MainActivity

object Notifications {
    const val CHANNEL_LINK = "link"
    const val CHANNEL_FROM_MAC = "from_mac"
    const val LINK_NOTIFICATION_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Connection to your Mac", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Lync connected so codes and files reach your Mac instantly."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FROM_MAC, "From your Mac", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Text and files sent from your Mac."
            },
        )
    }

    fun linkNotification(context: Context, state: LinkState): Notification {
        val text = when (state) {
            is LinkState.Connected -> "Connected to ${state.macName}"
            is LinkState.Searching -> "Looking for ${state.macName}"
            is LinkState.Pairing -> "Pairing with ${state.macName}"
            else -> "Not paired with a Mac"
        }
        return NotificationCompat.Builder(context, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Puts text from the Mac on the clipboard and says so. */
    fun textFromMac(context: Context, text: String, macName: String) {
        Handler(Looper.getMainLooper()).post {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("From $macName", text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
                }
            }
            runCatching { clipboard.setPrimaryClip(clip) }
        }
        post(context, text.hashCode(), NotificationCompat.Builder(context, CHANNEL_FROM_MAC)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle("Copied from $macName")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build())
    }

    fun filesFromMac(context: Context, summary: String) {
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        post(context, summary.hashCode(), NotificationCompat.Builder(context, CHANNEL_FROM_MAC)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle("Received $summary")
            .setContentText("Saved to Downloads › Lync")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        if (!app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Notifications)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
