package qhybrid.android.sync

import qhybrid.android.db.WatchRepository
import qhybrid.android.settings.SettingsPrefs

/**
 * WP14 — assembles a [SyncInput] for the active watch by reading the WP4 Room rows + the WP16g
 * app settings. This is the (suspending) DB-reading bridge between Room and the pure
 * [SyncOrchestrator]; keeping it here means the orchestrator stays a pure function of [SyncInput]
 * and is unit-testable without coroutines/Room.
 *
 * Slot split (WP5 16/16): alarms with `slotId` in 0..15 go to [SyncInput.alarms]; 16..31 go to
 * [SyncInput.calendarAlarms]. Settings are taken from the active watch row (vibration strength)
 * and the app prefs (nudge / second timezone). Returns a no-watch input when none is active.
 */
class SyncDataLoader(
    private val repo: WatchRepository,
    private val prefs: SettingsPrefs,
) {

    /** Read the active watch's full configuration into a [SyncInput]. */
    suspend fun load(): SyncInput {
        val watch = repo.getActiveWatch()
            ?: return SyncInput(watch = null)
        val mac = watch.macAddress

        val allAlarms = repo.getAlarms(mac)
        val standard = allAlarms.filter { it.slotId in 0..15 }
        val calendar = allAlarms.filter { it.slotId in 16..31 }
        val rules = repo.getRules(mac)
        val buttons = repo.getButtons(mac)

        val app = prefs.get()
        val settings = SyncSettings(
            vibrationStrength = watch.vibrationStrength,
            nudgeEnabled = app.nudgeEnabled,
            // Only push a nudge command when the feature is enabled; otherwise leave it out so a
            // sync pass doesn't keep re-applying an "off" default. (The persisted pref is kept.)
            nudgeMinutes = if (app.nudgeEnabled) app.nudgeMinutes else null,
            secondTimezoneOffsetMinutes = app.secondTimezoneOffsetMinutes,
        )

        return SyncInput(
            watch = watch,
            alarms = standard,
            calendarAlarms = calendar,
            rules = rules,
            buttons = buttons,
            settings = settings,
        )
    }
}
