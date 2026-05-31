package qhybrid.android.navcue

import qhybrid.android.navcue.TurnCueMapper.Maneuver
import qhybrid.android.navcue.TurnCueMapper.Stage
import qhybrid.android.navcue.TurnCueMapper.TurnEvent

/**
 * WP-NAV — the **pure**, unit-testable brain that turns OsmAnd's continuous next-turn stream into
 * well-timed buzz+point cues. Mirrors WP-TRACKER's [qhybrid.android.tracker.TrackerDispatcher]:
 * holds the de-bounce/dedup state, runs the pure [TurnCueMapper], and forwards a non-null cue to
 * the injected [NavCueEffects] seam. Android-free (the buzz + hand move live behind the seam, the
 * clock is injected) so the whole timing policy is verified with a fake.
 *
 * **Why a brain is needed.** OsmAnd's `registerForNavigationUpdates` does NOT emit a discrete
 * "turn now"; it streams `{nextManeuver, distanceToTurn}` on every route-progress update (≈ each
 * GPS tick). This dispatcher decides WHEN to cue from that stream:
 *
 *   - **Two-stage** (user decision): a gentle "turn SOON" double-buzz at [Config.soonMeters] (~40 m)
 *     and a stronger "turn NOW" long-buzz at [Config.nowMeters] (~10 m). Each stage fires at most
 *     once per turn (dedup keyed on the maneuver + a monotonic step counter).
 *   - **Soon-suppression** (user decision): the SOON stage is skipped if it would fire within
 *     [Config.soonSuppressMs] (~15 s, time-based) of the LAST cue of the PREVIOUS turn — so a
 *     "left, then immediately right" pair doesn't machine-gun. The NOW stage is NEVER suppressed
 *     (it is the critical one).
 *   - **Dedup / new-turn detection:** a NEW turn is detected when the maneuver changes OR the
 *     distance jumps UP past the soon threshold (we've passed the old turn and are now counting
 *     down to the next). On a new turn the per-turn fired-flags reset.
 *   - **ARRIVE:** when [Config.arriveMeters] (~5 m) is reached on the final step (signalled by the
 *     source via [onArrive] / an [Maneuver.ARRIVE] event) we fire a distinct arrive cue once.
 *   - **OFF_ROUTE:** an [Maneuver.OFF_ROUTE] event fires the distinct "go back" cue (both hands to
 *     6 o'clock), debounced so it buzzes once per off-route episode, not every tick.
 *
 * All thresholds are [Config] knobs (defaults tuned for walking-in-a-city, the stated use case).
 * Never throws.
 */
class NavCueDispatcher(
    private val effects: NavCueEffects,
    private val config: Config = Config(),
    /** Monotonic clock in ms (injected for tests). Production: `SystemClock.elapsedRealtime()`. */
    private val clock: () -> Long = { 0L },
) {

    /** Tunable thresholds; defaults tuned for walking. All distances in metres, times in ms. */
    data class Config(
        /** Fire the gentle "turn soon" cue when distance first drops to/below this (~40 m). */
        val soonMeters: Int = 40,
        /** Fire the stronger "turn now" cue when distance first drops to/below this (~10 m). */
        val nowMeters: Int = 10,
        /** Fire the arrive cue when the final-step distance drops to/below this (~5 m). */
        val arriveMeters: Int = 5,
        /** Skip a SOON cue if the last cue (of the previous turn) was within this window (~15 s). */
        val soonSuppressMs: Long = 15_000,
        /** Min gap between OFF_ROUTE buzzes so a sustained off-route doesn't buzz every tick. */
        val offRouteDebounceMs: Long = 20_000,
    )

    /** The outcome of one [onTurn] call (for logging/tests); null when nothing was emitted. */
    sealed interface Emitted {
        val cue: TurnCueMapper.TurnCue

        data class Soon(val maneuver: Maneuver, override val cue: TurnCueMapper.TurnCue) : Emitted
        data class Now(val maneuver: Maneuver, override val cue: TurnCueMapper.TurnCue) : Emitted
        data class Arrive(override val cue: TurnCueMapper.TurnCue) : Emitted
        data class OffRoute(override val cue: TurnCueMapper.TurnCue) : Emitted
    }

    // ---- per-turn dedup state -------------------------------------------------
    private var currentManeuver: Maneuver? = null
    private var step: Long = 0
    private var soonFiredStep: Long = -1
    private var nowFiredStep: Long = -1
    private var lastCueAtMs: Long = Long.MIN_VALUE
    private var lastOffRouteAtMs: Long = Long.MIN_VALUE
    private var arriveFired = false

    /**
     * Handle one navigation update. Returns what (if anything) was emitted. The source converts an
     * OsmAnd `ADirectionInfo` into a [TurnEvent] (via [OsmAndTurnTypes.toManeuver]) and calls this.
     * OFF_ROUTE and ARRIVE maneuvers are handled distinctly; UNKNOWN is a graceful no-op.
     */
    fun onTurn(event: TurnEvent): Emitted? {
        return when (event.maneuver) {
            Maneuver.UNKNOWN -> null
            Maneuver.OFF_ROUTE -> handleOffRoute()
            Maneuver.ARRIVE -> handleArrive()
            else -> handleDirectional(event)
        }
    }

    /**
     * Signal arrival explicitly (the source may know the final step independent of a turn type).
     * Equivalent to `onTurn(TurnEvent(ARRIVE, 0))`.
     */
    fun onArrive(): Emitted? = handleArrive()

    /** Reset all state — call when navigation stops/starts so a new route cues cleanly. */
    fun reset() {
        currentManeuver = null
        step = 0
        soonFiredStep = -1
        nowFiredStep = -1
        lastCueAtMs = Long.MIN_VALUE
        lastOffRouteAtMs = Long.MIN_VALUE
        arriveFired = false
    }

    private fun handleDirectional(event: TurnEvent): Emitted? {
        // Detect a NEW turn: maneuver changed, or distance jumped back up past the soon threshold
        // (we've passed the previous turn and are counting down to the next of the same type).
        val isNewTurn = currentManeuver != event.maneuver ||
            (soonFiredStep >= 0 && event.distanceMeters > config.soonMeters * 2)
        if (isNewTurn) {
            currentManeuver = event.maneuver
            step++
            soonFiredStep = -1
            nowFiredStep = -1
            arriveFired = false
        }

        val now = clock()

        // NOW stage — fire once per turn, never suppressed (the critical cue at the corner).
        if (event.distanceMeters <= config.nowMeters && nowFiredStep != step) {
            val cue = TurnCueMapper.decide(event.maneuver, Stage.NOW) ?: return null
            nowFiredStep = step
            // A NOW cue also covers the SOON slot (don't then fire a late soon for this turn).
            soonFiredStep = step
            lastCueAtMs = now
            effects.buzzAndPoint(cue.hourDeg, cue.minDeg, cue.buzzPattern)
            return Emitted.Now(event.maneuver, cue)
        }

        // SOON stage — fire once per turn, but suppressed if too soon after the last cue.
        if (event.distanceMeters in (config.nowMeters + 1)..config.soonMeters && soonFiredStep != step) {
            soonFiredStep = step // mark attempted regardless, so we don't re-evaluate every tick
            val sinceLast = now - lastCueAtMs
            if (lastCueAtMs != Long.MIN_VALUE && sinceLast < config.soonSuppressMs) {
                return null // suppressed: previous turn's cue was too recent
            }
            val cue = TurnCueMapper.decide(event.maneuver, Stage.SOON) ?: return null
            lastCueAtMs = now
            effects.buzzAndPoint(cue.hourDeg, cue.minDeg, cue.buzzPattern)
            return Emitted.Soon(event.maneuver, cue)
        }

        return null
    }

    private fun handleArrive(): Emitted? {
        if (arriveFired) return null
        arriveFired = true
        val cue = TurnCueMapper.decide(Maneuver.ARRIVE, Stage.NOW) ?: return null
        lastCueAtMs = clock()
        effects.buzzAndPoint(cue.hourDeg, cue.minDeg, cue.buzzPattern)
        return Emitted.Arrive(cue)
    }

    private fun handleOffRoute(): Emitted? {
        val now = clock()
        if (lastOffRouteAtMs != Long.MIN_VALUE && now - lastOffRouteAtMs < config.offRouteDebounceMs) {
            return null
        }
        lastOffRouteAtMs = now
        val cue = TurnCueMapper.decide(Maneuver.OFF_ROUTE, Stage.NOW) ?: return null
        lastCueAtMs = now
        effects.buzzAndPoint(cue.hourDeg, cue.minDeg, cue.buzzPattern)
        return Emitted.OffRoute(cue)
    }
}
