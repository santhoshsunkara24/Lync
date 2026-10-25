package app.wallhop.link

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app running in the background so the link to the Mac stays up.
 * Shows a quiet, minimised notification, which Android requires for this.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var link: LinkManager
    /** Android refused the foreground start (Android 12+ background restrictions); the service just ends. */
    private var refused = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = link.sendStatus()
    }

    @android.annotation.SuppressLint("MissingPermission") // The status notify() is wrapped in runCatching.
    override fun onCreate() {
        super.onCreate()
        link = LinkManager.get(this)
        Notifications.createChannels(this)
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.LINK_NOTIFICATION_ID,
                Notifications.linkNotification(this, link.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) when the system
            // restarts the service from the background on Android 12+. Not fatal: the app starts
            // it again next time it's opened. Remembered, so Settings can say why Lync stopped.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) throw e
            refused = true
            setRefused(this, true)
            stopSelf()
            return
        }
        setRefused(this, false)
        link.start()
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // "Run in background" off: once a transfer ends with no Lync screen open, stop again.
        scope.launch {
            link.transfer.map { it?.phase }.distinctUntilChanged().drop(1).collect { phase ->
                if (phase != TransferPhase.Waiting && phase != TransferPhase.Sending) stopIfIdle(this@LinkService)
            }
        }
        // The send queue in the notification shade, so a hidden share sheet still reports.
        scope.launch {
            link.transfers.collect { list ->
                Notifications.transferProgress(this@LinkService, TransferQueue.shown(list), link.pairedMac?.name, TransferQueue.waitingBehind(list))
            }
        }
        scope.launch {
            link.state.distinctUntilChanged { a, b -> a::class == b::class && a == b }.collect { state ->
                runCatching {
                    NotificationManagerCompat.from(this@LinkService)
                        .notify(Notifications.LINK_NOTIFICATION_ID, Notifications.linkNotification(this@LinkService, state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND_FILES) {
            val uris = buildList {
                val clip = intent.clipData
                if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
                if (isEmpty()) IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java)?.let(::addAll)
            }
            val id = intent.getStringExtra(EXTRA_REQUEST_ID)
            if (uris.isNotEmpty()) if (id != null) link.sendFiles(uris, id) else link.sendFiles(uris)
        }
        if (refused) {
            stopSelf()
            return START_NOT_STICKY
        }
        link.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        scope.cancel()
        // "Run in background" off and no Lync screen open: nothing should keep running, so stop
        // the reconnect loop, Bonjour, the network callback and the watchers too.
        if (!app.wallhop.core.Features.on(this, app.wallhop.core.Features.Key.Background) && screensOpen == 0) link.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SEND_FILES = "app.wallhop.SEND_FILES"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_REQUEST_ID = "request"

        /** Lync screens (main app, share sheet) currently started; the service may stop only at 0. */
        @Volatile var screensOpen = 0

        /** Android refused to keep Lync running in the background the last time it tried. */
        fun wasRefused(context: Context): Boolean =
            context.getSharedPreferences("service", Context.MODE_PRIVATE).getBoolean("refused", false)

        private fun setRefused(context: Context, refused: Boolean) {
            context.getSharedPreferences("service", Context.MODE_PRIVATE).edit().putBoolean("refused", refused).apply()
        }

        /**
         * With "Run in background" off, stops the service when nothing needs it: no Lync screen
         * open and no transfer waiting or sending.
         */
        fun stopIfIdle(context: Context) {
            if (app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Background) || screensOpen > 0) return
            val phase = LinkManager.get(context).transfer.value?.phase
            if (phase == TransferPhase.Waiting || phase == TransferPhase.Sending) return
            stop(context)
        }

        /** Stops the foreground service; the link then only lives while the app is open. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LinkService::class.java)) }
        }

        /** Starts the service. Only call while the app is in the foreground or from an allowed broadcast. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java)) }
        }

        /**
         * Hands shared files to the service along with read permission, so they stay readable
         * after the sheet closes. If the sharing app didn't allow passing that permission on,
         * the files are copied into the app's cache first. Returns false if nothing is readable.
         */
        fun sendFiles(context: Context, uris: List<Uri>, requestId: String? = null): Boolean {
            if (uris.isEmpty()) return false
            val intent = Intent(context, LinkService::class.java).setAction(ACTION_SEND_FILES)
            requestId?.let { intent.putExtra(EXTRA_REQUEST_ID, it) }
            val clip = android.content.ClipData.newRawUri("files", uris.first())
            uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                ContextCompat.startForegroundService(context, intent)
                return true
            } catch (_: SecurityException) {
                // Fall through and copy.
            } catch (_: IllegalStateException) {
                // Service start not allowed right now; fall through and copy.
            }
            // Copy off the main thread; the sheet stays open meanwhile, which keeps read access alive.
            val app = context.applicationContext
            Thread {
                val copied = SharedFileCache.copy(context, uris)
                val link = LinkManager.get(app)
                // Files that couldn't be copied are counted, so the result says "2 of 5 sent".
                val skipped = uris.size - copied.uris.size
                // Even when none could be read it's queued, so the failure shows (card, sheet,
                // notification) instead of nothing happening.
                if (requestId != null) link.sendFiles(copied.uris, requestId, skipped, copied.storageFull)
                else link.sendFiles(copied.uris, skipped = skipped, storageFull = copied.storageFull)
            }.start()
            start(context)
            return true
        }
    }
}

/** Restarts the link after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the system broadcasts; ignore anything else another app might send.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (MacStore(context).get() != null && app.wallhop.core.Features.on(context, app.wallhop.core.Features.Key.Background)) LinkService.start(context)
    }
}

/** True while the main Lync screen is started, so received files show in the app, not as a notification. */
object AppVisibility {
    @Volatile var foreground = false
}

/** Cancel and Retry on the transfer notification. */
class TransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifications.EXTRA_TRANSFER) ?: return
        val link = LinkManager.get(context)
        when (intent.action) {
            Notifications.ACTION_CANCEL_TRANSFER -> link.cancelTransfer(id)
            Notifications.ACTION_RETRY_TRANSFER -> {
                LinkService.start(context)
                // Nothing left to resend (the files went away): pick them again in Lync. (Android 12+
                // blocks this from a notification's broadcast; failures that need it get a Choose
                // again button that opens Lync directly.)
                if (!link.retryTransfer(id)) {
                    link.clearFinishedTransfer()
                    runCatching {
                        context.startActivity(Intent(context, app.wallhop.ui.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(Notifications.EXTRA_PICK, true))
                    }
                }
            }
        }
    }
}
