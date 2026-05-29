package qhybrid.android.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WP-PROGRESS (sub-part 1) — **process-wide, in-memory holder** for the live "is a watch sync in
 * flight?" signal, mirroring WP3 [qhybrid.android.WatchState] and WP-ACTIVITY
 * [qhybrid.android.sleep.ActivityState] exactly (a plain `object`, single writer = the WP3
 * [qhybrid.android.WatchConnectionService], read-only observers everywhere else).
 *
 * **WHY IN-MEMORY (stated explicitly).** A "Save to watch" is fire-and-poke (`syncNow` =
 * a `startService` intent) and returns instantly; the real BLE write takes seconds (file transfer
 * + CRC + the bounded 30 s alarm-future wait), and there is **no in-flight progress signal** the UI
 * can observe today. This holder makes the sync state VISIBLE — it does NOT by itself fix a write
 * that fails on-device, so it surfaces SUCCESS/ERROR honestly from the [SyncResult]. Per the plan's
 * cross-cutting rules this is an **in-memory process-wide state holder** (mirroring `WatchState` /
 * `ActivityState`) for the live UI signal; persisted settings stay in Room. NO speculative Room
 * schema is added — a sync's progress is ephemeral by nature.
 *
 * **SCOPE.** This is a single, shared "watch is syncing" signal. The button file is one combined
 * `compileMultiEntry(top, mid, bot)` write, and the orchestrator runs alarms/notifications/buttons/
 * settings in one pass, so per-section spinners are NOT independently trackable — every screen's
 * Save button observes this same signal.
 *
 * The clock is injected ([nowMillis] on [publish]) so this stays clock-free for tests, exactly like
 * [qhybrid.android.sleep.ActivityState.publish].
 */
object SyncState {

    /** The life-cycle phase of the most-recent sync the service is aware of. */
    enum class SyncPhase {
        /** No sync attempted yet (pristine), or the last one is fully acknowledged. */
        IDLE,

        /** A sync pass is in flight on the ble-worker (the Save buttons show a spinner + disable). */
        SYNCING,

        /** The last sync pass completed (a [SyncResult] was produced — see the honesty note). */
        SUCCESS,

        /** The last sync pass threw before producing a result (e.g. lost link mid-write). */
        ERROR,
    }

    data class SyncStatus(
        /** The current phase (starts [SyncPhase.IDLE]). */
        val phase: SyncPhase = SyncPhase.IDLE,
        /**
         * The last completed pass's [SyncResult] summary (null while [SyncPhase.SYNCING] for the
         * first sync, or when a pass errored before producing one). Surfaced honestly so the UI can
         * report what was actually performed/skipped/errored.
         */
        val lastResult: SyncResult? = null,
        /** A short human-readable failure message when [phase] is [SyncPhase.ERROR] (else null). */
        val errorMessage: String? = null,
        /** Epoch-millis of the last [publish] (0 == never). For "last synced" UI / debug. */
        val lastUpdatedMillis: Long = 0L,
    ) {
        /** True while a sync pass is in flight (Save buttons spin + disable). */
        val isSyncing: Boolean get() = phase == SyncPhase.SYNCING

        /**
         * True when the last completed pass had any per-section error (the BLE write may have
         * partially failed even though the pass itself didn't throw). Lets the UI surface a
         * partial-failure note honestly rather than a blanket "Saved to watch".
         */
        val hadSectionErrors: Boolean get() = (lastResult?.errors?.isNotEmpty() == true)
    }

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Service-only: publish a new [phase]. [result] is the completed-pass summary (carried through
     * on SUCCESS; ignored/kept-null on SYNCING). [errorMessage] is the failure text on ERROR.
     * [nowMillis] is injected so this stays clock-free for tests.
     *
     * Honesty note: SUCCESS means "the sync pass ran to completion and produced a [SyncResult]" —
     * inspect [SyncStatus.hadSectionErrors] / [SyncResult.errors] for per-section failures; ERROR
     * means the pass threw before producing one.
     */
    fun publish(
        phase: SyncPhase,
        result: SyncResult? = null,
        errorMessage: String? = null,
        nowMillis: Long,
    ) {
        val cur = _status.value
        _status.value = SyncStatus(
            phase = phase,
            // Keep the previous result while SYNCING so the UI can still show the last summary;
            // overwrite it on a terminal phase that carries a fresh one.
            lastResult = when (phase) {
                SyncPhase.SYNCING -> cur.lastResult
                else -> result ?: cur.lastResult
            },
            errorMessage = if (phase == SyncPhase.ERROR) errorMessage else null,
            lastUpdatedMillis = nowMillis,
        )
    }

    /** Test-only reset to the pristine never-synced state. */
    internal fun reset() {
        _status.value = SyncStatus()
    }
}
