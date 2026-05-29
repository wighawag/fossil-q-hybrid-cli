package qhybrid.android.sleep

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository

/**
 * WP16f — the Sleep/Activity screen's immutable UI state. A combination of:
 *   - the WP4 active watch (observed) — purely to know which watch is active and to disable/empty
 *     the screen when none is connected, and
 *   - the injectable [ActivitySource] data ([ActivityChartData]) — per-day steps/calories + the
 *     sleep timeline + the aggregate sleep summary, adapted from WP8 [SleepActivityAdapter].
 *
 * **READ-ONLY + MODEL-AGNOSTIC.** This screen only displays parsed activity/sleep data; the only
 * "action" is [SleepActivityViewModel.refresh], which goes through the deferred [ActivitySource]
 * seam ([ServiceActivitySource.ACTIVITY_WIRED] = false until the activity-file fetch WP lands).
 * There is NO write/upload path and NO per-model branching.
 *
 * **NO PERSISTED STORE.** WP8 is an on-demand parser; there is no activity Room entity (see
 * [ActivitySource]). When there is no active watch the data is forced empty so the screen reads as
 * "nothing to show".
 */
data class SleepActivityUiState(
    /** The WP4 active watch (observed), or null if none. */
    val activeWatch: WatchEntity? = null,
    /** The parsed per-day + sleep data from the injectable source (empty until fetched). */
    val data: ActivityChartData = ActivityChartData.EMPTY,
) {
    val activeMac: String? get() = activeWatch?.macAddress
    val hasActiveWatch: Boolean get() = activeWatch != null

    /** The per-day step goal from the WP4 row (model-agnostic default 10k); 0 if no watch. */
    val stepGoal: Int get() = activeWatch?.stepGoal ?: 0

    val days: List<DaySummary> get() = data.days
    val sleep: List<SleepSegment> get() = data.sleep
    val sleepSummary: SleepSummary get() = data.sleepSummary

    val totalSteps: Int get() = data.totalSteps
    val totalCalories: Int get() = data.totalCalories
    val totalActiveMinutes: Int get() = data.totalActiveMinutes

    /** True when there is an active watch but no parsed data yet (the on-device-pending case). */
    val isEmpty: Boolean get() = !data.hasData

    /** Whether the refresh control should be enabled (needs an active watch). */
    val canRefresh: Boolean get() = hasActiveWatch
}

/**
 * WP16f — observes the WP4 active watch (for [SleepActivityUiState.activeWatch] /
 * [SleepActivityUiState.hasActiveWatch]) and combines the injectable [ActivitySource] data into the
 * same UiState. Mirrors WP16e's `combine(observeActiveWatch(), …)` structure exactly (an
 * injectable seam with a `*_WIRED=false` deferral flag; a production [factory]).
 *
 * When there is no active watch the data half is forced empty (the screen disables/empties). The
 * sole intent, [refresh], is a read-only request delegated to the [ActivitySource] seam and
 * reports whether the real fetch→parse pipeline is wired yet.
 */
open class SleepActivityViewModel(
    private val repo: WatchRepository,
    private val source: ActivitySource,
    // Tests inject a real/Unconfined scope; production passes null → uses [viewModelScope].
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val coroutineScope: CoroutineScope = scope ?: viewModelScope

    val uiState: StateFlow<SleepActivityUiState> =
        combine(repo.observeActiveWatch(), source.data) { active, data ->
            SleepActivityUiState(
                activeWatch = active,
                // No active watch → show nothing (read-only, model-agnostic, no leftover data).
                data = if (active == null) ActivityChartData.EMPTY else data,
            )
        }.stateIn(
            coroutineScope,
            SharingStarted.WhileSubscribed(5_000),
            SleepActivityUiState(),
        )

    // ---- intents -------------------------------------------------------------

    /**
     * Request a refresh of the parsed activity data via the [ActivitySource] seam (read-only).
     * No-op (returns false) when there is no active watch. Returns whether the real fetch→parse
     * pipeline is wired yet (false until the activity-file read WP lands; the UI surfaces an
     * "on-device-pending" note when false).
     */
    fun refresh(): Boolean {
        if (!uiState.value.hasActiveWatch) return false
        return source.refresh()
    }

    companion object {
        /** Production factory: real [WatchRepository] + [ServiceActivitySource]. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SleepActivityViewModel(
                        repo = WatchRepository(appContext),
                        source = ServiceActivitySource(appContext),
                    ) as T
            }
        }
    }
}
