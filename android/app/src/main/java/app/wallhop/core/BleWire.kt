package app.wallhop.core

import com.google.crypto.tink.subtle.Hkdf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The Bluetooth LE fallback's keys and framing (docs/PROTOCOL.md, Bluetooth fallback). Plain
 * JVM code, no Android, so the unit tests can run it.
 */
object BleWire {
    /** The phone's GATT service, and its one characteristic (write from the Mac, notify to it). */
    val SERVICE: UUID = UUID.fromString("6c796e63-0001-4b1e-9c3a-5d2f7e8a1b40")
    val CHARACTERISTIC: UUID = UUID.fromString("6c796e63-0002-4b1e-9c3a-5d2f7e8a1b40")
    /** The standard Client Characteristic Configuration descriptor, written to subscribe. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** The advertised tag changes every 15 minutes, so it can't be used to follow the phone around. */
    const val TAG_WINDOW_SECONDS = 900L
    const val TAG_SIZE = 8
    /** Largest message after reassembly; anything bigger closes the link. */
    const val MAX_MESSAGE = 16 * 1024

    private val INFO_TAG = "lync ble v1 tag".toByteArray()
    private val INFO_PHONE_TO_MAC = "lync ble v1 phone->mac".toByteArray()
    private val INFO_MAC_TO_PHONE = "lync ble v1 mac->phone".toByteArray()

    /** The key both devices can work out from the pairing alone: HKDF of DH(phone static, Mac static). */
    fun tagKey(staticShared: ByteArray, phonePub: ByteArray, macPub: ByteArray): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", staticShared, Crypto.sha256(phonePub, macPub), INFO_TAG, 32)

    /** What the phone advertises for [window] (unix seconds / [TAG_WINDOW_SECONDS]); only its paired Mac recognises it. */
    fun tag(tagKey: ByteArray, window: Long): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(tagKey, "HmacSHA256"))
        mac.update("lync-ble-tag".toByteArray())
        mac.update(ByteBuffer.allocate(8).putLong(window).array())
        return mac.doFinal().copyOf(TAG_SIZE)
    }

    fun window(unixMillis: Long): Long = unixMillis / 1000 / TAG_WINDOW_SECONDS

    /**
     * Keys for one Bluetooth connection, as (phone→Mac, Mac→phone). Same construction as the
     * Wi-Fi session (both statics and both ephemerals), with labels of its own.
     */
    fun sessionKeys(ikm: ByteArray, phonePub: ByteArray, phoneEph: ByteArray, macPub: ByteArray, macEph: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = Crypto.sha256(phonePub, phoneEph, macPub, macEph)
        return Hkdf.computeHkdf("HMACSHA256", ikm, salt, INFO_PHONE_TO_MAC, 32) to
            Hkdf.computeHkdf("HMACSHA256", ikm, salt, INFO_MAC_TO_PHONE, 32)
    }

    /** The phone's side of the key agreement: DH(ePhone, eMac) ‖ DH(sPhone, eMac) ‖ DH(ePhone, sMac). */
    fun phoneIkm(phonePrivate: ByteArray, phoneEphPrivate: ByteArray, macPub: ByteArray, macEph: ByteArray): ByteArray =
        Crypto.dh(phoneEphPrivate, macEph) + Crypto.dh(phonePrivate, macEph) + Crypto.dh(phoneEphPrivate, macPub)

    /**
     * Splits a frame into GATT writes or notifications of at most [maxChunk] bytes. Each starts
     * with one header byte: 1 on the last piece of a frame, 0 when more follow.
     */
    fun fragment(frame: ByteArray, maxChunk: Int): List<ByteArray> {
        require(maxChunk >= 2) { "Chunk too small" }
        val room = maxChunk - 1
        if (frame.isEmpty()) return listOf(byteArrayOf(FINAL))
        return (frame.indices step room).map { start ->
            val end = minOf(start + room, frame.size)
            byteArrayOf(if (end == frame.size) FINAL else MORE) + frame.copyOfRange(start, end)
        }
    }

    /** Puts fragments back together. Not thread-safe: one per direction of one connection. */
    class Reassembler(private val max: Int = MAX_MESSAGE) {
        private val buffer = ByteArrayOutputStream()

        /** Adds one piece; returns the whole frame once its last piece is in, else null. */
        fun add(chunk: ByteArray): ByteArray? {
            if (chunk.isEmpty()) throw IOException("Empty fragment")
            val flags = chunk[0]
            if (flags != FINAL && flags != MORE) throw IOException("Bad fragment")
            if (buffer.size() + chunk.size - 1 > max) throw IOException("Message too large")
            buffer.write(chunk, 1, chunk.size - 1)
            if (flags == MORE) return null
            return buffer.toByteArray().also { buffer.reset() }
        }
    }

    private const val FINAL: Byte = 1
    private const val MORE: Byte = 0
}
