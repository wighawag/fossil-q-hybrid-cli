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
 * **DEFERRED (on-device-pending):** the *actual* filter-byte upload pipeline (compile the
 * per-app rows with WP6 [qhybrid.protocol.requests.fossil.notification.NotificationCompiler]
 * and push the 32-byte-per-entry filter file over BLE) is **WP14**. Until then "Save to
 * watch" persists to Room (done by the ViewModel intents) and pokes the service's existing
 * sync-on-connect path; the filter is NOT yet written to the device hardware.
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
        // Re-run the existing sync-on-connect operations. The dedicated notification-filter
        // upload (WP6 compile → BLE write) is WP14 and not added here (no new wire behavior).
        WatchConnectionService.syncNow(appContext)
        return FILTER_UPLOAD_WIRED
    }

    companion object {
        /** Flip to true when WP14 wires the real filter-byte upload pipeline. */
        const val FILTER_UPLOAD_WIRED = false
    }
}
