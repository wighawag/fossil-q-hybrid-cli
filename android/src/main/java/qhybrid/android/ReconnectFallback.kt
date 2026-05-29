package qhybrid.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * WP3 — minimal reconnect fallback for API 26–30 (where CompanionDeviceService /
 * startObservingDevicePresence are unavailable, since those are S+).
 *
 * This is deliberately NOT continuous scanning: [arm] kicks off a single, MAC-filtered,
 * LOW_POWER scan with a hard timeout; the first matching advertisement triggers a connect
 * and the scan stops. On API 31+ this is a no-op (CDM presence is the real mechanism, and
 * the project's test hardware is on 31+).
 *
 * The single-link nature of the watch means we only ever arm when disconnected.
 */
object ReconnectFallback {

    private const val TAG = "FossilQ-Fallback"
    private const val SCAN_TIMEOUT_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun arm(context: Context, mac: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // CDM presence observation is the mechanism on S+; nothing to do.
            return
        }
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return

        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = mgr?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run {
            Log.w(TAG, "arm: no scanner / BT off")
            return
        }
        stop(scanner) // cancel any previous one-shot

        val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val found = result.device?.address ?: return
                Log.i(TAG, "fallback scan found $found — connecting")
                stop(scanner)
                WatchConnectionService.onDeviceAppeared(context, found.uppercase())
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "fallback scan failed: $errorCode")
                stop(scanner)
            }
        }
        scanCallback = cb
        try {
            Log.i(TAG, "fallback one-shot scan armed for $mac")
            scanner.startScan(listOf(filter), settings, cb)
            handler.postDelayed({ stop(scanner) }, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "fallback startScan failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stop(scanner: android.bluetooth.le.BluetoothLeScanner) {
        scanCallback?.let {
            runCatching { scanner.stopScan(it) }
            scanCallback = null
        }
    }
}
