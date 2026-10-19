package app.wallhop.core

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Key agreement, key derivation and the pairing code. See docs/PROTOCOL.md. */
object Crypto {
    private val INFO_PHONE_TO_MAC = "wallhop v1 phone->mac".toByteArray()
    private val INFO_MAC_TO_PHONE = "wallhop v1 mac->phone".toByteArray()

    fun generatePrivateKey(): ByteArray = X25519.generatePrivateKey()

    fun publicKey(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)

    fun dh(privateKey: ByteArray, peerPublic: ByteArray): ByteArray =
        X25519.computeSharedSecret(privateKey, peerPublic)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun pairingCode(phonePub: ByteArray, macPub: ByteArray): String {
        val hash = sha256("wallhop-pair-v1".toByteArray(), phonePub, macPub)
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

    /** Session keys for the phone: (send = phone→mac, receive = mac→phone). */
    fun sessionKeys(ikm: ByteArray, transcript: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = sha256(transcript)
        val phoneToMac = Hkdf.computeHkdf("HMACSHA256", ikm, salt, INFO_PHONE_TO_MAC, 32)
        val macToPhone = Hkdf.computeHkdf("HMACSHA256", ikm, salt, INFO_MAC_TO_PHONE, 32)
        return phoneToMac to macToPhone
    }
}

/** Encrypts and decrypts frames for one connection, with counter nonces per direction. */
class SecureChannel(sendKey: ByteArray, receiveKey: ByteArray) {
    private val sender = InsecureNonceChaCha20Poly1305(sendKey)
    private val receiver = InsecureNonceChaCha20Poly1305(receiveKey)
    private var sendCounter = 0L
    private var receiveCounter = 0L

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray = sender.encrypt(nonce(sendCounter++), plaintext, EMPTY)

    fun open(sealed: ByteArray): ByteArray = receiver.decrypt(nonce(receiveCounter++), sealed, EMPTY)

    private fun nonce(counter: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
