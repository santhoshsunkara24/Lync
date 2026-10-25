package app.wallhop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemsTest {
    @Test fun classifiesConnectFailures() {
        assertEquals(Failure.Refused, Problems.classify(java.net.ConnectException("failed to connect: ECONNREFUSED (Connection refused)")))
        assertEquals(Failure.NoRoute, Problems.classify(java.net.ConnectException("failed to connect: ENETUNREACH (Network is unreachable)")))
        assertEquals(Failure.NoRoute, Problems.classify(java.net.NoRouteToHostException("EHOSTUNREACH")))
        assertEquals(Failure.Timeout, Problems.classify(java.net.SocketTimeoutException("connect timed out")))
        assertEquals(Failure.UnknownHost, Problems.classify(java.net.UnknownHostException("mac.local")))
        assertEquals(Failure.Dropped, Problems.classify(java.io.EOFException()))
        assertEquals(Failure.Unpaired, Problems.classify(app.wallhop.link.UnpairedException(null)))
        assertEquals(Failure.WrongMac, Problems.classify(app.wallhop.link.WrongMacException()))
        assertEquals(Failure.Protocol, Problems.classify(app.wallhop.link.HandshakeException("Unexpected reply: x")))
        assertEquals(Failure.Protocol, Problems.classify(BadFrameException("Frame too large: 9")))
        assertEquals(Failure.KeyLocked, Problems.classify(KeyLockedException(null)))
        assertEquals(Failure.Other, Problems.classify(IllegalArgumentException()))
    }

    @Test fun awayReasons() {
        fun away(vararg f: Failure, wifiOn: Boolean = true, onWifi: Boolean = true, seen: Boolean = false, works: Boolean = true) =
            Problems.away(f.toList(), wifiOn, onWifi, seen, works)
        assertEquals(Away.NoWifi, away(Failure.Timeout, wifiOn = false, onWifi = false))
        assertEquals(Away.NotOnWifi, away(onWifi = false))
        assertEquals(Away.LyncNotRunning, away(Failure.Refused))
        assertEquals(Away.MacNotOnNetwork, away(Failure.Timeout))
        assertEquals(Away.MacNotOnNetwork, away())
        assertEquals(Away.MacNotAnswering, away(Failure.Timeout, seen = true))
        // Bonjour broken: not seeing the Mac proves nothing.
        assertEquals(Away.MacNotAnswering, away(Failure.Timeout, works = false))
        assertEquals(Away.MacReset, away(Failure.WrongMac, Failure.Refused))
        assertEquals(Away.KeyLocked, away(Failure.KeyLocked, onWifi = false))
        assertEquals(Away.VersionMismatch, away(Failure.Protocol))
    }

    @Test fun awayWording() {
        val wifi = Problems.away(Away.NoWifi)
        assertEquals("Wi‑Fi is off", wifi.title)
        assertEquals("Turn on Wi‑Fi to reach your Mac.", wifi.action)
        assertEquals(Fix.WifiSettings, wifi.fix)
        assertEquals("Wi‑Fi is off. Turn on Wi‑Fi to reach your Mac.", wifi.sentence)
        assertEquals(listOf(Fix.WifiSettings, Fix.TryAgain), Problems.away(Away.MacNotOnNetwork).fixes)
        assertEquals(Fix.PairAgain, Problems.away(Away.MacReset).fix)
        // Every reason has a short why and a one-line what to do.
        Away.entries.forEach { reason ->
            val p = Problems.away(reason)
            assertTrue(reason.name, p.title.split(' ').size <= 6)
            assertTrue(reason.name, p.action.endsWith("."))
        }
    }

    @Test fun pairNoCodes() {
        assertEquals("Mac is pairing another phone", Problems.pairNo("busy", qr = false).title)
        assertEquals("Too many tries", Problems.pairNo("rate", qr = false).title)
        assertEquals(Fix.Update, Problems.pairNo("version", qr = false).fix)
        assertEquals(Problems.CODE_EXPIRED, Problems.pairNo("expired", qr = true))
        assertEquals(Problems.DECLINED, Problems.pairNo("declined", qr = true))
        assertEquals(Problems.NOT_QR_MAC, Problems.pairNo("mismatch", qr = true))
        // Old Macs send no code; unknown codes fall back the same way.
        assertEquals(Problems.CODE_EXPIRED, Problems.pairNo(null, qr = true))
        assertEquals(Problems.DECLINED, Problems.pairNo(null, qr = false))
        assertEquals(Problems.DECLINED, Problems.pairNo("something_new", qr = false))
    }

    @Test fun pairingFailures() {
        fun pairing(f: Failure, step: PairStep = PairStep.Connecting, qr: Boolean = false, typed: Boolean = false,
                    onWifi: Boolean = true, seen: Boolean = true, ms: Long = 0) =
            Problems.pairing(f, step, qr, typed, onWifi, seen, ms)
        assertEquals(Problems.NOT_SAME_WIFI, pairing(Failure.Timeout, qr = true, seen = false))
        assertEquals(Problems.NOT_SAME_WIFI, pairing(Failure.NoRoute, onWifi = false))
        assertEquals("Mac isn't answering", pairing(Failure.Timeout).title)
        assertEquals("Mac name not found", pairing(Failure.UnknownHost, typed = true).title)
        assertEquals(Problems.NOT_LYNC, pairing(Failure.Protocol, typed = true))
        assertEquals("Open Lync on your Mac and try again.", pairing(Failure.Refused).action)
        assertEquals("Open Lync on your Mac, or check the address and port.", pairing(Failure.Refused, typed = true).action)
        assertEquals(Problems.CODE_TIMED_OUT, pairing(Failure.Dropped, PairStep.Confirming, ms = 118_000))
        assertEquals(Problems.CODE_TIMED_OUT, pairing(Failure.Timeout, PairStep.Confirming, ms = 5_000))
        assertEquals(Problems.ENDED, pairing(Failure.Dropped, PairStep.Confirming, ms = 20_000))
        assertEquals(Fix.TryAgain, Problems.ENDED.fix)
    }

    @Test fun fileAcks() {
        assertEquals("Mac is out of space", Problems.fileAck("storage_full").title)
        assertEquals("File got damaged on the way", Problems.fileAck("checksum").title)
        assertEquals("Didn't arrive on your Mac", Problems.fileAck(null).title)
        assertEquals("Didn't arrive on your Mac", Problems.fileAck("new_code").title)
    }

    @Test fun batchOutcomes() {
        assertNull(Problems.sent(3, emptyList(), emptyList()))
        val partial = Problems.sent(3, listOf("storage_full"), emptyList())!!
        assertEquals("2 of 3 sent", partial.title)
        assertEquals("Mac is out of space. Free up space on your Mac, then try again.", partial.action)
        assertEquals("Mac is out of space", Problems.sent(1, listOf("storage_full"), emptyList())!!.title)
        val one = Problems.sent(1, emptyList(), listOf("IMG_1.jpg"))!!
        assertEquals("Couldn't open IMG_1.jpg", one.title)
        assertEquals(Fix.ChooseAgain, one.fix)
        assertEquals(Problems.CANT_OPEN, Problems.sent(2, emptyList(), listOf("a", "b")))
        assertEquals("1 of 3 sent", Problems.sent(3, emptyList(), listOf("a", "b"))!!.title)
    }

    @Test fun lostConnection() {
        assertEquals(listOf(Fix.TryAgain), Problems.connectionLost(onWifi = true).fixes)
        assertEquals(listOf(Fix.TryAgain, Fix.WifiSettings), Problems.connectionLost(onWifi = false).fixes)
    }

    @Test fun receivedFromMac() {
        assertEquals(Problems.PHONE_FULL_RECEIVE, Problems.received(0, 3, "storage_full"))
        assertEquals("2 of 3 saved", Problems.received(2, 3, "checksum").title)
        assertEquals(Problems.TRANSFER_STOPPED, Problems.received(0, 2, null, stopped = true))
        assertEquals("1 of 4 saved", Problems.received(1, 4, null, stopped = true).title)
    }

    @Test fun writeErrors() {
        assertEquals("storage_full", Problems.writeErr(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("storage_full", Problems.writeErr(RuntimeException(java.io.IOException("No space left on device"))))
        assertEquals("permission", Problems.writeErr(SecurityException()))
        assertEquals("write_failed", Problems.writeErr(java.io.IOException("EIO")))
        assertEquals("write_failed", Problems.writeErr(null))
    }

    @Test fun smsCodes() {
        assertEquals("airplane", Problems.smsErr(2)) // RESULT_ERROR_RADIO_OFF
        assertEquals("no_service", Problems.smsErr(4))
        assertEquals("limit", Problems.smsErr(5)) // LIMIT_EXCEEDED
        assertEquals("limit", Problems.smsErr(6)) // FDN_CHECK_FAILURE
        assertEquals("limit", Problems.smsErr(7)) // SHORT_CODE_NOT_ALLOWED
        assertEquals("limit", Problems.smsErr(8))
        assertEquals("bad_number", Problems.smsErr(11))
        assertEquals("failed", Problems.smsErr(1))
        assertFalse(Problems.smsErr(999).isEmpty())
    }
}
