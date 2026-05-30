package qhybrid.android.onboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WP-ONBOARD — process-wide, in-memory holder for the live "is a brand-new watch being provisioned
 * right now?" signal. Mirrors [qhybrid.android.WatchState] / [qhybrid.android.sync.SyncState]
 * exactly (a plain `object`, single writer = the WP3 [qhybrid.android.WatchConnectionService],
 * read-only observers everywhere else).
 *
 * **Why separate from [qhybrid.android.sync.SyncState].** Provisioning happens on the FIRST connect
 * of an unknown watch, BEFORE it is "added" (no DB row yet), and the user is waiting on the
 * add-a-watch surface (Dashboard) for it to finish. It is semantically distinct from an ongoing
 * Save/Sync of an already-added watch, and warrants its own "Adding your watch…" modal with a
 * success/failure outcome (a failed provision means the watch was NOT added and should be retried).
 *
 * In-memory by nature (a provisioning attempt is ephemeral); no Room schema is added.
 */
object ProvisioningState {

    enum class Phase {
        /** No provisioning attempted yet, or the last one is fully acknowledged. */
        IDLE,

        /** A provisioning pass is in flight (the add-watch modal shows a spinner). */
        PROVISIONING,

        /** The watch was provisioned and added successfully. */
        ADDED,

        /** Provisioning failed — the watch was NOT added (will retry on the next connect). */
        FAILED,
    }

    data class Status(
        val phase: Phase = Phase.IDLE,
        /** MAC of the watch being / just provisioned (for the modal text), or null. */
        val mac: String? = null,
        /** Short failure message when [phase] is [Phase.FAILED] (else null). */
        val errorMessage: String? = null,
        /** Epoch-millis of the last [publish] (0 == never). */
        val lastUpdatedMillis: Long = 0L,
    ) {
        val isProvisioning: Boolean get() = phase == Phase.PROVISIONING
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Service-only: publish a new provisioning [phase] for [mac]. [nowMillis] injected for tests. */
    fun publish(
        phase: Phase,
        mac: String? = null,
        errorMessage: String? = null,
        nowMillis: Long,
    ) {
        val cur = _status.value
        _status.value = Status(
            phase = phase,
            mac = mac ?: cur.mac,
            errorMessage = if (phase == Phase.FAILED) errorMessage else null,
            lastUpdatedMillis = nowMillis,
        )
    }

    /** Test-only reset to the pristine state. */
    internal fun reset() {
        _status.value = Status()
    }
}
