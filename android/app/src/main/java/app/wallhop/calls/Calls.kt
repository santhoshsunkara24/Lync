package app.wallhop.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.wallhop.link.LinkManager
import app.wallhop.link.MacStore
import java.util.UUID

/** One phone call, as the Mac sees it. */
data class CallInfo(
    val id: String,
    val state: String,
    val number: String?,
    val name: String?,
    val sim: String?,
)

/**
 * Listens for the phone's call state and tells the Mac. Android sends this broadcast once
 * without the number and again with it (when call log access is granted), so updates for
 * the same call reuse one id.
 */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Calls)) return
        if (MacStore(context).get() == null) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
        val subscription = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        CallTracker.onState(context, state, number, subscription, this)
    }
}

object CallTracker {
    private var current: CallInfo? = null
    /** A call is ringing or under way, as far as Lync has seen. */
    val active: Boolean get() = current != null
    private var answered = false
    /** When the current call started ringing (wall clock, like the call log). */
    private var ringingSince = 0L

    /** [receiver] keeps the broadcast alive while the call log is checked after a call ends. */
    @Synchronized
    fun onState(context: Context, state: String, number: String?, subscription: Int, receiver: BroadcastReceiver? = null) {
        val link = LinkManager.get(context)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val previous = current
                val call = CallInfo(
                    id = previous?.takeIf { it.state == "ringing" }?.id ?: UUID.randomUUID().toString(),
                    state = "ringing",
                    number = number ?: previous?.number,
                    name = (number ?: previous?.number)?.let { contactName(context, it) } ?: previous?.name,
                    sim = simLabel(context, subscription) ?: previous?.sim,
                )
                if (call == previous) return
                if (previous?.id != call.id) ringingSince = System.currentTimeMillis()
                answered = false
                current = call
                link.sendCall(call)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val call = current ?: return // An outgoing call; nothing to show.
                if (call.state == "active") return
                answered = true
                current = call.copy(state = "active")
                link.sendCall(current!!)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val call = current ?: return
                current = null
                if (answered || call.state == "active") {
                    answered = false
                    link.sendCall(call.copy(state = "ended"))
                    return
                }
                // Not answered: missed, unless it was declined on the phone. The call log says
                // which, but only once the phone app has written it, a moment after the call ends.
                val since = ringingSince
                val pending = runCatching { receiver?.goAsync() }.getOrNull()
                Thread {
                    try {
                        Thread.sleep(1_500)
                        val declined = wasDeclined(context, since)
                        link.sendCall(call.copy(state = if (declined) "ended" else "missed"))
                    } finally {
                        pending?.finish()
                    }
                }.start()
            }
        }
    }

    /** True when the call log shows the call that rang at [since] was declined (not missed). */
    private fun wasDeclined(context: Context, since: Long): Boolean {
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) return false
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} >= ?", arrayOf((since - 10_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { if (it.moveToFirst()) it.getInt(0) == CallLog.Calls.REJECTED_TYPE else false } ?: false
        }.getOrDefault(false)
    }

    /** "SIM 1 · Jio", only when more than one SIM is active. */
    @SuppressLint("MissingPermission")
    private fun simLabel(context: Context, subscriptionId: Int): String? {
        if (!granted(context, Manifest.permission.READ_PHONE_STATE)) return null
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val active = runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        if (active.size < 2) return null
        val info = active.firstOrNull { it.subscriptionId == subscriptionId } ?: return null
        val carrier = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: info.carrierName?.toString()
        return listOfNotNull("SIM ${info.simSlotIndex + 1}", carrier).joinToString(" · ")
    }

    private fun contactName(context: Context, number: String): String? {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Answers or declines the ringing call when the Mac asks. Answering needs the "answer calls"
 * permission; declining can also press the phone app's own hang-up button.
 */
object CallControl {
    /**
     * Does it. Null when it worked, else why not, for the `call_cmd` ack (PROTOCOL.md): calls_off,
     * permission, no_call or failed.
     */
    @SuppressLint("MissingPermission") // Both checked below.
    fun performErr(context: Context, action: String): String? {
        if (!app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Calls)) return "calls_off"
        val canAnswer = granted(context, Manifest.permission.ANSWER_PHONE_CALLS)
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return "failed"
        // What Lync saw, or Android's answer when it may ask (Lync may have started mid-call).
        val inCall = CallTracker.active || !granted(context, Manifest.permission.READ_PHONE_STATE) ||
            runCatching { telecom.isInCall }.getOrDefault(true)
        if (!inCall) return "no_call"
        return runCatching {
            when (action) {
                "answer" -> if (!canAnswer) "permission" else { telecom.acceptRingingCall(); null }
                "decline", "end" -> when {
                    app.wallhop.otp.OtpNotificationListener.endCall(context) -> null
                    !canAnswer -> "permission"
                    else -> "failed"
                }
                else -> "failed"
            }
        }.getOrDefault("failed")
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Calls needs these: call state, the caller's number (call log) and answering. */
    val required = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )
    /** Optional: names instead of numbers. Calls still work without it. */
    val optional = arrayOf(Manifest.permission.READ_CONTACTS)
}
