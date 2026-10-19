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
import app.wallhop.core.Crypto
import app.wallhop.core.Identity
import app.wallhop.core.Message
import app.wallhop.core.toHex
import app.wallhop.link.Session.Companion.b64
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    data class Searching(val macName: String) : LinkState
    data class Connected(val macName: String, val host: String) : LinkState
    /** `code` is null while connecting, then the 6-digit code to compare on both screens. */
    data class Pairing(val macName: String, val code: String?) : LinkState
    data class PairingFailed(val macName: String, val reason: String) : LinkState
    /** The Mac forgot this phone, so it has to pair again. */
    data class Forgotten(val macName: String) : LinkState
}

data class TransferItem(val uri: Uri, val name: String, val size: Long, val mime: String)

enum class TransferPhase { Waiting, Sending, Done, Failed }

data class TransferState(
    val id: String,
    val items: List<TransferItem>,
    val sentBytes: Long,
    val totalBytes: Long,
    val phase: TransferPhase,
    val message: String? = null,
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (sentBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class ActivityEntry(
    val id: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    val time: Long = System.currentTimeMillis(),
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

    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer.asStateFlow()

    private val _activity = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val activity: StateFlow<List<ActivityEntry>> = _activity.asStateFlow()

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
    private var loopJob: Job? = null
    private var pairingJob: Job? = null
    private var transferJob: Job? = null
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

    /** Starts discovery and the reconnect loop. Safe to call many times. */
    init {
        // When the Mac reappears on the network (for example after it wakes), reconnect right away.
        discovery.onFound = { found ->
            val mac = store.get()
            if (mac != null && _state.value !is LinkState.Connected && found.name == mac.name) poke()
        }
    }

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
    }

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
            if (_state.value !is LinkState.Connected && _state.value !is LinkState.Forgotten) {
                _state.value = LinkState.Searching(mac.name)
            }

            var connected = false
            var sessionMs = 0L
            for ((host, port, resolved) in candidates(mac)) {
                val result = runCatching {
                    val socket = Session.open(host, port)
                    try {
                        Session.handshake(socket, identity, phoneName, mac.publicKey).first
                    } catch (e: Exception) {
                        runCatching { socket.close() }
                        throw e
                    }
                }
                val opened = result.getOrNull()
                // "unpaired" isn't authenticated, so it never deletes the pairing: anyone on the
                // network could send it. When it comes from the address Bonjour gave for our Mac's
                // name with our Mac's key, the phone asks the user to pair again (keeping the keys
                // until they do) and keeps retrying, so a Mac that still knows us reconnects.
                // Any other Mac (a second Mac, a test copy) is just the wrong one: try the next.
                val unpaired = result.exceptionOrNull() as? UnpairedException
                if (unpaired != null) {
                    if (resolved && unpaired.macPub != null && java.security.MessageDigest.isEqual(unpaired.macPub, mac.publicKey)) {
                        _state.value = LinkState.Forgotten(mac.name)
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
                if (_state.value !is LinkState.Forgotten) _state.value = LinkState.Searching(mac.name)
                waitForWake(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(if (_state.value is LinkState.Forgotten) 60_000L else 15_000L)
            }
        }
    }

    /**
     * Places to try, best first: Bonjour results for this Mac, its last address, other Macs found.
     * The flag marks addresses Bonjour resolved for this Mac's name.
     */
    private fun candidates(mac: PairedMac): List<Triple<String, Int, Boolean>> {
        val found = discovery.macs.value
        val list = mutableListOf<Triple<String, Int, Boolean>>()
        found.filter { it.name == mac.name }.forEach { list += Triple(it.host, it.port, true) }
        if (mac.host != null) list += Triple(mac.host, mac.port, false)
        found.filter { it.name != mac.name }.forEach { list += Triple(it.host, it.port, false) }
        return list.distinctBy { it.first to it.second }
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
        // A Glyph pulse when the Mac connects, but not for a reconnect after a short network blip.
        val now = android.os.SystemClock.elapsedRealtime()
        if (disconnectedAt == 0L || now - disconnectedAt >= 2 * 60_000) {
            app.wallhop.glyph.Glyphs.play(context, app.wallhop.glyph.Glyphs.Effect.Connected)
        }
        iconsSent.clear()
        _state.value = LinkState.Connected(session.macName, session.host)
        _connectedSince.value = System.currentTimeMillis()
        lastBatterySent = -1
        sendStatus()
        sendTheme(session)
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
            incoming.cancelAll()
            if (this.session === session) this.session = null
            disconnectedAt = android.os.SystemClock.elapsedRealtime()
            _latencyMs.value = null
            _connectedSince.value = null
            _macInfo.value = _macInfo.value.copy(locked = false)
            store.get()?.let { _state.value = LinkState.Searching(it.name) }
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
                macInfoStore.save(info, jpeg, hash)
            }
            "ack" -> session.completeAck(message)
            "text" -> {
                val text = message.bodyText
                Notifications.textFromMac(context, text, session.macName)
                record(ActivityEntry(UUID.randomUUID().toString(), ActivityEntry.Kind.FromMac, "Text from your Mac", text))
                message.string("id")?.let { session.send(Message.of("ack", "id" to it, "ok" to true)) }
            }
            // Replies, actions and dismissals only while notification mirroring is on in Lync.
            "notif_reply" -> {
                val ok = notificationsOn && message.string("key")?.let {
                    app.wallhop.otp.OtpNotificationListener.reply(context, it, message.bodyText)
                } ?: false
                message.string("id")?.let { session.send(Message.of("ack", "id" to it, "ok" to ok)) }
            }
            "notif_dismiss" -> if (notificationsOn) {
                message.string("key")?.let { app.wallhop.otp.OtpNotificationListener.dismiss(it) }
            }
            "notif_action" -> {
                val key = message.string("key")
                val index = message.header.optInt("i", -1)
                if (notificationsOn && key != null && index >= 0) {
                    app.wallhop.otp.OtpNotificationListener.performAction(context, key, index)
                }
            }
            "sms_threads", "sms_thread" -> {
                val id = message.string("id") ?: return
                scope.launch {
                    // A provider that throws (an odd OEM SMS database, a revoked permission) gets an
                    // error reply instead of crashing the app.
                    val reply = runCatching {
                        when {
                            !sms.canRead || !app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Texts) -> Message.of("sms_result", "id" to id, "err" to "permission")
                            message.type == "sms_threads" ->
                                Message.of("sms_result", "id" to id, body = sms.threads().toString().toByteArray())
                            else -> Message.of("sms_result", "id" to id,
                                body = sms.messages(message.string("thread").orEmpty()).toString().toByteArray())
                        }
                    }.getOrElse { Message.of("sms_result", "id" to id, "err" to "unavailable") }
                    runCatching { session.send(reply) }
                }
            }
            "sms_send" -> {
                val id = message.string("id")
                val sim = if (message.header.has("sim")) message.header.optInt("sim", -1) else null
                // Off the read loop: waiting for the carrier can take a while.
                scope.launch {
                    val result = if (app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Texts)) {
                        sms.send(message.string("address").orEmpty(), message.bodyText, sim)
                    } else {
                        Result.failure(IllegalStateException("Texts are turned off in Lync"))
                    }
                    runCatching {
                        session.send(Message.of("ack", "id" to id, "ok" to result.isSuccess,
                            "err" to result.exceptionOrNull()?.message))
                    }
                }
            }
            "open_url" -> message.string("url")?.let { app.wallhop.extras.LinkFromMac.show(context, it) }
            "ring" -> if (message.string("action") == "stop") app.wallhop.extras.PhoneFinder.stop(context)
                else app.wallhop.extras.PhoneFinder.start(context)
            "media_cmd" -> message.string("action")?.let { action -> mainHandler.post { media.command(action) } }
            "call_cmd" -> message.string("action")?.let { app.wallhop.calls.CallControl.perform(context, it) }
            // Files fail one at a time (reported in the file_end ack); the link stays up.
            "batch_start" -> runCatching { incoming.batchStart(message) }
            "file_start" -> runCatching { incoming.fileStart(message) }
            "file_chunk" -> runCatching { incoming.fileChunk(message) }
            "file_end" -> {
                val error = runCatching { incoming.fileEnd(message) }.getOrElse { "couldn't save" }
                session.send(Message.of("ack", "id" to message.string("id"), "ok" to (error == null), "err" to error))
            }
            "batch_end" -> runCatching { incoming.batchEnd(message, session.macName) }.getOrNull()?.let { summary ->
                record(ActivityEntry(UUID.randomUUID().toString(), ActivityEntry.Kind.FromMac, summary, "Saved to Downloads › Lync"))
            }
            else -> Unit // Unknown types are ignored so newer Macs keep working.
        }
    }

    private val notificationsOn: Boolean
        get() = app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.AppNotifications)

    // MARK: - Pairing

    /** Pairs using a scanned QR: no code to compare, and the Mac's key is checked against it. */
    fun pair(invite: QrInvite) = pair(invite.host, invite.port, invite.name, invite)

    /** Starts pairing with a Mac found on the network or typed in by hand. */
    fun pair(host: String, port: Int, name: String, invite: QrInvite? = null) {
        stopPairing()
        _state.value = LinkState.Pairing(name, null)
        // Set once connected, so cancelling closes the socket and unblocks the read straight away.
        val pairingSocket = java.util.concurrent.atomic.AtomicReference<java.net.Socket?>(null)
        lateinit var job: Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // Pairing again replaces a key the Keystore can no longer unlock.
                val identity = Identity.get(context, regenerateIfUnreadable = true)
                // A QR lists every address the Mac has; use whichever answers first.
                val target = if (invite != null && invite.hosts.size > 1) firstReachable(invite.hosts, port) ?: host else host
                val socket = Session.open(target, port)
                pairingSocket.set(socket)
                socket.use {
                    ensureActive() // Cancelled while connecting.
                    socket.soTimeout = 130_000 // The user has two minutes to confirm on the Mac.
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val output = socket.getOutputStream().buffered()
                    // Pairing v2 (PROTOCOL.md): commit to a nonce first and reveal it only after the
                    // Mac has sent its own, so a man in the middle can't grind keys to match the code.
                    val phoneNonce = Crypto.pairNonce()
                    val request = JSONObject()
                        .put("t", "pair")
                        .put("v", 2)
                        .put("name", phoneName)
                        .put("pub", identity.publicKey.b64())
                        .put("commit", Crypto.pairingCommit(identity.publicKey, phoneNonce).b64())
                    if (invite != null) {
                        val proof = Crypto.qrProof(invite.token, identity.publicKey, invite.macPub)
                        request.put("qr", proof.b64())
                    }
                    Session.writeJson(output, request)

                    val wait = Session.readJson(input)
                    if (wait.optString("t") != "pair_wait") throw IOException("This Mac didn't accept pairing")
                    val macPub = android.util.Base64.decode(wait.getString("pub"), android.util.Base64.NO_WRAP)
                    val macNonce = runCatching { android.util.Base64.decode(wait.optString("nonce"), android.util.Base64.NO_WRAP) }
                        .getOrNull()?.takeIf { it.size == Crypto.PAIR_NONCE_SIZE }
                        ?: throw IOException("This Mac needs an update to pair. Update Lync on your Mac.")
                    if (macPub.size != 32) throw IOException("Pairing failed.")
                    val macName = wait.optString("name", name)
                    // With a QR, the Mac must be the one whose key is in the code.
                    if (invite != null && !java.security.MessageDigest.isEqual(macPub, invite.macPub)) {
                        throw IOException("This isn't the Mac from the QR code.")
                    }
                    Session.writeJson(output, JSONObject().put("t", "pair_nonce").put("nonce", phoneNonce.b64()))
                    val code = if (invite != null) null else Crypto.pairingCode(identity.publicKey, macPub, phoneNonce, macNonce)
                    _state.value = LinkState.Pairing(macName, code)

                    when (Session.readJson(input).optString("t")) {
                        "pair_ok" -> {
                            // Cancelled while the Mac was accepting: don't pair behind the user's back.
                            ensureActive()
                            store.save(PairedMac(Crypto.deviceId(macPub), macName, macPub, target, port))
                            _state.value = LinkState.Searching(macName)
                        }
                        "pair_no" -> throw PairingDeclinedException()
                        else -> throw IOException("Pairing ended unexpectedly")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = when (e) {
                    is PairingDeclinedException ->
                        if (invite != null) "That QR code has expired. Scan the new one on your Mac." else "Pairing was declined on the Mac."
                    is java.net.SocketTimeoutException -> "The Mac didn't respond in time."
                    is java.net.ConnectException -> "Couldn't reach the Mac. Check it's on the same Wi-Fi and Lync is open."
                    else -> e.message ?: "Pairing failed."
                }
                if (_state.value is LinkState.Pairing && job.isActive) _state.value = LinkState.PairingFailed(name, reason)
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
        session?.close()
        store.forget()
        macInfoStore.clear()
        _macInfo.value = MacInfo()
        _state.value = LinkState.NotPaired
        poke()
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

    /** The open session, waiting up to [timeoutMs] for one. Never returns a closed session. */
    private suspend fun awaitSession(timeoutMs: Long): Session? {
        session?.takeIf { !it.closed }?.let { return it }
        start()
        return withTimeoutOrNull(timeoutMs) {
            var open: Session? = null
            while (open == null) {
                state.first { it is LinkState.Connected }
                open = session?.takeIf { !it.closed }
                if (open == null) delay(200) // Connected but the session just dropped: wait for the next one.
            }
            open
        }
    }

    /** Sends a one-time code. Duplicates within three minutes (SMS plus its notification) are dropped. */
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

    fun sendOtp(code: String, sender: String, app: String): Job? {
        val now = System.currentTimeMillis()
        recentCodes.entries.removeAll { now - it.value > 3 * 60_000 }
        if (recentCodes.putIfAbsent(code, now) != null) return null

        return scope.launch {
            val session = awaitSession(20_000)
            val id = UUID.randomUUID().toString()
            val ack = session?.let {
                runCatching {
                    it.request(Message.of("otp", "id" to id, "code" to code, "sender" to sender, "app" to app))
                }.getOrNull()
            }
            if (BuildConfig.STRESS_HOOKS) Log.d("WallhopLink", "otp delivered=${ack != null}")
            if (ack != null) {
                record(ActivityEntry(id, ActivityEntry.Kind.Otp, code, if (sender.isBlank()) "Copied on your Mac" else "From $sender · copied on your Mac"))
            } else {
                // Not delivered: forget it, so the same code from another source (or the inbox
                // scan after reconnecting) can still go across.
                recentCodes.remove(code, now)
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
        val session = session?.takeIf { !it.closed } ?: return
        scope.launch {
            val icon = if (iconsSent.add(packageName)) appIconPng(packageName) else null
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
            runCatching { session.send(message) }.onFailure { iconsSent.remove(packageName) }
        }
    }

    /** Tells the Mac about a call. Waits briefly for the link so a ringing call still shows. */
    fun sendCall(call: app.wallhop.calls.CallInfo) {
        scope.launch {
            val session = awaitSession(if (call.state == "ringing") 6_000 else 2_000) ?: return@launch
            runCatching {
                session.send(Message.of("call", "id" to call.id, "state" to call.state,
                    "number" to call.number, "name" to call.name, "sim" to call.sim))
            }
            if (call.state == "missed") {
                record(ActivityEntry(UUID.randomUUID().toString(), ActivityEntry.Kind.Call,
                    "Missed call", call.name ?: call.number ?: "Unknown number"))
            }
        }
    }

    fun notificationRemoved(key: String) {
        val session = session?.takeIf { !it.closed } ?: return
        scope.launch { runCatching { session.send(Message.of("notif_removed", "key" to key)) } }
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

    /** Opens a link in the Mac's browser. */
    suspend fun openOnMac(url: String): Boolean {
        val session = awaitSession(8_000) ?: return false
        val id = UUID.randomUUID().toString()
        val ack = runCatching { session.request(Message.of("open_url", "id" to id, "url" to url)) }.getOrNull()
        if (ack != null) record(ActivityEntry(id, ActivityEntry.Kind.TextSent, "Link opened on your Mac", url))
        return ack != null
    }

    suspend fun sendText(text: String): Boolean {
        val session = awaitSession(8_000) ?: return false
        val id = UUID.randomUUID().toString()
        val ack = runCatching { session.request(Message.of("text", "id" to id, body = text.toByteArray())) }.getOrNull()
        if (ack != null) record(ActivityEntry(id, ActivityEntry.Kind.TextSent, "Text sent", text))
        return ack != null
    }

    /**
     * Sends files to the Mac. Waits up to ten minutes for the Mac if it isn't nearby. [id] names
     * the transfer, so a share sheet can follow its own transfer and not an earlier one.
     * [skipped] counts files the caller already couldn't read (the share fallback copy); they're
     * reported in the finished transfer's message along with any here that can't be read.
     */
    @Synchronized
    fun sendFiles(uris: List<Uri>, id: String = UUID.randomUUID().toString(), skipped: Int = 0) {
        transferJob?.cancel()
        transferJob = scope.launch {
            // Reading names and sizes queries other apps' providers: never on the main thread.
            val items = uris.mapNotNull { describe(it) }
            val unreadable = skipped + uris.size - items.size
            if (items.isEmpty()) {
                _transfer.value = TransferState(id, emptyList(), 0, 0, TransferPhase.Failed, "Couldn't read these files")
                return@launch
            }
            val total = items.sumOf { it.size.coerceAtLeast(0) }
            _transfer.value = TransferState(id, items, 0, total, TransferPhase.Waiting)

            val session = awaitSession(10 * 60_000)
            if (session == null) {
                fail(id, "Your Mac isn't nearby. Try again when it's on the same Wi-Fi.")
                return@launch
            }
            _transfer.update { it?.copy(phase = TransferPhase.Sending) }
            // Progress at most ten times a second: every chunk would redraw the screens for nothing.
            var shownAt = 0L
            val error = stream(session, id, items) { sent ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - shownAt >= 100) {
                    shownAt = now
                    _transfer.update { it?.copy(sentBytes = sent) }
                }
            }
            if (error != null) {
                fail(id, error)
            } else {
                // Some files couldn't be read at all: say so rather than a plain "Done".
                val note = if (unreadable > 0) "${items.size} of ${items.size + unreadable} sent — couldn't read $unreadable" else null
                _transfer.update { it?.copy(sentBytes = total, phase = TransferPhase.Done, message = note) }
                record(ActivityEntry(id, ActivityEntry.Kind.FilesSent, summary(items), note ?: "Copied on your Mac"))
            }
        }
    }

    /** Streams files in one batch. Returns an error message, or null when the Mac confirmed every file. */
    private suspend fun stream(
        session: Session,
        id: String,
        items: List<TransferItem>,
        onSent: (Long) -> Unit,
    ): String? = try {
        val acks = mutableListOf<kotlinx.coroutines.CompletableDeferred<Message>>()
        val total = items.sumOf { it.size.coerceAtLeast(0) }
        session.send(Message.of("batch_start", "batch" to id, "count" to items.size, "bytes" to total))
        var sent = 0L
        for (item in items) {
            val fileId = UUID.randomUUID().toString()
            session.send(Message.of("file_start", "id" to fileId, "batch" to id,
                "name" to item.name, "size" to item.size, "mime" to item.mime))
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                while (true) {
                    val read = input.readFully(buffer)
                    if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                    digest.update(chunk)
                    session.send(Message.of("file_chunk", "id" to fileId, body = chunk))
                    sent += read
                    onSent(sent)
                }
            } ?: throw IOException("Couldn't read ${item.name}")
            acks += session.expectAck(fileId)
            session.send(Message.of("file_end", "id" to fileId, "sha256" to digest.digest().toHex()))
        }
        session.send(Message.of("batch_end", "batch" to id))

        val results = withTimeoutOrNull(60_000) { acks.map { it.await() } }
        val failed = results?.count { it.header.optBoolean("ok", true).not() } ?: items.size
        when {
            results == null -> "Your Mac didn't confirm the transfer."
            failed > 0 -> "$failed of ${items.size} didn't arrive."
            else -> null
        }
    } catch (e: Exception) {
        // Closing the session cancels the acks we're waiting on. That isn't our job being
        // cancelled, so report it rather than leaving the transfer stuck on "Sending".
        if (e is kotlinx.coroutines.CancellationException && !kotlinx.coroutines.currentCoroutineContext().isActive) throw e
        if (e is kotlinx.coroutines.CancellationException || session.closed) "Connection lost" else "The connection dropped. Try again."
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        _transfer.value = null
    }

    fun clearFinishedTransfer() {
        val phase = _transfer.value?.phase
        if (phase == TransferPhase.Done || phase == TransferPhase.Failed) _transfer.value = null
    }

    private fun fail(id: String, message: String) {
        _transfer.update { if (it?.id == id) it.copy(phase = TransferPhase.Failed, message = message) else it }
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

    fun removeActivity(id: String) {
        _activity.update { list -> list.filterNot { it.id == id } }
    }

    private fun record(entry: ActivityEntry) {
        _activity.update { (listOf(entry) + it).take(30) }
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

        @Volatile private var instance: LinkManager? = null

        fun get(context: Context): LinkManager = instance ?: synchronized(this) {
            instance ?: LinkManager(context.applicationContext).also { instance = it }
        }

        fun summary(items: List<TransferItem>): String {
            val images = items.all { it.mime.startsWith("image/") }
            val videos = items.all { it.mime.startsWith("video/") }
            val noun = when {
                images -> if (items.size == 1) "photo" else "photos"
                videos -> if (items.size == 1) "video" else "videos"
                else -> if (items.size == 1) "file" else "files"
            }
            return "${items.size} $noun"
        }
    }
}

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
