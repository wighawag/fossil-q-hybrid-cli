package qhybrid.android.buttons

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP16d — narrow, injectable seam for the "Save to watch" intent (mirrors WP16b's `AlarmSync`
 * and WP16c's `NotificationSync`) so [ButtonsViewModel] is unit-testable with a fake (no
 * Android service, no BLE).
 *
 * The production impl ([ServiceButtonSync]) forwards to the existing WP3
 * [WatchConnectionService.syncNow] static entry point — it adds **NO new BLE/protocol
 * behavior** (WP16d constraint).
 *
 * **DEFERRED (on-device-pending):** the *actual* button-config upload pipeline (compile the
 * per-button rows with WP7 [qhybrid.protocol.requests.fossil.button.ButtonCompiler] /
 * [qhybrid.protocol.ButtonConfigBuilder] and push the SETTINGS_BUTTONS (0x0600) file over
 * BLE) is **WP14**. Until then "Save to watch" persists to Room (done by the ViewModel
 * intents) and pokes the service's existing sync-on-connect path; the button config is NOT
 * yet written to the device hardware.
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
        // Re-run the existing sync-on-connect operations. The dedicated button-config upload
        // (WP7 compile → BLE write of the 0x0600 file) is WP14 and not added here (no new
        // wire behavior).
        WatchConnectionService.syncNow(appContext)
        return BUTTON_UPLOAD_WIRED
    }

    companion object {
        /** Flip to true when WP14 wires the real button-config upload pipeline. */
        const val BUTTON_UPLOAD_WIRED = false
    }
}
