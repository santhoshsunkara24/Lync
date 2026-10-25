package app.wallhop.link

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * Keeps files moving at the network's full speed while a transfer runs: Wi‑Fi out of power-save,
 * and the CPU awake so a long send doesn't stall when the screen turns off. Held only while
 * files are going either way, and released the moment the last one ends.
 */
internal class TransferLocks(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createWifiLock(
            // Low latency is the full-speed lock from Android 10; high-perf before it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "Lync:transfer",
        )?.apply { setReferenceCounted(false) }
    private val cpu = context.applicationContext.getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lync:transfer")
        ?.apply { setReferenceCounted(false) }
    private val holders = mutableSetOf<String>()

    /** [id] is a transfer that started; calling it again for a running one just extends the CPU lock. */
    @Synchronized
    fun hold(id: String) {
        holders += id
        runCatching { if (wifi?.isHeld == false) wifi.acquire() }
        // A cap, in case an end never arrives (the Mac vanished mid-batch); renewed per transfer.
        runCatching { cpu?.acquire(MAX_HOLD_MS) }
    }

    @Synchronized
    fun release(id: String) {
        if (holders.remove(id) && holders.isEmpty()) releaseLocks()
    }

    private fun releaseLocks() {
        runCatching { if (wifi?.isHeld == true) wifi.release() }
        runCatching { if (cpu?.isHeld == true) cpu.release() }
    }

    private companion object {
        const val MAX_HOLD_MS = 30 * 60_000L
    }
}
