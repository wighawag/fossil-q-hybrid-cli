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
 * **WIRED (WP14).** The protocol helpers (`FossilController.setVibrationStrength` /
 * `setInactivityNudge` / `setSecondTimezone` → `ConfigurationPutRequest` items 0x0A / 0x09 / 0x11)
 * are now driven through the WP3 [WatchConnectionService] on its ble-worker by the WP14
 * SyncOrchestrator. The flow is **persist-then-sync**: the [SettingsViewModel] writes the value
 * (vibration → the WP4 [qhybrid.android.db.WatchRepository] row; nudge / second timezone →
 * [SettingsPrefs]) and then this seam pokes `syncNow`, which reloads the active watch's config
 * (including the just-saved value) and applies the live `ConfigurationPutRequest` commands. A
 * single live apply path covers all three; [SETTINGS_WIRED] is `true`. (On-device BLE effect is
 * verified-pending hardware; the apply logic is unit-tested in the `sync` package.) No wire bytes
 * are invented — the golden-tested façade settings methods are reused.
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
        // WP14: syncNow now drives the SyncOrchestrator, which applies the just-persisted
        // vibration / nudge / second-timezone values live via the golden-tested façade settings
        // methods on the WP3 service's ble-worker (no new wire behavior, no invented bytes).
        WatchConnectionService.syncNow(appContext)
        return SETTINGS_WIRED
    }

    companion object {
        /** WP14: the live settings ConfigurationPutRequest commands are wired (syncNow → SyncOrchestrator). */
        const val SETTINGS_WIRED = true
    }
}
