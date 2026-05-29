package qhybrid.android.notifications

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP16c — narrow, injectable seam for the "Save to watch" intent (mirrors WP16b's
 * `AlarmSync`) so [NotificationsViewModel] is unit-testable with a fake (no Android
 * service, no BLE).
 *
 * The production impl ([ServiceNotificationSync]) forwards to the existing WP3
 * [WatchConnectionService.syncNow] static entry point — it adds **NO new BLE/protocol
 * behavior** (WP16c constraint).
 *
 * **WIRED (WP14):** the filter-byte upload pipeline is now live — "Save to watch" persists to
 * Room (the ViewModel intents) then triggers [WatchConnectionService.syncNow], which runs the
 * WP14 SyncOrchestrator: it compiles the per-app rows with WP6
 * [qhybrid.protocol.requests.fossil.notification.NotificationCompiler] (32-byte-per-entry) and
 * pushes the filter file over BLE via the WP3 service's ble-worker. [FILTER_UPLOAD_WIRED] is
 * `true`. (The on-device BLE effect is verified-pending hardware; the compile logic is
 * unit-tested in the `sync` package.)
 */
interface NotificationSync {
    /**
     * Persist-then-push: the ViewModel has already written the notification rows to Room;
     * this triggers the service so the (WP14) upload pipeline will pick them up. Returns
     * whether the filter-byte upload is actually wired yet (false until WP14).
     */
    fun saveToWatch(): Boolean
}

/**
 * Production [NotificationSync] — forwards to the WP3 service's existing `syncNow` entry
 * point. Holds the application context so it never leaks an Activity.
 */
class ServiceNotificationSync(context: Context) : NotificationSync {
    private val appContext = context.applicationContext

    override fun saveToWatch(): Boolean {
        // WP14: syncNow now drives the SyncOrchestrator (WP6 compile → BLE filter-file write)
        // on the service's ble-worker. The rows are already persisted to Room by the intents.
        WatchConnectionService.syncNow(appContext)
        return FILTER_UPLOAD_WIRED
    }

    companion object {
        /** WP14: the real filter-byte upload pipeline is wired (via syncNow → SyncOrchestrator). */
        const val FILTER_UPLOAD_WIRED = true
    }
}
