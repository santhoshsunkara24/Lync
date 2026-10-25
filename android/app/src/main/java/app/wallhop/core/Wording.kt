package app.wallhop.core

/**
 * User-facing wording that more than one screen needs, kept in one place and unit tested.
 * Words agreed with the Mac app: "Ready to paste on your Mac", "Codes", "Texts", "Activity".
 */
object Wording {
    const val READY = "Ready to paste on your Mac"
    const val READY_SHORT = "Ready to paste"
    const val RELEASES_URL = "https://github.com/santhoshsunkara24/Lync/releases"

    /** "photo"/"photos", "video"/"videos" or "file"/"files" for these MIME types. */
    fun noun(mimes: List<String>): String {
        val one = mimes.size == 1
        return when {
            mimes.isNotEmpty() && mimes.all { it.startsWith("image/") } -> if (one) "photo" else "photos"
            mimes.isNotEmpty() && mimes.all { it.startsWith("video/") } -> if (one) "video" else "videos"
            else -> if (one) "file" else "files"
        }
    }

    /** "3 photos". */
    fun count(mimes: List<String>): String = "${mimes.size} ${noun(mimes)}"

    /** "3 photos from MacBook Air". */
    fun received(mimes: List<String>, macName: String): String = "${count(mimes)} from $macName"

    /** "1:05" for the time left; never negative. */
    fun countdown(ms: Long): String {
        val seconds = ((ms.coerceAtLeast(0) + 999) / 1000)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** "482913" → "482 913". */
    fun spaced(code: String): String =
        if (code.length == 6 && code.all(Char::isDigit)) "${code.take(3)} ${code.takeLast(3)}" else code

    /** The Latest card's copy button, for TalkBack: "Copy code 482 913". */
    fun copyCodeLabel(code: String): String = "Copy code ${spaced(code)}"

    /** The pairing code read digit by digit: "Pairing code 4 8 2, 9 1 3". */
    fun pairingCodeDescription(code: String): String {
        val digits = code.filter(Char::isDigit)
        val groups = if (digits.length == 6) listOf(digits.take(3), digits.takeLast(3)) else listOf(digits)
        return "Pairing code " + groups.joinToString(", ") { it.toList().joinToString(" ") }
    }

    /** The little Mac on Home in one sentence: "MacBook Air, connected, 82% battery". */
    fun miniMac(kind: String, connected: Boolean, locked: Boolean, battery: Int?, charging: Boolean): String = buildList {
        add(kind)
        add(if (!connected) "not connected" else if (locked) "connected, locked" else "connected")
        if (battery != null) add("$battery% battery" + if (charging) ", charging" else "")
    }.joinToString(", ")

    /** Android's name for the permission group, as the user sees it in Settings › Permissions. */
    fun permissionName(permission: String): String = when (permission.substringAfterLast('.')) {
        "RECEIVE_SMS", "READ_SMS", "SEND_SMS" -> "SMS"
        "READ_CALL_LOG" -> "Call logs"
        "READ_CONTACTS" -> "Contacts"
        "POST_NOTIFICATIONS" -> "Notifications"
        "READ_PHONE_STATE", "ANSWER_PHONE_CALLS" -> "Phone"
        "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT" -> "Nearby devices"
        else -> "the permission"
    }

    /** The feature's name in sentences. */
    fun feature(key: Features.Key): String = when (key) {
        Features.Key.Codes -> "Codes"
        Features.Key.Calls -> "Calls"
        Features.Key.Texts -> "Texts"
        Features.Key.Notifications -> "Alerts on this phone"
        Features.Key.AppNotifications -> "Phone notifications on Mac"
        Features.Key.Background -> "Run in background"
        Features.Key.Bluetooth -> "Bluetooth backup"
        else -> "This"
    }

    /** First "no": "Codes stay off until you allow SMS". */
    fun deniedToast(key: Features.Key, permission: String): String = when (key) {
        Features.Key.Notifications -> "Alerts stay off until you allow notifications"
        Features.Key.Bluetooth -> "Bluetooth backup stays off until you allow Nearby devices"
        else -> "${feature(key)} stay off until you allow ${permissionName(permission)}"
    }

    /** Contacts whose name or number contains [query] (case-insensitive), up to [limit], no repeats. */
    fun matchContacts(contacts: List<Pair<String, String>>, query: String, limit: Int = 20): List<Pair<String, String>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val digits = q.filter(Char::isDigit)
        return contacts.asSequence()
            .filter { (name, number) ->
                name.contains(q, ignoreCase = true) || number.contains(q, ignoreCase = true) ||
                    (digits.length >= 3 && digits.length == q.count { it.isDigit() || it in " +-()" } && number.filter(Char::isDigit).contains(digits))
            }
            .distinctBy { (name, number) -> name.lowercase() to number.filter(Char::isDigit) }
            .take(limit)
            .toList()
    }

    /** Ids of entries older than [days] days before [now]. */
    fun <T> olderThan(entries: List<T>, now: Long, days: Int, time: (T) -> Long): List<T> =
        entries.filter { now - time(it) > days * 24L * 3_600_000L }
}
