package qhybrid.android.navcue

import android.util.Log
import qhybrid.android.navcue.TurnCueMapper.TurnEvent

/**
 * WP-NAV — the shared, backend-agnostic glue from a raw OsmAnd navigation callback to the pure
 * [NavCueDispatcher] (+ diagnostics). Both AIDL backends (the `net.osmand.aidlapi.*` V2 service and
 * the legacy `net.osmand.aidl.*` service) deliver the SAME three integers
 * (`turnType`, `distanceTo`, `isLeftSide`) via their respective `ADirectionInfo`; this turns them
 * into a [TurnEvent] and runs the dispatcher, so the namespace-specific backends stay tiny.
 */
object NavCueFeed {
    private const val TAG = "FossilQ-NavCue"

    /** Feed one raw nav update (from either AIDL namespace) into [dispatcher]. Never throws. */
    fun onRaw(dispatcher: NavCueDispatcher, turnType: Int, distanceTo: Int, leftSide: Boolean) {
        NavCueDiagnostics.onRawCallback(turnType, distanceTo, leftSide)
        val maneuver = OsmAndTurnTypes.toManeuver(turnType)
        val distance = distanceTo.coerceAtLeast(0)
        NavCueDiagnostics.onMapped(maneuver.name, distance)
        runCatching {
            val emitted = dispatcher.onTurn(TurnEvent(maneuver, distance))
            if (emitted != null) {
                val c = emitted.cue
                NavCueDiagnostics.onDecision(
                    "${emitted::class.simpleName} → hands ${c.hourDeg}° vibe ${c.buzzPattern}",
                    isCue = true,
                )
            } else {
                NavCueDiagnostics.onDecision("no cue (out-of-band / dedup / suppressed)", isCue = false)
            }
        }.onFailure {
            Log.w(TAG, "dispatcher.onTurn failed", it)
            NavCueDiagnostics.error(NavCueDiagnostics.Stage.DECISION, "dispatcher.onTurn failed: ${it.message}")
        }
    }
}

/**
 * WP-NAV — a backend that binds one OsmAnd AIDL flavour/namespace and feeds nav updates to a
 * [NavCueDispatcher]. Two impls: [AidlApiV2NavSource] (`net.osmand.aidlapi.*` V2 service) and
 * [AidlLegacyNavSource] (`net.osmand.aidl.*` service). [OsmAndNavSource] selects/probes between
 * them per the [NavCueBackend] config.
 */
interface NavUpdateSource {
    /** A short human id for the backend (for logs/UI), e.g. "aidlapi-v2" / "aidl-v2". */
    val id: String

    /**
     * Bind [pkg]'s service for this backend + register for nav updates. Returns true iff the bind
     * was accepted by the system (`bindService` returned true). Idempotent per instance.
     */
    fun start(pkg: String): Boolean

    /** Unregister + unbind. Idempotent. */
    fun stop()
}
