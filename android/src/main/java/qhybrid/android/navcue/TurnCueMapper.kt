package qhybrid.android.navcue

import qhybrid.android.notifications.VibePatterns

/**
 * WP-NAV — the **pure**, unit-testable core of the turn-by-turn navigation cue feature: map a
 * normalized [TurnEvent] (next maneuver + distance-to-turn) onto a [TurnCue] (which absolute hand
 * degrees to point + which buzz pattern to play). Mirrors WP-TRACKER's
 * [qhybrid.android.tracker.TrackerController]: no Android, no BLE, no OsmAnd — those live behind
 * seams in [NavCueDispatcher] / the on-device source.
 *
 * **Watch primitive (GROUND TRUTH, golden-tested).** `FossilController.buzz(pattern, hourDeg,
 * minDeg)` vibrates NOW and moves BOTH hands to absolute degrees (0–359), reusing the
 * NOTIFICATION_FILTER + NOTIFICATION_PLAY path — NO new wire bytes. So "point left" = move the
 * hands to 270° (9 o'clock), "right" = 90° (3 o'clock), etc. The watch resumes timekeeping after.
 *
 * **Both hands point** (user decision): for a directional cue BOTH the hour and minute hands point
 * to the same degree, so the direction is unambiguous on the dial. ARRIVE and OFF_ROUTE use their
 * own distinct poses (see [decide]).
 *
 * **Distance is NOT supplied here.** OsmAnd's `registerForNavigationUpdates` streams the next turn's
 * type + a live distance-to-turn continuously (every route-progress update), NOT a discrete
 * "turn now" event. The threshold/debounce/dedup that turns that stream into well-timed cues lives
 * in [NavCueDispatcher]; this mapper is the pure type→pose+buzz table only.
 */
object TurnCueMapper {

    /**
     * The normalized maneuver set — a superset that both OsmAnd turn types and (future) Maps
     * maneuver strings map onto. See [OsmAndTurnTypes] for the OsmAnd int → [Maneuver] mapping.
     */
    enum class Maneuver {
        STRAIGHT,
        SLIGHT_LEFT,
        LEFT,
        SHARP_LEFT,
        SLIGHT_RIGHT,
        RIGHT,
        SHARP_RIGHT,
        U_TURN,
        ROUNDABOUT,
        ARRIVE,
        OFF_ROUTE,
        UNKNOWN,
    }

    /**
     * Which stage of the two-stage cueing this is. The dispatcher decides WHICH stage fires (and
     * when); the mapper only varies the buzz STRENGTH by stage so a "turn now" feels stronger than
     * a "turn soon". The hand pose is identical for both stages (same direction).
     */
    enum class Stage {
        /** Early, gentle heads-up cue (~40 m before the turn). */
        SOON,

        /** Imminent, stronger cue right at the corner (~10 m). */
        NOW,
    }

    /**
     * A normalized turn event fed to [NavCueDispatcher]. [maneuver] is the NEXT turn's type;
     * [distanceMeters] is the live distance to it (>= 0). Source-agnostic (OsmAnd AIDL today).
     */
    data class TurnEvent(val maneuver: Maneuver, val distanceMeters: Int)

    /**
     * The decided cue: point BOTH hands to ([hourDeg], [minDeg]) (0–359) and buzz [buzzPattern]
     * (a 0–9 vibe value, see [VibePatterns]). Consumed by the [NavCueEffects] seam, which calls
     * `FossilController.buzz(buzzPattern, hourDeg, minDeg)`.
     */
    data class TurnCue(val hourDeg: Int, val minDeg: Int, val buzzPattern: Int)

    /** Clock-face degrees for each directional maneuver (0° = 12 o'clock, clockwise). */
    const val DEG_STRAIGHT = 0
    const val DEG_SLIGHT_RIGHT = 45
    const val DEG_RIGHT = 90
    const val DEG_SHARP_RIGHT = 135
    const val DEG_U_TURN = 180
    const val DEG_SHARP_LEFT = 225
    const val DEG_LEFT = 270
    const val DEG_SLIGHT_LEFT = 315

    /** OFF_ROUTE "go back" pose (user decision): both hands to 6 o'clock = 180° (straight down). */
    const val DEG_OFF_ROUTE = 180

    /** ARRIVE pose: both hands to 12 o'clock = 0° (a clean "you're here" upright). */
    const val DEG_ARRIVE = 0

    /**
     * The absolute hand degrees BOTH hands point to for a [maneuver], or `null` for a maneuver that
     * has no meaningful direction to point ([Maneuver.UNKNOWN]). ARRIVE and OFF_ROUTE return their
     * distinct poses. Pure + total over the enum.
     */
    fun degreesFor(maneuver: Maneuver): Int? = when (maneuver) {
        Maneuver.STRAIGHT -> DEG_STRAIGHT
        Maneuver.SLIGHT_RIGHT -> DEG_SLIGHT_RIGHT
        Maneuver.RIGHT -> DEG_RIGHT
        Maneuver.SHARP_RIGHT -> DEG_SHARP_RIGHT
        Maneuver.U_TURN -> DEG_U_TURN
        Maneuver.SHARP_LEFT -> DEG_SHARP_LEFT
        Maneuver.LEFT -> DEG_LEFT
        Maneuver.SLIGHT_LEFT -> DEG_SLIGHT_LEFT
        // A roundabout has no single turn angle on the dial; point straight (proceed into it).
        Maneuver.ROUNDABOUT -> DEG_STRAIGHT
        Maneuver.ARRIVE -> DEG_ARRIVE
        Maneuver.OFF_ROUTE -> DEG_OFF_ROUTE
        Maneuver.UNKNOWN -> null
    }

    /**
     * The buzz pattern for a [maneuver] at a [stage]:
     *   - directional SOON  → [VibePatterns.TWO_SHORT] (gentle double "turn soon"),
     *   - directional NOW   → [VibePatterns.ONE_LONG]  (stronger "turn NOW"),
     *   - ARRIVE            → [VibePatterns.THREE_SHORT] (a distinct celebratory triple),
     *   - OFF_ROUTE         → [VibePatterns.CALL] (a distinct triple, "you've left the route"),
     *   - UNKNOWN           → no cue (handled by [decide] returning null).
     * Pure + total.
     */
    fun buzzFor(maneuver: Maneuver, stage: Stage): Int = when (maneuver) {
        Maneuver.ARRIVE -> VibePatterns.THREE_SHORT
        Maneuver.OFF_ROUTE -> VibePatterns.CALL
        Maneuver.UNKNOWN -> VibePatterns.NO_VIBE
        else -> if (stage == Stage.NOW) VibePatterns.ONE_LONG else VibePatterns.TWO_SHORT
    }

    /**
     * Decide the [TurnCue] for a [maneuver] at a [stage], or `null` for [Maneuver.UNKNOWN] (a
     * graceful no-op — we never buzz a direction we can't point). Both hands point to the same
     * degree. Never throws.
     */
    fun decide(maneuver: Maneuver, stage: Stage): TurnCue? {
        val deg = degreesFor(maneuver) ?: return null
        val pattern = buzzFor(maneuver, stage)
        return TurnCue(hourDeg = deg, minDeg = deg, buzzPattern = pattern)
    }
}
