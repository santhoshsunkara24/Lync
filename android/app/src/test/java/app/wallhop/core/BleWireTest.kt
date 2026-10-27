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
 * The Bluetooth fallback's framing, tag and encryption (Noise, as the Mac runs it), phone side
 * against a Mac side (docs/PROTOCOL.md, Bluetooth fallback). The handshake bytes themselves are
 * checked against the Mac's in NoiseTest.
 */
class BleWireTest {
    private val phonePriv = ByteArray(32) { 0x11 }
    private val phoneEphPriv = ByteArray(32) { 0x22 }
    private val macPriv = ByteArray(32) { 0x33 }
    private val macEphPriv = ByteArray(32) { 0x44 }
    private val phonePub = Crypto.publicKey(phonePriv)
    private val macPub = Crypto.publicKey(macPriv)

    /** A Bluetooth handshake as both sides run it (the Mac initiates), returning (phone, Mac) channels. */
    private fun channels(): Pair<SecureChannel, SecureChannel> {
        val mac = Noise.Handshake(Noise.Role.Initiator, Crypto.BLE_PROLOGUE, macPriv, phonePub, macEphPriv)
        val phone = Noise.Handshake(Noise.Role.Responder, Crypto.BLE_PROLOGUE, phonePriv, ephemeral = phoneEphPriv)
        phone.readMessage(mac.writeMessage())
        mac.readMessage(phone.writeMessage())
        return SecureChannel(phone.split()) to SecureChannel(mac.split())
    }

    /** A message as it goes inside a sealed frame: 2-byte header length, JSON header, body. */
    private fun plaintext(json: String, body: ByteArray = ByteArray(0)): ByteArray {
        val header = json.toByteArray()
        return ByteBuffer.allocate(2 + header.size + body.size).putShort(header.size.toShort()).put(header).put(body).array()
    }

    @Test fun phoneLearnsTheMacsKeyOnlyEncrypted() {
        val mac = Noise.Handshake(Noise.Role.Initiator, Crypto.BLE_PROLOGUE, macPriv, phonePub, macEphPriv)
        val hello = mac.writeMessage()
        // The Mac's static key never appears in the clear.
        assertEquals(-1, hello.toHex().indexOf(macPub.toHex()))
        val phone = Noise.Handshake(Noise.Role.Responder, Crypto.BLE_PROLOGUE, phonePriv)
        phone.readMessage(hello)
        assertArrayEquals(macPub, phone.remoteStatic)
        // A Wi‑Fi handshake can't be used on Bluetooth: the prologue differs.
        val wifi = Noise.Handshake(Noise.Role.Initiator, Crypto.TCP_PROLOGUE, macPriv, phonePub, macEphPriv)
        assertThrows(IOException::class.java) { Noise.Handshake(Noise.Role.Responder, Crypto.BLE_PROLOGUE, phonePriv).readMessage(wifi.writeMessage()) }
    }

    @Test fun codeRoundTripThroughFragmentsAndEncryption() {
        val (phone, mac) = channels()
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
        val (phone, _) = channels()
        val sealed = phone.seal(plaintext("""{"t":"otp","code":"111111"}"""))
        val flipped = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { channels().second.open(flipped) }
        val mac = channels().second
        mac.open(sealed)
        assertThrows(Exception::class.java) { mac.open(sealed) } // Same frame again: the counter moved on.
        // A Mac with another key can't even start: the phone can't read its hello.
        val stranger = Noise.Handshake(Noise.Role.Initiator, Crypto.BLE_PROLOGUE, ByteArray(32) { 0x55 }, phonePub)
        val hello = stranger.writeMessage()
        val phoneSide = Noise.Handshake(Noise.Role.Responder, Crypto.BLE_PROLOGUE, phonePriv)
        phoneSide.readMessage(hello) // It decrypts (any Mac may knock), but it isn't the paired Mac:
        assertNotEquals(macPub.toHex(), phoneSide.remoteStatic!!.toHex())
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
    @Test fun tagVector() {
        val tagKey = BleWire.tagKey(Crypto.dh(phonePriv, macPub), phonePub, macPub)
        assertEquals("eb87fa08c61a1082", BleWire.tag(tagKey, 1_000_000).toHex())
    }
}
