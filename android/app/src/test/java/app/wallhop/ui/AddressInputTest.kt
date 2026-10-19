package app.wallhop.ui

import app.wallhop.link.LinkManager
import app.wallhop.ui.screens.AddressInput
import app.wallhop.ui.screens.parseMacAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressInputTest {

    private fun valid(input: String, host: String, port: Int = LinkManager.DEFAULT_PORT) =
        assertEquals(input, AddressInput.Valid(host, port), parseMacAddress(input))

    private fun invalid(input: String) =
        assertTrue(input, parseMacAddress(input) is AddressInput.Invalid)

    @Test fun ipv4() {
        valid("192.168.1.20", "192.168.1.20")
        valid(" 192.168.1.20:5000 ", "192.168.1.20", 5000)
    }

    @Test fun hostName() {
        valid("santhoshs-mac.local", "santhoshs-mac.local")
        valid("mac.local:47474", "mac.local", 47474)
    }

    @Test fun ipv6() {
        valid("fe80::1", "fe80::1")
        valid("[fe80::1]", "fe80::1")
        valid("[fe80::1%wlan0]:5000", "fe80::1%wlan0", 5000)
        valid("2001:db8::8a2e:370:7334", "2001:db8::8a2e:370:7334")
    }

    @Test fun rejectsBadInput() {
        invalid("")
        invalid("192.168.1.300")
        invalid("192.168.1")
        invalid("192.168.1.20:0")
        invalid("192.168.1.20:70000")
        invalid("192.168.1.20:port")
        invalid("[fe80::1")
        invalid("[fe80::1]x")
        invalid("my mac")
        invalid("fe80:::1")
    }
}
