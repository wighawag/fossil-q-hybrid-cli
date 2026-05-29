package qhybrid.android.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * WP-PROGRESS (sub-part 3) — narrow, injectable seam exposing the process-wide [SyncState] as a
 * [StateFlow] each screen's ViewModel can observe, so a ViewModel is unit-testable with a FAKE
 * source (no Android service, no BLE, no global singleton timing). Mirrors the per-feature `*Sync`
 * seams (e.g. [qhybrid.android.buttons.ButtonSync]).
 *
 * The production impl ([GlobalSyncStateSource]) reads the real [SyncState.status]; tests use
 * [FakeSyncStateSource] to drive arbitrary phases deterministically.
 */
interface SyncStateSource {
    /** The live sync status (SYNCING / SUCCESS / ERROR / IDLE) the Save buttons observe. */
    val status: StateFlow<SyncState.SyncStatus>
}

/** Production [SyncStateSource] — forwards the process-wide [SyncState.status]. */
class GlobalSyncStateSource : SyncStateSource {
    override val status: StateFlow<SyncState.SyncStatus> get() = SyncState.status
}

/** Test/fake [SyncStateSource] — a driveable [MutableStateFlow] of [SyncState.SyncStatus]. */
class FakeSyncStateSource(
    initial: SyncState.SyncStatus = SyncState.SyncStatus(),
) : SyncStateSource {
    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<SyncState.SyncStatus> get() = _status

    /** Drive the fake to a new phase, mirroring the service's [SyncState.publish] contract. */
    fun set(
        phase: SyncState.SyncPhase,
        result: SyncResult? = null,
        errorMessage: String? = null,
        nowMillis: Long = 0L,
    ) {
        _status.value = SyncState.SyncStatus(
            phase = phase,
            lastResult = result ?: _status.value.lastResult,
            errorMessage = if (phase == SyncState.SyncPhase.ERROR) errorMessage else null,
            lastUpdatedMillis = nowMillis,
        )
    }
}
