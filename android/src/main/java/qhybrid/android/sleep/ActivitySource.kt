package qhybrid.android.sleep

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import qhybrid.android.WatchConnectionService

/**
 * WP16f — narrow, injectable seam for the parsed activity/sleep data the Sleep/Activity screen
 * renders (mirrors WP16b's `AlarmSync`, WP16c's `NotificationSync`, WP16d's
 * [qhybrid.android.buttons.ButtonSync], and WP16e's
 * [qhybrid.android.calibration.CalibrationSync]) so [SleepActivityViewModel] is unit-testable with
 * a fake (no Android service, no BLE, no real activity file).
 *
 * **DATA-SOURCE DECISION (drives the whole design).** WP8 ([qhybrid.protocol.activity.ActivitySummarizer]
 * / `ActivityParser`) is a *pure on-demand* function over a raw binary activity file (`byte[]`).
 * There is **NO persisted activity/sleep store** — WP4's Room schema has no activity entity, and
 * the WP16/WP16f breakdown does NOT say WP16f owns one. We therefore add **NO new Room
 * entity/DAO/repository method** and **NO speculative DB schema**. Instead the screen renders
 * from this injectable [ActivitySource]; the real fetch→parse pipeline (BLE read of the watch's
 * activity file → `ActivityParser.parse` → [SleepActivityAdapter]) is **deferred** behind the
 * [ServiceActivitySource.ACTIVITY_WIRED] = `false` flag.
 *
 * **DEFERRED (on-device-pending).** Reading the activity file off the watch and parsing it is its
 * own work package: it needs the WP3 [WatchConnectionService] to expose an activity-file read
 * action (and likely a small store/cache for the parsed result). The protocol parser already
 * exists (`ActivityParser` / `ActivitySummarizer`, WP8) — only the *fetch* is missing. Until that
 * lands, [ServiceActivitySource] emits an empty [ActivityChartData] and reports
 * [ServiceActivitySource.ACTIVITY_WIRED] = `false` so the UI flags the data as not-yet-fetched and
 * `refresh()` is a no-op poke (no invented wire bytes).
 */
interface ActivitySource {
    /** The current parsed activity/sleep data for the active watch (empty until fetched). */
    val data: Flow<ActivityChartData>

    /**
     * Request a refresh of the activity data (re-read the file off the watch + re-parse). Returns
     * whether the real fetch→parse pipeline is actually wired yet (`false` until the activity-file
     * read WP lands; the UI surfaces an "on-device-pending" note when `false`).
     */
    fun refresh(): Boolean
}

/**
 * Production [ActivitySource] — pokes the WP3 service's existing `syncNow` entry point on refresh
 * and (for now) emits an empty [ActivityChartData].
 *
 * It deliberately adds **NO new BLE/protocol behavior** and **NO DB**: reading the watch's
 * activity file + parsing it (then optionally caching the result) is a later WP. Until then this
 * seam only pokes the existing service path; no activity bytes are read/written, none invented.
 */
class ServiceActivitySource(context: Context) : ActivitySource {
    private val appContext = context.applicationContext

    // Until the fetch→parse pipeline is wired, the data is always empty. Kept as a StateFlow so
    // the wiring WP can simply push real parsed data here without touching the ViewModel.
    private val _data = MutableStateFlow(ActivityChartData.EMPTY)
    override val data: Flow<ActivityChartData> = _data.asStateFlow()

    override fun refresh(): Boolean {
        // Poke the existing sync-on-connect path. The dedicated activity-file read + parse
        // (BLE read of the activity file → ActivityParser.parse → SleepActivityAdapter, then
        // push into [_data]) is a later WP and not added here (no new wire behavior, no invented
        // bytes, no DB).
        WatchConnectionService.syncNow(appContext)
        return ACTIVITY_WIRED
    }

    companion object {
        /** Flip to true when the activity-file read→parse pipeline is wired (later WP). */
        const val ACTIVITY_WIRED = false
    }
}
