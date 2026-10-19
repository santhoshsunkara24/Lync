package app.wallhop.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.wallhop.core.Message
import app.wallhop.otp.OtpNotificationListener
import java.io.ByteArrayOutputStream

/**
 * Follows whatever is playing on the phone (Spotify, YouTube Music, podcasts…) and reports it
 * to the Mac. Uses the notification access the user already granted; no extra permission.
 */
class MediaWatcher(private val context: Context, private val send: (Message) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, OtpNotificationListener::class.java)
    private var controller: MediaController? = null
    private var lastArtKey: String? = null
    private var pending: Runnable? = null
    private var started = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedule()
        override fun onPlaybackStateChanged(state: PlaybackState?) = schedule()
        override fun onSessionDestroyed() = refresh()
    }

    fun start() {
        if (started) return
        started = runCatching {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler)
            true
        }.getOrDefault(false) // Needs notification access.
        refresh()
    }

    fun stop() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(callback)
        controller = null
        started = false
    }

    /** Sends the current state again, for example right after connecting. */
    fun resend() {
        lastArtKey = null
        schedule()
    }

    private fun refresh() {
        val list = runCatching { sessions.getActiveSessions(listenerComponent) }.getOrNull().orEmpty()
        choose(list)
    }

    private fun choose(list: List<MediaController>) {
        // Ignore idle sessions (call audio, disconnected Bluetooth, system players with nothing loaded).
        val usable = list.filter { controller ->
            controller.metadata != null && controller.playbackState?.state !in setOf(
                null, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR, PlaybackState.STATE_STOPPED,
            )
        }
        val best = usable.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: usable.firstOrNull()
            ?: list.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, handler)
            lastArtKey = null
        }
        schedule()
    }

    private fun schedule() {
        pending?.let(handler::removeCallbacks)
        pending = Runnable { report() }.also { handler.postDelayed(it, 250) }
    }

    private fun report() {
        val current = controller
        val metadata = current?.metadata
        if (current == null || metadata == null) {
            send(Message.of("media", "active" to false))
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val state = current.playbackState
        val artKey = "${current.packageName}|$title|$artist"
        val art = if (artKey != lastArtKey) artwork(metadata) else null
        if (art != null || artKey != lastArtKey) lastArtKey = artKey

        send(Message.of(
            "media",
            "active" to true,
            "app" to appLabel(current.packageName),
            "title" to title,
            "artist" to artist,
            "playing" to (state?.state == PlaybackState.STATE_PLAYING),
            "duration" to metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            "position" to (state?.position ?: 0L),
            "art" to (art != null),
            body = art ?: ByteArray(0),
        ))
    }

    /**
     * Sends the same media key presses as headphone buttons. Every music app responds to these,
     * including ones (like YouTube Music) that don't announce skip actions the standard way.
     */
    fun command(action: String) {
        val audio = context.getSystemService(AudioManager::class.java)
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> null
        }
        if (key != null) {
            val target = controller
            val down = KeyEvent(KeyEvent.ACTION_DOWN, key)
            val up = KeyEvent(KeyEvent.ACTION_UP, key)
            // Prefer the session we're showing; fall back to whichever app has media focus.
            val handled = target?.let { it.dispatchMediaButtonEvent(down) && it.dispatchMediaButtonEvent(up) } ?: false
            if (!handled) {
                audio.dispatchMediaKeyEvent(down)
                audio.dispatchMediaKeyEvent(up)
            }
            schedule()
            return
        }
        when (action) {
            "volume_up", "volume_down" -> {
                val direction = if (action == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            }
        }
    }

    private fun artwork(metadata: MediaMetadata): ByteArray? {
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        return runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, 200, 200 * bitmap.height / bitmap.width.coerceAtLeast(1), true)
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
