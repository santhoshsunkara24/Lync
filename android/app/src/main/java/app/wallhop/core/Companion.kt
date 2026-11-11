package app.wallhop.core

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * Lync as the paired Mac's companion app, in Android's sense (CompanionDeviceManager).
 *
 * From Android 17, texts carrying a one-time code in the WebOTP or SMS Retriever format
 * ("… @example.com #123456") are held back from every app for three hours, except the app the
 * code is for, the default SMS app and the companion apps of connected devices. Linking Lync to
 * the Mac this way is what lets those codes through. Android finds the Mac itself: the Mac
 * advertises [BEACON] for a minute when asked ("companion_beacon"), and the user confirms once
 * in Android's own dialog.
 */
object Companion {
    /** What the Mac advertises while it waits to be linked; not the Bluetooth fallback's service. */
    val BEACON: UUID = UUID.fromString("6c796e63-0002-4b1e-9c3a-5d2f7e8a1b40")

    /** Android holds code texts back from apps that aren't a companion (Android 17, API 37). */
    val needed: Boolean get() = Build.VERSION.SDK_INT >= 37

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    /** Lync is already a companion of some device (the Mac). */
    fun linked(context: Context): Boolean = runCatching {
        val cdm = manager(context) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cdm.myAssociations.isNotEmpty()
        else @Suppress("DEPRECATION") cdm.associations.isNotEmpty()
    }.getOrDefault(false)

    /**
     * Asks Android to find the Mac and link Lync to it. [onPending] gets Android's dialog to show
     * (start it with an IntentSenderRequest); [onDone] says whether it worked, and why not.
     */
    fun request(activity: Activity, onPending: (IntentSender) -> Unit, onDone: (ok: Boolean, error: String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return onDone(false, null)
        val cdm = manager(activity) ?: return onDone(false, null)
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(BEACON)).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        runCatching {
            cdm.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = onDone(true, null)
                override fun onFailure(error: CharSequence?) = onDone(false, error?.toString())
            })
        }.onFailure { onDone(false, it.message) }
    }
}
