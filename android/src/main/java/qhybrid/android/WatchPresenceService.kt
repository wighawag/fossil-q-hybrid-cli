package qhybrid.android

import android.annotation.SuppressLint
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * WP3 — event-driven reconnect (API 31+ / S).
 *
 * The system binds this [CompanionDeviceService] and wakes it whenever an associated
 * device appears nearby — NO continuous scanning on our side, and the binding elevates
 * our process priority. On appearance we hand the MAC to [WatchConnectionService] which
 * runs the (single-threaded, blocking) connect+init.
 *
 * Borrows the dispatch logic from Gadgetbridge's `GBCompanionDeviceService` (the API
 * 31 / 33 / Baklava callback variants), re-expressed standalone.
 *
 * On API 26–30 this class is never instantiated by the system (CompanionDeviceService is
 * S+); [ReconnectFallback] handles those versions.
 */
@RequiresApi(Build.VERSION_CODES.S)
class WatchPresenceService : CompanionDeviceService() {

    companion object {
        private const val TAG = "FossilQ-Presence"
    }

    // API 31 (S) and 32 — String MAC variant. Deprecated since T but the only one called
    // on S/Sv2.
    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "onDeviceAppeared(String) $address")
            WatchConnectionService.onDeviceAppeared(applicationContext, address.uppercase())
        }
    }

    @SuppressLint("MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        // The GATT drop already publishes DISCONNECTED; nothing to do here.
    }

    // API 33 (TIRAMISU)+ — AssociationInfo variant.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val mac = associationInfo.deviceMacAddress?.toString()?.uppercase()
        Log.i(TAG, "onDeviceAppeared(AssociationInfo) $mac")
        if (mac != null) {
            WatchConnectionService.onDeviceAppeared(applicationContext, mac)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // no-op (see above)
    }
}
