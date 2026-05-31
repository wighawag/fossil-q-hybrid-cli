package qhybrid.android.navcue

/**
 * WP-NAV — the narrow, injectable seam for the on-device nav-cue effect (mirrors WP-TRACKER's
 * [qhybrid.android.tracker.TrackerEffects]). The production impl
 * ([qhybrid.android.navcue.ServiceNavCueDispatch.SystemNavCueEffects]) buzzes the watch AND moves
 * BOTH hands to the turn direction in a single call via the existing golden primitive
 * `FossilController.buzz(pattern, hourDeg, minDeg)` (NO new wire bytes). Tests inject a fake so the
 * whole timing/decision policy in [NavCueDispatcher] is verified without OsmAnd or BLE.
 */
interface NavCueEffects {
    /**
     * Buzz the watch NOW with vibe [pattern] (0..9) AND point BOTH hands to ([hourDeg], [minDeg])
     * (each 0..359). One call = one `FossilController.buzz(pattern, hourDeg, minDeg)`.
     */
    fun buzzAndPoint(hourDeg: Int, minDeg: Int, pattern: Int)
}

/** A no-op [NavCueEffects] — the safe default so callers/tests never touch device effects. */
object NoopNavCueEffects : NavCueEffects {
    override fun buzzAndPoint(hourDeg: Int, minDeg: Int, pattern: Int) {}
}
