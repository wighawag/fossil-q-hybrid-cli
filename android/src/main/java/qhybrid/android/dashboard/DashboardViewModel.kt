package qhybrid.android.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import qhybrid.android.WatchState
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository
import qhybrid.android.sleep.ActivityState

/**
 * WP16a — the Dashboard's immutable UI state. A pure function of (live link status from
 * WP3 [WatchState]) + (the WP4 active-watch row & registry). The Composable renders this;
 * the ViewModel owns combining the two reactive sources into it.
 *
 * WP-ACTIVITY: [steps] is now LIVE — it is the published step total from the in-memory
 * [ActivityState] holder (parsed from the watch's activity file via WP8). It is `null` only until
 * the first fetch completes (the UI shows a placeholder then). [stepGoal] comes from the active
 * watch row (WP4).
 */
data class DashboardUiState(
    val link: WatchState.LinkState = WatchState.LinkState.IDLE,
    val statusMessage: String? = null,
    /** Battery preferring the LIVE link value, falling back to the active watch's last-known. */
    val batteryPercent: Int? = null,
    val firmware: String? = null,
    val model: String? = null,
    val mtu: Int = 0,
    /** MAC the live link is for (WP3) — may differ from [activeWatch] briefly while switching. */
    val connectedMac: String? = null,
    /** The WP4 active watch (the one that receives live notifications), or null if none. */
    val activeWatch: WatchEntity? = null,
    /** Full registry for the active-watch selector (WP4 observeWatches). */
    val watches: List<WatchEntity> = emptyList(),
    /** WP16f-pending: live step count. Null = not yet wired (UI shows a placeholder). */
    val steps: Int? = null,
    /** Step goal from the active watch row (WP4), default 10000. */
    val stepGoal: Int = 10000,
    /** Epoch-millis of the last activity fetch (0 == never), for an optional "last updated" hint. */
    val activityUpdatedMillis: Long = 0L,
) {
    /** True once the live link is fully up. */
    val isConnected: Boolean get() = link == WatchState.LinkState.INITIALIZED

    /** True while a connect/auth/init is in progress. */
    val isBusy: Boolean
        get() = link == WatchState.LinkState.CONNECTING ||
            link == WatchState.LinkState.INITIALIZING ||
            link == WatchState.LinkState.AUTH_REQUIRED

    /** Convenience for the UI: which watch we should show as "selected" in the picker. */
    val selectedMac: String? get() = activeWatch?.macAddress
}

/**
 * WP16a — combines WP3 [WatchState.status] (live link/battery/firmware/model/mtu) with the
 * WP4 active-watch row + registry into one [DashboardUiState], and exposes the dashboard
 * intents (set active watch; connect/disconnect/sync; find watch).
 *
 * Connect/disconnect/sync/find delegate to the injectable [WatchActions] seam so the
 * ViewModel is unit-testable with a fake (no Android service, no BLE). Active-watch
 * selection goes through [WatchRepository] (WP4). NO new BLE/protocol behavior is added.
 */
open class DashboardViewModel(
    private val repo: WatchRepository,
    private val actions: WatchActions,
    private val watchStatus: Flow<WatchState.WatchStatus> = WatchState.status,
    // WP-ACTIVITY: the live parsed-activity holder (injectable for tests; production = the
    // process-wide [ActivityState] the WP3 service publishes into).
    private val activityStatus: Flow<ActivityState.ActivityStatus> = ActivityState.status,
    // Tests inject a TestScope; production passes null → uses [viewModelScope].
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val coroutineScope: CoroutineScope = scope ?: viewModelScope

    val uiState: StateFlow<DashboardUiState> =
        combine(
            watchStatus,
            repo.observeActiveWatch(),
            repo.observeWatches(),
            activityStatus,
        ) { status, active, watches, activity ->
            DashboardUiState(
                link = status.link,
                statusMessage = status.message,
                // Prefer the live link battery; fall back to the active watch's last-known.
                batteryPercent = status.battery ?: active?.batteryLevel?.takeIf { it > 0 },
                firmware = status.firmware ?: active?.firmwareVersion,
                model = status.model ?: active?.model,
                mtu = status.mtu,
                connectedMac = status.mac,
                activeWatch = active,
                watches = watches,
                // WP-ACTIVITY: live step total from the parsed activity file (null until the first
                // fetch completes; only meaningful when a watch is active).
                steps = if (active == null) null else activity.steps,
                stepGoal = active?.stepGoal ?: 10000,
                activityUpdatedMillis = activity.lastUpdatedMillis,
            )
        }.stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    // ---- intents -------------------------------------------------------------

    /** WP4: make [mac] the single active watch (the one receiving live notifications). */
    fun setActiveWatch(mac: String) {
        coroutineScope.launch { repo.setActiveWatch(mac) }
    }

    /**
     * WP3: connect to [mac], or (when null) the active watch's mac taken from the current
     * combined [uiState] — no extra DB roundtrip. If null is still resolved (no active
     * watch yet), the service falls back to the CDM-associated mac.
     */
    fun connect(mac: String? = null) {
        actions.connect(mac ?: uiState.value.selectedMac)
    }

    fun disconnect() = actions.disconnect()
    fun sync() = actions.sync()

    /** WP16a stub — see [WatchActions.findWatch]; real choreography is on-device-pending. */
    fun findWatch() = actions.findWatch()

    companion object {
        /**
         * Production factory: real [WatchRepository] + [ServiceWatchActions] forwarding to
         * the WP3 service. Used by [androidx.lifecycle.viewmodel.compose.viewModel].
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(
                        repo = WatchRepository(appContext),
                        actions = ServiceWatchActions(appContext),
                        // scope = null → the VM uses its own viewModelScope.
                    ) as T
            }
        }
    }
}
