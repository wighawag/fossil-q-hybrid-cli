package qhybrid.android.navcue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.android.navcue.NavCueDispatcher.Emitted
import qhybrid.android.navcue.TurnCueMapper.Maneuver
import qhybrid.android.navcue.TurnCueMapper.TurnEvent
import qhybrid.android.notifications.VibePatterns

/**
 * WP-NAV — unit tests for the pure [NavCueDispatcher] timing brain: two-stage firing, the SOON
 * suppression after a recent cue (time-based, user decision), per-turn dedup, new-turn detection,
 * ARRIVE, and OFF_ROUTE debounce. A fake [NavCueEffects] + an injected clock — no Android, no BLE.
 */
class NavCueDispatcherTest {

    private class FakeEffects : NavCueEffects {
        data class Call(val hourDeg: Int, val minDeg: Int, val pattern: Int)
        val calls = mutableListOf<Call>()
        override fun buzzAndPoint(hourDeg: Int, minDeg: Int, pattern: Int) {
            calls += Call(hourDeg, minDeg, pattern)
        }
    }

    private var now = 1000L
    private fun clock() = now
    private fun dispatcher(config: NavCueDispatcher.Config = NavCueDispatcher.Config()) =
        FakeEffects().let { it to NavCueDispatcher(it, config, clock = ::clock) }

    @Test
    fun twoStage_soonThenNow_fireOncePerTurn() {
        val (fx, d) = dispatcher()
        // Approaching a LEFT: far → no cue, ~40m → SOON, ~10m → NOW.
        assertNull(d.onTurn(TurnEvent(Maneuver.LEFT, 200)))
        val soon = d.onTurn(TurnEvent(Maneuver.LEFT, 38))
        assertTrue(soon is Emitted.Soon)
        // More ticks in the soon band do NOT re-fire.
        assertNull(d.onTurn(TurnEvent(Maneuver.LEFT, 30)))
        assertNull(d.onTurn(TurnEvent(Maneuver.LEFT, 20)))
        val nowCue = d.onTurn(TurnEvent(Maneuver.LEFT, 8))
        assertTrue(nowCue is Emitted.Now)
        // And the now band does NOT re-fire.
        assertNull(d.onTurn(TurnEvent(Maneuver.LEFT, 3)))

        assertEquals(2, fx.calls.size)
        // Both hands LEFT = 270°; soon = double, now = long.
        assertEquals(FakeEffects.Call(270, 270, VibePatterns.TWO_SHORT), fx.calls[0])
        assertEquals(FakeEffects.Call(270, 270, VibePatterns.ONE_LONG), fx.calls[1])
    }

    @Test
    fun soonSuppressed_whenTooSoonAfterPreviousCue_butNowStillFires() {
        val (fx, d) = dispatcher(NavCueDispatcher.Config(soonSuppressMs = 15_000))
        // First turn LEFT — soon fires at t=1000.
        now = 1000
        assertTrue(d.onTurn(TurnEvent(Maneuver.LEFT, 38)) is Emitted.Soon)
        assertTrue(d.onTurn(TurnEvent(Maneuver.LEFT, 8)) is Emitted.Now) // now at t=1000

        // Immediately a NEW turn RIGHT just 5s later, soon band — SUPPRESSED (within 15s window).
        now = 6_000
        assertNull(d.onTurn(TurnEvent(Maneuver.RIGHT, 38)))
        // But the NOW cue for that RIGHT still fires (never suppressed).
        now = 9_000
        assertTrue(d.onTurn(TurnEvent(Maneuver.RIGHT, 8)) is Emitted.Now)

        // calls: LEFT soon, LEFT now, RIGHT now (no RIGHT soon).
        assertEquals(3, fx.calls.size)
        assertEquals(VibePatterns.TWO_SHORT, fx.calls[0].pattern) // LEFT soon
        assertEquals(VibePatterns.ONE_LONG, fx.calls[1].pattern)  // LEFT now
        assertEquals(FakeEffects.Call(90, 90, VibePatterns.ONE_LONG), fx.calls[2]) // RIGHT now
    }

    @Test
    fun soonFires_whenEnoughTimeHasPassedSinceLastCue() {
        val (fx, d) = dispatcher(NavCueDispatcher.Config(soonSuppressMs = 15_000))
        now = 1000
        assertTrue(d.onTurn(TurnEvent(Maneuver.LEFT, 8)) is Emitted.Now) // last cue at t=1000

        // New turn RIGHT well after the window (20s later) — soon NOT suppressed.
        now = 21_000
        assertTrue(d.onTurn(TurnEvent(Maneuver.RIGHT, 38)) is Emitted.Soon)
    }

    @Test
    fun newTurnSameType_afterPassingPrevious_reArmsCues() {
        val (fx, d) = dispatcher(NavCueDispatcher.Config(soonSuppressMs = 0)) // disable suppression
        now = 0
        assertTrue(d.onTurn(TurnEvent(Maneuver.LEFT, 38)) is Emitted.Soon)
        assertTrue(d.onTurn(TurnEvent(Maneuver.LEFT, 8)) is Emitted.Now)
        // Distance jumps back up past 2x soon (passed that LEFT; next LEFT far away) → new turn.
        assertNull(d.onTurn(TurnEvent(Maneuver.LEFT, 500)))
        // Approaching the NEXT left re-arms the soon + now.
        assertTrue(d.onTurn(TurnEvent(Maneuver.LEFT, 38)) is Emitted.Soon)
        assertTrue(d.onTurn(TurnEvent(Maneuver.LEFT, 8)) is Emitted.Now)
        assertEquals(4, fx.calls.size)
    }

    @Test
    fun arrive_firesOnce() {
        val (fx, d) = dispatcher()
        val a = d.onArrive()
        assertTrue(a is Emitted.Arrive)
        assertNull(d.onArrive()) // dedup
        assertEquals(1, fx.calls.size)
        assertEquals(VibePatterns.THREE_SHORT, fx.calls[0].pattern)
    }

    @Test
    fun offRoute_debouncedToOncePerEpisode() {
        val (fx, d) = dispatcher(NavCueDispatcher.Config(offRouteDebounceMs = 20_000))
        now = 0
        assertTrue(d.onTurn(TurnEvent(Maneuver.OFF_ROUTE, 0)) is Emitted.OffRoute)
        now = 5_000
        assertNull(d.onTurn(TurnEvent(Maneuver.OFF_ROUTE, 0))) // within debounce
        now = 25_000
        assertTrue(d.onTurn(TurnEvent(Maneuver.OFF_ROUTE, 0)) is Emitted.OffRoute) // re-fires
        assertEquals(2, fx.calls.size)
        // "go back" pose: both hands 180°.
        assertEquals(FakeEffects.Call(180, 180, VibePatterns.CALL), fx.calls[0])
    }

    @Test
    fun unknown_isAlwaysNoOp() {
        val (fx, d) = dispatcher()
        assertNull(d.onTurn(TurnEvent(Maneuver.UNKNOWN, 5)))
        assertNull(d.onTurn(TurnEvent(Maneuver.UNKNOWN, 50)))
        assertTrue(fx.calls.isEmpty())
    }

    @Test
    fun reset_clearsAllStateSoNextRouteCuesCleanly() {
        val (fx, d) = dispatcher(NavCueDispatcher.Config(soonSuppressMs = 0))
        now = 0
        d.onTurn(TurnEvent(Maneuver.LEFT, 8)) // now cue
        d.reset()
        // Same maneuver/distance after reset cues again (state cleared).
        assertTrue(d.onTurn(TurnEvent(Maneuver.LEFT, 38)) is Emitted.Soon)
        assertEquals(2, fx.calls.size)
    }
}
