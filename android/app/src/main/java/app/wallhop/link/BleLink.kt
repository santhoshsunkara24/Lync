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
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
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
    private var advertising = false
    private var advertisedWindow = -1L
    private var rotateJob: Job? = null
    private var connection: Connection? = null
    private var receiverRegistered = false

    /** True between [start] and [stop], whether or not Bluetooth is on right now. */
    @Volatile var wanted = false
        private set

    private val _problem = kotlinx.coroutines.flow.MutableStateFlow<app.wallhop.core.Problem?>(null)
    /** Why advertising didn't start (shown under the Bluetooth backup switch); null when it's fine. */
    val problem: kotlinx.coroutines.flow.StateFlow<app.wallhop.core.Problem?> = _problem

    /** Starts advertising for [mac]. Safe to call again; a different Mac starts over. */
    @Synchronized
    fun start(identity: Identity, mac: PairedMac) {
        if (!permitted(context)) return
        if (wanted && this.mac?.publicKey?.contentEquals(mac.publicKey) == true) return
        if (wanted) stop()
        this.identity = identity
        this.mac = mac
        wanted = true
        registerReceiver()
        openServer()
    }

    @Synchronized
    fun stop() {
        wanted = false
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
        val opened = runCatching { manager.openGattServer(context, callback) }.getOrNull() ?: return
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
        val identity = identity ?: return
        val mac = mac ?: return
        if (advertising || connection?.ready == true) return
        val window = BleWire.window(System.currentTimeMillis())
        val tagKey = BleWire.tagKey(Crypto.dh(identity.privateKey, mac.publicKey), identity.publicKey, mac.publicKey)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // The service UUID says "a Lync phone"; the tag in the scan response says which one, to
        // its own Mac only, and changes every 15 minutes.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleWire.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BleWire.SERVICE), BleWire.tag(tagKey, window))
            .build()
        advertising = runCatching { advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback) }.isSuccess
        advertisedWindow = window
        if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "advertising=$advertising")
        if (rotateJob?.isActive != true) rotateJob = scope.launch {
            while (isActive) {
                delay(60_000)
                // A new window: advertise the new tag.
                if (advertising && BleWire.window(System.currentTimeMillis()) != advertisedWindow) {
                    stopAdvertising()
                    startAdvertising()
                }
            }
        }
    }

    @Synchronized
    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _problem.value = null
        }

        override fun onStartFailure(errorCode: Int) {
            if (BuildConfig.STRESS_HOOKS) Log.d(TAG, "advertise failed $errorCode")
            // Already advertising isn't a failure.
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) _problem.value = app.wallhop.core.Problems.advertiseFailed(errorCode)
            synchronized(this@BleLink) { advertising = false }
        }
    }

    // MARK: - GATT server

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(this@BleLink) {
                val current = connection
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // One Mac at a time: a second central waits until the first is gone.
                    if (current != null && current.device.address != device.address) {
                        runCatching { server?.cancelConnection(device) }
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
            val conn = connectionFor(device)
            val ok = conn != null && descriptor.uuid == BleWire.CCCD && !preparedWrite && offset == 0
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

    @Synchronized
    private fun closeConnection(conn: Connection) {
        if (connection !== conn) return
        connection = null
        val wasReady = conn.ready
        conn.close()
        runCatching { server?.cancelConnection(conn.device) }
        if (wasReady) onPath(null)
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

        fun startTimeout() {
            // Connecting isn't enough: the handshake has to finish, or the slot is freed.
            timeout = scope.launch {
                delay(HANDSHAKE_TIMEOUT_MS)
                if (!ready) closeConnection(this@Connection)
            }
        }

        /** A write from the Mac, on a binder thread. Order matters, so frames are handled here. */
        fun received(value: ByteArray) {
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
                // hello: the Mac's static key must be the paired Mac's.
                val hello = JSONObject(String(frame, Charsets.UTF_8))
                if (hello.optString("t") != "hello") throw IOException("Expected hello")
                val macPub = hello.getString("pub").unb64()
                val macEph = hello.getString("eph").unb64()
                val paired = mac?.publicKey ?: throw IOException("Not paired")
                if (macEph.size != 32 || !MessageDigest.isEqual(macPub, paired)) throw IOException("Unknown Mac")
                val id = identity ?: throw IOException("No identity")
                val ephemeral = Crypto.generatePrivateKey()
                val ephemeralPub = Crypto.publicKey(ephemeral)
                val ikm = BleWire.phoneIkm(id.privateKey, ephemeral, paired, macEph)
                val (sendKey, receiveKey) = BleWire.sessionKeys(ikm, id.publicKey, ephemeralPub, paired, macEph)
                this.channel = SecureChannel(sendKey, receiveKey)
                val reply = JSONObject().put("t", "hello_ok").put("v", 1)
                    .put("pub", id.publicKey.b64()).put("eph", ephemeralPub.b64())
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
                    onPath(this@Connection)
                }
                return
            }
            when (message.type) {
                "ack" -> message.string("id")?.let { pending.remove(it)?.complete(message) }
                "ping" -> scope.launch { runCatching { send(Message.of("pong")) } }
                else -> onMessage(message)
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
            return try {
                send(message)
                withTimeoutOrNull(timeoutMs) { ack.await() }
            } finally {
                pending.remove(id)
            }
        }

        fun close() {
            closed = true
            ready = false
            timeout?.cancel()
            notified?.complete(false)
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    // MARK: - Bluetooth on and off

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> synchronized(this@BleLink) { if (wanted) openServer() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> teardown()
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(context, stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { context.unregisterReceiver(stateReceiver) }
    }

    companion object {
        private const val TAG = "WallhopBle"
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val NOTIFY_TIMEOUT_MS = 3_000L

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
