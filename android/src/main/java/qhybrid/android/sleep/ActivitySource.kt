package qhybrid.android.sleep

import android.content.Context
import kotlinx.coroutines.flow.Flow
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
 * **WP-ACTIVITY — NOW WIRED.** The fetch→parse pipeline is implemented: the WP3
 * [WatchConnectionService] exposes a `requestActivity` action (BLE read on the ble-worker), parses
 * the delivered file via the pure WP8-backed [ActivityFetcher], and publishes the result into the
 * process-wide in-memory [ActivityState] holder (the cache decision: in-memory like `WatchState`,
 * NO Room schema). [ServiceActivitySource] now observes [ActivityState] and its `refresh()` drives
 * the real `requestActivity` action, so [ServiceActivitySource.ACTIVITY_WIRED] = `true`. The actual
 * BLE file transfer + its effect on a real watch are on-device-pending; the fetch→parse→publish
 * logic is unit-tested against the fixtures + a fake byte source.
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
 * Production [ActivitySource] — observes the process-wide [ActivityState] holder (populated by the
 * WP3 service after a fetch+parse) and drives the WP3 `requestActivity` BLE-read action on refresh.
 *
 * Reuses the SAME fetch path the CLI `activity` command drives (`FossilController.requestActivity`
 * → `onActivityData`); invents **NO wire bytes** and adds **NO DB** (the parsed result is cached
 * in-memory in [ActivityState], the cache decision stated there). The actual BLE file transfer is
 * on-device-pending; the headless fetch→parse→publish logic is unit-tested.
 */
class ServiceActivitySource(context: Context) : ActivitySource {
    private val appContext = context.applicationContext

    /** Live parsed data from the process-wide holder the service publishes into. */
    override val data: Flow<ActivityChartData> = ActivityState.data

    override fun refresh(): Boolean {
        // WP-ACTIVITY: drive the real activity-file read on the WP3 service's ble-worker. The
        // result is parsed via [ActivityFetcher] and published into [ActivityState], which [data]
        // observes (so the UI updates when the transfer completes). No invented wire bytes.
        WatchConnectionService.requestActivity(appContext, keep = false)
        return ACTIVITY_WIRED
    }

    companion object {
        /** WP-ACTIVITY: the activity-file read→parse→publish pipeline is now wired. */
        const val ACTIVITY_WIRED = true
    }
}
