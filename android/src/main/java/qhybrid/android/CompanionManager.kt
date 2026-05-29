package qhybrid.android

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * WP3 — CompanionDeviceManager glue (associate / observe-presence / battery-opt).
 *
 * Borrows the *logic* from Gadgetbridge's `BondingUtil` (associate flow, getAssociations
 * fast-path, startObservingDevicePresence) but is standalone — no GBApplication/greenDAO.
 *
 * The associated watch MAC is persisted in a tiny SharedPreferences blob behind this
 * helper so WP4 (Room) can swap the backing store without touching the service.
 *
 * API notes (minSdk 26):
 *  - `CompanionDeviceManager.associate(...)` is API 26+.
 *  - `startObservingDevicePresence(String)` + the [WatchPresenceService] callbacks are
 *    API 31+ (S). On 26–30 we fall back to [ReconnectFallback]; presence observation here
 *    is a no-op on those versions.
 */
object CompanionManager {

    private const val TAG = "FossilQ-CDM"
    private const val PREFS = "fossilq_companion"
    private const val KEY_MAC = "associated_mac"

    // ---- associated-MAC persistence (isolated; WP4 can replace) -------------

    fun getAssociatedMac(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAC, null)

    fun setAssociatedMac(context: Context, mac: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().apply {
                if (mac == null) remove(KEY_MAC) else putString(KEY_MAC, mac.uppercase())
            }.apply()
    }

    // ---- CompanionDeviceManager ---------------------------------------------

    private fun cdm(context: Context): CompanionDeviceManager? =
        context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager

    /** True if [mac] is already associated with us (so we can skip the chooser). */
    fun isAssociated(context: Context, mac: String): Boolean {
        val manager = cdm(context) ?: return false
        return try {
            @Suppress("DEPRECATION")
            manager.associations.any { it.equals(mac, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "isAssociated check failed", e)
            false
        }
    }

    /**
     * Build the CDM association request for a single BLE device, optionally filtered to
     * a known MAC. Returns null if BLE filtering isn't possible.
     */
    private fun buildRequest(mac: String?): AssociationRequest {
        val filterBuilder = BluetoothLeDeviceFilter.Builder()
        if (mac != null && BluetoothAdapter.checkBluetoothAddress(mac)) {
            val scan = ScanFilter.Builder().setDeviceAddress(mac).build()
            filterBuilder.setScanFilter(scan)
        }
        return AssociationRequest.Builder()
            .addDeviceFilter(filterBuilder.build())
            .setSingleDevice(mac != null)
            .build()
    }

    /**
     * Launch the CDM association chooser. The chosen device's chooser is delivered via
     * [callback] (the Activity must then start the returned IntentSender for result —
     * see [MainActivity]). If [mac] is already associated, [onAlreadyAssociated] is
     * invoked instead and no chooser is shown.
     */
    fun associate(
        context: Context,
        mac: String?,
        callback: CompanionDeviceManager.Callback,
        onAlreadyAssociated: (String) -> Unit,
    ) {
        val manager = cdm(context) ?: run {
            Log.e(TAG, "No CompanionDeviceManager")
            return
        }
        if (mac != null && isAssociated(context, mac)) {
            Log.i(TAG, "Already associated with $mac — skipping chooser")
            startObserving(context, mac)
            onAlreadyAssociated(mac)
            return
        }
        Log.i(TAG, "Starting CDM association request (mac=$mac)")
        manager.associate(buildRequest(mac), callback, null)
    }

    /** Arm event-driven presence wakeups for [mac] (API 31+). No-op (false) below S. */
    @RequiresPermission(value = "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE", conditional = true)
    fun startObserving(context: Context, mac: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "startObserving: API ${Build.VERSION.SDK_INT} < S — using fallback")
            return false
        }
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            Log.w(TAG, "startObserving: invalid mac $mac")
            return false
        }
        val manager = cdm(context) ?: return false
        return try {
            manager.startObservingDevicePresence(mac)
            Log.i(TAG, "startObservingDevicePresence($mac)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "startObservingDevicePresence failed", e)
            false
        }
    }

    @RequiresPermission(value = "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE", conditional = true)
    fun stopObserving(context: Context, mac: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = cdm(context) ?: return
        try {
            manager.stopObservingDevicePresence(mac)
        } catch (e: Exception) {
            Log.w(TAG, "stopObservingDevicePresence failed", e)
        }
    }

    /** Resolve the MAC for a CDM association id (used by [WatchPresenceService] on T+). */
    fun macForAssociationId(context: Context, id: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = cdm(context) ?: return null
        return try {
            manager.myAssociations.firstOrNull { it.id == id }
                ?.deviceMacAddress?.toString()?.uppercase()
        } catch (e: Exception) {
            Log.w(TAG, "macForAssociationId failed", e)
            null
        }
    }

    // ---- battery optimization ------------------------------------------------

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Fire the system "ignore battery optimizations" prompt for our package. */
    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (isIgnoringBatteryOptimizations(activity)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "battery-opt prompt failed; opening settings list", e)
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
