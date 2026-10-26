package app.wallhop.core

/**
 * Every error the user sees, in one place: why (a few words), what to do (one line), and the
 * buttons that do it. Never raw exception text. Unit tested (ProblemsTest).
 */
data class Problem(val title: String, val action: String, val fixes: List<Fix> = emptyList()) {
    /** The main button, if there is one. */
    val fix: Fix? get() = fixes.firstOrNull()
    /** One line, for notifications and Android toasts: "Wi‑Fi is off. Turn on Wi‑Fi to reach your Mac." */
    val sentence: String get() = "$title. $action"
}

/** What a button under an error does. [label] is the button's text. */
enum class Fix(val label: String) {
    WifiSettings("Wi‑Fi settings"),
    TryAgain("Try again"),
    PairAgain("Pair again"),
    /** No Mac paired yet (looking around first): the connect screen. */
    PairMac("Pair your Mac"),
    ChooseAgain("Choose again"),
    StorageSettings("Storage settings"),
    NotificationSettings("Notification settings"),
    BatterySettings("Battery settings"),
    TurnOnBluetooth("Turn on"),
    /** The releases page, for a newer Lync. */
    Update("Get update"),
    OpenLync("Open Lync"),
}

/** Why the paired Mac isn't connected. */
enum class Away {
    /** Wi‑Fi is switched off. */
    NoWifi,
    /** Wi‑Fi is on but not joined to a network. */
    NotOnWifi,
    MacNotOnNetwork,
    /** The Mac is there but nothing is listening: Lync isn't running on it. */
    LyncNotRunning,
    MacNotAnswering,
    /** Lync on the Mac was reinstalled or reset: a new key, or it doesn't know this phone's. */
    MacReset,
    /** The Keystore can't unlock this phone's key right now. */
    KeyLocked,
    VersionMismatch,
}

/** How one attempt to reach the Mac failed, from its exception. */
enum class Failure { Refused, Timeout, NoRoute, UnknownHost, Unpaired, WrongMac, KeyLocked, Protocol, Dropped, Other }

/** Where pairing got to when it failed. */
enum class PairStep {
    Connecting,
    /** The request is sent; waiting for the Mac's first answer. */
    Asking,
    /** The Mac is showing the request (and the code); waiting for Pair. */
    Confirming,
}

/** The Keystore can't unlock this phone's key right now (see Identity). */
class KeyLockedException(cause: Throwable?) : IllegalStateException("This phone's key can't be unlocked", cause)

object Problems {
    // MARK: - Reaching the Mac

    fun classify(e: Throwable?): Failure = when (e) {
        null -> Failure.Other
        is app.wallhop.link.UnpairedException -> Failure.Unpaired
        is app.wallhop.link.WrongMacException -> Failure.WrongMac
        is KeyLockedException -> Failure.KeyLocked
        is app.wallhop.link.HandshakeException, is BadFrameException, is org.json.JSONException -> Failure.Protocol
        is java.net.UnknownHostException -> Failure.UnknownHost
        is java.net.NoRouteToHostException -> Failure.NoRoute
        is java.net.SocketTimeoutException -> Failure.Timeout
        is java.net.ConnectException -> if (unreachable(e.message)) Failure.NoRoute else Failure.Refused
        is java.io.EOFException -> Failure.Dropped
        is java.net.SocketException -> when {
            unreachable(e.message) -> Failure.NoRoute
            e.message.orEmpty().contains("ECONNREFUSED") -> Failure.Refused
            else -> Failure.Dropped
        }
        else -> Failure.Other
    }

    /** ENETUNREACH / EHOSTUNREACH come as plain ConnectExceptions on Android. */
    private fun unreachable(message: String?): Boolean {
        val text = message.orEmpty()
        return text.contains("ENETUNREACH") || text.contains("EHOSTUNREACH") || text.contains("unreachable", ignoreCase = true)
    }

    /**
     * Why the Mac isn't connected, from one round of attempts ([failures] from its own addresses
     * only), whether the phone is on Wi‑Fi ([wifiOn] the radio, [onWifi] joined to a network),
     * whether Bonjour sees the Mac, and whether Bonjour is working at all.
     */
    fun away(failures: Collection<Failure>, wifiOn: Boolean, onWifi: Boolean, macSeen: Boolean, discoveryWorks: Boolean = true): Away = when {
        Failure.KeyLocked in failures -> Away.KeyLocked
        Failure.WrongMac in failures -> Away.MacReset
        Failure.Protocol in failures -> Away.VersionMismatch
        !onWifi -> if (wifiOn) Away.NotOnWifi else Away.NoWifi
        Failure.Refused in failures -> Away.LyncNotRunning
        macSeen || !discoveryWorks -> Away.MacNotAnswering
        else -> Away.MacNotOnNetwork
    }

    fun away(reason: Away): Problem = when (reason) {
        Away.NoWifi -> Problem("Wi‑Fi is off", "Turn on Wi‑Fi to reach your Mac.", listOf(Fix.WifiSettings))
        Away.NotOnWifi -> Problem("Not on Wi‑Fi", "Join the same Wi‑Fi as your Mac.", listOf(Fix.WifiSettings))
        Away.MacNotOnNetwork -> Problem("Mac not on this Wi‑Fi", "Join the same Wi‑Fi as your Mac, or wake it.", listOf(Fix.WifiSettings, Fix.TryAgain))
        Away.LyncNotRunning -> Problem("Lync isn't open on your Mac", "Open Lync on your Mac.", listOf(Fix.TryAgain))
        Away.MacNotAnswering -> Problem("Mac isn't answering", "Wake your Mac and check its firewall allows Lync.", listOf(Fix.TryAgain))
        Away.MacReset -> Problem("Your Mac was reset", "Pair again to reconnect.", listOf(Fix.PairAgain))
        Away.KeyLocked -> Problem("Phone's key is locked", "Unlock your phone, or pair again.", listOf(Fix.TryAgain, Fix.PairAgain))
        Away.VersionMismatch -> Problem("Lync versions don't match", "Update Lync on this phone and on your Mac.", listOf(Fix.Update))
    }

    /** Looking around without a Mac: sending needs one paired first. */
    val NO_MAC_YET = Problem("No Mac yet", "Pair your Mac to send to it.", listOf(Fix.PairMac))
    /** The Mac said it no longer knows this phone (state Forgotten). */
    val FORGOTTEN = Problem("Your Mac forgot this phone", "Pair again to send to your Mac.", listOf(Fix.PairAgain))
    /** A queued send that can't go because the Mac forgot this phone. */
    val FORGOTTEN_SEND = Problem("Your Mac forgot this phone", "Pair again, then send.", listOf(Fix.OpenLync))
    val NOT_PAIRED = Problem("No Mac paired", "Open Lync to pair.", listOf(Fix.OpenLync))
    /** Only Bluetooth reaches the Mac, and this needs Wi‑Fi. */
    val TEXT_NEEDS_WIFI = Problem("Text needs Wi‑Fi", "Join the same Wi‑Fi as your Mac.", listOf(Fix.WifiSettings))
    val FILES_NEED_WIFI = Problem("Files need Wi‑Fi", "Join the same Wi‑Fi as your Mac.", listOf(Fix.WifiSettings))
    val MAC_DIDNT_ANSWER = Problem("Mac didn't answer", "Check Lync is open on your Mac.", listOf(Fix.TryAgain))
    /** The Mac answered a text or link with ok false. */
    val MAC_REFUSED = Problem("Mac didn't take it", "Try again.", listOf(Fix.TryAgain))

    // MARK: - Pairing

    /** How pairing failed, as the user sees it. [confirmingMs]: how long the Mac had been asked. */
    fun pairing(
        failure: Failure,
        step: PairStep,
        qr: Boolean,
        typed: Boolean,
        onWifi: Boolean,
        macSeen: Boolean,
        confirmingMs: Long = 0,
    ): Problem {
        if (step == PairStep.Confirming) {
            return if (failure == Failure.Timeout || confirmingMs >= PAIR_WINDOW_MS - 10_000) CODE_TIMED_OUT else ENDED
        }
        return when (failure) {
            Failure.UnknownHost -> Problem("Mac name not found", "Check the address shown on your Mac.", listOf(Fix.TryAgain))
            Failure.Protocol -> NOT_LYNC
            Failure.Refused -> Problem(
                "Lync isn't open on your Mac",
                if (typed) "Open Lync on your Mac, or check the address and port." else "Open Lync on your Mac and try again.",
                listOf(Fix.TryAgain),
            )
            Failure.Timeout, Failure.NoRoute ->
                if (!onWifi || (!macSeen && (qr || typed))) NOT_SAME_WIFI
                else away(Away.MacNotAnswering)
            Failure.Dropped -> ENDED
            else -> if (!onWifi) NOT_SAME_WIFI else Problem("Couldn't pair", "Try again.", listOf(Fix.TryAgain))
        }
    }

    /** The Mac's `pair_no`, by its `err` code (PROTOCOL.md). Old Macs send none. */
    fun pairNo(err: String?, qr: Boolean): Problem = when (err) {
        "busy" -> Problem("Mac is pairing another phone", "Finish or cancel it on your Mac, then try again.", listOf(Fix.TryAgain))
        "rate" -> Problem("Too many tries", "Wait a minute, then try again.", listOf(Fix.TryAgain))
        "version" -> Problem("Lync needs updating", "Update Lync on this phone.", listOf(Fix.Update))
        "expired" -> CODE_EXPIRED
        "declined" -> DECLINED
        "mismatch" -> NOT_QR_MAC
        else -> if (qr) CODE_EXPIRED else DECLINED
    }

    /** The phone waits 130 s; the Mac's window is two minutes. */
    const val PAIR_WINDOW_MS = 120_000L

    val NOT_SAME_WIFI = Problem("Not on the same Wi‑Fi", "Connect both to the same Wi‑Fi and try again.", listOf(Fix.WifiSettings, Fix.TryAgain))
    val NOT_LYNC = Problem("That's not Lync", "Check the address and port shown on your Mac.", listOf(Fix.TryAgain))
    val CODE_TIMED_OUT = Problem("Code timed out", "Try again and click Pair on your Mac within 2 minutes.", listOf(Fix.TryAgain))
    val ENDED = Problem("Pairing ended unexpectedly", "Try again.", listOf(Fix.TryAgain))
    val CODE_EXPIRED = Problem("This code no longer works", "Show a new code on your Mac.", listOf(Fix.TryAgain))
    val DECLINED = Problem("Pairing declined on your Mac", "Try again and click Pair.", listOf(Fix.TryAgain))
    val NOT_QR_MAC = Problem("This isn't the Mac from the code", "Scan the code on the Mac you want to pair.", listOf(Fix.TryAgain))
    val MAC_NEEDS_UPDATE = Problem("Mac needs a Lync update", "Update Lync on your Mac.", listOf(Fix.Update))

    // MARK: - Sending files

    /** The Mac's per-file `ack` `err` (PROTOCOL.md, file acks). */
    fun fileAck(err: String?): Problem = when (err) {
        "storage_full" -> Problem("Mac is out of space", "Free up space on your Mac, then try again.", listOf(Fix.TryAgain))
        "checksum" -> Problem("File got damaged on the way", "Try again.", listOf(Fix.TryAgain))
        "permission" -> Problem("Mac couldn't save it", "Allow Lync to save files on your Mac, then try again.", listOf(Fix.TryAgain))
        "write_failed" -> Problem("Mac couldn't save it", "Try again.", listOf(Fix.TryAgain))
        "cancelled" -> Problem("Cancelled on your Mac", "Send it again if you meant to.", listOf(Fix.TryAgain))
        else -> Problem("Didn't arrive on your Mac", "Try again.", listOf(Fix.TryAgain))
    }

    /** Files that couldn't be opened on this phone, by name. */
    fun unreadable(names: List<String>): Problem =
        if (names.size == 1) Problem("Couldn't open ${names.first()}", "Pick the file again.", listOf(Fix.ChooseAgain))
        else CANT_OPEN

    /**
     * The outcome of a batch: null when every file arrived. [errs] holds the `err` of each file the
     * Mac turned down; [unreadable] the names of files this phone couldn't open.
     */
    fun sent(total: Int, errs: List<String?>, unreadable: List<String>): Problem? {
        val failed = errs.size + unreadable.size
        if (failed == 0) return null
        val reason = if (errs.isNotEmpty()) fileAck(errs.first()) else unreadable(unreadable)
        if (failed >= total) return reason
        return Problem("${total - failed} of $total sent", reason.sentence, reason.fixes)
    }

    val CANT_OPEN = Problem("Can't open these files", "Pick them again from Photos or Files.", listOf(Fix.ChooseAgain))
    val PHONE_FULL_SHARE = Problem("Phone storage is full", "Free up space, then share again.", listOf(Fix.StorageSettings))
    val MAC_STOPPED_ANSWERING = Problem("Mac stopped answering", "Check Lync is open on your Mac, then try again.", listOf(Fix.TryAgain))

    /** The connection went mid-send; [onWifi] false adds the Wi‑Fi button. */
    fun connectionLost(onWifi: Boolean) = Problem(
        "Wi‑Fi connection lost", "Try again; it sends when your Mac is back.",
        if (onWifi) listOf(Fix.TryAgain) else listOf(Fix.TryAgain, Fix.WifiSettings),
    )

    // MARK: - Files from the Mac

    /** What the phone answers in a file `ack` `err` for a write error, from its message. */
    fun writeErr(e: Throwable?): String = when {
        e == null -> "write_failed"
        e is SecurityException -> "permission"
        generateSequence(e) { it.cause }.any { it.message.orEmpty().contains("ENOSPC") || it.message.orEmpty().contains("No space left") } -> "storage_full"
        else -> "write_failed"
    }

    /** Files from the Mac that didn't all save: [saved] of [total], the first failure's code. */
    fun received(saved: Int, total: Int, err: String?, stopped: Boolean = false): Problem {
        val reason = when {
            stopped -> TRANSFER_STOPPED
            err == "storage_full" -> PHONE_FULL_RECEIVE
            err == "checksum" -> Problem("Files got damaged on the way", "Send them again from your Mac.")
            else -> Problem("Couldn't save the files", "Send them again from your Mac.")
        }
        return if (saved > 0) Problem("$saved of $total saved", reason.sentence, reason.fixes) else reason
    }

    val PHONE_FULL_RECEIVE = Problem("Phone storage is full", "Free up space and send again from your Mac.", listOf(Fix.StorageSettings))
    val TRANSFER_STOPPED = Problem("Transfer stopped", "Send it again from your Mac.")

    // MARK: - Texts (PROTOCOL.md, sms_send ack)

    /** The `err` for an SmsManager result code. */
    fun smsErr(resultCode: Int): String = when (resultCode) {
        android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane"
        android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
        android.telephony.SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
        android.telephony.SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
        android.telephony.SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
        android.telephony.SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "limit"
        RESULT_INVALID_ARGUMENTS, RESULT_INVALID_SMS_FORMAT -> "bad_number"
        else -> "failed"
    }

    // SmsManager.RESULT_INVALID_ARGUMENTS and RESULT_INVALID_SMS_FORMAT (Android 11): the values,
    // so older phones that send them still map.
    private const val RESULT_INVALID_ARGUMENTS = 11
    private const val RESULT_INVALID_SMS_FORMAT = 14

    // MARK: - Smaller things

    const val CODE_NOT_DELIVERED = "Didn't reach your Mac · tap to copy it here"
    const val CODE_EXPIRED_AWAY = "Code expired before your Mac was back"
    val NOTIFICATIONS_BLOCKED = Problem("Blocked in Android settings", "Allow notifications for Lync.", listOf(Fix.NotificationSettings))
    val BLUETOOTH_OFF = Problem("Bluetooth is off", "Turn it on to use the backup.", listOf(Fix.TurnOnBluetooth))
    val BLUETOOTH_UNSUPPORTED = Problem("This phone can't do Bluetooth backup", "Codes still arrive over Wi‑Fi.")

    /** Why Bluetooth advertising didn't start (AdvertiseCallback error codes). */
    fun advertiseFailed(errorCode: Int): Problem = when (errorCode) {
        android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> BLUETOOTH_UNSUPPORTED
        android.bluetooth.le.AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            Problem("Bluetooth is busy", "Close other apps using Bluetooth, then turn the backup off and on.")
        else -> Problem("Bluetooth backup didn't start", "Turn Bluetooth off and on, then try again.")
    }

    val CANT_STAY_RUNNING = Problem("Lync can't stay running", "Allow Unrestricted battery for Lync.", listOf(Fix.BatterySettings))
    const val BACKGROUND_DECLINED = "Background stays off until you allow it."
    val QR_NEWER = Problem("Code is from a newer Lync", "Update Lync on this phone.", listOf(Fix.Update))
    val CAMERA_FAILED = Problem("Couldn't start the camera", "Another app may be using it. Close it, then scan again, or go back and pick your Mac from the list.")
    val CLIPBOARD_NOT_TEXT = Problem("Only text from the clipboard", "Share photos with Send to Mac.")
    val CLIPBOARD_EMPTY = Problem("Clipboard is empty", "Copy some text first.")
    const val FILE_GONE = "File was moved or deleted"
    const val NO_BROWSER = "No browser found"
    const val NO_SETTINGS = "Open Settings › Apps › Lync"
}
