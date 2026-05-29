package qhybrid.android.alarms

import android.content.Context
import qhybrid.android.sync.ServiceSaveToWatch

/**
 * WP16b — narrow, injectable seam for the "Save to watch" intent (mirrors WP16a's
 * `WatchActions`) so [AlarmsViewModel] is unit-testable with a fake (no Android service,
 * no BLE).
 *
 * The production impl ([ServiceAlarmSync]) forwards to the existing WP3
 * [WatchConnectionService.syncNow] static entry point — it adds **NO new BLE/protocol
 * behavior** (WP16b constraint).
 *
 * **WIRED (WP14):** the alarm-byte upload pipeline is now live — "Save to watch" persists to
 * Room (the ViewModel intents) then triggers [WatchConnectionService.syncNow], which runs the
 * WP14 SyncOrchestrator: it compiles the slot-0–15 rows with WP5
 * [qhybrid.protocol.requests.fossil.alarm.AlarmCompiler] and pushes the alarm file over BLE via
 * the WP3 service's ble-worker. [ALARM_UPLOAD_WIRED] is `true`. (The on-device BLE effect is
 * verified-pending hardware; the compile+order logic is unit-tested in the `sync` package.)
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
        // WP14: drives the SyncOrchestrator (WP5 compile → BLE alarm-file write) on the service's
        // ble-worker; rows are already persisted to Room by the intents.
        // WP-SYNCFIX: publish SYNCING immediately (spinner shows at once) + connect-then-sync.
        return ServiceSaveToWatch.trigger(appContext) && ALARM_UPLOAD_WIRED
    }

    companion object {
        /** WP14: the real alarm-byte upload pipeline is wired (via syncNow → SyncOrchestrator). */
        const val ALARM_UPLOAD_WIRED = true
    }
}
