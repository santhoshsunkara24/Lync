package app.wallhop.extras

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.service.quicksettings.TileService
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import app.wallhop.R
import app.wallhop.glyph.Glyphs
import app.wallhop.link.LinkManager
import app.wallhop.link.Notifications
import kotlinx.coroutines.launch

/** True when [text] is a single web link and nothing else. */
fun isSingleUrl(text: String): Boolean {
    val trimmed = text.trim()
    return !trimmed.contains(' ') && !trimmed.contains('\n') &&
        Patterns.WEB_URL.matcher(trimmed).matches() &&
        (trimmed.startsWith("http://") || trimmed.startsWith("https://"))
}

/**
 * Rings the phone loudly, even on silent, so it can be found. Stops after 30 seconds,
 * when the user taps "Found it", or when the Mac says stop.
 */
object PhoneFinder {
    private const val NOTIFICATION_ID = 77
    private const val ACTION_STOP = "app.wallhop.STOP_RINGING"
    private var player: MediaPlayer? = null
    private val stopLater = Runnable { stop(appContext ?: return@Runnable) }
    private var appContext: Context? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        handler.post {
            if (player != null) return@post
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = runCatching {
                MediaPlayer().apply {
                    // The alarm stream plays even when the phone is set to silent or vibrate.
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    setDataSource(app, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
            vibrate(app)
            Glyphs.play(app, Glyphs.Effect.Ringing)
            showNotification(app)
            handler.postDelayed(stopLater, 30_000)
        }
    }

    fun stop(context: Context) {
        handler.post {
            handler.removeCallbacks(stopLater)
            player?.runCatching { stop(); release() }
            player = null
            vibrator(context)?.cancel()
            Glyphs.stop(context)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun vibrate(context: Context) {
        vibrator(context)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
    }

    /** The phone's vibrator on every Android version Wallhop supports (10 and later). */
    private fun vibrator(context: Context): android.os.Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.os.Vibrator::class.java)
        }

    @android.annotation.SuppressLint("MissingPermission") // notify() is wrapped in runCatching.
    private fun showNotification(context: Context) {
        val stop = PendingIntent.getBroadcast(
            context, 3, Intent(context, StopRingingReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_FROM_MAC)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle("Your Mac is looking for this phone")
            .setContentText("Tap Found it to stop the sound")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(0, "Found it", stop)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

class StopRingingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PhoneFinder.stop(context)
}

/** Shows "Open link from your Mac"; Android doesn't let background apps open a browser by themselves. */
object LinkFromMac {
    @android.annotation.SuppressLint("MissingPermission") // notify() is wrapped in runCatching.
    fun show(context: Context, url: String) {
        val open = PendingIntent.getActivity(
            context, url.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_FROM_MAC)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle("Link from your Mac")
            .setContentText(url)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(url.hashCode(), notification) }
    }
}

/** Quick Settings tile: one tap sends whatever is on the phone's clipboard to the Mac. */
class ClipboardTileService : TileService() {
    override fun onClick() {
        val intent = Intent(this, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 4, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * Invisible screen that reads the clipboard and sends it. Android only lets the app in front
 * read the clipboard, so the tile opens this for a moment.
 */
class SendClipboardActivity : ComponentActivity() {
    private var sent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sent) return
        sent = true
        val text = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "Your clipboard is empty", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val link = LinkManager.get(this)
        lifecycleScope.launch {
            val ok = if (isSingleUrl(text)) link.openOnMac(text.trim()) else link.sendText(text)
            Toast.makeText(
                this@SendClipboardActivity,
                when {
                    !ok -> "Couldn't reach your Mac"
                    isSingleUrl(text) -> "Opened on your Mac"
                    else -> "Sent to your Mac"
                },
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }
}
