package qhybrid.android.calibration

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP16e — narrow, injectable seam for the live calibration commands (mirrors WP16b's `AlarmSync`,
 * WP16c's `NotificationSync`, and WP16d's [qhybrid.android.buttons.ButtonSync]) so
 * [CalibrationViewModel] is unit-testable with a fake (no Android service, no BLE).
 *
 * **CALIBRATION IS EPHEMERAL — NOTHING IS PERSISTED.** Unlike the other screens, calibration is a
 * physical "set zero = current hand position" handshake, NOT stored data: the meaningful value is
 * a LIVE delta relative to where the hands physically are this instant. There is therefore NO Room
 * entity / DAO / repository method, and "Apply" is a fire-and-forget LIVE command, not a save.
 *
 * **DEFERRED (on-device-pending — WP14 / WP F).** The *actual* move-hands / save-calibration
 * pipeline (`FossilQAdapter.requestHandsControl` → `setHands(hour,min,sub)` → `saveCalibration`
 * → `releaseHandsControl` → `syncTime`, the same sequence the CLI `calibrate` command runs) is
 * NOT yet exposed via the WP3 [WatchConnectionService] static entry points. The protocol helpers
 * exist on `qhybrid.protocol.FossilQAdapter`, but wiring them through the foreground service as a
 * new BLE action is its own work package. Until then this seam ONLY pokes the existing service
 * (no new wire bytes are invented) and reports the [CALIBRATION_WIRED] flag as `false` so the UI
 * can flag the apply path as on-device-pending.
 */
interface CalibrationSync {
    /**
     * Fire-and-forget LIVE apply of the current calibration session (move-hands + save-calibration
     * handshake). Returns whether the real move-hands/save-calibration pipeline is actually wired
     * yet (`false` until WP14 / WP F; the UI surfaces an "on-device-pending" note when `false`).
     *
     * @param hourDegrees  the hour-hand offset to apply (0–359), or null to leave it untouched.
     * @param minuteDegrees the minute-hand offset to apply (0–359), or null to leave it untouched.
     * @param subDegrees   the sub-eye offset to apply (0–359), or null to leave it untouched.
     */
    fun apply(hourDegrees: Int?, minuteDegrees: Int?, subDegrees: Int?): Boolean
}

/**
 * Production [CalibrationSync] — forwards to the WP3 service's existing `syncNow` entry point.
 * Holds the application context so it never leaks an Activity.
 *
 * It deliberately adds **NO new BLE/protocol behavior**: the move-hands / save-calibration command
 * sequence is WP14 / WP F (see [CalibrationSync]). Until then "Apply" just pokes the existing
 * service path; no calibration bytes are written to the device.
 */
class ServiceCalibrationSync(context: Context) : CalibrationSync {
    private val appContext = context.applicationContext

    override fun apply(hourDegrees: Int?, minuteDegrees: Int?, subDegrees: Int?): Boolean {
        // Poke the existing sync-on-connect path. The dedicated calibration handshake
        // (requestHandsControl → setHands → saveCalibration → releaseHandsControl → syncTime,
        // via the WP3 service) is WP14 / WP F and not added here (no new wire behavior, no
        // invented bytes). Calibration is ephemeral, so there is nothing to persist either.
        WatchConnectionService.syncNow(appContext)
        return CALIBRATION_WIRED
    }

    companion object {
        /** Flip to true when WP14 / WP F wires the real move-hands/save-calibration pipeline. */
        const val CALIBRATION_WIRED = false
    }
}
