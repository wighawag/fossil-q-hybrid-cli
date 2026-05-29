package qhybrid.android.alarms

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP16b — narrow, injectable seam for the "Save to watch" intent (mirrors WP16a's
 * `WatchActions`) so [AlarmsViewModel] is unit-testable with a fake (no Android service,
 * no BLE).
 *
 * The production impl ([ServiceAlarmSync]) forwards to the existing WP3
 * [WatchConnectionService.syncNow] static entry point — it adds **NO new BLE/protocol
 * behavior** (WP16b constraint).
 *
 * **DEFERRED (on-device-pending):** the *actual* alarm-byte upload pipeline (compile the
 * slot-0–15 rows with WP5 [qhybrid.protocol.requests.fossil.alarm.AlarmCompiler] and push
 * the alarm file over BLE) is **WP14**. Until then "Save to watch" persists to Room (done
 * by the ViewModel intents) and pokes the service's existing sync-on-connect path; the
 * alarms are NOT yet written to the device hardware.
 */
interface AlarmSync {
    /**
     * Persist-then-push: the ViewModel has already written the alarm rows to Room; this
     * triggers the service so the (WP14) upload pipeline will pick them up. Returns whether
     * the byte upload is actually wired yet (false until WP14).
     */
    fun saveToWatch(): Boolean
}

/**
 * Production [AlarmSync] — forwards to the WP3 service's existing `syncNow` entry point.
 * Holds the application context so it never leaks an Activity.
 */
class ServiceAlarmSync(context: Context) : AlarmSync {
    private val appContext = context.applicationContext

    override fun saveToWatch(): Boolean {
        // Re-run the existing sync-on-connect operations. The dedicated alarm-file upload
        // (WP5 compile → BLE write) is WP14 and not added here (no new wire behavior).
        WatchConnectionService.syncNow(appContext)
        return ALARM_UPLOAD_WIRED
    }

    companion object {
        /** Flip to true when WP14 wires the real alarm-byte upload pipeline. */
        const val ALARM_UPLOAD_WIRED = false
    }
}
