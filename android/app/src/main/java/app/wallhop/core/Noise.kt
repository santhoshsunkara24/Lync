package app.wallhop.core

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import com.google.crypto.tink.subtle.X25519
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Noise_IK_25519_ChaChaPoly_SHA256 (noiseprotocol.org, revision 34): the session handshake on
 * Wi‑Fi and Bluetooth (PROTOCOL.md, v3). The initiator already knows the responder's static key
 * (both sides learn each other's at pairing) and sends its own encrypted, so neither key ever
 * crosses the air in the clear. Checked against the official test vectors (NoiseTest), and
 * against the Mac's output (docs/vectors/lync-v3.json).
 */
object Noise {
    private val PROTOCOL_NAME = "Noise_IK_25519_ChaChaPoly_SHA256".toByteArray(Charsets.US_ASCII)
    const val KEY_SIZE = 32
    const val TAG_SIZE = 16

    class Failure(message: String) : IOException(message)

    /** One direction's key and counter. Nonces are 4 zero bytes and a 64-bit little-endian counter. */
    class Cipher internal constructor(key: ByteArray?) {
        private val aead = key?.let { InsecureNonceChaCha20Poly1305(it) }
        var counter = 0L
            private set

        fun encrypt(plaintext: ByteArray, ad: ByteArray = EMPTY): ByteArray {
            val aead = aead ?: return plaintext
            if (counter == -1L) throw Failure("Nonces used up")
            return aead.encrypt(nonce(counter), plaintext, ad).also { counter++ }
        }

        fun decrypt(ciphertext: ByteArray, ad: ByteArray = EMPTY): ByteArray {
            val aead = aead ?: return ciphertext
            if (ciphertext.size < TAG_SIZE || counter == -1L) throw Failure("Bad message")
            val plaintext = try {
                aead.decrypt(nonce(counter), ciphertext, ad)
            } catch (e: java.security.GeneralSecurityException) {
                throw Failure("Doesn't decrypt")
            }
            counter++
            return plaintext
        }

        private fun nonce(counter: Long): ByteArray =
            ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (counter ushr (8 * i)).toByte() }
    }

    /** HKDF as Noise defines it (two outputs), on HMAC-SHA256. */
    fun hkdf(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val out1 = hmac(temp, byteArrayOf(1))
        val out2 = hmac(temp, out1 + byteArrayOf(2))
        return out1 to out2
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** The chaining key, the handshake hash and the current cipher. */
    private class SymmetricState {
        // The name is exactly 32 bytes, so it is the initial hash as it stands.
        var h: ByteArray = PROTOCOL_NAME.copyOf()
        var ck: ByteArray = h.copyOf()
        var cipher = Cipher(null)

        fun mixHash(data: ByteArray) { h = sha256(h + data) }

        fun mixKey(ikm: ByteArray) {
            val (next, key) = hkdf(ck, ikm)
            ck = next
            cipher = Cipher(key)
        }

        fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encrypt(plaintext, h).also { mixHash(it) }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray = cipher.decrypt(ciphertext, h).also { mixHash(ciphertext) }

        fun split(): Pair<Cipher, Cipher> {
            val (k1, k2) = hkdf(ck, EMPTY)
            return Cipher(k1) to Cipher(k2)
        }
    }

    enum class Role { Initiator, Responder }

    /**
     * One IK handshake: two messages, then two ciphers.
     *
     *     <- s
     *     ...
     *     -> e, es, s, ss
     *     <- e, ee, se
     *
     * [remoteStatic] is required for the initiator. [ephemeral] is for test vectors only.
     */
    class Handshake(
        val role: Role,
        prologue: ByteArray,
        private val staticPrivate: ByteArray,
        remoteStatic: ByteArray? = null,
        private var ephemeral: ByteArray? = null,
    ) {
        private val state = SymmetricState()
        private val staticPublic = X25519.publicFromPrivate(staticPrivate)
        private var remoteEphemeral: ByteArray? = null
        /** The other side's static key: given to the initiator, learned by the responder from message 1. */
        var remoteStatic: ByteArray? = remoteStatic
            private set
        private var step = 0

        init {
            state.mixHash(prologue)
            when (role) {
                Role.Initiator -> state.mixHash(requireNotNull(remoteStatic) { "The initiator needs the responder's key" })
                Role.Responder -> state.mixHash(staticPublic)
            }
        }

        /** The handshake hash once both messages are done (the same on both sides). */
        val hash: ByteArray get() = state.h.copyOf()
        val isComplete: Boolean get() = step == 2

        private fun dh(private: ByteArray, public: ByteArray?): ByteArray =
            try {
                X25519.computeSharedSecret(private, public ?: throw Failure("Out of order"))
            } catch (e: java.security.InvalidKeyException) {
                throw Failure("Bad key")
            }

        /** Message 1 for the initiator, message 2 for the responder. */
        fun writeMessage(payload: ByteArray = EMPTY): ByteArray {
            val e = ephemeral ?: X25519.generatePrivateKey()
            ephemeral = e
            val ePub = X25519.publicFromPrivate(e)
            state.mixHash(ePub)
            var out = ePub
            when {
                role == Role.Initiator && step == 0 -> {
                    state.mixKey(dh(e, remoteStatic))                      // es
                    out += state.encryptAndHash(staticPublic)              // s
                    state.mixKey(dh(staticPrivate, remoteStatic))          // ss
                }
                role == Role.Responder && step == 1 -> {
                    state.mixKey(dh(e, remoteEphemeral))                   // ee
                    state.mixKey(dh(e, remoteStatic))                      // se
                }
                else -> throw Failure("Out of order")
            }
            out += state.encryptAndHash(payload)
            step++
            return out
        }

        /** Message 1 for the responder, message 2 for the initiator. Returns the payload. */
        fun readMessage(message: ByteArray): ByteArray {
            if (message.size < KEY_SIZE) throw Failure("Too short")
            val re = message.copyOfRange(0, KEY_SIZE)
            remoteEphemeral = re
            state.mixHash(re)
            var rest = message.copyOfRange(KEY_SIZE, message.size)
            when {
                role == Role.Responder && step == 0 -> {
                    state.mixKey(dh(staticPrivate, re))                    // es
                    val sealed = KEY_SIZE + TAG_SIZE
                    if (rest.size < sealed) throw Failure("Too short")
                    remoteStatic = state.decryptAndHash(rest.copyOfRange(0, sealed)) // s
                    rest = rest.copyOfRange(sealed, rest.size)
                    state.mixKey(dh(staticPrivate, remoteStatic))          // ss
                }
                role == Role.Initiator && step == 1 -> {
                    val e = ephemeral ?: throw Failure("Out of order")
                    state.mixKey(dh(e, re))                                // ee
                    state.mixKey(dh(staticPrivate, re))                    // se
                }
                else -> throw Failure("Out of order")
            }
            val payload = state.decryptAndHash(rest)
            step++
            return payload
        }

        /** The transport ciphers, once both messages are done: (send, receive) for this side. */
        fun split(): Pair<Cipher, Cipher> {
            if (!isComplete) throw Failure("Handshake not finished")
            val (c1, c2) = state.split()
            return if (role == Role.Initiator) c1 to c2 else c2 to c1
        }
    }

    private val EMPTY = ByteArray(0)
}
