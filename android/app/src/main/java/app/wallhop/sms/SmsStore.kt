package app.wallhop.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads conversations and sends texts on request from the Mac. Text (SMS) only for now;
 * picture messages (MMS) aren't included yet.
 */
class SmsStore(private val context: Context) {
    private val names = HashMap<String, String?>()

    val canRead: Boolean get() = granted(Manifest.permission.READ_SMS)
    val canSend: Boolean get() = granted(Manifest.permission.SEND_SMS)

    /** Latest conversations, newest first. */
    fun threads(limit: Int = 100): JSONObject {
        val threads = LinkedHashMap<Long, JSONObject>()
        val unread = HashMap<Long, Int>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                Telephony.Sms.TYPE, Telephony.Sms.READ),
            null, null, "${Telephony.Sms.DATE} DESC LIMIT 3000",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val thread = cursor.getLong(0)
                val type = cursor.getInt(4)
                if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && cursor.getInt(5) == 0) {
                    unread[thread] = (unread[thread] ?: 0) + 1
                }
                if (thread in threads || threads.size >= limit) continue
                val address = cursor.getString(1).orEmpty()
                threads[thread] = JSONObject()
                    .put("thread", thread.toString())
                    .put("address", address)
                    .put("name", contactName(address) ?: JSONObject.NULL)
                    .put("snippet", cursor.getString(2).orEmpty().take(140))
                    .put("date", cursor.getLong(3))
            }
        }
        val list = JSONArray()
        threads.forEach { (id, json) -> list.put(json.put("unread", unread[id] ?: 0)) }
        return JSONObject().put("threads", list).put("sims", simsJson())
    }

    /** Messages in one conversation, oldest first. */
    fun messages(threadId: String, limit: Int = 300): JSONObject {
        val sims = simLabels()
        val list = ArrayList<JSONObject>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.SUBSCRIPTION_ID),
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId),
            "${Telephony.Sms.DATE} DESC LIMIT $limit",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getInt(3)
                val outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                list += JSONObject()
                    .put("id", cursor.getLong(0).toString())
                    .put("body", cursor.getString(1).orEmpty())
                    .put("date", cursor.getLong(2))
                    .put("out", outgoing)
                    .put("failed", type == Telephony.Sms.MESSAGE_TYPE_FAILED)
                    .put("sim", sims[cursor.getInt(4)] ?: JSONObject.NULL)
            }
        }
        return JSONObject().put("thread", threadId).put("messages", JSONArray(list.asReversed()))
    }

    /** Sends a text. Android saves it to the phone's Sent messages automatically. */
    @SuppressLint("MissingPermission")
    fun send(address: String, text: String, subscriptionId: Int?): Result<Unit> = runCatching {
        require(canSend) { "permission" }
        require(address.isNotBlank() && text.isNotBlank()) { "empty" }
        val manager: SmsManager = if (subscriptionId != null && subscriptionId >= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        val parts = manager.divideMessage(text)
        if (parts.size > 1) manager.sendMultipartTextMessage(address, null, parts, null, null)
        else manager.sendTextMessage(address, null, text, null, null)
    }

    private fun simsJson(): JSONArray {
        val array = JSONArray()
        simLabels().takeIf { it.size > 1 }?.forEach { (id, label) ->
            array.put(JSONObject().put("id", id).put("label", label))
        }
        return array
    }

    @SuppressLint("MissingPermission")
    private fun simLabels(): Map<Int, String> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyMap()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyMap()
        val active = runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return active.associate { info ->
            val carrier = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: info.carrierName?.toString()
            info.subscriptionId to listOfNotNull("SIM ${info.simSlotIndex + 1}", carrier).joinToString(" · ")
        }
    }

    private fun contactName(address: String): String? {
        if (address.isBlank() || !granted(Manifest.permission.READ_CONTACTS)) return null
        return names.getOrPut(address) {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        val permissions = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
        )
    }
}

/** Tells the Mac when texts arrive or are sent, so an open Messages window stays current. */
class SmsChangeWatcher(private val context: Context, private val onChange: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val notify = Runnable { onChange() }
    private var registered = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(notify)
            handler.postDelayed(notify, 600)
        }
    }

    fun start() {
        if (registered || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        registered = true
    }
}
