package app.wallhop.sms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Reads conversations and sends texts on request from the Mac. Text (SMS) only for now;
 * picture messages (MMS) aren't included yet.
 */
class SmsStore(private val context: Context) {
    // Read from several coroutines at once. "" means no contact, as the map can't hold null.
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

    val canRead: Boolean get() = granted(Manifest.permission.READ_SMS)
    val canSend: Boolean get() = granted(Manifest.permission.SEND_SMS)
    val canReadContacts: Boolean get() = granted(Manifest.permission.READ_CONTACTS)

    /**
     * Contacts whose name or number contains [query], for the Mac's Texts window: up to 20
     * (name, number) pairs. The provider narrows it down; [app.wallhop.core.Wording.matchContacts]
     * applies the exact rule.
     */
    fun searchContacts(query: String, limit: Int = 20): List<Pair<String, String>> {
        val q = query.trim()
        if (q.isEmpty() || !canReadContacts) return emptyList()
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val name = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val number = ContactsContract.CommonDataKinds.Phone.NUMBER
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        // Numbers are stored with spaces and dashes: "98765" should find "+91 98765 43210".
        val digits = q.filter(Char::isDigit)
        val likeNumber = if (digits.length >= 3) "%" + digits.toList().joinToString("%") + "%" else like
        val found = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            phone, arrayOf(name, number),
            "$name LIKE ? ESCAPE '\\' OR $number LIKE ? ESCAPE '\\'", arrayOf(like, likeNumber),
            "$name COLLATE NOCASE ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && found.size < 200) {
                found += cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty()
            }
        }
        return app.wallhop.core.Wording.matchContacts(found, q, limit)
    }

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

    /**
     * Sends a text and waits for the radio's answer for every part (up to 30 s), so the Mac hears
     * "sent" only once it really went. Android saves it to the phone's Sent messages automatically.
     */
    @SuppressLint("MissingPermission")
    suspend fun send(address: String, text: String, subscriptionId: Int?): Result<Unit> = runCatching {
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
        // One action per send, so results from another text can't be mistaken for this one's.
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val results = Channel<Int>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList(parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    context, index, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            })
            if (parts.size > 1) manager.sendMultipartTextMessage(address, null, parts, sent, null)
            else manager.sendTextMessage(address, null, text, sent.first(), null)
            val codes = withTimeoutOrNull(SEND_TIMEOUT_MS) { List(parts.size) { results.receive() } }
                ?: error("No answer from the network")
            codes.firstOrNull { it != Activity.RESULT_OK }?.let { error(sendError(it)) }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun sendError(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Airplane mode is on"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No mobile service"
        SmsManager.RESULT_ERROR_NULL_PDU, SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "The text couldn't be sent"
        else -> "The text couldn't be sent ($code)"
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
        names[address]?.let { return it.ifEmpty { null } }
        val name = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        names[address] = name.orEmpty()
        return name
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val SEND_TIMEOUT_MS = 30_000L

        /** Texts needs SMS; without these it can't work at all. */
        val required = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
        )
        /** Nice to have: names instead of numbers, and which SIM. Texts works without them. */
        val optional = arrayOf(
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
        // Some phones throw here even with the permission granted (work profiles, OEM SMS apps).
        registered = runCatching {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        }.isSuccess
    }

    fun stop() {
        if (!registered) return
        handler.removeCallbacks(notify)
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        registered = false
    }
}
