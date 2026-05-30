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
     * The result of one calendar read. Crucially distinguishes a genuine **empty calendar**
     * ([ok] with no events) from a **failed/unavailable read** (provider error, query exception,
     * permission lost). This matters because [CalendarRefresher] does a DESTRUCTIVE full-replace of
     * slots 16–31 from the events — so it must NEVER wipe existing alarms just because a transient
     * read returned nothing.
     */
    data class Read(val ok: Boolean, val events: List<CalendarEvent>) {
        companion object {
            /** A successful read (possibly with zero events). */
            fun success(events: List<CalendarEvent>) = Read(ok = true, events = events)
            /** A FAILED read — the caller must NOT treat this as "no events". */
            val FAILED = Read(ok = false, events = emptyList())
        }
    }

    /**
     * The user's events starting in `[now, now + windowDays)` across their visible calendars,
     * as plain `(title, startEpochMillis)` pairs ready for the pure WP9
     * [qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper]. All-day events are excluded
     * by the production impl (no meaningful wall-clock alarm time).
     *
     * Returns a [Read] so a FAILED read (provider error / cursor null) is distinguishable from a
     * genuinely empty calendar — see [Read].
     */
    fun readUpcoming(nowEpochMillis: Long, windowDays: Int): Read
}

/**
 * A fake [CalendarSource] returning a fixed [events] list (default empty), used as the VM/test
 * default so nothing touches the real provider. The [nowEpochMillis]/[windowDays] are ignored —
 * the window filter is applied downstream by the pure WP9 mapper.
 */
class FakeCalendarSource(
    private val events: List<CalendarEvent> = emptyList(),
    /** When false, every read reports a FAILED [CalendarSource.Read] (no events, ok=false). */
    private val ok: Boolean = true,
) : CalendarSource {
    override fun readUpcoming(nowEpochMillis: Long, windowDays: Int): CalendarSource.Read =
        if (ok) CalendarSource.Read.success(events) else CalendarSource.Read.FAILED
}
