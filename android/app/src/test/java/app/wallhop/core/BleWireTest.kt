package app.wallhop.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The Bluetooth fallback's framing and encryption, phone side against a Mac side built from the
 * same formulas (docs/PROTOCOL.md, Bluetooth fallback). The fixed keys give the test vector the
 * protocol document lists; the Mac's code must produce the same values.
 */
class BleWireTest {
    private val phonePriv = ByteArray(32) { 0x11 }
    private val phoneEphPriv = ByteArray(32) { 0x22 }
    private val macPriv = ByteArray(32) { 0x33 }
    private val macEphPriv = ByteArray(32) { 0x44 }
    private val phonePub = Crypto.publicKey(phonePriv)
    private val phoneEph = Crypto.publicKey(phoneEphPriv)
    private val macPub = Crypto.publicKey(macPriv)
    private val macEph = Crypto.publicKey(macEphPriv)

    /** The Mac's side of the key agreement: DH(eMac, ePhone) ‖ DH(eMac, sPhone) ‖ DH(sMac, ePhone). */
    private fun macIkm() = Crypto.dh(macEphPriv, phoneEph) + Crypto.dh(macEphPriv, phonePub) + Crypto.dh(macPriv, phoneEph)

    /** A message as it goes inside a sealed frame: 2-byte header length, JSON header, body. */
    private fun plaintext(json: String, body: ByteArray = ByteArray(0)): ByteArray {
        val header = json.toByteArray()
        return ByteBuffer.allocate(2 + header.size + body.size).putShort(header.size.toShort()).put(header).put(body).array()
    }

    @Test fun bothSidesDeriveTheSameKeys() {
        val phone = BleWire.sessionKeys(BleWire.phoneIkm(phonePriv, phoneEphPriv, macPub, macEph), phonePub, phoneEph, macPub, macEph)
        val mac = BleWire.sessionKeys(macIkm(), phonePub, phoneEph, macPub, macEph)
        assertArrayEquals(phone.first, mac.first)
        assertArrayEquals(phone.second, mac.second)
        assertNotEquals(phone.first.toHex(), phone.second.toHex())
    }

    @Test fun differentFromTheWifiSessionKeys() {
        val ikm = BleWire.phoneIkm(phonePriv, phoneEphPriv, macPub, macEph)
        val ble = BleWire.sessionKeys(ikm, phonePub, phoneEph, macPub, macEph)
        val wifi = Crypto.sessionKeys(ikm, phonePub + phoneEph + macPub + macEph)
        assertNotEquals(ble.first.toHex(), wifi.first.toHex())
    }

    @Test fun codeRoundTripThroughFragmentsAndEncryption() {
        val (p2m, m2p) = BleWire.sessionKeys(BleWire.phoneIkm(phonePriv, phoneEphPriv, macPub, macEph), phonePub, phoneEph, macPub, macEph)
        val phone = SecureChannel(p2m, m2p)
        val mac = SecureChannel(m2p, p2m)
        val reassembler = BleWire.Reassembler()

        val messages = listOf(
            plaintext("""{"t":"otp","id":"1","code":"482913","sender":"HDFC","app":"SMS"}"""),
            plaintext("""{"t":"notif","key":"k","title":"${"x".repeat(900)}"}"""),
            plaintext("""{"t":"call","id":"c","state":"ringing"}"""),
        )
        for (message in messages) {
            val sealed = phone.seal(message)
            // The code never appears in the clear.
            assertEquals(-1, String(sealed, Charsets.ISO_8859_1).indexOf("482913"))
            val pieces = BleWire.fragment(sealed, 20) // The smallest notification (MTU 23).
            pieces.forEach { assert(it.size <= 20) }
            val frames = pieces.mapNotNull { reassembler.add(it) }
            assertEquals(1, frames.size)
            assertArrayEquals(message, mac.open(frames.single()))
        }
        // And back: the Mac's ack.
        val ack = plaintext("""{"t":"ack","id":"1","ok":true}""")
        assertArrayEquals(ack, phone.open(mac.seal(ack)))
    }

    @Test fun tamperedOrReplayedFramesAreRejected() {
        val (p2m, m2p) = BleWire.sessionKeys(macIkm(), phonePub, phoneEph, macPub, macEph)
        val phone = SecureChannel(p2m, m2p)
        val sealed = phone.seal(plaintext("""{"t":"otp","code":"111111"}"""))
        val flipped = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { SecureChannel(m2p, p2m).open(flipped) }
        val mac = SecureChannel(m2p, p2m)
        mac.open(sealed)
        assertThrows(Exception::class.java) { mac.open(sealed) } // Same frame again: the counter moved on.
        // A Mac with another key can't read it.
        val stranger = BleWire.sessionKeys(Crypto.dh(ByteArray(32) { 0x55 }, phoneEph) + macIkm().copyOfRange(32, 96), phonePub, phoneEph, macPub, macEph)
        assertThrows(Exception::class.java) { SecureChannel(stranger.second, stranger.first).open(sealed) }
    }

    @Test fun fragmentEdges() {
        assertEquals(listOf(1.toByte()), BleWire.fragment(ByteArray(0), 20).single().toList())
        val exact = ByteArray(19) { it.toByte() }
        assertEquals(1, BleWire.fragment(exact, 20).size)
        assertEquals(2, BleWire.fragment(ByteArray(20), 20).size)
        val reassembler = BleWire.Reassembler(max = 30)
        assertNull(reassembler.add(byteArrayOf(0) + ByteArray(19)))
        assertThrows(IOException::class.java) { reassembler.add(byteArrayOf(1) + ByteArray(19)) }
        assertThrows(IOException::class.java) { BleWire.Reassembler().add(byteArrayOf(7, 1)) }
    }

    @Test fun tagRotatesAndNeedsThePairing() {
        val phoneSide = BleWire.tagKey(Crypto.dh(phonePriv, macPub), phonePub, macPub)
        val macSide = BleWire.tagKey(Crypto.dh(macPriv, phonePub), phonePub, macPub)
        assertArrayEquals(phoneSide, macSide)
        assertEquals(BleWire.TAG_SIZE, BleWire.tag(phoneSide, 1).size)
        assertNotEquals(BleWire.tag(phoneSide, 1).toHex(), BleWire.tag(phoneSide, 2).toHex())
        assertEquals(1_111L, BleWire.window(1_111L * 900_000 + 899_999))
    }

    /** Test vector, also in docs/PROTOCOL.md: the Mac's implementation is checked against it. */
    @Test fun vector() {
        val tagKey = BleWire.tagKey(Crypto.dh(phonePriv, macPub), phonePub, macPub)
        val (p2m, m2p) = BleWire.sessionKeys(BleWire.phoneIkm(phonePriv, phoneEphPriv, macPub, macEph), phonePub, phoneEph, macPub, macEph)
        val sealed = SecureChannel(p2m, m2p).seal(plaintext("""{"t":"ready"}"""))
        assertEquals(VECTOR_TAG, BleWire.tag(tagKey, 1_000_000).toHex())
        assertEquals(VECTOR_P2M, p2m.toHex())
        assertEquals(VECTOR_M2P, m2p.toHex())
        assertEquals(VECTOR_READY, sealed.toHex())
    }

    private companion object {
        const val VECTOR_TAG = "eb87fa08c61a1082"
        const val VECTOR_P2M = "a52d1d7a9201aad4a7c8b3887433e011c283050bc75b044c19069c5920a9234b"
        const val VECTOR_M2P = "326c08e1c1627ef3a8625f9a6bf60acd700065a3e8dd27e0a1693befd092a70f"
        const val VECTOR_READY = "7c7294c7f537c91f8e6d155c5b0bff0bc0bc1c54f8e939f56753d46df64d0d"
    }
}
