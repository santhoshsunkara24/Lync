package app.wallhop.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Noise_IK_25519_ChaChaPoly_SHA256 against the official vector (cacophony), and Lync's own v3
 * vector, which the Mac's implementation is checked against too (mac/scripts/crypto-check.sh):
 * the same bytes from both sides means they interoperate.
 */
class NoiseTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun officialVector() {
        val prologue = hex("4a6f686e2047616c74")
        val iStatic = hex("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1")
        val iEph = hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
        val rStatic = hex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893")
        val rEph = hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")
        val messages = listOf(
            "4c756477696720766f6e204d69736573" to "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944718da798efbcd91528520204f904b9bd6c7413dccdc214d951e15253e39987f18146e8cd0873654207148333479d4d16c289f0294b29960a72f48e0b7bba2e89083169825e59642148d492020664ccf7",
            "4d757272617920526f746862617264" to "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f1448088435361e70b2ed446e6c9ec387d1d6b3b840f194e373979d241b203c4acafccf5",
            "462e20412e20486179656b" to "050e9f3c8fac16b68dbce8f8c4bfbf6617c897f9ada4aa29aa19c8",
            "4361726c204d656e676572" to "344233a6cabb7141d80f3da2fedc311d9646bbb0f505afe403a667",
            "4a65616e2d426170746973746520536179" to "62cdeeb172ad7ade7aa7d9e069da5790f12331bfa00177787a1d0810c67dc3b2b4",
            "457567656e2042f6686d20766f6e2042617765726b" to "029bead1b40992327044d409d9a1f3ad8f36c3c452775d557e18bbeb2e8dfcead32d514024",
        )
        val initiator = Noise.Handshake(Noise.Role.Initiator, prologue, iStatic, Crypto.publicKey(rStatic), iEph)
        val responder = Noise.Handshake(Noise.Role.Responder, prologue, rStatic, ephemeral = rEph)
        val m1 = initiator.writeMessage(hex(messages[0].first))
        assertEquals(messages[0].second, m1.toHex())
        assertArrayEquals(hex(messages[0].first), responder.readMessage(m1))
        assertArrayEquals(Crypto.publicKey(iStatic), responder.remoteStatic)
        val m2 = responder.writeMessage(hex(messages[1].first))
        assertEquals(messages[1].second, m2.toHex())
        assertArrayEquals(hex(messages[1].first), initiator.readMessage(m2))
        assertEquals("0b0f68fb0c27e03ce9b97565995ed4838cc0581b762ef72b062f6a546419fad7", initiator.hash.toHex())
        assertArrayEquals(initiator.hash, responder.hash)
        val (iSend, iReceive) = initiator.split()
        val (rSend, rReceive) = responder.split()
        messages.drop(2).forEachIndexed { n, (payload, cipher) ->
            // Transport messages alternate, the initiator first.
            val (send, receive) = if (n % 2 == 0) iSend to rReceive else rSend to iReceive
            val sent = send.encrypt(hex(payload))
            assertEquals(cipher, sent.toHex())
            assertArrayEquals(hex(payload), receive.decrypt(sent))
        }
    }

    @Test fun tamperedOrMisaddressedMessagesFail() {
        val mac = ByteArray(32) { 0x33 }
        val phone = Noise.Handshake(Noise.Role.Initiator, Crypto.TCP_PROLOGUE, ByteArray(32) { 0x11 }, Crypto.publicKey(mac))
        val m1 = phone.writeMessage("hi".toByteArray())
        val bad = m1.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { Noise.Handshake(Noise.Role.Responder, Crypto.TCP_PROLOGUE, mac).readMessage(bad) }
        // Another Mac (reinstalled, new key) can't read it at all.
        assertThrows(IOException::class.java) {
            Noise.Handshake(Noise.Role.Responder, Crypto.TCP_PROLOGUE, ByteArray(32) { 0x55 }).readMessage(m1)
        }
    }

    /** docs/vectors/lync-v3.json, written by the Mac's implementation. */
    @Test fun lyncVectorMatchesTheMac() {
        val json = File("../../docs/vectors/lync-v3.json").readText()
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"([0-9a-f]+)\"").find(json)!!.groupValues[1]
        val phone = ByteArray(32) { 0x11 }; val phoneEph = ByteArray(32) { 0x22 }
        val mac = ByteArray(32) { 0x33 }; val macEph = ByteArray(32) { 0x44 }
        val ready = byteArrayOf(0, 13) + """{"t":"ready"}""".toByteArray()

        val p = Noise.Handshake(Noise.Role.Initiator, Crypto.TCP_PROLOGUE, phone, Crypto.publicKey(mac), phoneEph)
        val m = Noise.Handshake(Noise.Role.Responder, Crypto.TCP_PROLOGUE, mac, ephemeral = macEph)
        val t1 = p.writeMessage("""{"name":"Pixel","app":"1.0.0"}""".toByteArray())
        assertEquals(field("tcp_msg1"), t1.toHex())
        m.readMessage(t1)
        val t2 = m.writeMessage("""{"name":"MacBook","app":"1.0.0","paired":true}""".toByteArray())
        assertEquals(field("tcp_msg2"), t2.toHex())
        p.readMessage(t2)
        assertEquals(field("tcp_hash"), p.hash.toHex())
        assertEquals(field("tcp_ready_phone"), SecureChannel(p.split()).seal(ready).toHex())

        val mb = Noise.Handshake(Noise.Role.Initiator, Crypto.BLE_PROLOGUE, mac, Crypto.publicKey(phone), macEph)
        val pb = Noise.Handshake(Noise.Role.Responder, Crypto.BLE_PROLOGUE, phone, ephemeral = phoneEph)
        val b1 = mb.writeMessage()
        assertEquals(field("ble_msg1"), b1.toHex())
        pb.readMessage(b1)
        val b2 = pb.writeMessage()
        assertEquals(field("ble_msg2"), b2.toHex())
        mb.readMessage(b2)
        assertEquals(field("ble_hash"), mb.hash.toHex())
        assertEquals(field("ble_ready_mac"), SecureChannel(mb.split()).seal(ready).toHex())
    }
}
