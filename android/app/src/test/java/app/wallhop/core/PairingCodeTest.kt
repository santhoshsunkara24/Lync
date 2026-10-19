package app.wallhop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Pairing v2 test vector; the Mac's tests and docs/PROTOCOL.md use the same values. */
class PairingCodeTest {
    private val phonePub = ByteArray(32) { 0x01 }
    private val macPub = ByteArray(32) { 0x02 }
    private val phoneNonce = ByteArray(16) { 0x03 }
    private val macNonce = ByteArray(16) { 0x04 }

    @Test fun commit() = assertEquals(
        "6d60c260fc54cb7820ca62a0395b94dd3b4aaef9a65e4df4cd282ae0fd2a20e6",
        Crypto.pairingCommit(phonePub, phoneNonce).toHex(),
    )

    @Test fun code() = assertEquals("817595", Crypto.pairingCode(phonePub, macPub, phoneNonce, macNonce))

    @Test fun codeDependsOnBothNonces() {
        val code = Crypto.pairingCode(phonePub, macPub, phoneNonce, macNonce)
        assertNotEquals(code, Crypto.pairingCode(phonePub, macPub, macNonce, phoneNonce))
        assertEquals(6, code.length)
    }

    @Test fun nonceSize() = assertEquals(16, Crypto.pairNonce().size)
}
