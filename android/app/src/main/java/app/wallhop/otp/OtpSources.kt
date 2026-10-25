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
        if (!app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Codes)) return
        if (MacStore(context).get() == null) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        val code = OtpExtractor.extract(body) ?: return
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
        if (!app.wallhop.core.Features.on(this, app.wallhop.core.Features.Key.AppNotifications)) return
        if (MacStore(this).get() == null) return
        val notification = sbn.notification
        val (title, text) = content(notification)
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
    private fun content(notification: Notification): Pair<String, String> {
        val extras = notification.extras
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)?.let { style ->
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
            val sbn = runCatching { listener.activeNotifications }.getOrNull()?.firstOrNull { it.key == key } ?: return "gone"
            val action = replyAction(sbn.notification) ?: return "no_reply_action"
            val inputs = action.remoteInputs ?: return "no_reply_action"
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return if (runCatching { action.actionIntent.send(context, 0, intent) }.isSuccess) null else "failed"
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
