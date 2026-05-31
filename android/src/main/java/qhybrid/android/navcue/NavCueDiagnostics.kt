package qhybrid.android.navcue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * WP-NAV — a process-wide, in-memory diagnostic bus for the navigation-cue pipeline. Every stage
 * pushes a timestamped [Entry] here; the [NavCueDiagnosticsScreen] observes [log] + [status] live so
 * we can SEE what OsmAnd is delivering, how it maps, what the dispatcher decides, and whether the
 * watch play actually goes out — instead of debugging blind.
 *
 * Pure Kotlin (no Android types) so it stays trivially testable and can be fed from the Binder
 * thread (OsmAnd callbacks) and the ble-worker alike. A bounded ring buffer ([MAX_ENTRIES]) keeps
 * memory flat during a long drive.
 */
object NavCueDiagnostics {

    /** A single diagnostic line. [level] drives colour/filtering in the UI. */
    data class Entry(
        val atMs: Long,
        val level: Level,
        val stage: Stage,
        val message: String,
    )

    enum class Level { INFO, CUE, WARN, ERROR }

    /** Which pipeline stage emitted the line (for grouping/filtering). */
    enum class Stage { SOURCE, RAW, MAPPED, DECISION, WATCH, LIFECYCLE }

    /** A compact snapshot of the most-recent state, for the header cards. */
    data class Status(
        val enabled: Boolean = false,
        val osmAndInstalled: Boolean = false,
        val boundPackage: String? = null,
        val bound: Boolean = false,
        val registeredCallbackId: Long? = null,
        val lastRawAtMs: Long? = null,
        val lastRawTurnType: Int? = null,
        val lastRawDistanceM: Int? = null,
        val lastManeuver: String? = null,
        val lastCueAtMs: Long? = null,
        val lastCueText: String? = null,
        val linkUpAtLastCue: Boolean? = null,
        val totalRawCallbacks: Long = 0,
        val totalCuesSent: Long = 0,
    )

    const val MAX_ENTRIES = 400

    private val _log = MutableStateFlow<List<Entry>>(emptyList())
    val log: StateFlow<List<Entry>> = _log

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** Wall-clock injected so this stays unit-testable; production uses System.currentTimeMillis. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    fun add(level: Level, stage: Stage, message: String) {
        val e = Entry(clock(), level, stage, message)
        _log.update { prev ->
            val next = if (prev.size >= MAX_ENTRIES) prev.drop(prev.size - MAX_ENTRIES + 1) else prev
            next + e
        }
    }

    fun info(stage: Stage, message: String) = add(Level.INFO, stage, message)
    fun warn(stage: Stage, message: String) = add(Level.WARN, stage, message)
    fun error(stage: Stage, message: String) = add(Level.ERROR, stage, message)
    fun cue(stage: Stage, message: String) = add(Level.CUE, stage, message)

    fun clear() {
        _log.value = emptyList()
    }

    // ---- status mutators (each also logs a line) ------------------------------

    fun onLifecycle(message: String, enabled: Boolean? = null, osmAndInstalled: Boolean? = null) {
        _status.update {
            it.copy(
                enabled = enabled ?: it.enabled,
                osmAndInstalled = osmAndInstalled ?: it.osmAndInstalled,
            )
        }
        info(Stage.LIFECYCLE, message)
    }

    fun onBound(pkg: String?, bound: Boolean) {
        _status.update { it.copy(boundPackage = pkg, bound = bound) }
        info(Stage.SOURCE, if (bound) "bound OsmAnd service: $pkg" else "OsmAnd service disconnected")
    }

    fun onRegistered(callbackId: Long) {
        _status.update { it.copy(registeredCallbackId = callbackId) }
        info(Stage.SOURCE, "registered for nav updates (callbackId=$callbackId)")
    }

    fun onRawCallback(turnType: Int, distanceM: Int, leftSide: Boolean) {
        _status.update {
            it.copy(
                lastRawAtMs = clock(),
                lastRawTurnType = turnType,
                lastRawDistanceM = distanceM,
                totalRawCallbacks = it.totalRawCallbacks + 1,
            )
        }
        info(Stage.RAW, "OsmAnd: turnType=$turnType distance=${distanceM}m leftSide=$leftSide")
    }

    fun onMapped(maneuver: String, distanceM: Int) {
        _status.update { it.copy(lastManeuver = maneuver) }
        info(Stage.MAPPED, "→ $maneuver @ ${distanceM}m")
    }

    fun onDecision(message: String, isCue: Boolean) {
        if (isCue) cue(Stage.DECISION, message) else info(Stage.DECISION, message)
    }

    fun onCueSent(text: String, linkUp: Boolean) {
        _status.update {
            it.copy(
                lastCueAtMs = clock(),
                lastCueText = text,
                linkUpAtLastCue = linkUp,
                totalCuesSent = if (linkUp) it.totalCuesSent + 1 else it.totalCuesSent,
            )
        }
        if (linkUp) cue(Stage.WATCH, "sent to watch: $text")
        else warn(Stage.WATCH, "DROPPED (watch link down): $text")
    }
}
