package app.wallhop.core

import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Keys, the pairing code, the QR proof and the Noise prologues. See docs/PROTOCOL.md. */
object Crypto {
    /** Noise prologues (PROTOCOL.md, v3): a handshake made for one path can't be used on the other. */
    val TCP_PROLOGUE = "lync-tcp-v3".toByteArray(Charsets.US_ASCII)
    val BLE_PROLOGUE = "lync-ble-v3".toByteArray(Charsets.US_ASCII)

    fun generatePrivateKey(): ByteArray = X25519.generatePrivateKey()

    fun publicKey(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)

    fun dh(privateKey: ByteArray, peerPublic: ByteArray): ByteArray =
        X25519.computeSharedSecret(privateKey, peerPublic)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    /** Length of each side's pairing nonce (pairing v2). */
    const val PAIR_NONCE_SIZE = 16

    fun pairNonce(): ByteArray = ByteArray(PAIR_NONCE_SIZE).also { java.security.SecureRandom().nextBytes(it) }

    /**
     * Pairing v2 commitment: the phone commits to its nonce before it sees the Mac's, so neither
     * side can grind keys or nonces to make the six digits match (PROTOCOL.md, Pairing).
     */
    fun pairingCommit(phonePub: ByteArray, phoneNonce: ByteArray): ByteArray =
        sha256("lync-pair-commit-v2".toByteArray(Charsets.UTF_8), phonePub, phoneNonce)

    /** The 6-digit code both screens show, from both keys and both nonces. */
    fun pairingCode(phonePub: ByteArray, macPub: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray): String {
        val hash = sha256("lync-pair-v2".toByteArray(Charsets.UTF_8), phonePub, macPub, phoneNonce, macNonce)
        val value = ByteBuffer.wrap(hash, 0, 4).int.toLong() and 0xFFFFFFFFL
        return "%06d".format(value % 1_000_000)
    }

    /**
     * Proof that this phone scanned the Mac's pairing QR: HMAC-SHA256 of both public keys under
     * the QR's one-time secret. The secret itself never crosses the network (PROTOCOL.md).
     */
    fun qrProof(token: ByteArray, phonePub: ByteArray, macPub: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(token, "HmacSHA256"))
        mac.update("wallhop-qr-v1".toByteArray())
        mac.update(phonePub)
        mac.update(macPub)
        return mac.doFinal()
    }

    fun deviceId(publicKey: ByteArray): String = sha256(publicKey).copyOf(8).toHex()
}

/**
 * Encrypts and decrypts frames for one connection: the two ciphers from the Noise handshake,
 * (send, receive), one per direction (PROTOCOL.md, Sealed frames).
 */
class SecureChannel(ciphers: Pair<Noise.Cipher, Noise.Cipher>) {
    private val sender = ciphers.first
    private val receiver = ciphers.second

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray = sender.encrypt(plaintext)

    @Synchronized
    fun open(sealed: ByteArray): ByteArray = receiver.decrypt(sealed)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
