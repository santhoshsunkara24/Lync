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
import kotlinx.coroutines.launch

/**
 * Keeps the app running in the background so the link to the Mac stays up.
 * Shows a quiet, minimised notification, which Android requires for this.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var link: LinkManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = link.sendStatus()
    }

    @android.annotation.SuppressLint("MissingPermission") // The status notify() is wrapped in runCatching.
    override fun onCreate() {
        super.onCreate()
        link = LinkManager.get(this)
        Notifications.createChannels(this)
        ServiceCompat.startForeground(
            this,
            Notifications.LINK_NOTIFICATION_ID,
            Notifications.linkNotification(this, link.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        link.start()
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
            if (uris.isNotEmpty()) link.sendFiles(uris)
        }
        link.start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SEND_FILES = "app.wallhop.SEND_FILES"
        private const val EXTRA_URIS = "uris"

        /** Starts the service. Only call while the app is in the foreground or from an allowed broadcast. */
        /** Stops the foreground service; the link then only lives while the app is open. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LinkService::class.java)) }
        }

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java)) }
        }

        /**
         * Hands shared files to the service along with read permission, so they stay readable
         * after the sheet closes. If the sharing app didn't allow passing that permission on,
         * the files are copied into the app's cache first. Returns false if nothing is readable.
         */
        fun sendFiles(context: Context, uris: List<Uri>): Boolean {
            if (uris.isEmpty()) return false
            val intent = Intent(context, LinkService::class.java).setAction(ACTION_SEND_FILES)
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
                val copies = SharedFileCache.copy(context, uris)
                if (copies.isNotEmpty()) LinkManager.get(app).sendFiles(copies)
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
