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
 * the current window then runs [action]. So N edits within the window collapse to one [action] run,
 * the window after the LAST edit (a TRAILING debounce — the timer resets on every schedule).
 *
 * The window is read from [windowMillisProvider] ON EACH SCHEDULE, so a configurable window (e.g. an
 * app pref the user tunes at runtime) takes effect for the next burst without rebuilding the
 * debouncer. The fixed-[windowMillis] constructor is kept for callers with a constant window.
 */
class CoroutineDebouncer(
    private val scope: CoroutineScope,
    private val windowMillisProvider: () -> Long,
) : Debouncer {
    /** Convenience: a constant window. */
    constructor(scope: CoroutineScope, windowMillis: Long = 750) : this(scope, { windowMillis })

    private var job: Job? = null

    override fun schedule(action: () -> Unit) {
        job?.cancel()
        val window = windowMillisProvider().coerceAtLeast(0)
        job = scope.launch {
            delay(window)
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
