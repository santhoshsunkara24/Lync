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

/**
 * The clipboard's text, or why there's none to send: it's empty, or it holds something that
 * isn't text (a photo, a file). Android only lets the app in front read it.
 */
fun readClipboard(context: Context): Pair<String?, app.wallhop.core.Problem?> {
    val clip = runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull()
        ?.takeIf { it.itemCount > 0 } ?: return null to app.wallhop.core.Problems.CLIPBOARD_EMPTY
    val item = clip.getItemAt(0)
    // A photo or file copied: coerceToText would send its content:// address, which is no use on the Mac.
    if (item.text == null && item.htmlText == null && (item.uri != null || item.intent != null)) {
        return null to app.wallhop.core.Problems.CLIPBOARD_NOT_TEXT
    }
    val text = item.coerceToText(context)?.toString()
    return if (text.isNullOrBlank()) null to app.wallhop.core.Problems.CLIPBOARD_EMPTY else text to null
}

/** True when [text] is a single web link and nothing else. */
fun isSingleUrl(text: String): Boolean {
    val trimmed = text.trim()
    return !trimmed.contains(' ') && !trimmed.contains('\n') &&
        Patterns.WEB_URL.matcher(trimmed).matches() &&
        (trimmed.startsWith("http://") || trimmed.startsWith("https://"))
}

/**
 * "Ring phone": rings loudly, even on silent, so the phone can be found. Stops after 30 seconds,
 * when the user taps Stop (notification or the banner on Home), or when the Mac says stop.
 * Works without notification permission: the sound, the banner and the timeout don't need it.
 */
object PhoneFinder {
    private val _ringing = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** True while ringing, for Home's "Your Mac is ringing this phone" banner. */
    val ringing: kotlinx.coroutines.flow.StateFlow<Boolean> = _ringing
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
            if (player != null || _ringing.value) return@post
            _ringing.value = true
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
            _ringing.value = false
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
        val open = PendingIntent.getActivity(
            context, 5,
            Intent(context, app.wallhop.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle("Your Mac is ringing this phone")
            .setContentText("Tap Stop to end the sound")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setTimeoutAfter(30_000)
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .addAction(0, "Stop", stop)
        // Full screen over the lock screen where Android allows it (Android 14 asks the user).
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()) builder.setFullScreenIntent(open, true)
        val notification = builder.build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

class StopRingingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = PhoneFinder.stop(context)
}

/** Shows "Open link from your Mac"; Android doesn't let background apps open a browser by themselves. */
object LinkFromMac {
    /** False when Android blocks Lync's notifications, so the Mac can say it couldn't be shown. */
    @android.annotation.SuppressLint("MissingPermission") // notify() is wrapped in runCatching.
    fun show(context: Context, url: String): Boolean {
        if (Notifications.blocked(context, Notifications.CHANNEL_FROM_MAC)) return false
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
        return runCatching { NotificationManagerCompat.from(context).notify(url.hashCode(), notification) }.isSuccess
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
        val (text, empty) = readClipboard(this)
        if (text == null) {
            say(empty ?: app.wallhop.core.Problems.CLIPBOARD_EMPTY)
            finish()
            return
        }
        val link = LinkManager.get(this)
        // Not paired, forgotten, or already known to be away: say so now instead of after a wait.
        val state = link.state.value
        val now = when {
            link.pairedMac == null -> app.wallhop.core.Problems.NOT_PAIRED
            state is app.wallhop.link.LinkState.Forgotten -> app.wallhop.core.Problem(app.wallhop.core.Problems.FORGOTTEN.title, "Pair again in Lync.")
            state is app.wallhop.link.LinkState.Searching && state.reason != null -> link.reachProblem()
            else -> null
        }
        if (now != null) {
            say(now)
            finish()
            return
        }
        Toast.makeText(this, "Sending to Mac…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val problem = if (isSingleUrl(text)) link.openOnMac(text.trim()) else link.sendText(text)
            if (problem != null) say(problem)
            else Toast.makeText(
                this@SendClipboardActivity,
                if (isSingleUrl(text)) "Opened on your Mac" else app.wallhop.core.Wording.READY,
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    /** A problem as an Android toast, the only thing this see-through screen can show. */
    private fun say(problem: app.wallhop.core.Problem) {
        Toast.makeText(this, problem.sentence, Toast.LENGTH_LONG).show()
    }
}
