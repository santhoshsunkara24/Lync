package app.wallhop.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WordingTest {
    @Test fun counts() {
        assertEquals("1 photo", Wording.count(listOf("image/jpeg")))
        assertEquals("3 photos", Wording.count(listOf("image/jpeg", "image/png", "image/heic")))
        assertEquals("2 videos", Wording.count(listOf("video/mp4", "video/quicktime")))
        assertEquals("2 files", Wording.count(listOf("image/jpeg", "application/pdf")))
        assertEquals("1 file", Wording.count(listOf("application/pdf")))
        assertEquals("3 photos from MacBook Air", Wording.received(listOf("image/jpeg", "image/png", "image/png"), "MacBook Air"))
    }

    @Test fun countdown() {
        assertEquals("2:00", Wording.countdown(120_000))
        assertEquals("1:05", Wording.countdown(64_200))
        assertEquals("0:01", Wording.countdown(1))
        assertEquals("0:00", Wording.countdown(-5))
    }

    @Test fun codeLabels() {
        assertEquals("Copy code 482 913", Wording.copyCodeLabel("482913"))
        assertEquals("Copy code G-12345", Wording.copyCodeLabel("G-12345"))
        assertEquals("Pairing code 4 8 2, 9 1 3", Wording.pairingCodeDescription("482913"))
    }

    @Test fun miniMac() {
        assertEquals("MacBook Air, connected, 82% battery", Wording.miniMac("MacBook Air", true, false, 82, false))
        assertEquals("Mac, not connected", Wording.miniMac("Mac", false, false, null, false))
        assertEquals("MacBook Pro, connected, locked, 40% battery, charging", Wording.miniMac("MacBook Pro", true, true, 40, true))
    }

    @Test fun permissionWording() {
        assertEquals("Codes stay off until you allow SMS", Wording.deniedToast(Features.Key.Codes, "android.permission.RECEIVE_SMS"))
        assertEquals("Calls stay off until you allow Call logs", Wording.deniedToast(Features.Key.Calls, "android.permission.READ_CALL_LOG"))
        assertEquals("Texts stay off until you allow SMS", Wording.deniedToast(Features.Key.Texts, "android.permission.SEND_SMS"))
        assertEquals("Alerts stay off until you allow notifications", Wording.deniedToast(Features.Key.Notifications, "android.permission.POST_NOTIFICATIONS"))
        assertEquals("Phone", Wording.permissionName("android.permission.ANSWER_PHONE_CALLS"))
        assertEquals("Contacts", Wording.permissionName("android.permission.READ_CONTACTS"))
    }

    @Test fun contactMatching() {
        val contacts = listOf(
            "Mom" to "+91 98765 43210",
            "Momo Café" to "080-1234",
            "Anand" to "+91 99999 11111",
            "mom" to "+919876543210",
        )
        assertEquals(listOf("Mom" to "+91 98765 43210", "Momo Café" to "080-1234"), Wording.matchContacts(contacts, "MOM"))
        assertEquals(listOf("Mom" to "+91 98765 43210"), Wording.matchContacts(contacts, "98765"))
        assertEquals(listOf("Anand" to "+91 99999 11111"), Wording.matchContacts(contacts, "nan"))
        assertEquals(emptyList<Pair<String, String>>(), Wording.matchContacts(contacts, "  "))
        val many = (1..50).map { "Person $it" to "$it" }
        assertEquals(20, Wording.matchContacts(many, "person").size)
    }

    @Test fun olderThanAWeek() {
        val now = 100L * 24 * 3_600_000
        val day = 24 * 3_600_000L
        val times = listOf(now - day, now - 7 * day + 1, now - 8 * day, now - 30 * day)
        assertEquals(listOf(now - 8 * day, now - 30 * day), Wording.olderThan(times, now, 7) { it })
    }
}
