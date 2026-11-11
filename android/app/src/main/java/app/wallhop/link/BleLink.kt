package app.wallhop.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import app.wallhop.BuildConfig
import app.wallhop.core.BleWire
import app.wallhop.core.Crypto
import app.wallhop.core.Identity
import app.wallhop.core.Message
import app.wallhop.core.Noise
import app.wallhop.core.SecureChannel
import app.wallhop.link.Session.Companion.b64
import app.wallhop.link.Session.Companion.unb64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Somewhere messages can go to the Mac: the Wi-Fi session, or the Bluetooth fallback. */
interface MacPath {
    val closed: Boolean
    suspend fun send(message: Message)
    /** Sends a message with an `id` and waits for its `ack`. Returns null on timeout. */
    suspend fun request(message: Message, timeoutMs: Long): Message?
}

/**
 * The Bluetooth LE fallback (docs/PROTOCOL.md, Bluetooth fallback): while the Wi-Fi link is down,
 * the phone advertises a GATT service its paired Mac connects to. Only small messages go this
 * way (codes, calls, notifications); files stay on Wi-Fi.
 *
 * Every Bluetooth call is behind [permitted] (checked in [start]), hence the lint suppression.
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The Mac finished the handshake ([path]) or the link ended (null). */
    private val onPath: (MacPath?) -> Unit,
    /** A sealed message from the Mac other than `ready`, `ack` and `ping`. */
    private val onMessage: (Message) -> Unit,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private var identity: Identity? = null
    private var mac: PairedMac? = null
    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertisedWindow = -1L
    /** When advertising last started (elapsed realtime). */
    private var advertisedAt = 0L
    private var rotateJob: Job? = null
    private var connection: Connection? = null
    private var receiverRegistered = false

    /** True between [start] and [stop], whether or not Bluetooth is on right now. */
    @Volatile var wanted = false
        private set

    private val _problem = kotlinx.coroutines.flow.MutableStateFlow<app.wallhop.core.Problem?>(null)
    /** Why advertising didn't start (shown under the Bluetooth backup switch); null when it's fine. */
    val problem: kotlinx.coroutines.flow.StateFlow<app.wallhop.core.Problem?> = _problem

    private val _radioOff = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The backup is wanted but this phone's Bluetooth is switched off, for a quiet line on Home. */
    val radioOff: kotlinx.coroutines.flow.StateFlow<Boolean> = _radioOff

    /** Starts advertising for [mac]. Safe to call again; a different Mac starts over. */
    @Synchronized
    fun start(identity: Identity, mac: PairedMac) {
        if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "start: wanted=$wanted server=${server != null} radio=${adapter?.isEnabled}")
        if (!permitted(context)) return
        if (wanted && this.mac?.publicKey?.contentEquals(mac.publicKey) == true) return
        if (wanted) stop()
        this.identity = identity
        this.mac = mac
        wanted = true
        registerReceiver()
        _radioOff.value = adapter?.isEnabled != true
        openServer()
    }

    @Synchronized
    fun stop() {
        if (BuildConfig.STRESS_HOOKS && wanted) Log.d(TAG, "stop")
        wanted = false
        _radioOff.value = false
        unregisterReceiver()
        teardown()
        identity = null
        mac = null
    }

    /** Closes everything but stays [wanted], e.g. while Bluetooth is switched off. */
    @Synchronized
    private fun teardown() {
        _problem.value = null
        rotateJob?.cancel()
        rotateJob = null
        stopAdvertising()
        connection?.let { closeConnection(it) }
        runCatching { server?.close() }
        server = null
        characteristic = null
    }

    @Synchronized
    private fun openServer() {
        if (server != null || adapter?.isEnabled != true) return
        val opened = runCatching { manager.openGattServer(context, callback) }.getOrNull()
        if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "openServer: opened=${opened != null}")
        if (opened == null) return
        val service = BluetoothGattService(BleWire.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val char = BluetoothGattCharacteristic(
            BleWire.CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        char.addDescriptor(BluetoothGattDescriptor(
            BleWire.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        ))
        service.addCharacteristic(char)
        server = opened
        characteristic = char
        // Advertising starts once the service is in place (onServiceAdded).
        if (!opened.addService(service)) teardown()
    }

    // MARK: - Advertising

    @Synchronized
    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            // Bluetooth is on but there's no LE advertiser: this phone can't be the backup's end.
            if (adapter?.isEnabled == true) _problem.value = app.wallhop.core.Problems.BLUETOOTH_UNSUPPORTED
            return
        }
        if (identity == null || mac == null) return
        if (connection?.ready == true) return
        ensureRotation()
        val window = BleWire.window(System.currentTimeMillis())
        // Already on: a new 15-minute window only changes the tag, in place. Stopping and starting
        // a set to change it is what left sets behind on some phones (Android didn't stop the old
        // ones), until the phone ran out and the Mac had only stale tags to find.
        advertisingSet?.let { set ->
            if (window != advertisedWindow) {
                runCatching { set.setScanResponseData(scanResponse(window)) }
                    .onSuccess { advertisedWindow = window }
                    .onFailure { stopAdvertising(); startAdvertising() }
            }
            return
        }
        // One is starting; its callback finishes the job. Unless it never answered: try again.
        if (setCallback != null) {
            if (android.os.SystemClock.elapsedRealtime() - advertisedAt < 10_000) return
            stopAdvertising()
        }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        // The service UUID says "a Lync phone"; the tag in the scan response says which one, to
        // its own Mac only, and changes every 15 minutes.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleWire.SERVICE))
            .build()
        val cb = newSetCallback()
        setCallback = cb
        advertisedWindow = window
        advertisedAt = android.os.SystemClock.elapsedRealtime()
        val started = runCatching { advertiser.startAdvertisingSet(params, data, scanResponse(window), null, null, cb) }.isSuccess
        if (!started) setCallback = null
        if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "startAdvertisingSet requested=$started")
    }

    /** The scan response for [window]: this phone's tag, readable by its own Mac only. */
    private fun scanResponse(window: Long): AdvertiseData {
        val tagKey = BleWire.tagKey(Crypto.dh(identity!!.privateKey, mac!!.publicKey), identity!!.publicKey, mac!!.publicKey)
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BleWire.SERVICE), BleWire.tag(tagKey, window))
            .build()
    }

    /**
     * Once a minute while wanted: the tag follows the window, and a set that failed to start (or
     * that Android stopped) is tried again, rather than giving up until the screen comes on.
     */
    private fun ensureRotation() {
        if (rotateJob?.isActive == true) return
        rotateJob = scope.launch {
            while (isActive) {
                delay(60_000)
                synchronized(this@BleLink) {
                    if (wanted && connection?.ready != true) startAdvertising()
                }
            }
        }
    }

    /**
     * Makes sure the Mac can find this phone right now. Timers stop while the phone is in deep
     * sleep, so after hours asleep the advertised tag is hours old (the Mac no longer recognises
     * it) and a link the Mac slept on can still look open. Called whenever something has to go to
     * the Mac and when the screen comes on: a silent link is dropped and the tag is brought up to
     * date at once, instead of waiting for timers that haven't run.
     */
    @Synchronized
    fun refresh() {
        if (!wanted) return
        val conn = connection
        if (conn != null && conn.ready &&
            android.os.SystemClock.elapsedRealtime() - conn.lastHeard > DEAD_AFTER_MS) {
            if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "refresh: silent link, closing")
            closeConnection(conn)
        }
        if (server == null) {
            openServer()
            return
        }
        if (connection?.ready == true) return
        // No link: Android can stop a set on its own (Bluetooth or Wi-Fi changing, the stack
        // restarting) without telling us, so now and then start it afresh. But not more than every
        // 20 s: each restart can come with a new private address, and a Mac halfway through
        // connecting to the old one would lose it. Otherwise just bring the tag up to date.
        val now = android.os.SystemClock.elapsedRealtime()
        if (advertisingSet != null && now - advertisedAt > RESTART_EVERY_MS) {
            if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "refresh: advertising afresh")
            stopAdvertising()
        }
        startAdvertising()
    }

    @Synchronized
    private fun stopAdvertising() {
        // Always ask Android, even when we think it already stopped: on some phones a set can
        // stay on after a central connects, and skipping this leaves it advertising a stale tag.
        val cb = setCallback ?: return
        setCallback = null
        advertisingSet = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
    }

    /** The callback of the set that's meant to be on (or starting); null when none is. */
    private var setCallback: AdvertisingSetCallback? = null
    /** The set that's on, once Android has started it. */
    private var advertisingSet: AdvertisingSet? = null

    private fun newSetCallback(): AdvertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "set started status=$status")
            synchronized(this@BleLink) {
                if (setCallback !== this) {
                    // Started after we moved on (stopped, or restarted): stop it, or it would
                    // advertise on, unowned, even after Lync is closed.
                    runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }
                    return
                }
                if (status == ADVERTISE_SUCCESS && set != null) {
                    advertisingSet = set
                    _problem.value = null
                } else {
                    // Failed: say why under the switch, and let the next minute try again.
                    setCallback = null
                    advertisingSet = null
                    _problem.value = app.wallhop.core.Problems.advertiseFailed(status)
                }
            }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            synchronized(this@BleLink) {
                if (setCallback === this) { setCallback = null; advertisingSet = null }
            }
        }
    }

    // MARK: - GATT server

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "gatt state=$newState status=$status device=${device.address.takeLast(5)} current=${connection?.device?.address?.takeLast(5)}")
            synchronized(this@BleLink) {
                val current = connection
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // One Mac at a time. But Macs change their Bluetooth address for privacy, so the
                    // Mac coming back can look like a second one while we still hold its old link,
                    // which went away without Android telling us. An old link that isn't really
                    // connected any more, hasn't finished its handshake, or has gone quiet makes
                    // way for the new one; a live one keeps its place.
                    if (current != null && current.device.address != device.address) {
                        val stillThere = runCatching {
                            manager.getConnectionState(current.device, BluetoothProfile.GATT_SERVER) == BluetoothProfile.STATE_CONNECTED
                        }.getOrDefault(false)
                        val quiet = android.os.SystemClock.elapsedRealtime() - current.lastHeard > REPLACE_QUIET_MS
                        if (stillThere && current.ready && !quiet) {
                            if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "second central turned away")
                            runCatching { server?.cancelConnection(device) }
                            return
                        }
                        if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "old link gone (connected=$stillThere ready=${current.ready} quiet=$quiet): taking the new one")
                        closeConnection(current)
                        connection = Connection(device).also { it.startTimeout() }
                        return
                    }
                    if (current == null) connection = Connection(device).also { it.startTimeout() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && current?.device?.address == device.address) {
                    closeConnection(current)
                    // Advertising may have stopped when the Mac connected; start it afresh.
                    if (wanted) {
                        stopAdvertising()
                        startAdvertising()
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            connectionFor(device)?.mtu = mtu
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            // Subscribing is the Mac's first step on every new link. It can come over a radio link
            // that never went down (the Mac slept with it open), so no "connected" callback came.
            val conn = connectionFor(device) ?: adopt(device)
            val ok = conn != null && descriptor.uuid == BleWire.CCCD && !preparedWrite && offset == 0
            if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "subscribe ok=$ok conn=${conn != null} device=${device.address.takeLast(5)}")
            if (ok) conn.subscribed = value?.firstOrNull()?.toInt()?.and(1) == 1
            if (responseNeeded) respond(device, requestId, ok)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val conn = connectionFor(device)
            // Long (prepared) writes aren't used: the Mac splits frames to fit one write each.
            val ok = conn != null && characteristic.uuid == BleWire.CHARACTERISTIC && !preparedWrite && offset == 0 && value != null
            if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "write ok=$ok len=${value?.size} device=${device.address.takeLast(5)}")
            if (responseNeeded) respond(device, requestId, ok)
            if (ok) conn.received(value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            connectionFor(device)?.notified?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, ok: Boolean) {
        runCatching {
            server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
        }
    }

    @Synchronized
    private fun connectionFor(device: BluetoothDevice): Connection? = connection?.takeIf { it.device.address == device.address }

    /** A central we have no connection for starts a handshake: take it on if the slot is free. */
    @Synchronized
    private fun adopt(device: BluetoothDevice): Connection? {
        if (!wanted || connection != null) return null
        return Connection(device).also { connection = it; it.startTimeout() }
    }

    @Synchronized
    private fun closeConnection(conn: Connection) {
        if (connection !== conn) return
        connection = null
        val wasReady = conn.ready
        conn.close()
        runCatching { server?.cancelConnection(conn.device) }
        if (wasReady) onPath(null)
        // Advertise again straight away: the radio link may stay up (the Mac holds it), so no
        // "disconnected" callback is coming to do it, and without it the Mac can't find us.
        if (wanted) {
            stopAdvertising()
            startAdvertising()
        }
    }

    /** One connected central: the handshake, then sealed messages. */
    private inner class Connection(val device: BluetoothDevice) : MacPath {
        @Volatile var mtu = 23
        @Volatile var subscribed = false
        @Volatile var ready = false
        @Volatile var notified: CompletableDeferred<Boolean>? = null
        @Volatile override var closed = false
            private set
        private val reassembler = BleWire.Reassembler()
        private var channel: SecureChannel? = null
        private val sendLock = Mutex()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Message>>()
        private var timeout: Job? = null
        private var heartbeat: Job? = null
        /** When the Mac last sent anything (elapsedRealtime, which keeps counting through deep sleep). */
        @Volatile var lastHeard = android.os.SystemClock.elapsedRealtime()

        fun startTimeout() {
            // Connecting isn't enough: the handshake has to finish, or the slot is freed.
            timeout = scope.launch {
                delay(HANDSHAKE_TIMEOUT_MS)
                if (!ready) closeConnection(this@Connection)
            }
        }

        /** A write from the Mac, on a binder thread. Order matters, so frames are handled here. */
        fun received(value: ByteArray) {
            lastHeard = android.os.SystemClock.elapsedRealtime()
            val frame = try {
                synchronized(reassembler) { reassembler.add(value) } ?: return
            } catch (_: IOException) {
                closeConnection(this)
                return
            }
            try {
                handle(frame)
            } catch (e: Exception) {
                if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "closing: ${e.message}")
                closeConnection(this)
            }
        }

        @Synchronized
        private fun handle(frame: ByteArray) {
            val channel = channel
            if (channel == null) {
                // hello (Noise IK, the Mac initiating): it must come from the paired Mac's key.
                // Nothing goes back to anyone else, and this phone's key never crosses in the clear.
                val hello = JSONObject(String(frame, Charsets.UTF_8))
                if (hello.optString("t") != "hello") throw IOException("Expected hello")
                val paired = mac?.publicKey ?: throw IOException("Not paired")
                val id = identity ?: throw IOException("No identity")
                val noise = Noise.Handshake(Noise.Role.Responder, Crypto.BLE_PROLOGUE, id.privateKey)
                noise.readMessage(hello.getString("noise").unb64())
                if (!MessageDigest.isEqual(noise.remoteStatic, paired)) throw IOException("Unknown Mac")
                val reply = JSONObject().put("t", "hello_ok").put("noise", noise.writeMessage().b64())
                this.channel = SecureChannel(noise.split())
                scope.launch { runCatching { write(reply.toString().toByteArray(Charsets.UTF_8)) }.onFailure { closeConnection(this@Connection) } }
                return
            }
            val message = Message.decode(channel.open(frame))
            if (!ready) {
                // The Mac's ready decrypted, so it holds the paired Mac's key. Answer, then use the link.
                if (message.type != "ready") throw IOException("Expected ready")
                scope.launch {
                    runCatching { send(Message.of("ready")) }.onFailure { closeConnection(this@Connection); return@launch }
                    ready = true
                    timeout?.cancel()
                    stopAdvertising()
                    startHeartbeat()
                    onPath(this@Connection)
                }
                return
            }
            when (message.type) {
                "ack" -> message.string("id")?.let { pending.remove(it)?.complete(message) }
                "ping" -> scope.launch { runCatching { send(Message.of("pong")) } }
                "pong" -> Unit // Only for lastHeard.
                else -> onMessage(message)
            }
        }

        /**
         * A link can look open and carry nothing (the Mac went to sleep holding it), and the radio
         * won't say so. Ping like the Wi-Fi session does; silence for [DEAD_AFTER_MS] ends it.
         */
        private fun startHeartbeat() {
            heartbeat = scope.launch {
                while (isActive && !closed) {
                    delay(PING_EVERY_MS)
                    if (android.os.SystemClock.elapsedRealtime() - lastHeard > DEAD_AFTER_MS) {
                        if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "silent link, closing")
                        closeConnection(this@Connection)
                        return@launch
                    }
                    runCatching { send(Message.of("ping")) }.onFailure { closeConnection(this@Connection); return@launch }
                }
            }
        }

        override suspend fun send(message: Message) {
            sendLock.withLock {
                val channel = channel ?: throw IOException("Not ready")
                write(channel.seal(message.encode()), locked = true)
            }
        }

        /** Sends one frame as notifications, each waiting for the last to go out. */
        private suspend fun write(frame: ByteArray, locked: Boolean = false) {
            if (!locked) return sendLock.withLock { write(frame, locked = true) }
            if (closed || !subscribed) throw IOException("Not subscribed")
            val char = characteristic ?: throw IOException("Closed")
            val gatt = server ?: throw IOException("Closed")
            for (chunk in BleWire.fragment(frame, (mtu - 3).coerceAtLeast(20))) {
                val done = CompletableDeferred<Boolean>()
                notified = done
                val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.notifyCharacteristicChanged(device, char, false, chunk) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    char.value = chunk
                    @Suppress("DEPRECATION")
                    gatt.notifyCharacteristicChanged(device, char, false)
                }
                if (!queued || withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { done.await() } != true) throw IOException("Notify failed")
            }
        }

        override suspend fun request(message: Message, timeoutMs: Long): Message? {
            val id = requireNotNull(message.string("id"))
            val ack = CompletableDeferred<Message>().also { pending[id] = it }
            val reply = try {
                send(message)
                withTimeoutOrNull(timeoutMs) { ack.await() }
            } catch (e: IOException) {
                closeConnection(this)
                throw e
            } finally {
                pending.remove(id)
            }
            // The Mac always acks; no answer means this link is dead. Close it so the next try
            // gets a fresh one instead of going down the same hole.
            if (reply == null && !closed) {
                if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "no ack, closing")
                closeConnection(this)
            }
            return reply
        }

        fun close() {
            closed = true
            ready = false
            timeout?.cancel()
            heartbeat?.cancel()
            notified?.complete(false)
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    // MARK: - Bluetooth on and off

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> synchronized(this@BleLink) { _radioOff.value = false; if (wanted) openServer() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> { _radioOff.value = wanted; teardown() }
            }
        }
    }

    /** The phone woke up (screen on, or unlocked): be findable with a current tag straight away. */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(context, stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            // System broadcasts: delivered to a not-exported receiver too.
            ContextCompat.registerReceiver(context, wakeReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) },
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { context.unregisterReceiver(stateReceiver) }
        runCatching { context.unregisterReceiver(wakeReceiver) }
    }

    companion object {
        private const val TAG = "WallhopBle"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val NOTIFY_TIMEOUT_MS = 3_000L
        /** Same rhythm as the Wi-Fi session: a ping every 15 s, and 45 s of silence is a dead link. */
        private const val PING_EVERY_MS = 15_000L
        private const val DEAD_AFTER_MS = 45_000L
        /** A refresh with no link restarts advertising at most this often. */
        private const val RESTART_EVERY_MS = 20_000L
        /** An old link this quiet makes way for a new connection from the Mac. */
        private const val REPLACE_QUIET_MS = 20_000L

        /** Android 12+ asks for "Nearby devices"; before that the Bluetooth permissions are granted at install. */
        val required: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyArray()
            }

        /** The phone can do it (Bluetooth LE advertising) and Android allows it. */
        fun permitted(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
                required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
}
