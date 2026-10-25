package app.wallhop.link

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.wallhop.R
import app.wallhop.ui.MainActivity

object Notifications {
    const val CHANNEL_LINK = "link"
    const val CHANNEL_FROM_MAC = "from_mac"
    const val CHANNEL_FILES = "files_from_mac"
    const val CHANNEL_TRANSFERS = "transfers"
    const val CHANNEL_RING = "ring_phone"
    const val LINK_NOTIFICATION_ID = 1
    private const val TRANSFER_NOTIFICATION_ID = 5
    private const val FILES_GROUP = "app.wallhop.FILES_FROM_MAC"
    private const val FILES_SUMMARY_ID = 6
    private const val PROBLEM_NOTIFICATION_ID = 12
    const val ACTION_CANCEL_TRANSFER = "app.wallhop.CANCEL_TRANSFER"
    const val ACTION_RETRY_TRANSFER = "app.wallhop.RETRY_TRANSFER"
    const val EXTRA_TRANSFER = "transfer"
    /** MainActivity extra: open this tab (1 = Activity). */
    const val EXTRA_TAB = "tab"
    /** MainActivity extra: drop the old pairing and show the connect screen. */
    const val EXTRA_PAIR_AGAIN = "pair_again"
    /** MainActivity extra: open the last photo or file picker (a Retry with nothing left to resend). */
    const val EXTRA_PICK = "pick"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, "Connection to your Mac", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Lync connected so codes and files reach your Mac instantly."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FROM_MAC, "From your Mac", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Text and links sent from your Mac."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FILES, "Files from your Mac", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Photos and files your Mac sends to this phone."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, "Sending to your Mac", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of photos, files and text on their way to your Mac."
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RING, "Ring phone", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When your Mac rings this phone so you can find it."
                // The sound comes from Lync's own player on the alarm stream, so the channel is silent.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun linkNotification(context: Context, state: LinkState): Notification {
        // Why it isn't connected, once a round of attempts has failed.
        val problem = (state as? LinkState.Searching)?.reason?.let { app.wallhop.core.Problems.away(it) }
        val text = when (state) {
            is LinkState.Connected -> "Connected to ${state.macName}"
            is LinkState.Searching -> problem?.title ?: "Looking for ${state.macName}"
            is LinkState.Pairing -> "Pairing with ${state.macName}"
            // Keys are kept and the phone keeps trying, so this isn't "not paired".
            is LinkState.Forgotten -> "${state.macName} doesn't recognise this phone"
            else -> "Not paired with a Mac"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle(text)
            .setContentText(problem?.action)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // The button that helps: Pair again, or Wi‑Fi settings.
        val fix = if (state is LinkState.Forgotten) app.wallhop.core.Fix.PairAgain else problem?.fix
        when (fix) {
            app.wallhop.core.Fix.PairAgain -> builder.addAction(0, fix.label, PendingIntent.getActivity(
                context, 13,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_PAIR_AGAIN, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            app.wallhop.core.Fix.WifiSettings -> app.wallhop.core.Open.intent(context, fix)?.let { wifi ->
                builder.addAction(0, fix.label, PendingIntent.getActivity(
                    context, 14, wifi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
                ))
            }
            else -> Unit
        }
        if (state is LinkState.Connected || state is LinkState.Searching) {
            // Android only lets the app in front read the clipboard, so this opens a see-through screen for a moment.
            val send = PendingIntent.getActivity(
                context, 7,
                Intent(context, app.wallhop.extras.SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Send clipboard", send)
        }
        return builder.build()
    }

    /**
     * Puts text from the Mac on the clipboard, marked sensitive so Android doesn't show it in
     * the clipboard preview, and says so without repeating the text.
     */
    fun textFromMac(context: Context, text: String, macName: String) {
        Handler(Looper.getMainLooper()).post {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("From $macName", text)
            clip.description.extras = PersistableBundle().apply {
                // ClipDescription.EXTRA_IS_SENSITIVE (Android 13); older versions ignore it.
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            runCatching { clipboard.setPrimaryClip(clip) }
        }
        post(context, 4, NotificationCompat.Builder(context, CHANNEL_FROM_MAC)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle("Text from your Mac copied")
            .setContentText("Paste it anywhere on this phone")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build())
    }

    /**
     * Files from the Mac while no Lync screen is open: a heads-up with the first photo, Open and
     * Share. Tapping opens the file (one) or Activity (several). Batches group together.
     */
    fun filesFromMac(context: Context, batch: ReceivedBatch) {
        if (!canPost(context)) return
        Thread {
            val first = batch.uris.firstOrNull() ?: return@Thread
            val picture = if (batch.mimes.firstOrNull()?.startsWith("image/") == true) {
                runCatching { context.contentResolver.loadThumbnail(first, android.util.Size(512, 512), null) }.getOrNull()
            } else null
            val id = batch.uris.hashCode()
            val tap = if (batch.uris.size == 1) openFileIntent(context, id, batch) else openApp(context, tab = 1)
            val builder = NotificationCompat.Builder(context, CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_wallhop)
                .setContentTitle(batch.summary)
                .setContentText("Saved to Downloads › Lync")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setGroup(FILES_GROUP)
                .addAction(0, "Open", tap)
                .addAction(0, "Share", shareIntent(context, id, batch))
            if (picture != null) {
                builder.setLargeIcon(picture)
                    .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as android.graphics.Bitmap?))
            }
            post(context, id, builder.build())
            // A summary so several batches fold into one group.
            post(context, FILES_SUMMARY_ID, NotificationCompat.Builder(context, CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_wallhop)
                .setContentTitle("Files from your Mac")
                .setGroup(FILES_GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .setContentIntent(openApp(context, tab = 1))
                .build())
        }.start()
    }

    /** Opens one received file in the app that handles its type. */
    fun viewIntent(uri: android.net.Uri, mime: String?): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Share sheet for received files. */
    fun shareChooser(uris: List<android.net.Uri>, mimes: List<String>): Intent {
        val type = mimes.distinct().singleOrNull() ?: if (mimes.all { it.startsWith("image/") }) "image/*" else "*/*"
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newRawUri("files", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        send.clipData = clip
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Downloads, where Lync's folder is; for "Open" with several files. */
    fun downloadsIntent(): Intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openFileIntent(context: Context, id: Int, batch: ReceivedBatch): PendingIntent = PendingIntent.getActivity(
        context, id, viewIntent(batch.uris.first(), batch.mimes.firstOrNull()), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun shareIntent(context: Context, id: Int, batch: ReceivedBatch): PendingIntent = PendingIntent.getActivity(
        context, id + 1, shareChooser(batch.uris, batch.mimes), PendingIntent.FLAG_IMMUTABLE,
    )

    private var lastTransferKey: String? = null
    private var lastTransferAt = 0L

    /**
     * The send queue in the notification shade, so a hidden share sheet still reports:
     * "Sending to <Mac>" with progress and Cancel; "Couldn't send" with Retry; then
     * "Ready to paste on your Mac".
     */
    @Synchronized
    fun transferProgress(context: Context, shown: TransferState?, macName: String?, behind: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (shown == null) {
            lastTransferKey = null
            runCatching { manager.cancel(TRANSFER_NOTIFICATION_ID) }
            return
        }
        // Android drops updates that come faster than a few a second: every 5 % or phase change.
        val key = "${shown.id}:${shown.phase}:${(shown.progress * 20).toInt()}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastTransferKey) return
        if (shown.phase == TransferPhase.Sending && lastTransferKey?.startsWith("${shown.id}:Sending") == true && now - lastTransferAt < 400) return
        lastTransferKey = key
        lastTransferAt = now
        val mac = macName ?: "your Mac"
        val what = when {
            shown.text != null -> if (shown.isLink) "Link" else "Text"
            shown.items.isEmpty() -> "Files"
            else -> LinkManager.summary(shown.items)
        }
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, TransferActionReceiver::class.java).setAction(action).putExtra(EXTRA_TRANSFER, shown.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val more = if (behind > 0) " · $behind more waiting" else ""
        val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentIntent(openApp(context))
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        when (shown.phase) {
            TransferPhase.Waiting -> builder
                .setContentTitle("Waiting for $mac")
                .setContentText("$what$more · sends when your Mac is back")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .addAction(0, "Cancel", action(ACTION_CANCEL_TRANSFER, 8))
            TransferPhase.Sending -> builder
                .setContentTitle("Sending to $mac")
                .setContentText("$what$more")
                .setProgress(100, (shown.progress * 100).toInt(), shown.text != null)
                .setOngoing(true)
                .addAction(0, "Cancel", action(ACTION_CANCEL_TRANSFER, 8))
            TransferPhase.Done -> builder
                .setContentTitle(if (shown.isLink) "Opened on your Mac" else app.wallhop.core.Wording.READY)
                .setContentText(shown.message ?: what)
                .setAutoCancel(true)
                .setTimeoutAfter(5_000)
            // "Didn't send to MacBook Air", and why: "Mac is out of space. Free up space on your Mac…"
            TransferPhase.Failed -> {
                val why = shown.problem?.sentence ?: shown.message ?: what
                builder
                    .setContentTitle("Didn't send to $mac")
                    .setContentText(why)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(why))
                    .setAutoCancel(true)
                // Files that can't be opened again: the button opens Lync's picker instead of retrying.
                if (shown.problem?.fix == app.wallhop.core.Fix.ChooseAgain) {
                    builder.addAction(0, app.wallhop.core.Fix.ChooseAgain.label, PendingIntent.getActivity(
                        context, 16,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_PICK, true),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ))
                } else {
                    builder.addAction(0, "Retry", action(ACTION_RETRY_TRANSFER, 9))
                }
            }
        }
        post(context, TRANSFER_NOTIFICATION_ID, builder.build())
    }

    /**
     * Something went wrong with no screen open (files from the Mac that didn't save): a quiet
     * notification with the reason and its button, if it's one Android settings can do.
     */
    fun problem(context: Context, problem: app.wallhop.core.Problem) {
        val builder = NotificationCompat.Builder(context, CHANNEL_FILES)
            .setSmallIcon(R.drawable.ic_stat_wallhop)
            .setContentTitle(problem.title)
            .setContentText(problem.action)
            .setStyle(NotificationCompat.BigTextStyle().bigText(problem.action))
            .setContentIntent(openApp(context, tab = 1))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        val fix = problem.fix
        val open = fix?.takeIf { it == app.wallhop.core.Fix.StorageSettings }?.let { app.wallhop.core.Open.intent(context, it) }
        if (fix != null && open != null) {
            builder.addAction(0, fix.label, PendingIntent.getActivity(
                context, 15, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            ))
        }
        post(context, PROBLEM_NOTIFICATION_ID, builder.build())
    }

    /**
     * Android won't show Lync's notifications on this [channel]: turned off for the app, the
     * channel set to off, or notification permission denied.
     */
    fun blocked(context: Context, channel: String): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return true
        return manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** "Alerts on this phone" is on and Android allows notifications. */
    fun canPost(context: Context): Boolean {
        if (!app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Notifications)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    @android.annotation.SuppressLint("MissingPermission") // canPost checks it.
    private fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun openApp(context: Context, tab: Int? = null): PendingIntent = PendingIntent.getActivity(
        context, if (tab == null) 1 else 10 + tab,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (tab != null) putExtra(EXTRA_TAB, tab) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
