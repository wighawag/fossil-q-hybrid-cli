package qhybrid.android.calendar

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper.CalendarEvent

/**
 * WP13 (Step 2) — the production [CalendarSource]: reads the user's upcoming events from the
 * Android calendar provider via `CalendarContract.Instances`.
 *
 * **Why `Instances` (not raw `Events`):** `Instances.query(...)` expands recurring events into the
 * concrete dated occurrences that fall inside a time range — exactly the `[now, now + windowDays)`
 * window the WP9 mapper wants. Querying raw `Events` would require expanding RRULEs by hand.
 *
 * **All-day events are skipped.** An all-day event's `BEGIN` is midnight UTC, which maps to an
 * arbitrary local hour — there is no meaningful wall-clock alarm time, and the watch alarm is a
 * wall-clock minute/hour. So `Instances.ALL_DAY = 1` rows are dropped.
 *
 * Requires `READ_CALENDAR` (a normal runtime permission; see [CalendarAccess]); the caller must
 * gate the read on the grant. Reads MUST happen off the main thread (the cursor query blocks). The
 * read is app-only — it never touches BLE or any wire bytes.
 */
class SystemCalendarSource(context: Context) : CalendarSource {

    private val appContext = context.applicationContext

    override fun upcomingEvents(nowEpochMillis: Long, windowDays: Int): List<CalendarEvent> {
        val windowEnd = nowEpochMillis + windowDays.toLong() * MILLIS_PER_DAY
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(nowEpochMillis.toString())
            .appendPath(windowEnd.toString())
            .build()

        val out = ArrayList<CalendarEvent>()
        val cursor: Cursor? = runCatching {
            appContext.contentResolver.query(
                uri,
                PROJECTION,
                /* selection */ null,
                /* selectionArgs */ null,
                /* sortOrder */ "${CalendarContract.Instances.BEGIN} ASC",
            )
        }.onFailure { Log.w(TAG, "calendar query failed", it) }.getOrNull()

        cursor?.use { c ->
            val idxTitle = c.getColumnIndex(CalendarContract.Instances.TITLE)
            val idxBegin = c.getColumnIndex(CalendarContract.Instances.BEGIN)
            val idxAllDay = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            while (c.moveToNext()) {
                // Skip all-day events — no meaningful wall-clock alarm time.
                if (idxAllDay >= 0 && c.getInt(idxAllDay) == 1) continue
                val begin = if (idxBegin >= 0) c.getLong(idxBegin) else continue
                val title = if (idxTitle >= 0) c.getString(idxTitle) else null
                out.add(CalendarEvent(title ?: "", begin))
            }
        }
        Log.i(TAG, "calendar read: ${out.size} timed event(s) in window")
        return out
    }

    private companion object {
        private const val TAG = "FossilQ-CalendarSrc"
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        private val PROJECTION = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )
    }
}
