package qhybrid.android.tracker

import qhybrid.android.tracker.TrackerController.TrackerAction
import qhybrid.android.tracker.TrackerController.WaypointKind

/**
 * WP-TRACKER \u2014 the testable glue between the watch's `onEventJson` music-gesture stream and the
 * on-device tracker effects (record a GPS waypoint / ring the phone / buzz back). Mirrors WP12's
 * [qhybrid.android.music.MusicDispatcher]: parse the JSON line via the pure
 * [TrackerController.parse], run the pure [TrackerController.decide], and forward the outcome to the
 * injected [TrackerEffects] seam. Android-free \u2014 the GPS fix, Room write, ring and buzz all live
 * behind [TrackerEffects] so the whole routing path is unit-testable with a fake.
 *
 * Adds NO new wire bytes \u2014 the JSON contract is already emitted by the adapter; buzz-back reuses the
 * existing [qhybrid.android.WatchConnectionService.buzzNow] / `buzzPlayOnly` path.
 */
class TrackerDispatcher(
    /** The on-device effects seam (production = [ServiceTrackerDispatch.SystemTrackerEffects]). */
    private val effects: TrackerEffects,
) {

    /**
     * Handle one `onEventJson` line. Returns the [TrackerAction] taken (for logging/tests), or
     * `null` when the line was not a tracker-mapped gesture (a graceful no-op). Never throws.
     *
     * **Caller contract:** only call this when the global multi-function role is TRACKER \u2014 the
     * MUSIC role keeps dispatching to [qhybrid.android.music.ServiceMusicDispatch] (see the routing
     * in [qhybrid.android.WatchConnectionService]). The two never both run for one 0x05 event.
     */
    fun onEventJson(json: String?): TrackerAction? {
        val gesture = TrackerController.parse(json) ?: return null
        val action = TrackerController.decide(gesture)
        when (action) {
            is TrackerAction.Log -> {
                // The waypoint effect owns its OWN feedback (received tick now, then success/failure
                // when the GPS fix resolves), so we do NOT buzzBack the action pattern here (that
                // would double-buzz + buzz "success" before GPS even resolved). See
                // [ServiceTrackerDispatch.recordWaypointWithFeedback].
                effects.recordWaypoint(action.kind)
            }
            is TrackerAction.RingPhone -> {
                effects.ringPhone()
                effects.buzzBack(action.buzzPattern)
            }
        }
        return action
    }
}

/**
 * WP-TRACKER \u2014 narrow, injectable seam for the on-device tracker effects (mirrors WP12's
 * [qhybrid.android.music.MusicSessionDispatcher]). The production impl
 * ([ServiceTrackerDispatch.SystemTrackerEffects]) records a GPS fix into the Room waypoint table,
 * rings the phone, and buzzes the watch via the existing buzz path; tests inject a fake so all the
 * routing logic above is verified without GPS / Room / BLE.
 *
 * The real GPS + ring effects are wired ([SystemLocationSource] / [SystemPhoneRinger], zero Google
 * Play Services); they need a live device + granted location permission. They are cleanly
 * seam-injected so the decision path is fully unit-tested.
 */
interface TrackerEffects {
    /** Capture the current GPS fix and persist it as a [kind] waypoint (best-effort). */
    fun recordWaypoint(kind: WaypointKind)

    /** Ring/find the phone (loud tone), so a pocketed user can locate it / confirm range. */
    fun ringPhone()

    /** Buzz the watch back with vibe [pattern] (0..9) to confirm the action landed. */
    fun buzzBack(pattern: Int)

    companion object {
        /**
         * WP-TRACKER: whether the on-device tracker effects are FULLY wired. The buzz-back + Room
         * write, the live GPS fix ([SystemLocationSource]) and the loud phone ring
         * ([SystemPhoneRinger]) are all wired behind the location/audio seams (zero Google Play
         * Services); pending only the on-device hardware sign-off in WP-TRACKER-GPS-WIRING-PLAN.md.
         */
        const val TRACKER_EFFECTS_WIRED = true
    }
}

/** A no-op [TrackerEffects] used as a safe default so callers/tests never touch device effects. */
object NoopTrackerEffects : TrackerEffects {
    override fun recordWaypoint(kind: WaypointKind) {}
    override fun ringPhone() {}
    override fun buzzBack(pattern: Int) {}
}
