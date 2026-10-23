package qhybrid.android.settings

import android.content.Context
import qhybrid.android.WatchConnectionService

/**
 * WP-TZSYNC: injectable seam for the manual "Sync time now" button (mirrors [FullSync]) so
 * [SettingsViewModel] stays unit-testable with a fake.
 *
 * The service already re-pushes the time on every connect, on TIMEZONE_CHANGED / TIME_SET, at the
 * zone's next DST transition (AlarmManager), and when a screen-on / periodic check sees the UTC
 * offset move. This button is the explicit escape hatch, e.g.
 * to correct drift on the watch. Time is NOT a SyncSection, so "Sync all" does not cover it.
 */
interface TimeSync {
    /** Push the phone's time + UTC offset to the active watch (connects first if needed). */
    fun syncTime(): Boolean
}

/** Production [TimeSync]: pokes the WP3 service. Holds the application context only. */
class ServiceTimeSync(context: Context) : TimeSync {
    private val appContext = context.applicationContext

    override fun syncTime(): Boolean {
        WatchConnectionService.syncTimeNow(appContext)
        return true
    }
}

/** No-op [TimeSync] default for callers/tests that don't exercise the time-sync path. */
object NoopTimeSync : TimeSync {
    override fun syncTime(): Boolean = false
}
