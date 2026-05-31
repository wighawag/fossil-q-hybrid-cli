package qhybrid.android.tracker

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import qhybrid.android.db.WaypointEntity
import qhybrid.android.db.WatchRepository

/** WP-TRACKER — the waypoint-viewer screen state: the logged waypoints, newest first. */
data class WaypointsUiState(
    val waypoints: List<WaypointEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = waypoints.isEmpty()
}

/**
 * WP-TRACKER — state holder for the minimal waypoint viewer (list + GPX export + clear). Observes
 * the WP4 [WatchRepository] waypoint table; the GPX serialization is the pure [GpxWriter] (the file
 * write + share-sheet are in the screen via the existing WP15 FileProvider). Injectable scope for
 * tests; production uses [viewModelScope].
 */
open class WaypointsViewModel(
    private val repo: WatchRepository,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val coroutineScope: CoroutineScope = scope ?: viewModelScope

    val uiState: StateFlow<WaypointsUiState> =
        repo.observeWaypoints()
            .map { WaypointsUiState(it) }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), WaypointsUiState())

    /** Build the GPX document for ALL waypoints (chronological), suspending on the DB read. */
    suspend fun buildGpx(): String = GpxWriter.toGpx(repo.getWaypointsChronological())

    /** Delete one waypoint by id. */
    fun delete(id: Long) {
        coroutineScope.launch { repo.deleteWaypoint(id) }
    }

    /** Clear the whole waypoint log. */
    fun clearAll() {
        coroutineScope.launch { repo.clearWaypoints() }
    }

    companion object {
        const val EXPORT_FILENAME = "fossilq-waypoints.gpx"
        const val MIME_TYPE = "application/gpx+xml"

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WaypointsViewModel(repo = WatchRepository(appContext)) as T
            }
        }
    }
}
