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
 * **Read via the `Instances` range URI (`CONTENT_URI/<begin>/<end>`).** This is the original,
 * field-proven query. (An earlier attempt to use `Instances.query(...)` as the primary path to
 * force eager expansion regressed real devices — it could return a usable-looking cursor whose
 * columns didn't resolve, yielding 0 events for a calendar that genuinely had events — so we keep
 * the range-URI cursor as primary. The lazy-expansion delay is tolerated; the on-change observer +
 * connect refresh re-read once the provider catches up.)
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

    override fun readUpcoming(nowEpochMillis: Long, windowDays: Int): CalendarSource.Read {
        val windowEnd = nowEpochMillis + windowDays.toLong() * MILLIS_PER_DAY
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(nowEpochMillis.toString())
            .appendPath(windowEnd.toString())
            .build()

        // The original, field-proven query: a range-bounded Instances cursor. runCatching captures
        // a provider failure as a FAILED read (so the caller never wipes existing alarms for it).
        var failed = false
        val cursor: Cursor? = runCatching {
            appContext.contentResolver.query(
                uri,
                PROJECTION,
                /* selection */ null,
                /* selectionArgs */ null,
                /* sortOrder */ "${CalendarContract.Instances.BEGIN} ASC",
            )
        }.onFailure {
            Log.w(TAG, "calendar query failed", it)
            failed = true
        }.getOrNull()

        // A query EXCEPTION is a failed read — do NOT report "0 events" (the caller would wipe the
        // existing calendar alarms). But a NON-NULL cursor (even empty) is a genuine success, and
        // a NULL cursor with no exception is treated as a successful empty read (matches the
        // original working behaviour — most providers return a real cursor, never null here).
        if (failed) {
            cursor?.close()
            return CalendarSource.Read.FAILED
        }
        if (cursor == null) {
            Log.i(TAG, "calendar read: null cursor (no provider) — treating as empty")
            return CalendarSource.Read.success(emptyList())
        }

        val out = ArrayList<CalendarEvent>()
        cursor.use { c ->
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
        return CalendarSource.Read.success(out)
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
