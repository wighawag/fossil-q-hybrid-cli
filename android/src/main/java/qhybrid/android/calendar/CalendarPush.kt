package qhybrid.android.calendar

import android.content.Context
import qhybrid.android.WatchConnectionService
import qhybrid.android.sync.SyncSection

/**
 * WP13 (Step 3) — narrow, injectable seam for "a calendar refresh changed the rows, push them to
 * the watch". Mirrors the WP11 [qhybrid.android.notifications.NotificationPlay] seam pattern so the
 * refresh-driven push is unit-testable with a fake (no Android service, no BLE).
 *
 * **SILENT push (decided).** A background calendar change is a silent background effect — exactly
 * like a WP11 posted notification — NOT a user-initiated foreground save. So the production impl
 * pokes [WatchConnectionService.syncNow] DIRECTLY (targeted to `ALARMS_ONLY`) and publishes **no**
 * `SyncState` (no "Saving to watch…" modal). This is the deliberate asymmetry vs. the foreground
 * Alarms-screen auto-save (WP-SYNCSTATUS Step 4), which DOES show the modal via
 * [qhybrid.android.sync.ServiceSaveToWatch].
 *
 * The watch's on-watch ✓ for the calendar rows then flips for free via the existing per-watch
 * `alarmsSyncedAt` marker, written at the `result.performed` hook in `runOnConnectSync`. **Invents
 * NO new wire bytes** — it re-pushes the same golden-tested 32-slot alarm file.
 */
interface CalendarPush {
    /** Push the (changed) calendar alarm rows to the watch silently. Returns whether wired. */
    fun pushAlarmsSilently(): Boolean
}

/**
 * Production [CalendarPush] — pokes the WP3 service's `syncNow(ALARMS_ONLY)` (connect-then-sync if
 * the link is down) WITHOUT publishing SYNCING. Holds the application context so it never leaks an
 * Activity.
 */
class ServiceCalendarPush(context: Context) : CalendarPush {
    private val appContext = context.applicationContext

    override fun pushAlarmsSilently(): Boolean {
        WatchConnectionService.syncNow(appContext, SyncSection.ALARMS_ONLY)
        return true
    }
}

/** No-op [CalendarPush] default — never pokes the service (used by tests / non-pushing callers). */
object NoopCalendarPush : CalendarPush {
    override fun pushAlarmsSilently(): Boolean = false
}
