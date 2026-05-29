package qhybrid.android.buttons

import android.content.Context
import qhybrid.android.sync.ServiceSaveToWatch

/**
 * WP16d — narrow, injectable seam for the "Save to watch" intent (mirrors WP16b's `AlarmSync`
 * and WP16c's `NotificationSync`) so [ButtonsViewModel] is unit-testable with a fake (no
 * Android service, no BLE).
 *
 * The production impl ([ServiceButtonSync]) forwards to the existing WP3
 * [WatchConnectionService.syncNow] static entry point — it adds **NO new BLE/protocol
 * behavior** (WP16d constraint).
 *
 * **WIRED (WP14):** the button-config upload pipeline is now live — "Save to watch" persists to
 * Room (the ViewModel intents) then triggers [WatchConnectionService.syncNow], which runs the
 * WP14 SyncOrchestrator: it compiles the per-button rows with WP7
 * [qhybrid.protocol.requests.fossil.button.ButtonCompiler] / [qhybrid.protocol.ButtonConfigBuilder]
 * and pushes the SETTINGS_BUTTONS (0x0600) file over BLE via the WP3 service's ble-worker.
 * [BUTTON_UPLOAD_WIRED] is `true`. (The on-device BLE effect is verified-pending hardware; the
 * compile + action/dial-mode mapping logic is unit-tested in the `sync` package.)
 */
interface ButtonSync {
    /**
     * Persist-then-push: the ViewModel has already written the button-mapping rows to Room;
     * this triggers the service so the (WP14) upload pipeline will pick them up. Returns
     * whether the button-config upload is actually wired yet (false until WP14).
     */
    fun saveToWatch(): Boolean
}

/**
 * Production [ButtonSync] — forwards to the WP3 service's existing `syncNow` entry point.
 * Holds the application context so it never leaks an Activity.
 */
class ServiceButtonSync(context: Context) : ButtonSync {
    private val appContext = context.applicationContext

    override fun saveToWatch(): Boolean {
        // WP14: drives the SyncOrchestrator (WP7 compile → BLE 0x0600 file write) on the service's
        // ble-worker; rows are already persisted to Room by the intents.
        // WP-SYNCFIX: publish SYNCING immediately (spinner shows at once) + connect-then-sync.
        return ServiceSaveToWatch.trigger(appContext) && BUTTON_UPLOAD_WIRED
    }

    companion object {
        /** WP14: the real button-config upload pipeline is wired (via syncNow → SyncOrchestrator). */
        const val BUTTON_UPLOAD_WIRED = true
    }
}
