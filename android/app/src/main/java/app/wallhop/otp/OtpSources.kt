package app.wallhop.otp

import android.app.Notification
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import app.wallhop.BuildConfig
import app.wallhop.link.LinkManager
import app.wallhop.link.MacStore
import app.wallhop.notify.MirroredApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Reads incoming SMS and forwards one-time codes to the Mac. Nothing else leaves the phone. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        fun why(reason: String) { if (BuildConfig.STRESS_HOOKS) Log.d("WallhopOtp", "sms: $reason") }
        if (!app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Codes)) return why("codes off")
        if (MacStore(context).get() == null) return why("no Mac paired")
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return why("no messages")
        if (messages.isEmpty()) return why("no messages")

        val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        val code = OtpExtractor.extract(body) ?: return why("no code in the text")
        why("code found, sending")
        val sender = messages.first().displayOriginatingAddress.orEmpty()

        // Keep the process alive long enough to reconnect and deliver.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(9_000) { LinkManager.get(context).sendOtp(code, sender, "SMS")?.join() }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Reads other apps' notifications, once the user grants notification access, to:
 * - find one-time codes (WhatsApp, email, banking apps), and
 * - mirror notifications from the apps the user picked to the Mac.
 *
 * On Android 15 and later the system hides codes in many notifications from listeners, so SMS
 * stays the main code source. The class name stays the same so granted access isn't lost.
 */
class OtpNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        active = this
    }

    override fun onListenerDisconnected() {
        if (active === this) active = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // A new text in the SMS app: check the inbox as well, a second chance for its code if the
        // SMS broadcast missed it (Android hides codes in the notification itself from us).
        if (sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this) && !sbn.isOngoing &&
            System.currentTimeMillis() - sbn.postTime < CODE_MAX_AGE_MS) {
            LinkManager.get(this).checkInboxSoon()
        }
        if (!app.wallhop.core.Features.on(this, app.wallhop.core.Features.Key.AppNotifications)) return
        if (MacStore(this).get() == null) return
        val notification = sbn.notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val (title, text) = content(notification, style)
        if (text.isBlank()) return

        // Apps re-post old notifications (an update, a reboot): a code older than five minutes is no use.
        val fresh = System.currentTimeMillis() - sbn.postTime < CODE_MAX_AGE_MS
        if (!sbn.isOngoing && fresh && app.wallhop.core.Features.on(this, app.wallhop.core.Features.Key.Codes)) {
            val code = OtpExtractor.extract("$title $text")
            if (BuildConfig.DEBUG) Log.d(TAG, "notification from ${sbn.packageName}: code found=${code != null}")
            if (code != null) {
                LinkManager.get(this).sendOtp(code, title.ifBlank { appLabel(sbn.packageName) }, sbn.packageName)
                // The SMS app's own notification for a text the SMS receiver already handled
                // isn't mirrored as well; any other app's notification still is.
                if (sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this)) return
            }
        }

        val isSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (sbn.isOngoing || isSummary) return
        val mirrored = MirroredApps.get(this)
        mirrored.noteSeen(sbn.packageName)
        if (!mirrored.isEnabled(sbn.packageName)) return
        // Remembered so a reply from the Mac still goes through after the app replaces or clears it.
        replyAction(notification)?.let {
            remember(sbn.key, sbn.packageName, notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), it)
        }
        // A chat app adds your own reply to its notification: that's not news, so it isn't mirrored.
        if (isOwnMessage(sbn.packageName, text, style)) return

        LinkManager.get(this).sendNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appLabel(sbn.packageName),
            title = title,
            text = text,
            time = sbn.postTime,
            canReply = replyAction(notification) != null,
            actions = notification.actions.orEmpty().map { action ->
                action.title.toString() to (action.remoteInputs?.any { it.allowFreeFormInput } == true)
            },
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (MirroredApps.get(this).isEnabled(sbn.packageName)) {
            LinkManager.get(this).notificationRemoved(sbn.key)
        }
    }

    /** Title and text, using the latest message for chat-style notifications. */
    private fun content(notification: Notification, style: NotificationCompat.MessagingStyle?): Pair<String, String> {
        val extras = notification.extras
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        style?.let { style ->
            style.messages.lastOrNull()?.let { message ->
                val sender = message.person?.name?.toString()
                val conversation = style.conversationTitle?.toString()
                title = when {
                    conversation != null && sender != null -> "$sender · $conversation"
                    sender != null -> sender
                    else -> title
                }
                text = message.text?.toString() ?: text
            }
        }
        return title to text
    }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    companion object {
        private const val TAG = "WallhopNotify"
        private const val CODE_MAX_AGE_MS = 5 * 60_000L

        @Volatile private var active: OtpNotificationListener? = null

        /** A mirrored notification's reply button, kept after the notification itself is gone. */
        private class Replyable(val key: String, val pkg: String, val title: String, val action: Notification.Action)
        private val replyables = object : LinkedHashMap<String, Replyable>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Replyable>) = size > 60
        }
        /** Replies just sent from the Mac (package, text, when), to recognise them coming back. */
        private val sentReplies = ArrayList<Triple<String, String, Long>>()
        private const val OWN_REPLY_WINDOW_MS = 2 * 60_000L

        private fun remember(key: String, pkg: String, title: String, action: Notification.Action) =
            synchronized(replyables) { replyables[key] = Replyable(key, pkg, title, action) }

        /**
         * The latest message is the user's own: the chat app's "you" in the conversation, or a
         * reply the Mac just sent through this app (the same words, within two minutes).
         */
        private fun isOwnMessage(pkg: String, text: String, style: NotificationCompat.MessagingStyle?): Boolean {
            val now = System.currentTimeMillis()
            val sentFromMac = synchronized(sentReplies) {
                sentReplies.removeAll { now - it.third > OWN_REPLY_WINDOW_MS }
                sentReplies.any { it.first == pkg && it.second.trim() == text.trim() }
            }
            if (sentFromMac) return true
            val last = style?.messages?.lastOrNull() ?: return false
            val person = last.person ?: return false
            val me = style.user
            return (me.key != null && me.key == person.key) ||
                (me.name != null && me.name.toString() == person.name?.toString())
        }

        private fun replyAction(notification: Notification): Notification.Action? {
            val actions = notification.actions.orEmpty().toList() +
                Notification.WearableExtender(notification).actions
            return actions.firstOrNull { action -> action.remoteInputs?.any { it.allowFreeFormInput } == true }
        }

        /**
         * Sends [text] through the notification's own reply action. Null when it went, else why not
         * (PROTOCOL.md, notif_reply ack): access_off, gone, no_reply_action or failed.
         */
        fun replyErr(context: Context, key: String, text: String): String? {
            val listener = active ?: return accessErr(context)
            val all = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
            val sbn = all.firstOrNull { it.key == key }
            val kept = synchronized(replyables) { replyables[key] }
            // The notification as it is now; else its reply button as it was (chat apps replace and
            // clear their notifications, but the button usually still works); else the newest
            // notification from the same conversation.
            val action = sbn?.let { replyAction(it.notification) }
                ?: kept?.action
                ?: kept?.let { k ->
                    all.filter { it.packageName == k.pkg && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == k.title }
                        .maxByOrNull { it.postTime }?.let { replyAction(it.notification) }
                }
                ?: return if (sbn != null) "no_reply_action" else "gone"
            val inputs = action.remoteInputs ?: return "no_reply_action"
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            val sent = runCatching { action.actionIntent.send(context, 0, intent) }
            if (sent.isFailure) return if (sent.exceptionOrNull() is android.app.PendingIntent.CanceledException) "gone" else "failed"
            // So the chat app's updated notification, which now shows this reply, isn't mirrored back.
            val pkg = sbn?.packageName ?: kept?.pkg
            if (pkg != null) synchronized(sentReplies) { sentReplies += Triple(pkg, text, System.currentTimeMillis()) }
            return null
        }

        /** Not connected to Android: access is off, or it's on and Android hasn't bound Lync yet. */
        private fun accessErr(context: Context): String =
            if (androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) "failed"
            else "access_off"

        private val hangUpWords = listOf(
            "decline", "reject", "hang up", "hangup", "end", "dismiss", "cancel",
            "rechazar", "colgar", "recusar", "desligar", "ablehnen", "auflegen", "refuser", "raccrocher",
            "отклонить", "拒绝", "挂断", "拒否", "거절", "अस्वीकार", "काटें",
        )

        /**
         * Declines a ringing call or hangs up an active one. Uses Android's end-call API when the
         * "answer calls" permission is granted, and otherwise presses the hang-up button on the
         * phone app's own call notification. Only the default phone app's notifications are
         * touched, and only a button that says decline or hang up: never a guess.
         */
        @android.annotation.SuppressLint("MissingPermission")
        fun endCall(context: Context): Boolean {
            val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)
            val canEnd = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ANSWER_PHONE_CALLS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (telecom != null && canEnd && runCatching { @Suppress("DEPRECATION") telecom.endCall() }.getOrDefault(false)) return true
            return endCallFromNotification(context, telecom?.defaultDialerPackage)
        }

        private fun endCallFromNotification(context: Context, dialerPackage: String?): Boolean {
            val listener = active ?: return false
            if (dialerPackage == null) return false
            val calls = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
                .filter { it.packageName == dialerPackage && it.notification.category == Notification.CATEGORY_CALL }
            for (sbn in calls) {
                val action = sbn.notification.actions.orEmpty().firstOrNull { action ->
                    val title = action.title?.toString()?.lowercase().orEmpty()
                    hangUpWords.any { title.contains(it) }
                } ?: continue
                if (runCatching { action.actionIntent.send(context, 0, Intent()) }.isSuccess) return true
            }
            return false
        }

        /** Taps one of the notification's action buttons, like "Mark as read". Null when it worked, else the ack code. */
        fun performActionErr(context: Context, key: String, index: Int): String? {
            val listener = active ?: return accessErr(context)
            val sbn = runCatching { listener.activeNotifications }.getOrNull()?.firstOrNull { it.key == key } ?: return "gone"
            val action = sbn.notification.actions?.getOrNull(index) ?: return "gone"
            return if (runCatching { action.actionIntent.send(context, 0, Intent()) }.isSuccess) null else "failed"
        }

        fun dismiss(key: String) {
            runCatching { active?.cancelNotification(key) }
        }
    }
}
