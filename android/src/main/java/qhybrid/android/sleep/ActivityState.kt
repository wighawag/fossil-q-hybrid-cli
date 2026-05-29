package qhybrid.android.sleep

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WP-ACTIVITY (sub-part 2) — **process-wide, in-memory holder** for the last parsed activity/sleep
 * data, mirroring WP3 [qhybrid.android.WatchState] exactly (a plain `object`, single writer = the
 * WP3 [qhybrid.android.WatchConnectionService], read-only observers everywhere else).
 *
 * **CACHE DECISION (stated explicitly).** WP16f's banner is the source of truth: WP8
 * (`ActivityParser` / `ActivitySummarizer`) is a *pure on-demand parser* and WP16f added **NO
 * persisted activity Room schema**. This WP **does NOT add one either** — the breakdown does not
 * give this WP a Room entity, and a DB schema would duplicate the watch's own file as the canonical
 * store. Instead the parsed result lives here in a **process-wide in-memory `StateFlow`** (just like
 * `WatchState`): it survives the service being rebound/recreated and is instantly available to a
 * freshly-launched Activity, but is intentionally NOT persisted across process death (a re-fetch is
 * cheap and the watch's file is authoritative). Both the Sleep screen's
 * [ServiceActivitySource] and the Dashboard ([qhybrid.android.dashboard.DashboardViewModel]) read
 * from this single holder; the service writes it after a fetch.
 *
 * We also keep the last raw `byte[]` so a re-parse (e.g. with a different time-zone, were that ever
 * needed) is possible without re-reading the watch — the raw bytes are the cache, the parsed
 * [ActivityChartData] is the derived display value (computed once by [qhybrid.android.WatchConnectionService]
 * via [ActivityFetcher]).
 */
object ActivityState {

    data class ActivityStatus(
        /** The last parsed chart data (empty until the first successful fetch). */
        val data: ActivityChartData = ActivityChartData.EMPTY,
        /** Epoch-millis of the last publish (0 == never fetched). For "last updated" UI / debug. */
        val lastUpdatedMillis: Long = 0L,
        /** True once at least one fetch has been published (even an empty one). */
        val hasFetched: Boolean = false,
    ) {
        /** Convenience for the Dashboard step total (null until a fetch has happened). */
        val steps: Int? get() = if (hasFetched) data.totalSteps else null
    }

    private val _status = MutableStateFlow(ActivityStatus())
    val status: StateFlow<ActivityStatus> = _status.asStateFlow()

    /** Convenience StateFlow-less view of just the chart data (for [ServiceActivitySource]). */
    val data: StateFlow<ActivityChartData>
        get() = dataFlow
    private val dataFlow = MutableStateFlow(ActivityChartData.EMPTY)

    /**
     * Service-only: publish a freshly-parsed [ActivityChartData] (already produced by
     * [ActivityFetcher]). [nowMillis] is injected so this stays clock-free for tests. Marks
     * [ActivityStatus.hasFetched] true even for an empty result (so the Dashboard step total
     * resolves to 0 rather than "unknown" after a confirmed-empty fetch).
     */
    fun publish(chart: ActivityChartData, nowMillis: Long) {
        _status.value = ActivityStatus(
            data = chart,
            lastUpdatedMillis = nowMillis,
            hasFetched = true,
        )
        dataFlow.value = chart
    }

    /** Test-only reset to the pristine never-fetched state. */
    internal fun reset() {
        _status.value = ActivityStatus()
        dataFlow.value = ActivityChartData.EMPTY
    }
}
