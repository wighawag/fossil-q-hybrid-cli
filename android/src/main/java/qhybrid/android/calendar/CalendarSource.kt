package qhybrid.android.calendar

import qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper.CalendarEvent

/**
 * WP13 — narrow, injectable seam for "read the user's upcoming calendar events" (mirrors the
 * WP11 [qhybrid.android.notifications.NotificationPlay] / WP16c
 * [qhybrid.android.notifications.InstalledAppsProvider] seam pattern). Splitting the read behind
 * this interface keeps the **map-and-persist** glue ([CalendarAlarmSync] / [CalendarRefresher])
 * unit-testable with a fake — no real `CalendarContract`, no `ContentObserver`, no Android.
 *
 * The production impl ([SystemCalendarSource]) queries `CalendarContract.Instances`; the test/VM
 * default ([FakeCalendarSource]) returns a canned list.
 */
interface CalendarSource {
    /**
     * The user's events starting in `[now, now + windowDays)` across their visible calendars,
     * as plain `(title, startEpochMillis)` pairs ready for the pure WP9
     * [qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper]. All-day events are excluded
     * by the production impl (no meaningful wall-clock alarm time). May be empty.
     */
    fun upcomingEvents(nowEpochMillis: Long, windowDays: Int): List<CalendarEvent>
}

/**
 * A fake [CalendarSource] returning a fixed [events] list (default empty), used as the VM/test
 * default so nothing touches the real provider. The [nowEpochMillis]/[windowDays] are ignored —
 * the window filter is applied downstream by the pure WP9 mapper.
 */
class FakeCalendarSource(
    private val events: List<CalendarEvent> = emptyList(),
) : CalendarSource {
    override fun upcomingEvents(nowEpochMillis: Long, windowDays: Int): List<CalendarEvent> = events
}
