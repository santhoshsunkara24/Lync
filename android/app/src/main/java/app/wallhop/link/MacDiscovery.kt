package app.wallhop.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A Mac running Wallhop, found on the local network. */
data class DiscoveredMac(val name: String, val host: String, val port: Int)

/** Finds Macs advertising `_wallhop._tcp` over Bonjour (mDNS). */
class MacDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _macs = MutableStateFlow<List<DiscoveredMac>>(emptyList())
    val macs: StateFlow<List<DiscoveredMac>> = _macs.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null

    /** Called whenever a Mac is found or re-announced on the network. */
    @Volatile var onFound: ((DiscoveredMac) -> Unit)? = null

    /**
     * False while Bonjour couldn't start (it's retried every few seconds). Then not seeing the
     * Mac says nothing about whether it's on this Wi‑Fi.
     */
    @Volatile var working = true
        private set
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retries = 0
    private val retry = Runnable { start() }

    // Older Android versions resolve one service at a time, so resolves are queued.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                working = true
                retries = 0
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(resolveQueue) { resolveQueue.addLast(service) }
                resolveNext()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                _macs.update { list -> list.filterNot { it.name == service.serviceName } }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@MacDiscovery) { listener = null }
                failed()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discoveryListener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure { listener = null; failed() }
    }

    /** Bonjour didn't start: try again in a few seconds (longer each time, up to a minute). */
    private fun failed() {
        working = false
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, (5_000L shl retries.coerceAtMost(3)).coerceAtMost(60_000L))
        retries++
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(retry)
        retries = 0
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        _macs.value = emptyList()
    }

    /** Restarts discovery, for example after the phone joins a different Wi-Fi. */
    fun restart() {
        stop()
        start()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            if (resolving) return
            resolveQueue.removeFirstOrNull()?.also { resolving = true }
        } ?: return

        val callback = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null) {
                    val mac = DiscoveredMac(info.serviceName, host.substringBefore('%'), info.port)
                    _macs.update { list -> list.filterNot { it.name == mac.name } + mac }
                    onFound?.invoke(mac)
                }
                finishResolve()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = finishResolve()
        }
        runCatching { nsd.resolveService(next, callback) }.onFailure { finishResolve() }
    }

    private fun finishResolve() {
        synchronized(resolveQueue) { resolving = false }
        resolveNext()
    }

    companion object {
        const val SERVICE_TYPE = "_wallhop._tcp"
    }
}
