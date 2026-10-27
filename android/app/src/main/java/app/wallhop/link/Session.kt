package app.wallhop.link

import android.util.Base64
import app.wallhop.core.Crypto
import app.wallhop.core.Framing
import app.wallhop.core.Identity
import app.wallhop.core.Message
import app.wallhop.core.Noise
import app.wallhop.core.SecureChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The paired Mac said it no longer knows this phone. [macPub] is that Mac's key: the answer came
 * inside the Noise handshake, so it really is from the Mac this phone paired with.
 */
class UnpairedException(val macPub: ByteArray?) : IOException("This Mac no longer recognises the phone")

/** The Mac turned pairing down; [err] is its `pair_no` code (PROTOCOL.md), null from older Macs. */
class PairingDeclinedException(val err: String? = null) : IOException("Pairing was declined on the Mac")

/** The Mac at this address has a different key from the paired one (reinstalled, or another Mac). */
class WrongMacException : IOException("This isn't the Mac you paired with")

/** The other side doesn't speak this handshake: a different protocol version, or not Lync. */
class HandshakeException(message: String) : IOException(message)

/** An open, encrypted connection to the paired Mac. */
class Session(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val channel: SecureChannel,
    val macName: String,
    val host: String,
    val port: Int,
) : MacPath {
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Message>>()

    @Volatile override var closed = false
        private set

    override suspend fun send(message: Message): Unit = withContext(Dispatchers.IO) {
        writeLock.withLock {
            Framing.write(output, channel.seal(message.encode()))
            output.flush()
        }
    }

    /** Registers interest in the `ack` for [id]. Call before sending the message. */
    fun expectAck(id: String): CompletableDeferred<Message> =
        CompletableDeferred<Message>().also { pending[id] = it }

    /** Sends a message with an `id` and waits for its `ack`. Returns null on timeout. */
    suspend fun request(message: Message): Message? = request(message, 10_000)

    override suspend fun request(message: Message, timeoutMs: Long): Message? {
        val id = requireNotNull(message.string("id"))
        val ack = expectAck(id)
        return try {
            send(message)
            withTimeoutOrNull(timeoutMs) { ack.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Blocks until the next message arrives. Throws when the connection drops. */
    fun read(): Message = Message.decode(channel.open(Framing.read(input)))

    fun completeAck(ack: Message) {
        ack.string("id")?.let { pending.remove(it)?.complete(ack) }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        /** The session protocol (`v` in `hello`): 3 is the Noise handshake. */
        const val PROTOCOL = 3
        /** No traffic for this long means the connection is dead. Heartbeats run every 15 s. */
        const val READ_TIMEOUT_MS = 45_000

        fun open(host: String, port: Int): Socket = Socket().apply {
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tcpNoDelay = true
            keepAlive = true
            soTimeout = 10_000
        }

        /**
         * Runs the session handshake from docs/PROTOCOL.md (v3, Noise IK) on an open socket, with
         * the paired Mac's key [macKey]. Only that Mac can answer it, and this phone's own key and
         * name go across encrypted.
         */
        fun handshake(
            socket: Socket,
            identity: Identity,
            phoneName: String,
            macKey: ByteArray,
        ): Pair<Session, ByteArray> {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            val noise = Noise.Handshake(Noise.Role.Initiator, Crypto.TCP_PROLOGUE, identity.privateKey, macKey)
            val about = JSONObject().put("name", phoneName).put("app", app.wallhop.BuildConfig.VERSION_NAME)
            writeJson(output, JSONObject()
                .put("t", "hello")
                .put("v", PROTOCOL)
                .put("noise", noise.writeMessage(about.toString().toByteArray(Charsets.UTF_8)).b64()))

            val reply = readJson(input)
            when (reply.optString("t")) {
                "hello_ok" -> Unit
                // A Lync Mac that couldn't read the hello: not the key this phone paired with (Lync
                // reinstalled or reset there, or simply another Mac). Unauthenticated: a hint only.
                "not_me" -> throw WrongMacException()
                else -> throw HandshakeException("Unexpected reply: ${reply.optString("t")}")
            }
            val answer = JSONObject(String(noise.readMessage(reply.getString("noise").unb64()), Charsets.UTF_8))
            // Authenticated: only the paired Mac could have written this.
            if (!answer.optBoolean("paired", false)) throw UnpairedException(macKey)
            val channel = SecureChannel(noise.split())

            val host = socket.inetAddress.hostAddress ?: ""
            val session = Session(socket, input, output, channel, answer.optString("name", "Mac").take(60), host, socket.port)
            Framing.write(output, channel.seal(Message.of("ready").encode()))
            output.flush()
            val ready = session.read()
            if (ready.type != "ready") throw HandshakeException("Handshake failed")
            socket.soTimeout = READ_TIMEOUT_MS
            return session to macKey
        }

        fun writeJson(output: OutputStream, json: JSONObject) {
            Framing.write(output, json.toString().toByteArray(Charsets.UTF_8))
            output.flush()
        }

        fun readJson(input: DataInputStream): JSONObject =
            JSONObject(String(Framing.read(input), Charsets.UTF_8))

        fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
        fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
    }
}
