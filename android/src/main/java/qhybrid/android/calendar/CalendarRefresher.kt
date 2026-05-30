package qhybrid.android.calendar

import qhybrid.android.db.WatchAlarmEntity
import qhybrid.android.db.WatchRepository
import java.time.ZoneId

/**
 * WP13 (Step 2) — the refresh orchestrator: read the user's calendar via the [CalendarSource] seam,
 * map to calendar-owned rows (slots 16–31) via the pure [CalendarAlarmSync] (WP9), then full-replace
 * those rows in Room via [WatchRepository.replaceCalendarAlarms].
 *
 * It is **fake-seam testable**: inject a [FakeCalendarSource] + an in-memory [WatchRepository] +
 * fixed `now`/`zone`, run [refresh], and assert Room ends with exactly the mapped slots 16–31. The
 * only Android-specific part is the production [SystemCalendarSource] passed in by the shell.
 *
 * **Change detection.** [refresh] reports whether the calendar rows actually changed (compared by
 * the wire-relevant fields, ignoring `updatedAt`). Step 3 uses this to decide whether to fire a
 * SILENT `ALARMS_ONLY` push — a no-op refresh (calendar unchanged) must not poke BLE.
 */
class CalendarRefresher(
    private val repo: WatchRepository,
    private val source: CalendarSource,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val now: () -> Long = System::currentTimeMillis,
    private val windowDays: Int = WINDOW_DAYS,
    // WP13 — how many minutes BEFORE each event the watch alarm rings (0 = at event time). Read
    // lazily each refresh so a Settings change takes effect on the next refresh without re-wiring.
    private val offsetMinutes: () -> Int = { 0 },
) {

    /** The outcome of one [refresh]: did the calendar rows actually change, and how many. */
    data class Result(val changed: Boolean, val rowCount: Int) {
        companion object {
            /** No active watch / nothing to do. */
            val NONE = Result(changed = false, rowCount = 0)
        }
    }

    /**
     * Read → map → full-replace slots 16–31 for the active watch. Returns whether the rows changed.
     * No-ops (returns [Result.NONE]) when there is no active watch.
     */
    suspend fun refresh(): Result {
        val mac = repo.getActiveWatch()?.macAddress ?: return Result.NONE
        val nowMillis = now()
        val events = source.upcomingEvents(nowMillis, windowDays)
        val newRows = CalendarAlarmSync.mapEventsToRows(mac, events, nowMillis, zone(), offsetMinutes())

        val existing = repo.getAlarms(mac).filter { it.slotId in 16..31 }
        val changed = !sameCalendarRows(existing, newRows)
        if (changed) {
            repo.replaceCalendarAlarms(mac, newRows)
        }
        return Result(changed = changed, rowCount = newRows.size)
    }

    /**
     * [refresh], then SILENTLY push the alarm file to the watch via [push] **only if the rows
     * actually changed** — a no-op refresh (calendar unchanged) must not poke BLE. This is the
     * single entry point the Step-3 [ContentObserver] / on-connect / on-grant callbacks drive
     * (debounced by the shell). Returns the [Result] so callers can log/observe.
     */
    suspend fun refreshAndMaybePush(push: CalendarPush): Result {
        val result = refresh()
        if (result.changed) {
            push.pushAlarmsSilently()
        }
        return result
    }

    private companion object {
        /** Mirror of WP9 `CalendarAlarmMapper.WINDOW_DAYS` (kept local to avoid a wire-class dep). */
        private const val WINDOW_DAYS = 7

        /**
         * Compare two calendar-row lists by the fields that affect the watch (slot/time/days/
         * enabled/repeating/label), ignoring `watchMac` (already re-keyed) and `updatedAt` (a write
         * timestamp, not content). Order-sensitive — both are slot-ordered (WP9 + DAO ORDER BY).
         */
        private fun sameCalendarRows(a: List<WatchAlarmEntity>, b: List<WatchAlarmEntity>): Boolean {
            if (a.size != b.size) return false
            val sa = a.sortedBy { it.slotId }
            val sb = b.sortedBy { it.slotId }
            return sa.zip(sb).all { (x, y) ->
                x.slotId == y.slotId &&
                    x.hour == y.hour &&
                    x.minute == y.minute &&
                    x.isEnabled == y.isEnabled &&
                    x.daysMask == y.daysMask &&
                    x.isRepeating == y.isRepeating &&
                    x.label == y.label
            }
        }
    }
}
