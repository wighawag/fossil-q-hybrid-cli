package qhybrid.android.navcue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import qhybrid.android.navcue.TurnCueMapper.Maneuver
import qhybrid.android.navcue.TurnCueMapper.Stage
import qhybrid.android.notifications.VibePatterns

/**
 * WP-NAV — unit tests for the pure [TurnCueMapper]: the direction→degrees table (both hands), the
 * per-stage buzz strength, ARRIVE / OFF_ROUTE distinct poses, and UNKNOWN no-op. No Android.
 */
class TurnCueMapperTest {

    @Test
    fun degrees_bothHandsPointTheTurnDirection() {
        assertEquals(0, TurnCueMapper.degreesFor(Maneuver.STRAIGHT))
        assertEquals(45, TurnCueMapper.degreesFor(Maneuver.SLIGHT_RIGHT))
        assertEquals(90, TurnCueMapper.degreesFor(Maneuver.RIGHT))
        assertEquals(135, TurnCueMapper.degreesFor(Maneuver.SHARP_RIGHT))
        assertEquals(180, TurnCueMapper.degreesFor(Maneuver.U_TURN))
        assertEquals(225, TurnCueMapper.degreesFor(Maneuver.SHARP_LEFT))
        assertEquals(270, TurnCueMapper.degreesFor(Maneuver.LEFT))
        assertEquals(315, TurnCueMapper.degreesFor(Maneuver.SLIGHT_LEFT))
        assertEquals(0, TurnCueMapper.degreesFor(Maneuver.ROUNDABOUT))
    }

    @Test
    fun offRoute_pointsBothHandsTo6oclock_goBack() {
        assertEquals(180, TurnCueMapper.degreesFor(Maneuver.OFF_ROUTE))
        val cue = TurnCueMapper.decide(Maneuver.OFF_ROUTE, Stage.NOW)!!
        // Both hands to 6 o'clock = 180° (the "turn around / go back" pose).
        assertEquals(180, cue.hourDeg)
        assertEquals(180, cue.minDeg)
        // A distinct off-route buzz (CALL triple), tellable apart from a planned U-turn.
        assertEquals(VibePatterns.CALL, cue.buzzPattern)
    }

    @Test
    fun arrive_distinctPoseAndTriple() {
        val cue = TurnCueMapper.decide(Maneuver.ARRIVE, Stage.NOW)!!
        assertEquals(0, cue.hourDeg)
        assertEquals(0, cue.minDeg)
        assertEquals(VibePatterns.THREE_SHORT, cue.buzzPattern)
    }

    @Test
    fun unknown_isNoCue() {
        assertNull(TurnCueMapper.degreesFor(Maneuver.UNKNOWN))
        assertNull(TurnCueMapper.decide(Maneuver.UNKNOWN, Stage.NOW))
        assertNull(TurnCueMapper.decide(Maneuver.UNKNOWN, Stage.SOON))
    }

    @Test
    fun directional_soonIsGentleDouble_nowIsStrongerLong() {
        val soon = TurnCueMapper.decide(Maneuver.LEFT, Stage.SOON)!!
        val now = TurnCueMapper.decide(Maneuver.LEFT, Stage.NOW)!!
        // Same direction (both hands to 270°), different buzz strength by stage.
        assertEquals(270, soon.hourDeg)
        assertEquals(270, soon.minDeg)
        assertEquals(270, now.hourDeg)
        assertEquals(VibePatterns.TWO_SHORT, soon.buzzPattern)
        assertEquals(VibePatterns.ONE_LONG, now.buzzPattern)
    }

    @Test
    fun bothHandsAlwaysEqual_forEveryPointableManeuver() {
        for (m in Maneuver.values()) {
            val cue = TurnCueMapper.decide(m, Stage.NOW) ?: continue
            assertEquals("both hands equal for $m", cue.hourDeg, cue.minDeg)
        }
    }
}
