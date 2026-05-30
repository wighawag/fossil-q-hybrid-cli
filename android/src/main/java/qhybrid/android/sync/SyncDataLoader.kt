package qhybrid.android.sync

import qhybrid.android.alarms.AlarmExpiry
import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.settings.SettingsPrefs
import java.time.ZoneId

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
    // Injected so the "has this one-off already fired?" suppression is deterministic/testable.
    // Production uses the wall clock + the device's zone.
    private val now: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    /** Read the active watch's full configuration into a [SyncInput]. */
    suspend fun load(): SyncInput {
        val watch = repo.getActiveWatch()
            ?: return SyncInput(watch = null)
        val mac = watch.macAddress

        val nowMillis = now()
        val z = zone()
        // A one-off (plain or single-weekday) whose single occurrence has already passed must NOT be
        // re-uploaded as active: the watch already fired+dropped it, so re-arming it would make it
        // fire AGAIN. We derive this LIVE from updatedAt + now (no Room mutation, so no spurious
        // pending-sync and no race: every sync recomputes it against the current clock) and mark the
        // alarm disabled IN THE INPUT ONLY — AlarmCompiler already drops disabled alarms from the
        // wire bytes. Repeating alarms are never suppressed. Mirrors the Alarms screen's display.
        val allAlarms = repo.getAlarms(mac).map { it.suppressIfPassedOneOff(nowMillis, z) }
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

    /**
     * If [this] is a one-off whose firing time has passed, return a copy with `isEnabled = false`
     * (so the compiler drops it from the upload); otherwise return it unchanged. The Room row is
     * never written — this only affects the bytes sent to the watch this pass.
     */
    private fun WatchAlarmEntity.suppressIfPassedOneOff(nowMillis: Long, zone: ZoneId): WatchAlarmEntity =
        // Use a conservative grace margin so we never disable a one-off the WATCH hasn't fired yet
        // when the phone clock runs ahead of the watch's (clock skew). Re-arming a one-off slightly
        // late is recoverable; silently killing a valid alarm is not.
        if (isEnabled && AlarmExpiry.hasPassed(this, nowMillis, zone, AlarmExpiry.UPLOAD_SUPPRESS_GRACE_MS))
            copy(isEnabled = false) else this
}
