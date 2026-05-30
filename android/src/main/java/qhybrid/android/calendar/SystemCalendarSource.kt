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
 * **Why `Instances.query(...)` and NOT a raw `CONTENT_URI/<begin>/<end>` cursor:** the provider's
 * `Instances` table is expanded **lazily/asynchronously** — a freshly-added (or server-synced)
 * event lands in the `Events` table immediately but its expanded occurrences can lag the
 * `Instances` table by MINUTES. `CalendarContract.Instances.query(...)` is documented to **trigger
 * the instance expansion for the requested range**, so reading through it makes the new occurrences
 * materialize on-demand instead of returning a stale/empty range. This is the fix for "a just-added
 * Sunday event didn't get an alarm until ~5 min later" — the raw-URI cursor read the not-yet-
 * expanded table.
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

        val out = ArrayList<CalendarEvent>()
        // Instances.query(...) FORCES the provider to expand the [begin, end) range on demand
        // (vs. a raw CONTENT_URI cursor which can read a stale, not-yet-expanded table). This is
        // what makes a just-added / just-synced event show up immediately instead of minutes later.
        val cursor: Cursor? = runCatching {
            CalendarContract.Instances.query(
                appContext.contentResolver,
                PROJECTION,
                /* begin */ nowEpochMillis,
                /* end */ windowEnd,
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
