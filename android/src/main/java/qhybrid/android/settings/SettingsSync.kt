package qhybrid.android.settings

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP16g — narrow, injectable seam for the LIVE settings commands the Settings screen can push to
 * the watch (mirrors WP16b's `AlarmSync`, WP16c's `NotificationSync`, WP16d's
 * [qhybrid.android.buttons.ButtonSync], WP16e's [qhybrid.android.calibration.CalibrationSync], and
 * WP16f's [qhybrid.android.sleep.ActivitySource]) so [SettingsViewModel] is unit-testable with a
 * fake (no Android service, no BLE).
 *
 * **DATA-SOURCE DECISION — which settings are live commands.** Three of the Settings values are
 * *live watch commands* (in addition to being persisted app-side):
 *   - **vibration strength** → `FossilController.setVibrationStrength(strength)`
 *     (config 0x0A — `VibrationStrengthConfigItem`),
 *   - **inactivity nudge** → `FossilController.setInactivityNudge(...)` (config 0x09 —
 *     `InactivityWarningItem`),
 *   - **second timezone** → `FossilController.setSecondTimezone(offsetMinutes)` (config 0x11 —
 *     `TimezoneOffsetConfigItem`).
 *
 * **DEFERRED (on-device-pending — WP14).** The protocol helpers ALREADY exist on
 * `qhybrid.protocol.FossilController` / `FossilQAdapter` (and are golden-tested in the protocol
 * layer), but they are NOT yet exposed via the WP3 [WatchConnectionService] static entry points.
 * Wiring those `ConfigurationPutRequest` items through the foreground service as new BLE actions is
 * its own work package (WP14, the same WP that wires the alarm/notification/button uploads).
 * Until then this seam ONLY pokes the existing service (`syncNow`) — no new wire bytes are
 * invented — and reports [SETTINGS_WIRED] = `false` so the UI can flag the live apply as
 * on-device-pending. The persisted values are saved regardless (WatchRepository / [SettingsPrefs]),
 * so flipping [SETTINGS_WIRED] to true later applies the already-stored prefs.
 *
 * The **preferred music app** is intentionally NOT here: it is a pure phone-side pref (the
 * music-control fallback launches it locally, ANDROID-PLAN §4.E); it is never sent to the watch.
 * **Settings transfer** is likewise not here: it is a WP4 DB operation
 * ([qhybrid.android.db.WatchRepository.transferSettings]), not a live command.
 */
interface SettingsSync {
    /**
     * Push the vibration strength (0–100) to the watch LIVE. Returns whether the real command
     * pipeline is wired yet (`false` until WP14; the UI surfaces an "on-device-pending" note).
     */
    fun applyVibrationStrength(strength: Int): Boolean

    /**
     * Push the inactivity-nudge config (enabled + duration minutes) to the watch LIVE. Returns
     * whether the real command pipeline is wired yet (`false` until WP14).
     */
    fun applyNudge(enabled: Boolean, minutes: Int): Boolean

    /**
     * Push the second-timezone offset (minutes from UTC) to the watch LIVE. Returns whether the
     * real command pipeline is wired yet (`false` until WP14).
     */
    fun applySecondTimezone(offsetMinutes: Int): Boolean
}

/**
 * Production [SettingsSync] — forwards to the WP3 service's existing `syncNow` entry point and
 * reports [SETTINGS_WIRED] = `false`. Holds the application context so it never leaks an Activity.
 *
 * It deliberately adds **NO new BLE/protocol behavior**: the live `ConfigurationPutRequest`
 * commands (vibration strength / inactivity nudge / second timezone) are WP14 (see [SettingsSync]).
 * Until then each apply just pokes the existing service path; no settings bytes are written to the
 * device, none invented.
 */
class ServiceSettingsSync(context: Context) : SettingsSync {
    private val appContext = context.applicationContext

    override fun applyVibrationStrength(strength: Int): Boolean = poke()
    override fun applyNudge(enabled: Boolean, minutes: Int): Boolean = poke()
    override fun applySecondTimezone(offsetMinutes: Int): Boolean = poke()

    private fun poke(): Boolean {
        // Poke the existing sync-on-connect path. The dedicated live settings commands
        // (FossilController.setVibrationStrength / setInactivityNudge / setSecondTimezone via the
        // WP3 service) are WP14 and not added here (no new wire behavior, no invented bytes).
        WatchConnectionService.syncNow(appContext)
        return SETTINGS_WIRED
    }

    companion object {
        /** Flip to true when WP14 wires the live settings ConfigurationPutRequest commands. */
        const val SETTINGS_WIRED = false
    }
}
