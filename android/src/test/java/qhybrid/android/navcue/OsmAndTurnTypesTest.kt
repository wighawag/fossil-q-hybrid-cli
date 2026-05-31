package qhybrid.android.navcue

import org.junit.Assert.assertEquals
import org.junit.Test
import qhybrid.android.navcue.TurnCueMapper.Maneuver

/**
 * WP-NAV — unit tests for the OsmAnd `turnType` int → [Maneuver] mapping against OsmAnd's exact
 * documented constant values (OsmandAidlConstants.TURN_TYPE_*). Pure, no Android.
 */
class OsmAndTurnTypesTest {

    @Test
    fun mapsEachOsmAndTurnTypeToTheRightManeuver() {
        assertEquals(Maneuver.STRAIGHT, OsmAndTurnTypes.toManeuver(1))   // C
        assertEquals(Maneuver.LEFT, OsmAndTurnTypes.toManeuver(2))       // TL
        assertEquals(Maneuver.SLIGHT_LEFT, OsmAndTurnTypes.toManeuver(3)) // TSLL
        assertEquals(Maneuver.SHARP_LEFT, OsmAndTurnTypes.toManeuver(4)) // TSHL
        assertEquals(Maneuver.RIGHT, OsmAndTurnTypes.toManeuver(5))      // TR
        assertEquals(Maneuver.SLIGHT_RIGHT, OsmAndTurnTypes.toManeuver(6)) // TSLR
        assertEquals(Maneuver.SHARP_RIGHT, OsmAndTurnTypes.toManeuver(7)) // TSHR
        assertEquals(Maneuver.SLIGHT_LEFT, OsmAndTurnTypes.toManeuver(8)) // KL keep-left
        assertEquals(Maneuver.SLIGHT_RIGHT, OsmAndTurnTypes.toManeuver(9)) // KR keep-right
        assertEquals(Maneuver.U_TURN, OsmAndTurnTypes.toManeuver(10))    // TU
        assertEquals(Maneuver.U_TURN, OsmAndTurnTypes.toManeuver(11))    // TRU right U-turn
        assertEquals(Maneuver.OFF_ROUTE, OsmAndTurnTypes.toManeuver(12)) // OFFR
        assertEquals(Maneuver.ROUNDABOUT, OsmAndTurnTypes.toManeuver(13)) // RNDB
        assertEquals(Maneuver.ROUNDABOUT, OsmAndTurnTypes.toManeuver(14)) // RNLB
    }

    @Test
    fun unknownOrOutOfRange_isUnknown() {
        assertEquals(Maneuver.UNKNOWN, OsmAndTurnTypes.toManeuver(0))
        assertEquals(Maneuver.UNKNOWN, OsmAndTurnTypes.toManeuver(-1))
        assertEquals(Maneuver.UNKNOWN, OsmAndTurnTypes.toManeuver(99))
    }
}
