package qhybrid.android.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WP-SYNCSTATUS (Step 4) — a tiny **injectable** debouncer so a burst of rapid edits coalesces into
 * a SINGLE deferred action (e.g. the Alarms auto-save: a burst of toggles must not spam BLE, and
 * the blocking "Saving…" modal must appear once per coalesced save, not per keystroke).
 *
 * Injectable so the auto-save is unit-testable without real time: tests use an
 * [ImmediateDebouncer] (runs the action synchronously, coalescing only conceptually) or a
 * [RecordingDebouncer] (records calls without running), while production uses [CoroutineDebouncer]
 * (cancel-and-relaunch a delayed coroutine).
 */
interface Debouncer {
    /** Schedule [action]; a new schedule before the window elapses REPLACES the pending one. */
    fun schedule(action: () -> Unit)
}

/**
 * Production debouncer: each [schedule] cancels the pending job and launches a fresh one that waits
 * [windowMillis] then runs [action]. So N edits within the window collapse to one [action] run,
 * [windowMillis] after the LAST edit.
 */
class CoroutineDebouncer(
    private val scope: CoroutineScope,
    private val windowMillis: Long = 750,
) : Debouncer {
    private var job: Job? = null

    override fun schedule(action: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(windowMillis)
            action()
        }
    }
}

/**
 * Test debouncer that runs [action] IMMEDIATELY (no delay). Useful to assert "an edit triggers a
 * save" deterministically. Coalescing is NOT modelled (each schedule runs); use [RecordingDebouncer]
 * to assert coalescing.
 */
class ImmediateDebouncer : Debouncer {
    var scheduleCount = 0
        private set

    override fun schedule(action: () -> Unit) {
        scheduleCount++
        action()
    }
}

/**
 * Test debouncer that RECORDS the latest scheduled action without running it, and counts schedules.
 * Call [fireNow] to run the single pending action (models "the window elapsed once after the burst")
 * — proving N schedules coalesce to ONE action run.
 */
class RecordingDebouncer : Debouncer {
    var scheduleCount = 0
        private set
    private var pending: (() -> Unit)? = null

    override fun schedule(action: () -> Unit) {
        scheduleCount++
        pending = action // a later schedule REPLACES the earlier pending action (coalescing)
    }

    /** Run the single pending (coalesced) action, if any. */
    fun fireNow() {
        pending?.invoke()
        pending = null
    }
}
