package qhybrid.android.navcue

import qhybrid.android.navcue.TurnCueMapper.Maneuver

/**
 * WP-NAV — the **pure**, unit-testable mapping from OsmAnd's `ADirectionInfo.turnType` int value
 * onto our normalized [Maneuver]. Kept separate from [TurnCueMapper] so each nav source has its own
 * source-specific mapper, testable in isolation against that source's exact emitted values (a
 * future `MapsManeuver` mapper would live alongside this one). No Android, no OsmAnd dependency —
 * just the documented int contract.
 *
 * **Values are GROUND TRUTH from OsmAnd** (`net.osmand.aidl.OsmandAidlConstants`, added with
 * `registerForNavigationUpdates`; the constants are stable across the `net.osmand.aidl.*` and
 * `net.osmand.aidlapi.*` namespaces):
 * ```
 *  1 C     continue (go straight)
 *  2 TL    turn left
 *  3 TSLL  turn slightly left
 *  4 TSHL  turn sharply left
 *  5 TR    turn right
 *  6 TSLR  turn slightly right
 *  7 TSHR  turn sharply right
 *  8 KL    keep left
 *  9 KR    keep right
 * 10 TU    U-turn (left)
 * 11 TRU   right U-turn
 * 12 OFFR  off route
 * 13 RNDB  roundabout
 * 14 RNLB  roundabout left
 * ```
 * A `distanceTo <= 0` with no meaningful turn, or any unrecognized value, maps to
 * [Maneuver.UNKNOWN] (the dispatcher then no-ops). ARRIVE is NOT an OsmAnd turn type — it is
 * derived by [NavCueDispatcher] from the route's final step collapsing to ~0 m (see there).
 */
object OsmAndTurnTypes {

    // OsmAnd turn-type constants (OsmandAidlConstants.TURN_TYPE_*).
    const val TURN_TYPE_C = 1
    const val TURN_TYPE_TL = 2
    const val TURN_TYPE_TSLL = 3
    const val TURN_TYPE_TSHL = 4
    const val TURN_TYPE_TR = 5
    const val TURN_TYPE_TSLR = 6
    const val TURN_TYPE_TSHR = 7
    const val TURN_TYPE_KL = 8
    const val TURN_TYPE_KR = 9
    const val TURN_TYPE_TU = 10
    const val TURN_TYPE_TRU = 11
    const val TURN_TYPE_OFFR = 12
    const val TURN_TYPE_RNDB = 13
    const val TURN_TYPE_RNLB = 14

    /**
     * Map an OsmAnd `turnType` int onto a normalized [Maneuver]. Total + never throws; any
     * unrecognized value → [Maneuver.UNKNOWN].
     *   - keep-left/keep-right (8/9) → the corresponding SLIGHT_* (a gentle lane keep),
     *   - both U-turn variants (10/11) → [Maneuver.U_TURN],
     *   - off-route (12) → [Maneuver.OFF_ROUTE] (the "go back" pose),
     *   - roundabouts (13/14) → [Maneuver.ROUNDABOUT] (point straight = proceed into it).
     */
    fun toManeuver(turnType: Int): Maneuver = when (turnType) {
        TURN_TYPE_C -> Maneuver.STRAIGHT
        TURN_TYPE_TL -> Maneuver.LEFT
        TURN_TYPE_TSLL -> Maneuver.SLIGHT_LEFT
        TURN_TYPE_TSHL -> Maneuver.SHARP_LEFT
        TURN_TYPE_TR -> Maneuver.RIGHT
        TURN_TYPE_TSLR -> Maneuver.SLIGHT_RIGHT
        TURN_TYPE_TSHR -> Maneuver.SHARP_RIGHT
        TURN_TYPE_KL -> Maneuver.SLIGHT_LEFT
        TURN_TYPE_KR -> Maneuver.SLIGHT_RIGHT
        TURN_TYPE_TU -> Maneuver.U_TURN
        TURN_TYPE_TRU -> Maneuver.U_TURN
        TURN_TYPE_OFFR -> Maneuver.OFF_ROUTE
        TURN_TYPE_RNDB -> Maneuver.ROUNDABOUT
        TURN_TYPE_RNLB -> Maneuver.ROUNDABOUT
        else -> Maneuver.UNKNOWN
    }
}
