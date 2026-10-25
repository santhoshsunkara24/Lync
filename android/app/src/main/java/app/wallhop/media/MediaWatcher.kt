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
    /** Every session that's around, each listened to, so whichever starts playing takes over at once. */
    private var watched: List<MediaController> = emptyList()
    private var lastArtKey: String? = null
    private var pending: Runnable? = null
    private var started = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list.orEmpty()) }

    // Any session changing (not only the one shown) can change what should be shown: another app
    // starting to play, the shown one pausing while another plays, a new track.
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = choose(watched)
        override fun onPlaybackStateChanged(state: PlaybackState?) = choose(watched)
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
        watched.forEach { runCatching { it.unregisterCallback(callback) } }
        watched = emptyList()
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
        watch(list)
        // Ignore idle sessions (call audio, disconnected Bluetooth, system players with nothing loaded).
        val usable = list.filter { controller ->
            controller.metadata != null && controller.playbackState?.state !in setOf(
                null, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR, PlaybackState.STATE_STOPPED,
            )
        }
        fun playing(c: MediaController) = c.playbackState?.state.let {
            it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
        }
        // What's playing wins; the one already shown keeps its place while it plays, so two apps
        // playing at once don't flip back and forth.
        val shown = usable.firstOrNull { it.sessionToken == controller?.sessionToken }
        val best = shown?.takeIf(::playing)
            ?: usable.firstOrNull(::playing)
            ?: shown
            ?: usable.firstOrNull()
            ?: list.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller = best
            lastArtKey = null
            // A different app: shown at once, not after the usual short wait.
            pending?.let(handler::removeCallbacks)
            report()
            return
        }
        schedule()
    }

    /** Listens to exactly the sessions in [list]: new ones added, gone ones dropped. */
    private fun watch(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        watched.filter { it.sessionToken !in tokens }.forEach { runCatching { it.unregisterCallback(callback) } }
        val known = watched.map { it.sessionToken }.toSet()
        list.filter { it.sessionToken !in known }.forEach { runCatching { it.registerCallback(callback, handler) } }
        watched = list
    }

    // Changes come in bursts (title, then artwork, then the playing state): one report for the
    // burst, quickly enough to feel immediate.
    private fun schedule() {
        pending?.let(handler::removeCallbacks)
        pending = Runnable { report() }.also { handler.postDelayed(it, 120) }
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
        val newTrack = artKey != lastArtKey
        val art = if (newTrack) artwork(metadata) else null
        if (art != null || newTrack) lastArtKey = artKey

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
            // A different track or app: whatever artwork the Mac had no longer belongs (sent even
            // when the new one has none, so the old cover doesn't stay under the new title).
            "new_track" to newTrack,
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
