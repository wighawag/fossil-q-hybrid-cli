package qhybrid.android.calendar

import qhybrid.android.db.WatchAlarmEntity
import qhybrid.protocol.requests.fossil.alarm.AlarmSlot
import qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper
import qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper.CalendarEvent
import java.time.ZoneId

/**
 * WP13 — the **pure** calendar→rows core. Given the active watch mac + the event list + the
 * injected `now`/`ZoneId`, it drives the golden-tested WP9
 * [CalendarAlarmMapper.mapEventsToAlarmSlots] and maps each emitted [AlarmSlot] to a Room
 * [WatchAlarmEntity] re-keyed to the active mac.
 *
 * **No Room, no Android, no `ContentObserver`, no BLE** — this is a deterministic function of its
 * inputs, so it is fully JVM-unit-testable. The provider read ([CalendarSource]) and the Room write
 * ([qhybrid.android.db.WatchRepository.replaceCalendarAlarms]) are the impure parts done by
 * [CalendarRefresher].
 *
 * **Slot ownership.** WP9 assigns slots **16, 17, …** (the calendar-owned range). The user-owned
 * standard alarms (0–15, the WP16b Alarms screen) are NEVER produced here. `updatedAt` is left at
 * its default `0` — the repository write path stamps it (the single chokepoint per WP-SYNCSTATUS).
 */
object CalendarAlarmSync {

    /**
     * Map [events] to the calendar-owned alarm rows (slots 16–31) for [mac].
     *
     * @param mac            the active watch mac (re-keyed onto each row; normalized upper-case)
     * @param events         the upcoming calendar events (may be empty)
     * @param nowEpochMillis the injected "now" (UTC epoch millis); window start
     * @param zone           the local zone for weekday/hour/minute computation
     * @return up to 16 non-repeating, enabled rows in slots 16, 17, … (empty when no events map)
     */
    fun mapEventsToRows(
        mac: String,
        events: List<CalendarEvent>,
        nowEpochMillis: Long,
        zone: ZoneId,
    ): List<WatchAlarmEntity> {
        val normalized = mac.uppercase()
        return CalendarAlarmMapper
            .mapEventsToAlarmSlots(events, nowEpochMillis, zone)
            .map { slot -> slot.toEntity(normalized) }
    }

    /** Pure [AlarmSlot] → [WatchAlarmEntity] mapping (re-key to [mac]; `updatedAt` left to repo). */
    private fun AlarmSlot.toEntity(mac: String): WatchAlarmEntity =
        WatchAlarmEntity(
            watchMac = mac,
            slotId = slotId,
            hour = hour,
            minute = minute,
            isEnabled = isEnabled,
            daysMask = daysMask,
            isRepeating = isRepeating,
            label = label,
            // updatedAt deliberately left at its default 0 — WatchRepository stamps it on write.
        )
}
