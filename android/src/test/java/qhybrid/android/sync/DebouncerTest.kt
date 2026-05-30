package qhybrid.android.sync

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WP-SYNCSTATUS (Step 4) — the debouncer coalesces a burst into ONE action run, [windowMillis]
 * after the LAST schedule. Uses [runTest]'s virtual clock (the [CoroutineDebouncer] runs on the
 * test scope).
 */
class DebouncerTest {

    @Test
    fun coalescesBurstIntoOneRun_afterTheWindow() = runTest {
        var runs = 0
        val d = CoroutineDebouncer(this, windowMillis = 750)

        // Three rapid schedules within the window → only the last survives.
        d.schedule { runs++ }
        advanceTimeBy(100)
        d.schedule { runs++ }
        advanceTimeBy(100)
        d.schedule { runs++ }
        runCurrent()
        assertEquals("nothing fires during the burst", 0, runs)

        // After the full window elapses past the LAST schedule, exactly one run.
        advanceTimeBy(750)
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun separateSchedulesEachRun_whenSpacedBeyondTheWindow() = runTest {
        var runs = 0
        val d = CoroutineDebouncer(this, windowMillis = 750)

        d.schedule { runs++ }
        advanceTimeBy(800) // window elapsed → first run
        runCurrent()
        assertEquals(1, runs)

        d.schedule { runs++ }
        advanceTimeBy(800)
        runCurrent()
        assertEquals(2, runs)
    }

    @Test
    fun immediateDebouncer_runsSynchronously() {
        var runs = 0
        val d = ImmediateDebouncer()
        d.schedule { runs++ }
        d.schedule { runs++ }
        assertEquals(2, runs)
        assertEquals(2, d.scheduleCount)
    }

    @Test
    fun recordingDebouncer_keepsOnlyLastUntilFired() {
        val log = mutableListOf<Int>()
        val d = RecordingDebouncer()
        d.schedule { log += 1 }
        d.schedule { log += 2 }
        d.schedule { log += 3 }
        assertEquals(3, d.scheduleCount)
        assertEquals(emptyList<Int>(), log) // nothing run yet
        d.fireNow()
        assertEquals(listOf(3), log) // only the last (coalesced) action ran
    }
}
