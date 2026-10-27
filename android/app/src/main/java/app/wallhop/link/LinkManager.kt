package app.wallhop.link

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.Build
import android.util.Log
import app.wallhop.BuildConfig
import app.wallhop.core.Away
import app.wallhop.core.Crypto
import app.wallhop.core.Failure
import app.wallhop.core.Identity
import app.wallhop.core.LocalNetwork
import app.wallhop.core.Message
import app.wallhop.core.PairStep
import app.wallhop.core.Problem
import app.wallhop.core.Problems
import app.wallhop.core.Wording
import app.wallhop.core.toHex
import app.wallhop.link.Session.Companion.b64
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the connection to the Mac is doing. */
sealed interface LinkState {
    data object NotPaired : LinkState
    /** [reason]: why the last round of attempts failed; null until one has. */
    data class Searching(val macName: String, val reason: Away? = null) : LinkState
    data class Connected(val macName: String, val host: String) : LinkState
    /** `code` is null while connecting, then the 6-digit code to compare on both screens. */
    data class Pairing(val macName: String, val code: String?) : LinkState
    data class PairingFailed(val macName: String, val problem: Problem) : LinkState
    /** The Mac forgot this phone, so it has to pair again. */
    data class Forgotten(val macName: String) : LinkState
}

/** Files that just arrived from the Mac, for the "received" popup and notification. */
data class ReceivedBatch(val macName: String, val uris: List<Uri>, val names: List<String>, val mimes: List<String>) {
    val summary: String get() = app.wallhop.core.Wording.received(mimes, macName)
}

/** Files on their way from the Mac, for the receiving banner. [uris] fill in as each file is saved. */
data class IncomingTransfer(
    val batch: String,
    val count: Int,
    val totalBytes: Long,
    val receivedBytes: Long = 0,
    val mimes: List<String> = emptyList(),
    val uris: List<Uri> = emptyList(),
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class TransferItem(val uri: Uri, val name: String, val size: Long, val mime: String)

enum class TransferPhase { Waiting, Sending, Done, Failed }

data class TransferState(
    val id: String,
    val items: List<TransferItem>,
    val sentBytes: Long,
    val totalBytes: Long,
    val phase: TransferPhase,
    /** A short note under the title: why it failed, or "2 of 3 sent". */
    val message: String? = null,
    /** Why it failed (or only partly went), with the buttons that help. */
    val problem: Problem? = null,
    /** A shared text or link waiting its turn, instead of files. */
    val text: String? = null,
    val isLink: Boolean = false,
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class ActivityEntry(
    val id: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    val time: Long = System.currentTimeMillis(),
    /** Files this entry is about (content URIs as strings): saved from the Mac, or sent to it. */
    val uris: List<String> = emptyList(),
) {
    enum class Kind { Otp, TextSent, FilesSent, FromMac, Call }
}

/**
 * Keeps one encrypted connection to the paired Mac alive, reconnects when the network
 * changes, and sends codes, text and files. One instance per process.
 */
class LinkManager private constructor(private val context: Context) {

    // Not cached here: [Identity.get] throws while the key can't be unlocked, and is retried next time.
    private val identity: Identity get() = Identity.get(context)
    private val store = MacStore(context)
    private val discovery = MacDiscovery(context)
    private val incoming = IncomingFiles(context)
    private val locks = TransferLocks(context)
    private val media = app.wallhop.media.MediaWatcher(context) { message ->
        val session = session?.takeIf { !it.closed && notificationsOn }
        if (session != null) scope.launch { runCatching { session.send(message) } }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sms = app.wallhop.sms.SmsStore(context)
    private val smsWatcher = app.wallhop.sms.SmsChangeWatcher(context) {
        scanInboxForCodes()
        val session = session?.takeIf { !it.closed }
        if (session != null && app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Texts)) scope.launch { runCatching { session.send(Message.of("sms_changed")) } }
    }
    // A failure in one task (a bad request from the Mac, a provider that throws) must never take
    // the app down; it's logged in test builds and otherwise ignored.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        if (BuildConfig.STRESS_HOOKS) Log.e("WallhopLink", "task failed", e)
    })

    private val _state = MutableStateFlow<LinkState>(
        store.get()?.let { LinkState.Searching(it.name) } ?: LinkState.NotPaired,
    )
    val state: StateFlow<LinkState> = _state.asStateFlow()

    val discovered: StateFlow<List<DiscoveredMac>> = discovery.macs

    // The send queue (TransferQueue): every share waits its turn, first in first out.
    private val _transfers = MutableStateFlow<List<TransferState>>(emptyList())
    val transfers: StateFlow<List<TransferState>> = _transfers.asStateFlow()
    private val _transfer = MutableStateFlow<TransferState?>(null)
    /** The transfer to show: the one sending, else the next waiting, else the last finished. */
    val transfer: StateFlow<TransferState?> = _transfer.asStateFlow()

    private val _incoming = MutableStateFlow<IncomingTransfer?>(null)
    /** Files coming from the Mac right now; null when nothing is arriving. */
    val incomingTransfer: StateFlow<IncomingTransfer?> = _incoming.asStateFlow()
    /** The live count, published at most every 100 ms so the banner doesn't redraw per chunk. */
    private var incomingNow: IncomingTransfer? = null
    private var incomingPublishedAt = 0L
    private var incomingPublishedPercent = -1
    /** Progress only when the percentage on screen changes (or once a second), like sending. */
    private fun publishIncoming(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        val percent = incomingNow?.let { (it.progress * 100).toInt() } ?: -1
        if (force || percent != incomingPublishedPercent && now - incomingPublishedAt >= 100 || now - incomingPublishedAt >= 1_000) {
            incomingPublishedAt = now
            incomingPublishedPercent = percent
            _incoming.value = incomingNow
        }
    }

    private val _received = kotlinx.coroutines.flow.MutableSharedFlow<ReceivedBatch>(extraBufferCapacity = 4)
    /** Files from the Mac, as each batch finishes (the in-app popup listens while a screen is open). */
    val received: kotlinx.coroutines.flow.SharedFlow<ReceivedBatch> = _received

    private val _activity = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val activity: StateFlow<List<ActivityEntry>> = _activity.asStateFlow()

    private val _notices = kotlinx.coroutines.flow.MutableSharedFlow<Problem>(extraBufferCapacity = 4)
    /** Problems with no screen of their own (files from the Mac that didn't save), for a toast while Lync is open. */
    val notices: kotlinx.coroutines.flow.SharedFlow<Problem> = _notices

    /** Rounds of connection attempts that failed, so "Try again" can wait for the next one's outcome. */
    private val attempts = MutableStateFlow(0)

    val pairedMac: PairedMac? get() = store.get()

    private val macInfoStore = MacInfoStore(context)
    private val _macInfo = MutableStateFlow(macInfoStore.load())
    /** The Mac's battery, lock state and wallpaper; the last known values while it's away. */
    val macInfo: StateFlow<MacInfo> = _macInfo.asStateFlow()

    private val _latencyMs = MutableStateFlow<Int?>(null)
    /** Round trip to the Mac, from the heartbeat. Null until measured or while disconnected. */
    val latencyMs: StateFlow<Int?> = _latencyMs.asStateFlow()
    @Volatile private var pingSentAt = 0L

    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    @Volatile private var session: Session? = null
    /** The Bluetooth fallback once its handshake is done; only used while [session] is down. */
    @Volatile private var blePath: MacPath? = null
    /** Set on pairing, so the first session after it lights the Glyphs whatever came before. */
    private val justPaired = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The session pairing confirmed with, waiting for the connect loop to take it over. */
    private val pairedSession = java.util.concurrent.atomic.AtomicReference<Session?>(null)
    private val _bluetooth = MutableStateFlow(false)
    /** Codes, calls and notifications are going over Bluetooth because Wi-Fi can't reach the Mac. */
    val bluetooth: StateFlow<Boolean> = _bluetooth.asStateFlow()
    private val ble = BleLink(context, scope, onPath = { path ->
        blePath = path
        _bluetooth.value = path != null
        // Codes that waited for the Mac go across now, over Bluetooth.
        if (path != null) pendingCodes.drain(System.currentTimeMillis()).forEach { sendOtp(it.code, it.sender, it.app) }
    }) { message ->
        when (message.type) {
            "call_cmd" -> message.string("action")?.let { action ->
                val err = app.wallhop.calls.CallControl.performErr(context, action)
                val id = message.string("id")
                val path = blePath
                if (id != null && path != null) scope.launch { runCatching { path.send(ack(id, err)) } }
            }
            else -> Unit // Everything else needs Wi-Fi.
        }
    }
    /** Why the Bluetooth backup isn't advertising (it failed to start), for Settings. */
    val bluetoothProblem: StateFlow<Problem?> get() = ble.problem
    private var bleJob: Job? = null
    private var loopJob: Job? = null
    private var pairingJob: Job? = null
    private val queueLock = Any()
    private val queueWake = Channel<Unit>(Channel.CONFLATED)
    private var queueWorker: Job? = null
    @Volatile private var runningId: String? = null
    @Volatile private var runningJob: Job? = null
    /**
     * What each queued transfer sends: the URIs (described when its turn comes), files already
     * skipped, and whether they were skipped because the phone's storage is full.
     */
    private data class Source(val uris: List<Uri>, val skipped: Int, val storageFull: Boolean = false)
    private val sources = ConcurrentHashMap<String, Source>()
    private val pendingCodes = PendingCodes()
    /** Codes that didn't reach the Mac: their Activity entry, updated when they go or expire. */
    private val pendingEntries = ConcurrentHashMap<String, String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val recentCodes = ConcurrentHashMap<String, Long>()
    private val iconsSent = ConcurrentHashMap.newKeySet<String>()
    private var lastBatterySent = -1
    private var networkCallbackRegistered = false

    val phoneName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    // MARK: - Lifecycle

    init {
        // When the Mac reappears on the network (for example after it wakes), reconnect right away.
        discovery.onFound = { found ->
            val mac = store.get()
            if (mac != null && _state.value !is LinkState.Connected && found.name == mac.name) poke()
        }
        // Ringing stopped here (the user silenced it, or it timed out): the Mac stops showing "Ringing".
        scope.launch {
            var was = false
            app.wallhop.extras.PhoneFinder.ringing.collect { ringing ->
                if (was && !ringing) session?.takeIf { !it.closed }?.let { runCatching { it.send(Message.of("ring", "action" to "stopped")) } }
                was = ringing
            }
        }
    }

    /** Starts discovery and the reconnect loop. Safe to call many times. */
    @Synchronized
    fun start() {
        discovery.start()
        mainHandler.post { startWatchers() }
        registerNetworkCallback()
        if (loopJob?.isActive == true) {
            poke()
            return
        }
        loopJob = scope.launch { connectLoop() }
        // Wi-Fi gets a few seconds to connect before Bluetooth steps in.
        updateBluetooth(BLUETOOTH_GRACE_MS)
    }

    /**
     * Path selection: Bluetooth advertises only while paired, switched on, allowed, and the Wi-Fi
     * session is down ([delayMs] after it went). Wi-Fi coming back closes it.
     */
    private fun updateBluetooth(delayMs: Long = 0) {
        synchronized(this) {
            bleJob?.cancel()
            bleJob = scope.launch {
                delay(delayMs)
                val mac = store.get()
                val wanted = loopJob?.isActive == true && mac != null && session?.closed != false &&
                    app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Bluetooth) && BleLink.permitted(context)
                val id = if (wanted) runCatching { identity }.getOrNull() else null
                if (mac != null && id != null) ble.start(id, mac) else ble.stop()
            }
        }
    }

    /** The Bluetooth switch or permission changed. */
    fun bluetoothChanged() = updateBluetooth()

    /**
     * Stops everything [start] started: the reconnect loop, the connection, Bonjour, the network
     * callback and the SMS and media watchers. Used when "Run in background" is off and the app
     * isn't on screen. Pairing and a transfer that's waiting call [start] again themselves.
     */
    @Synchronized
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        session?.close()
        pairedSession.getAndSet(null)?.close()
        bleJob?.cancel()
        ble.stop()
        discovery.stop()
        unregisterNetworkCallback()
        mainHandler.post { media.stop(); smsWatcher.stop() }
    }

    /** Media and SMS watchers, each only while its switch in Lync is on. Main thread. */
    private fun startWatchers() {
        if (app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.AppNotifications)) media.start() else media.stop()
        val texts = app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Texts)
        val codes = app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Codes)
        if (texts || codes) smsWatcher.start() else smsWatcher.stop()
    }

    /** Wakes the reconnect loop now instead of waiting for the backoff. */
    fun poke() {
        wake.trySend(Unit)
    }

    private suspend fun connectLoop() {
        var backoffMs = 1_000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val mac = store.get()
            if (mac == null || pairingJob?.isActive == true) {
                if (mac == null && _state.value !is LinkState.Pairing && _state.value !is LinkState.PairingFailed &&
                    _state.value !is LinkState.Forgotten) {
                    _state.value = LinkState.NotPaired
                }
                waitForWake(10_000)
                continue
            }
            // "Forgotten" stays up (it's a prompt for the user) while we keep trying in the background.
            // The last reason stays too, so Home doesn't flick back to "looking" on every round.
            if (_state.value !is LinkState.Connected && _state.value !is LinkState.Forgotten) {
                _state.value = LinkState.Searching(mac.name, (_state.value as? LinkState.Searching)?.reason)
            }
            // Nothing can be tried until the phone's key unlocks (e.g. just after a reboot).
            if (runCatching { identity }.isFailure) {
                if (_state.value !is LinkState.Forgotten) _state.value = LinkState.Searching(mac.name, Away.KeyLocked)
                attempts.value++
                waitForWake(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                continue
            }

            var connected = false
            var sessionMs = 0L
            // How each of our Mac's own addresses failed, for the reason shown on Home.
            val failures = mutableListOf<Failure>()
            for ((host, port, resolved, own) in candidates(mac)) {
                val result = runCatching {
                    // The session pairing just opened, while it's still up: no second connection.
                    pairedSession.getAndSet(null)?.takeIf { !it.closed } ?: run {
                        val socket = Session.open(host, port)
                        try {
                            Session.handshake(socket, identity, phoneName, mac.publicKey).first
                        } catch (e: Exception) {
                            runCatching { socket.close() }
                            throw e
                        }
                    }
                }
                val opened = result.getOrNull()
                // "Not paired" comes inside the Noise handshake, so it's from our own Mac. Still it
                // never deletes the pairing by itself: the phone asks the user to pair again
                // (keeping the keys until they do) and keeps retrying, so a Mac that still knows us
                // reconnects. Wherever it was reached: no one else could have said it.
                val unpaired = result.exceptionOrNull() as? UnpairedException
                if (unpaired != null) {
                    if (unpaired.macPub != null && java.security.MessageDigest.isEqual(unpaired.macPub, mac.publicKey)) {
                        _state.value = LinkState.Forgotten(mac.name)
                    } else if (resolved && unpaired.macPub != null) {
                        // Our Mac's name with a new key: Lync on it was reinstalled or reset.
                        failures += Failure.WrongMac
                    }
                    continue
                }
                if (opened != null) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                        opened.close() // Stopped while connecting.
                        return
                    }
                    connected = true
                    store.save(mac.copy(host = opened.host, port = opened.port, name = opened.macName))
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    runSession(opened)
                    sessionMs = android.os.SystemClock.elapsedRealtime() - startedAt
                    break
                }
                // Another Mac's address says nothing about ours; a different key only counts where
                // Bonjour found our Mac's name (an old address may belong to another Mac now).
                val failure = Problems.classify(result.exceptionOrNull())
                if (own && (failure != Failure.WrongMac || resolved)) failures += failure
            }
            if (store.get() == null) continue
            if (connected && sessionMs >= SHORT_SESSION_MS) {
                backoffMs = 1_000L
            } else if (connected) {
                // Dropped straight after connecting (a Mac that closes on us, a flaky network):
                // back off rather than reconnect in a tight loop. Pokes don't cut this short.
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            } else {
                val reason = awayReason(mac.name, failures)
                if (_state.value !is LinkState.Forgotten) _state.value = LinkState.Searching(mac.name, reason)
                attempts.value++
                waitForWake(backoffMs)
                val stuck = _state.value is LinkState.Forgotten || reason == Away.MacReset
                backoffMs = (backoffMs * 2).coerceAtMost(if (stuck) 60_000L else 15_000L)
            }
        }
    }

    /**
     * A place to try. [resolved]: Bonjour found it under this Mac's name. [own]: it's this Mac's
     * (Bonjour's or its last address), not another Mac that was found.
     */
    private data class Candidate(val host: String, val port: Int, val resolved: Boolean, val own: Boolean)

    /** Places to try, best first: Bonjour results for this Mac, its last address, other Macs found. */
    private fun candidates(mac: PairedMac): List<Candidate> {
        val found = discovery.macs.value
        val list = mutableListOf<Candidate>()
        found.filter { it.name == mac.name }.forEach { list += Candidate(it.host, it.port, resolved = true, own = true) }
        if (mac.host != null) list += Candidate(mac.host, mac.port, resolved = false, own = true)
        found.filter { it.name != mac.name }.forEach { list += Candidate(it.host, it.port, resolved = false, own = false) }
        return list.distinctBy { it.host to it.port }
    }

    /** Why the Mac called [name] isn't connected, after a round that failed with [failures]. */
    private fun awayReason(name: String, failures: Collection<Failure> = emptyList()): Away = Problems.away(
        failures,
        wifiOn = LocalNetwork.radioOn(context),
        onWifi = LocalNetwork.joined(context),
        macSeen = discovery.macs.value.any { it.name == name },
        discoveryWorks = discovery.working,
    )

    /**
     * "Try again": tries now and waits (up to 15 s) for the outcome, so the answer is connected
     * or the specific reason, never just "looking".
     */
    suspend fun retryNow(): LinkState {
        val before = attempts.value
        start()
        poke()
        withTimeoutOrNull(15_000) {
            while (_state.value is LinkState.Searching && attempts.value == before) delay(200)
        }
        return _state.value
    }

    /**
     * Why something can't go to the Mac right now, or null while the Wi‑Fi link is up. Text and
     * [files] need Wi‑Fi, so a Bluetooth-only link says so.
     */
    fun reachProblem(files: Boolean = false): Problem? {
        if (session?.closed == false) return null
        return when (val s = _state.value) {
            is LinkState.Connected -> null
            is LinkState.Forgotten -> Problems.FORGOTTEN
            is LinkState.Searching -> when {
                _bluetooth.value -> if (files) Problems.FILES_NEED_WIFI else Problems.TEXT_NEEDS_WIFI
                else -> Problems.away(s.reason ?: awayReason(s.macName))
            }
            else -> Problems.NOT_PAIRED
        }
    }

    /** Why a queued send stopped waiting: the Mac forgot this phone, or why it's away. */
    private fun giveUpProblem(): Problem = when (val s = _state.value) {
        is LinkState.Forgotten -> Problems.FORGOTTEN_SEND
        is LinkState.Searching -> Problems.away(s.reason ?: awayReason(s.macName))
        is LinkState.Connected -> Problems.MAC_STOPPED_ANSWERING
        else -> Problems.NOT_PAIRED
    }

    private suspend fun waitForWake(timeoutMs: Long) {
        select {
            wake.onReceive { }
            onTimeout(timeoutMs) { }
        }
    }

    /** When the last session ended (elapsedRealtime), or 0 before the first one. */
    private var disconnectedAt = 0L

    private suspend fun runSession(session: Session) {
        this.session = session
        // A Glyph pulse when the Mac connects: always just after pairing, otherwise not for a
        // reconnect after a short network blip.
        val now = android.os.SystemClock.elapsedRealtime()
        val justPaired = this.justPaired.getAndSet(false)
        if (justPaired || disconnectedAt == 0L || now - disconnectedAt >= 2 * 60_000) {
            app.wallhop.glyph.Glyphs.play(context, app.wallhop.glyph.Glyphs.Effect.Connected)
        }
        iconsSent.clear()
        updateBluetooth() // Wi-Fi is back: Bluetooth stands down.
        _state.value = LinkState.Connected(session.macName, session.host)
        _connectedSince.value = System.currentTimeMillis()
        lastBatterySent = -1
        sendStatus()
        sendTheme(session)
        // Codes that waited while the Mac was away go across now, if still under five minutes old.
        pendingCodes.drain(System.currentTimeMillis()).forEach { sendOtp(it.code, it.sender, it.app) }
        mainHandler.post { startWatchers(); media.resend() }

        val heartbeat = scope.launch {
            delay(1_000) // A first measurement soon after connecting, for the Mac card.
            while (isActive && !session.closed) {
                pingSentAt = android.os.SystemClock.elapsedRealtime()
                runCatching { session.send(Message.of("ping")) }.onFailure { session.close() }
                delay(15_000)
            }
        }
        try {
            while (!session.closed) {
                handleIncoming(session, session.read())
            }
        } catch (_: Exception) {
            // Connection dropped; the loop reconnects.
        } finally {
            heartbeat.cancel()
            session.close()
            // Files were still arriving: say the transfer stopped, and keep the ones that saved.
            incoming.cancelAll()?.let { stopped ->
                reportIncoming(Problems.received(stopped.saved.size, stopped.total, null, stopped = true), stopped.saved)
            }
            incomingNow?.let { locks.release("in:${it.batch}") }
            incomingNow = null; publishIncoming(force = true)
            if (this.session === session) this.session = null
            disconnectedAt = android.os.SystemClock.elapsedRealtime()
            _latencyMs.value = null
            _connectedSince.value = null
            _macInfo.value = _macInfo.value.copy(locked = false)
            store.get()?.let { _state.value = LinkState.Searching(it.name) }
            updateBluetooth(BLUETOOTH_GRACE_MS)
        }
    }

    private suspend fun handleIncoming(session: Session, message: Message) {
        when (message.type) {
            "ping" -> session.send(Message.of("pong"))
            "pong" -> if (pingSentAt > 0) {
                _latencyMs.value = (android.os.SystemClock.elapsedRealtime() - pingSentAt).toInt()
                pingSentAt = 0
            }
            "mac_info" -> {
                val h = message.header
                val hash = h.optString("wp").takeIf { it.isNotEmpty() }
                val jpeg = message.body.takeIf { it.isNotEmpty() }
                val wallpaper = when {
                    jpeg != null -> android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    hash == null -> null // Moving wallpaper: the card draws its own.
                    else -> _macInfo.value.wallpaper // Unchanged; the Mac only sends it when it differs.
                }
                val info = MacInfo(
                    battery = if (h.has("battery")) h.optInt("battery") else null,
                    charging = h.optBoolean("charging"),
                    locked = h.optBoolean("locked"),
                    laptop = h.optBoolean("laptop", true),
                    notch = h.optBoolean("notch", true),
                    wallpaper = wallpaper,
                )
                _macInfo.value = info
                macInfoStore.save(info, jpeg)
            }
            "ack" -> session.completeAck(message)
            "text" -> {
                val text = message.bodyText
                Notifications.textFromMac(context, text, session.macName)
                record(ActivityEntry(UUID.randomUUID().toString(), ActivityEntry.Kind.FromMac, "Text from your Mac", "Copied on this phone"))
                message.string("id")?.let { session.send(Message.of("ack", "id" to it, "ok" to true)) }
            }
            // Replies, actions and dismissals only while notification mirroring is on in Lync. The
            // ack says why one didn't work (PROTOCOL.md, notif_reply ack codes).
            "notif_reply" -> {
                val err = when {
                    !notificationsOn -> "mirroring_off"
                    else -> message.string("key")?.let {
                        app.wallhop.otp.OtpNotificationListener.replyErr(context, it, message.bodyText)
                    } ?: "gone"
                }
                message.string("id")?.let { session.send(ack(it, err)) }
            }
            "notif_dismiss" -> if (notificationsOn) {
                message.string("key")?.let { app.wallhop.otp.OtpNotificationListener.dismiss(it) }
            }
            "notif_action" -> {
                val key = message.string("key")
                val index = message.header.optInt("i", -1)
                val err = when {
                    !notificationsOn -> "mirroring_off"
                    key == null || index < 0 -> "failed"
                    else -> app.wallhop.otp.OtpNotificationListener.performActionErr(context, key, index)
                }
                message.string("id")?.let { session.send(ack(it, err)) }
            }
            "sms_threads", "sms_thread" -> {
                val id = message.string("id") ?: return
                scope.launch {
                    // A provider that throws (an odd OEM SMS database, a revoked permission) gets an
                    // error reply instead of crashing the app.
                    val reply = runCatching {
                        when {
                            !app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Texts) -> Message.of("sms_result", "id" to id, "err" to "texts_off")
                            !sms.canRead -> Message.of("sms_result", "id" to id, "err" to "permission")
                            message.type == "sms_threads" ->
                                Message.of("sms_result", "id" to id, body = sms.threads().toString().toByteArray())
                            else -> Message.of("sms_result", "id" to id,
                                body = sms.messages(message.string("thread").orEmpty()).toString().toByteArray())
                        }
                    }.getOrElse { Message.of("sms_result", "id" to id, "err" to "failed") }
                    runCatching { session.send(reply) }
                }
            }
            // Contacts search for the Mac's Texts window: names or numbers containing q.
            "contacts" -> {
                val id = message.string("id") ?: return
                val query = message.string("q").orEmpty()
                scope.launch {
                    val reply = runCatching {
                        if (!app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Texts)) {
                            Message.of("sms_result", "id" to id, "err" to "texts_off")
                        } else if (!sms.canReadContacts) {
                            Message.of("sms_result", "id" to id, "err" to "contacts_permission")
                        } else {
                            val items = org.json.JSONArray()
                            sms.searchContacts(query).forEach { (name, number) ->
                                items.put(JSONObject().put("name", name).put("number", number))
                            }
                            Message.of("sms_result", "id" to id, body = JSONObject().put("items", items).toString().toByteArray())
                        }
                    }.getOrElse { Message.of("sms_result", "id" to id, "err" to "failed") }
                    runCatching { session.send(reply) }
                }
            }
            "sms_send" -> {
                val id = message.string("id")
                val sim = if (message.header.has("sim")) message.header.optInt("sim", -1) else null
                // Off the read loop: waiting for the carrier can take a while.
                scope.launch {
                    // A code the Mac can word (PROTOCOL.md, sms_send ack), never the exception's text.
                    val err = if (app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Texts)) {
                        sms.send(message.string("address").orEmpty(), message.bodyText, sim)
                            .exceptionOrNull()?.let { (it as? app.wallhop.sms.SmsError)?.code ?: "failed" }
                    } else {
                        "texts_off"
                    }
                    if (id != null) runCatching { session.send(ack(id, err)) }
                }
            }
            // Android doesn't let a background app open the browser: a notification does, and
            // Activity keeps the link (tap opens it) in case notifications are blocked.
            "open_url" -> message.string("url")?.let { url ->
                val shown = app.wallhop.extras.LinkFromMac.show(context, url)
                record(ActivityEntry(UUID.randomUUID().toString(), ActivityEntry.Kind.FromMac, "Link from your Mac", url))
                message.string("id")?.let { session.send(ack(it, if (shown) null else "notifications_blocked")) }
            }
            "ring" -> if (message.string("action") == "stop") app.wallhop.extras.PhoneFinder.stop(context)
                else app.wallhop.extras.PhoneFinder.start(context)
            "media_cmd" -> message.string("action")?.let { action -> mainHandler.post { media.command(action) } }
            // The Mac wants a feature that's off: its ask sheet here, or a notification that opens it.
            "ask_feature" -> message.string("key")?.takeIf { it in setOf("codes", "calls", "texts", "app_notifications") }?.let { key ->
                if (AppVisibility.foreground) app.wallhop.ui.AppNav.ask.value = key
                else Notifications.askFromMac(context, key, when (key) {
                    "codes" -> "Your Mac would like your codes"
                    "calls" -> "Your Mac would like to show calls"
                    "texts" -> "Your Mac would like to show your texts"
                    else -> "Your Mac would like your notifications"
                })
            }
            // The Mac waits up to 3 s for this ack before saying the button didn't work.
            "call_cmd" -> message.string("action")?.let { action ->
                val err = app.wallhop.calls.CallControl.performErr(context, action)
                message.string("id")?.let { session.send(ack(it, err)) }
            }
            // Files fail one at a time (reported in the file_end ack); the link stays up.
            "batch_start" -> {
                runCatching { incoming.batchStart(message) }
                message.string("batch")?.let { batch ->
                    locks.hold("in:$batch")
                    incomingNow = IncomingTransfer(batch, message.header.optInt("count", 1), message.header.optLong("bytes", 0L))
                    publishIncoming(force = true)
                }
            }
            "file_start" -> {
                runCatching { incoming.fileStart(message) }
                incomingNow?.let { now ->
                    if (now.mimes.size < 3) {
                        incomingNow = now.copy(mimes = now.mimes + (message.string("mime") ?: "application/octet-stream"))
                        publishIncoming(force = true)
                    }
                }
            }
            "file_chunk" -> {
                runCatching { incoming.fileChunk(message) }
                incomingNow?.let { incomingNow = it.copy(receivedBytes = it.receivedBytes + message.body.size); publishIncoming() }
            }
            "file_end" -> {
                // A file ack code (PROTOCOL.md): storage_full, checksum, write_failed, permission.
                val error = runCatching { incoming.fileEnd(message) }.getOrElse { "write_failed" }
                session.send(Message.of("ack", "id" to message.string("id"), "ok" to (error == null), "err" to error))
                val saved = incoming.lastSaved
                incomingNow?.let { now ->
                    if (error == null && saved != null && now.uris.size < 3 && saved.first !in now.uris) {
                        incomingNow = now.copy(uris = now.uris + saved.first)
                        publishIncoming(force = true)
                    }
                }
            }
            "batch_end" -> {
                val finished = runCatching { incoming.batchEnd(message, session.macName) }.getOrNull()
                message.string("batch")?.let { locks.release("in:$it") }
                incomingNow = null
                publishIncoming(force = true)
                finished?.batch?.let { batch ->
                    record(ActivityEntry(UUID.randomUUID().toString(), ActivityEntry.Kind.FromMac, batch.summary,
                        "Saved to Downloads › Lync", uris = batch.uris.map { it.toString() }))
                    // A Lync screen in front shows its own popup; otherwise a heads-up notification.
                    if (AppVisibility.foreground) _received.tryEmit(batch) else Notifications.filesFromMac(context, batch)
                }
                // Some or all didn't save: say so, even when nothing did.
                if (finished != null && finished.failed > 0) {
                    reportIncoming(Problems.received(finished.total - finished.failed, finished.total, finished.err))
                }
            }
            else -> Unit // Unknown types are ignored so newer Macs keep working.
        }
    }

    private val notificationsOn: Boolean
        get() = app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.AppNotifications)

    /** An `ack` for [id]: ok, or not with [err] (a PROTOCOL.md code). */
    private fun ack(id: String, err: String?): Message = Message.of("ack", "id" to id, "ok" to (err == null), "err" to err)

    /**
     * Files from the Mac that didn't (all) save: an Activity entry (with any that did, [saved]),
     * and a toast while Lync is open or a notification otherwise.
     */
    private fun reportIncoming(problem: Problem, saved: List<Uri> = emptyList()) {
        record(ActivityEntry(UUID.randomUUID().toString(), ActivityEntry.Kind.FromMac, problem.title, problem.action,
            uris = saved.map { it.toString() }))
        if (AppVisibility.foreground) _notices.tryEmit(problem) else Notifications.problem(context, problem)
    }

    // MARK: - Pairing

    /** Pairs using a scanned QR: no code to compare, and the Mac's key is checked against it. */
    fun pair(invite: QrInvite) = pair(invite.host, invite.port, invite.name, invite)

    /** Starts pairing with a Mac found on the network, or typed in by hand ([typed]). */
    fun pair(host: String, port: Int, name: String, invite: QrInvite? = null, typed: Boolean = false) {
        stopPairing()
        _state.value = LinkState.Pairing(name, null)
        // Set once connected, so cancelling closes the socket and unblocks the read straight away.
        val pairingSocket = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>(null)
        lateinit var job: Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // Where it got to, so a failure can say what went wrong at that point.
            var step = PairStep.Connecting
            var askedAt = 0L
            var target = host
            try {
                // Pairing again replaces a key the Keystore can no longer unlock.
                val identity = Identity.get(context, regenerateIfUnreadable = true)
                // A QR lists every address the Mac has; use whichever answers first.
                target = if (invite != null && invite.hosts.size > 1) firstReachable(invite.hosts, port) ?: host else host
                val socket = Session.open(target, port)
                pairingSocket.set(socket)
                try {
                    ensureActive() // Cancelled while connecting.
                    socket.soTimeout = 130_000 // The user has two minutes to confirm on the Mac.
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val output = socket.getOutputStream().buffered()
                    // Pairing v2 (PROTOCOL.md): commit to a nonce first and reveal it only after the
                    // Mac has sent its own, so a man in the middle can't grind keys to match the code.
                    val phoneNonce = Crypto.pairNonce()
                    val request = JSONObject()
                        .put("t", "pair")
                        .put("v", Session.PROTOCOL)
                        .put("name", phoneName)
                        .put("pub", identity.publicKey.b64())
                        .put("commit", Crypto.pairingCommit(identity.publicKey, phoneNonce).b64())
                    if (invite != null) {
                        val proof = Crypto.qrProof(invite.token, identity.publicKey, invite.macPub)
                        request.put("qr", proof.b64())
                    }
                    step = PairStep.Asking
                    Session.writeJson(output, request)

                    val wait = Session.readJson(input)
                    when (wait.optString("t")) {
                        "pair_wait" -> Unit
                        // Turned down straight away: busy, too many tries, an old version, a used code.
                        "pair_no" -> {
                            // The Mac speaks an older protocol than this phone: it's the one to update.
                            if (wait.optString("err") == "version" && wait.optInt("v", Int.MAX_VALUE) < Session.PROTOCOL) throw MacTooOldException()
                            throw PairingDeclinedException(wait.optString("err").takeIf { it.isNotEmpty() })
                        }
                        else -> throw HandshakeException("Not a pairing reply")
                    }
                    val macPub = android.util.Base64.decode(wait.getString("pub"), android.util.Base64.NO_WRAP)
                    val macNonce = runCatching { android.util.Base64.decode(wait.optString("nonce"), android.util.Base64.NO_WRAP) }
                        .getOrNull()?.takeIf { it.size == Crypto.PAIR_NONCE_SIZE }
                        ?: throw MacTooOldException()
                    if (macPub.size != 32) throw HandshakeException("Bad key")
                    val macName = wait.optString("name", name)
                    // With a QR, the Mac must be the one whose key is in the code.
                    if (invite != null && !java.security.MessageDigest.isEqual(macPub, invite.macPub)) {
                        throw PairingDeclinedException("mismatch")
                    }
                    Session.writeJson(output, JSONObject().put("t", "pair_nonce").put("nonce", phoneNonce.b64()))
                    val code = if (invite != null) null else Crypto.pairingCode(identity.publicKey, macPub, phoneNonce, macNonce)
                    _state.value = LinkState.Pairing(macName, code)
                    step = PairStep.Confirming
                    askedAt = android.os.SystemClock.elapsedRealtime()

                    val answer = Session.readJson(input)
                    when (answer.optString("t")) {
                        "pair_ok" -> {
                            // Cancelled while the Mac was accepting: don't pair behind the user's back.
                            ensureActive()
                            // pair_ok itself isn't authenticated: a session on this connection only
                            // opens with the Mac whose key was just shown, and only once it really
                            // added this phone. Then that session is the first connection.
                            step = PairStep.Confirming
                            val (session, _) = Session.handshake(socket, identity, phoneName, macPub)
                            // Cancelled during that handshake: don't pair behind the user's back.
                            if (!isActive) { session.close(); ensureActive() }
                            pairingSocket.set(null) // Now the session's: not closed with the pairing.
                            store.save(PairedMac(Crypto.deviceId(macPub), session.macName.ifEmpty { macName }, macPub, target, port))
                            justPaired.set(true)
                            pairedSession.getAndSet(session)?.close()
                            updateBluetooth(BLUETOOTH_GRACE_MS)
                            _state.value = LinkState.Searching(macName)
                        }
                        "pair_no" -> throw PairingDeclinedException(answer.optString("err").takeIf { it.isNotEmpty() })
                        else -> throw java.io.EOFException("Pairing ended unexpectedly")
                    }
                } finally {
                    // Closed unless it became the session.
                    if (pairingSocket.get() === socket) runCatching { socket.close() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Why, in the user's words: never the exception's own text.
                val problem = when (e) {
                    is PairingDeclinedException -> Problems.pairNo(e.err, qr = invite != null)
                    is MacTooOldException -> Problems.MAC_NEEDS_UPDATE
                    else -> Problems.pairing(
                        Problems.classify(e), step, qr = invite != null, typed = typed,
                        onWifi = LocalNetwork.joined(context),
                        macSeen = discovery.macs.value.any { it.name == name || it.host == target || invite?.hosts?.contains(it.host) == true },
                        confirmingMs = if (askedAt > 0) android.os.SystemClock.elapsedRealtime() - askedAt else 0,
                    )
                }
                if (_state.value is LinkState.Pairing && job.isActive) _state.value = LinkState.PairingFailed(name, problem)
            } finally {
                synchronized(this@LinkManager) { if (pairingJob === job) pairingJob = null }
                poke()
            }
        }
        job.invokeOnCompletion { pairingSocket.get()?.let { runCatching { it.close() } } }
        synchronized(this) {
            pairingJob = job
            closePairingSocket = { pairingSocket.get()?.let { runCatching { it.close() } } }
        }
        job.start()
    }

    /** Closes the pairing connection, so a read blocked on it ends now rather than in two minutes. */
    @Volatile private var closePairingSocket: (() -> Unit)? = null

    private fun stopPairing() {
        pairingJob?.cancel()
        closePairingSocket?.invoke()
    }

    /** Tries every address at once and returns the first that accepts a connection. */
    private suspend fun firstReachable(hosts: List<String>, port: Int): String? = kotlinx.coroutines.coroutineScope {
        val winner = kotlinx.coroutines.CompletableDeferred<String?>()
        val sockets = hosts.map { java.net.Socket() }
        val attempts = hosts.zip(sockets).map { (candidate, socket) ->
            launch {
                runCatching {
                    socket.connect(java.net.InetSocketAddress(candidate, port), 3_000)
                    winner.complete(candidate)
                }
            }
        }
        launch {
            attempts.forEach { it.join() }
            winner.complete(null)
        }
        // Closing the sockets ends the slower attempts straight away.
        winner.await().also { sockets.forEach { runCatching { it.close() } } }
    }

    fun cancelPairing() {
        stopPairing()
        _state.value = store.get()?.let { LinkState.Searching(it.name) } ?: LinkState.NotPaired
    }

    fun forgetMac() {
        // Tell the Mac first (PROTOCOL.md, forget), so it drops this phone too; then close.
        // Over Wi-Fi and the Bluetooth link both at once, whichever are up: without it the Mac
        // never hears, keeps the pairing and goes on showing this phone as away.
        val open = session?.takeIf { !it.closed } ?: pairedSession.getAndSet(null)?.takeIf { !it.closed }
        pairedSession.getAndSet(null)?.close()
        val paths = listOfNotNull<MacPath>(open, blePath?.takeIf { !it.closed })
        session = null
        store.forget()
        scope.launch {
            paths.map { path ->
                async { withTimeoutOrNull(1_500) { runCatching { path.send(Message.of("forget")) } } }
            }.awaitAll()
            open?.close()
            // Then Bluetooth off for good: no advertising, and the Mac's link to this phone
            // dropped, rather than waiting on the job that decides whether it should run.
            ble.stop()
            updateBluetooth()
        }
        macInfoStore.clear()
        _macInfo.value = MacInfo()
        _state.value = LinkState.NotPaired
        poke()
    }

    /** Drops the old pairing (the Mac forgot this phone, was reset, or the key is locked) so the phone can pair again. */
    fun pairAgain() {
        if (store.get() != null) forgetMac() else _state.value = LinkState.NotPaired
    }

    fun dismissPairingProblem() {
        when {
            // The Mac said it no longer knows this phone and the user acknowledged it: only now
            // is the old pairing dropped, so the phone can pair again.
            _state.value is LinkState.Forgotten -> forgetMac()
            store.get() == null -> _state.value = LinkState.NotPaired
        }
    }

    // MARK: - Sending

    /**
     * The open session, waiting up to [timeoutMs] for one. Never returns a closed session. Gives up
     * at once when waiting can't help (the Mac forgot this phone or was reset); with [interactive]
     * (someone is watching a spinner), also for reasons a few seconds won't fix, like Wi‑Fi off.
     */
    private suspend fun awaitSession(timeoutMs: Long, interactive: Boolean = false): Session? {
        session?.takeIf { !it.closed }?.let { return it }
        if (hopeless(_state.value, interactive)) return null
        start()
        return withTimeoutOrNull(timeoutMs) {
            var open: Session? = null
            while (open == null) {
                val now = state.first { it is LinkState.Connected || hopeless(it, interactive) }
                if (now !is LinkState.Connected) return@withTimeoutOrNull null
                open = session?.takeIf { !it.closed }
                if (open == null) delay(200) // Connected but the session just dropped: wait for the next one.
            }
            open
        }
    }

    private fun hopeless(state: LinkState, interactive: Boolean): Boolean = when (state) {
        is LinkState.Forgotten, LinkState.NotPaired -> true
        is LinkState.Searching -> state.reason == Away.MacReset ||
            (interactive && state.reason in setOf(Away.NoWifi, Away.NotOnWifi, Away.KeyLocked, Away.VersionMismatch))
        else -> false
    }

    /**
     * The best way to the Mac: the Wi-Fi session, else the Bluetooth fallback, waiting up to
     * [timeoutMs] for either. Wi-Fi only things (files, text) use [awaitSession] instead.
     */
    private suspend fun awaitPath(timeoutMs: Long): MacPath? {
        fun open(): MacPath? = session?.takeIf { !it.closed } ?: blePath?.takeIf { !it.closed }
        open()?.let { return it }
        start()
        return withTimeoutOrNull(timeoutMs) {
            var path = open()
            while (path == null) {
                delay(200)
                path = open()
            }
            path
        }
    }

    /** Newest SMS already checked by [scanInboxForCodes]; starts at launch, so old texts are skipped. */
    @Volatile private var smsScannedUntil = System.currentTimeMillis()

    /**
     * Backup code source: when the SMS inbox changes, check messages that arrived since the last
     * look. Catches codes the SMS broadcast missed (e.g. the app was stopped). [sendOtp] drops
     * a code that already went across, so nothing is sent twice.
     */
    private fun scanInboxForCodes() {
        if (store.get() == null || !sms.canRead || !app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Codes)) return
        scope.launch {
            val since = smsScannedUntil
            val found = mutableListOf<Triple<String, String, Long>>()
            runCatching {
                context.contentResolver.query(
                    android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY, android.provider.Telephony.Sms.DATE),
                    "${android.provider.Telephony.Sms.DATE} > ?", arrayOf(since.toString()),
                    "${android.provider.Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    while (cursor.moveToNext() && found.size < 5) {
                        found += Triple(cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(), cursor.getLong(2))
                    }
                }
            }
            found.maxOfOrNull { it.third }?.let { smsScannedUntil = maxOf(smsScannedUntil, it) }
            // Only fresh texts: a code older than five minutes is no use any more.
            val fresh = System.currentTimeMillis() - 5 * 60_000
            for ((address, body, date) in found.sortedBy { it.third }) {
                if (date < fresh) continue
                app.wallhop.otp.OtpExtractor.extract(body)?.let { sendOtp(it, address, "SMS") }
            }
        }
    }

    /** Sends a one-time code. Duplicates within three minutes (SMS plus its notification) are dropped. */
    fun sendOtp(code: String, sender: String, app: String): Job? {
        val now = System.currentTimeMillis()
        recentCodes.entries.removeAll { now - it.value > 3 * 60_000 }
        if (recentCodes.putIfAbsent(code, now) != null) return null

        return scope.launch {
            val path = awaitPath(20_000)
            val id = UUID.randomUUID().toString()
            val ack = path?.let {
                runCatching {
                    it.request(Message.of("otp", "id" to id, "code" to code, "sender" to sender, "app" to app), 10_000)
                }.getOrNull()
            }
            if (BuildConfig.STRESS_HOOKS) Log.d("WallhopLink", "otp delivered=${ack != null}")
            if (ack != null) {
                // A code that waited for the Mac keeps its entry, now saying it's ready.
                val entry = pendingEntries.remove(code) ?: id
                record(ActivityEntry(entry, ActivityEntry.Kind.Otp, code, if (sender.isBlank()) Wording.READY else "From $sender · ready to paste"))
            } else {
                // Not delivered: forget it, so the same code from another source (or the inbox
                // scan after reconnecting) can still go across, and keep it with its time to send
                // when the Mac comes back (if that's within five minutes of the code arriving).
                recentCodes.remove(code, now)
                pendingCodes.add(PendingCodes.Code(code, sender, app, now))
                // Activity says so, and a tap copies it here instead.
                val entry = pendingEntries.getOrPut(code) { id }
                record(ActivityEntry(entry, ActivityEntry.Kind.Otp, code, Problems.CODE_NOT_DELIVERED))
                scope.launch {
                    delay(PendingCodes.MAX_AGE_MS)
                    if (pendingEntries.remove(code, entry)) {
                        _activity.update { list -> list.map { if (it.id == entry) it.copy(detail = Problems.CODE_EXPIRED_AWAY) else it } }
                    }
                }
            }
        }
    }

    /** Mirrors a notification to the Mac. Dropped if the Mac isn't connected; notifications aren't queued. */
    fun sendNotification(
        key: String,
        packageName: String,
        appName: String,
        title: String,
        text: String,
        time: Long,
        canReply: Boolean,
        actions: List<Pair<String, Boolean>> = emptyList(),
    ) {
        val path: MacPath = session?.takeIf { !it.closed } ?: blePath?.takeIf { !it.closed } ?: return
        scope.launch {
            // Icons only over Wi-Fi: Bluetooth is kept to small messages.
            val icon = if (path is Session && iconsSent.add(packageName)) appIconPng(packageName) else null
            val message = Message.of(
                "notif",
                "key" to key, "pkg" to packageName, "app" to appName,
                "title" to title.take(200), "text" to text.take(2_000),
                "time" to time, "reply" to canReply,
                "actions" to org.json.JSONArray().apply {
                    actions.forEachIndexed { index, (title, isReply) ->
                        put(org.json.JSONObject().put("i", index).put("title", title).put("reply", isReply))
                    }
                },
                body = icon ?: ByteArray(0),
            )
            runCatching { path.send(message) }.onFailure { if (icon != null) iconsSent.remove(packageName) }
        }
    }

    /** Tells the Mac about a call. Waits briefly for the link so a ringing call still shows. */
    fun sendCall(call: app.wallhop.calls.CallInfo) {
        scope.launch {
            val path = awaitPath(if (call.state == "ringing") 6_000 else 2_000) ?: return@launch
            runCatching {
                path.send(Message.of("call", "id" to call.id, "state" to call.state,
                    "number" to call.number, "name" to call.name, "sim" to call.sim))
            }
            if (call.state == "missed") {
                record(ActivityEntry(UUID.randomUUID().toString(), ActivityEntry.Kind.Call,
                    "Missed call", call.name ?: call.number ?: "Unknown number"))
            }
        }
    }

    fun notificationRemoved(key: String) {
        val path: MacPath = session?.takeIf { !it.closed } ?: blePath?.takeIf { !it.closed } ?: return
        scope.launch { runCatching { path.send(Message.of("notif_removed", "key" to key)) } }
    }

    private fun appIconPng(packageName: String): ByteArray? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(canvas)
        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }.getOrNull()

    /** Opens a link in the Mac's browser. Null when it did; otherwise why not. */
    suspend fun openOnMac(url: String): Problem? {
        val id = UUID.randomUUID().toString()
        val problem = request(Message.of("open_url", "id" to id, "url" to url))
        if (problem == null) record(ActivityEntry(id, ActivityEntry.Kind.TextSent, "Link opened on your Mac", url))
        return problem
    }

    /** Puts text on the Mac's clipboard. Null when it arrived; otherwise why not. */
    suspend fun sendText(text: String): Problem? {
        val id = UUID.randomUUID().toString()
        val problem = request(Message.of("text", "id" to id, body = text.toByteArray()))
        if (problem == null) record(ActivityEntry(id, ActivityEntry.Kind.TextSent, app.wallhop.core.Wording.READY, text))
        return problem
    }

    /** Sends [message] over Wi‑Fi and waits for its ack: null when the Mac took it, else why not. */
    private suspend fun request(message: Message): Problem? {
        val session = awaitSession(8_000, interactive = true) ?: return reachProblem() ?: Problems.MAC_DIDNT_ANSWER
        val ack = runCatching { session.request(message) }.getOrNull()
        return when {
            ack == null -> if (session.closed) Problems.connectionLost(LocalNetwork.joined(context)) else Problems.MAC_DIDNT_ANSWER
            !ack.header.optBoolean("ok", true) -> Problems.MAC_REFUSED
            else -> null
        }
    }

    /**
     * Sends files to the Mac. Joins the back of the queue: an earlier share that's waiting or
     * sending carries on. Each waits up to ten minutes for the Mac. [id] names the transfer, so a
     * share sheet can follow its own. [skipped] counts files the caller already couldn't read
     * (the share fallback copy); they're reported in the finished transfer's message.
     */
    fun sendFiles(uris: List<Uri>, id: String = UUID.randomUUID().toString(), skipped: Int = 0, storageFull: Boolean = false) {
        sources[id] = Source(uris, skipped, storageFull)
        updateTransfers { TransferQueue.enqueue(it, TransferState(id, emptyList(), 0, 0, TransferPhase.Waiting)) }
        // Names and sizes for the card straight away; reading them queries other apps' providers.
        scope.launch {
            val items = uris.mapNotNull { describe(it) }
            updateOne(id) { if (it.items.isEmpty() && it.phase == TransferPhase.Waiting) it.copy(items = items, totalBytes = items.sumOf { i -> i.size.coerceAtLeast(0) }) else it }
        }
        startQueue()
    }

    /** Queues a shared text or link like files, so it waits for the Mac instead of failing. */
    fun sendTextQueued(text: String, id: String = UUID.randomUUID().toString()) {
        val isLink = app.wallhop.extras.isSingleUrl(text)
        updateTransfers { TransferQueue.enqueue(it, TransferState(id, emptyList(), 0, 0, TransferPhase.Waiting, text = text, isLink = isLink)) }
        startQueue()
    }

    private fun updateTransfers(change: (List<TransferState>) -> List<TransferState>) = synchronized(queueLock) {
        val list = change(_transfers.value)
        _transfers.value = list
        _transfer.value = TransferQueue.shown(list)
    }

    private fun updateOne(id: String, change: (TransferState) -> TransferState) =
        updateTransfers { list -> list.map { if (it.id == id) change(it) else it } }

    /** One worker runs the queue, one transfer at a time. */
    @Synchronized
    private fun startQueue() {
        if (queueWorker?.isActive != true) {
            queueWorker = scope.launch {
                while (isActive) {
                    val next = synchronized(queueLock) { TransferQueue.next(_transfers.value) }
                    if (next == null) {
                        queueWake.receive()
                        continue
                    }
                    val job = launch { runTransfer(next) }
                    runningId = next.id
                    runningJob = job
                    job.join()
                    runningId = null
                    runningJob = null
                    // Cancelled mid-way: it's gone from the list already; anything still marked
                    // sending from it is put away so the next one can start.
                    updateTransfers { list -> list.filterNot { it.id == next.id && TransferQueue.active(it) && job.isCancelled } }
                }
            }
        }
        queueWake.trySend(Unit)
    }

    private suspend fun runTransfer(state: TransferState) {
        val id = state.id
        if (state.text != null) {
            val session = awaitSession(10 * 60_000)
            if (session == null) {
                fail(id, giveUpProblem())
                return
            }
            updateOne(id) { it.copy(phase = TransferPhase.Sending) }
            val problem = if (state.isLink) openOnMac(state.text.trim()) else sendText(state.text)
            if (problem == null) updateOne(id) { it.copy(phase = TransferPhase.Done) } else fail(id, problem)
            return
        }
        val source = sources[id] ?: Source(emptyList(), 0)
        val items = state.items.ifEmpty { source.uris.mapNotNull { describe(it) } }
        // Files this phone couldn't open before sending; their names aren't known.
        val skipped = source.skipped + source.uris.size - items.size
        if (items.isEmpty()) {
            fail(id, if (source.storageFull) Problems.PHONE_FULL_SHARE else Problems.CANT_OPEN)
            return
        }
        val total = items.sumOf { it.size.coerceAtLeast(0) }
        updateOne(id) { it.copy(items = items, totalBytes = total) }

        val session = awaitSession(10 * 60_000)
        if (session == null) {
            fail(id, giveUpProblem())
            return
        }
        updateOne(id) { it.copy(phase = TransferPhase.Sending) }
        // Progress only when the percentage on screen changes (or once a second for a slow one):
        // every chunk, or even ten times a second, would redraw the screens for nothing.
        var shownAt = 0L
        var shownPercent = -1L
        locks.hold(id)
        val streamed = try {
            stream(session, id, items) { sent ->
                val now = android.os.SystemClock.elapsedRealtime()
                val percent = if (total > 0) sent * 100 / total else 0L
                if (percent != shownPercent && now - shownAt >= 100 || now - shownAt >= 1_000) {
                    shownAt = now
                    shownPercent = percent
                    updateOne(id) { it.copy(sentBytes = sent) }
                }
            }
        } finally {
            locks.release(id)
        }
        // The connection went or the Mac stopped answering: nothing is known to have arrived.
        streamed.problem?.let { fail(id, it); return }
        val unreadable = streamed.unreadable + List(skipped) { "a file" }
        val problem = Problems.sent(items.size + skipped, streamed.errs, unreadable)
        val arrived = items.size - streamed.errs.size - streamed.unreadable.size
        if (problem != null && (arrived <= 0 || streamed.errs.isNotEmpty())) {
            fail(id, problem)
        } else {
            // Some files couldn't be opened here: "2 of 3 sent" rather than a plain "Done".
            updateOne(id) { it.copy(sentBytes = total, phase = TransferPhase.Done, message = problem?.title, problem = problem) }
            record(ActivityEntry(id, ActivityEntry.Kind.FilesSent, summary(items), problem?.title ?: app.wallhop.core.Wording.READY,
                uris = items.map { it.uri.toString() }))
        }
    }

    /**
     * How a batch went: the `err` of each file the Mac turned down, the names of files this phone
     * couldn't open, or a [problem] that stopped the whole batch (connection lost, no answer).
     */
    private class Streamed(val errs: List<String?>, val unreadable: List<String>, val problem: Problem? = null)

    /** Streams files in one batch and waits for the Mac's ack for each. */
    private suspend fun stream(
        session: Session,
        id: String,
        items: List<TransferItem>,
        onSent: (Long) -> Unit,
    ): Streamed {
        val unreadable = mutableListOf<String>()
        return try {
            val acks = mutableListOf<kotlinx.coroutines.CompletableDeferred<Message>>()
            val total = items.sumOf { it.size.coerceAtLeast(0) }
            session.send(Message.of("batch_start", "batch" to id, "count" to items.size, "bytes" to total))
            var sent = 0L
            for (item in items) {
                // Opened before file_start, so a file that can't be read is skipped, not half sent.
                val input = runCatching { context.contentResolver.openInputStream(item.uri) }.getOrNull()
                if (input == null) {
                    unreadable += item.name
                    continue
                }
                val fileId = UUID.randomUUID().toString()
                session.send(Message.of("file_start", "id" to fileId, "batch" to id,
                    "name" to item.name, "size" to item.size, "mime" to item.mime))
                val digest = MessageDigest.getInstance("SHA-256")
                var readFailed = false
                input.use {
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        // A read error is this file's problem; a send error (below) is the connection's.
                        val read = try { input.readFully(buffer) } catch (_: IOException) { readFailed = true; break }
                        if (read <= 0) break
                        val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                        digest.update(chunk)
                        session.send(Message.of("file_chunk", "id" to fileId, body = chunk))
                        sent += read
                        onSent(sent)
                    }
                }
                // A file that broke off mid-read ends with a checksum that can't match, so the Mac drops it.
                if (readFailed) unreadable += item.name else acks += session.expectAck(fileId)
                session.send(Message.of("file_end", "id" to fileId, "sha256" to if (readFailed) "" else digest.digest().toHex()))
            }
            session.send(Message.of("batch_end", "batch" to id))

            val results = withTimeoutOrNull(60_000) { acks.map { it.await() } }
                ?: return Streamed(emptyList(), unreadable, Problems.MAC_STOPPED_ANSWERING)
            Streamed(results.filterNot { it.header.optBoolean("ok", true) }.map { it.string("err") }, unreadable)
        } catch (e: Exception) {
            // Closing the session cancels the acks we're waiting on. That isn't our job being
            // cancelled, so report it rather than leaving the transfer stuck on "Sending".
            if (e is kotlinx.coroutines.CancellationException && !kotlinx.coroutines.currentCoroutineContext().isActive) {
                // Cancelled here (Cancel): tell the Mac, so it drops the half-received file now
                // instead of keeping it until the connection closes.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { session.send(Message.of("batch_cancel", "batch" to id)) }
                }
                throw e
            }
            Streamed(emptyList(), unreadable, Problems.connectionLost(LocalNetwork.joined(context)))
        }
    }

    /** Cancels a transfer ([id], or the one on show): stops it if it's sending, or takes it out of the queue. */
    fun cancelTransfer(id: String? = null) {
        val target = id ?: _transfer.value?.takeIf { TransferQueue.active(it) }?.id ?: return
        if (runningId == target) runningJob?.cancel()
        sources.remove(target)
        updateTransfers { list -> list.filterNot { it.id == target } }
        queueWake.trySend(Unit)
    }

    /** Sends a failed transfer again, at the back of the queue. Returns false if there's nothing to resend. */
    fun retryTransfer(id: String): Boolean {
        val failed = _transfers.value.firstOrNull { it.id == id } ?: return false
        updateTransfers { list -> list.filterNot { it.id == id } }
        val text = failed.text
        if (text != null) {
            sendTextQueued(text)
            return true
        }
        val uris = sources.remove(id)?.uris ?: failed.items.map { it.uri }
        if (uris.isEmpty()) return false
        sendFiles(uris)
        return true
    }

    fun clearFinishedTransfer() {
        updateTransfers { list -> list.filter(TransferQueue::active) }
    }

    private fun fail(id: String, problem: Problem) {
        updateOne(id) { it.copy(phase = TransferPhase.Failed, message = problem.title, problem = problem) }
    }

    private fun describe(uri: Uri): TransferItem? = runCatching {
        if (uri.scheme == "file") {
            val file = java.io.File(requireNotNull(uri.path))
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            return@runCatching TransferItem(uri, file.name, file.length(), mime)
        }
        var name = uri.lastPathSegment ?: "File"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.let { name = it }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        TransferItem(uri, name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
    }.getOrNull()

    // MARK: - Phone status

    /** The phone's wallpaper colours, so the Mac app matches it (PROTOCOL.md, theme). */
    private fun sendTheme(session: Session) {
        if (BuildConfig.STRESS_HOOKS) Log.d("WallhopLink", "sendTheme sdk=${android.os.Build.VERSION.SDK_INT}")
        val (primary, accent) = app.wallhop.ui.design.themeSeeds(context) ?: return
        // No second wallpaper colour: send the same contrasting hue the phone uses (hue + 110°).
        val tertiary = accent ?: FloatArray(3).let { hsl ->
            androidx.core.graphics.ColorUtils.colorToHSL(primary, hsl)
            hsl[0] = (hsl[0] + 110f) % 360f
            androidx.core.graphics.ColorUtils.HSLToColor(hsl)
        }
        scope.launch {
            val result = runCatching {
                session.send(Message.of("theme", "p" to (primary.toLong() and 0xFFFFFFFFL), "a" to (tertiary.toLong() and 0xFFFFFFFFL)))
            }
            if (BuildConfig.STRESS_HOOKS) Log.d("WallhopLink", "theme sent=${result.isSuccess} ${result.exceptionOrNull() ?: ""}")
        }
    }

    fun sendStatus() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val key = percent * 2 + if (charging) 1 else 0
        if (key == lastBatterySent) return
        lastBatterySent = key
        val session = session ?: return
        scope.launch { runCatching { session.send(Message.of("status", "battery" to percent, "charging" to charging)) } }
    }

    // MARK: - Helpers

    fun removeActivity(ids: Collection<String>) {
        val set = ids.toHashSet()
        _activity.update { list -> list.filterNot { it.id in set } }
    }

    /** Adds an entry at the top; one with the same id (a code that waited for the Mac) moves up, updated. */
    private fun record(entry: ActivityEntry) {
        _activity.update { list -> (listOf(entry) + list.filterNot { it.id == entry.id }).take(30) }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            discovery.restart()
            scope.launch {
                delay(1_500) // Give Bonjour a moment to find the Mac on the new network.
                poke()
            }
        }

        override fun onLost(network: Network) {
            session?.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        networkCallbackRegistered = runCatching { connectivity.registerNetworkCallback(request, networkCallback) }.isSuccess
    }

    private fun unregisterNetworkCallback() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
    }

    companion object {
        const val DEFAULT_PORT = 47474
        /** A session shorter than this counts as a failed attempt for the reconnect backoff. */
        private const val SHORT_SESSION_MS = 10_000L
        private const val CHUNK_SIZE = 256 * 1024
        /** How long the Wi-Fi link may be down before Bluetooth takes over. */
        private const val BLUETOOTH_GRACE_MS = 5_000L

        @Volatile private var instance: LinkManager? = null

        fun get(context: Context): LinkManager = instance ?: synchronized(this) {
            instance ?: LinkManager(context.applicationContext).also { instance = it }
        }

        fun summary(items: List<TransferItem>): String = app.wallhop.core.Wording.count(items.map { it.mime })
    }
}

/** The Mac's pairing reply has no nonce: it's from before pairing v2. */
private class MacTooOldException : IOException("This Mac needs an update to pair")

/** Reads until the buffer is full or the stream ends. Returns the number of bytes read. */
private fun java.io.InputStream.readFully(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val read = read(buffer, total, buffer.size - total)
        if (read < 0) break
        total += read
    }
    return total
}
