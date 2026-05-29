package qhybrid.android.calibration

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import qhybrid.android.db.WatchEntity
import qhybrid.android.db.WatchRepository

/**
 * WP16e — the Calibration screen's immutable UI state. A combination of:
 *   - the WP4 active watch (observed) — purely to know which watch is active and to disable the
 *     UI when none is connected, and
 *   - the IN-MEMORY, ephemeral calibration session (NOT persisted anywhere).
 *
 * **CALIBRATION IS EPHEMERAL — NOTHING IS PERSISTED.** [inProgress], [selectedHand], and
 * [offsets] live ONLY here in memory: they are zeroed on `enterCalibration()` and cleared on
 * `exitCalibration()`. Re-opening the screen ALWAYS starts a fresh, neutral session — there is no
 * saved offset to reload (a saved offset would re-apply a now-wrong correction; see
 * [CalibrationSync]). There is NO Room entity / DAO / repository method for calibration.
 *
 * **MODEL-AGNOSTIC:** the offsets map is keyed by the flat [CalibrationHands] catalog (hour /
 * minute / sub-eye); no per-model hand counts or sub-eye layouts are hard-coded.
 */
data class CalibrationUiState(
    /** The WP4 active watch (observed), or null if none. */
    val activeWatch: WatchEntity? = null,
    /** True while an in-memory calibration session is open (between enter and exit). */
    val inProgress: Boolean = false,
    /** The hand currently targeted by the nudge controls (only meaningful while in progress). */
    val selectedHand: String = CalibrationHands.DEFAULT,
    /**
     * The in-memory per-hand offset in degrees (0–359), keyed by the [CalibrationHands] id.
     * Hands not present in the map are treated as the neutral [HandDegrees.NEUTRAL] (0°).
     */
    val offsets: Map<String, Int> = emptyMap(),
) {
    val activeMac: String? get() = activeWatch?.macAddress
    val hasActiveWatch: Boolean get() = activeWatch != null

    /** Whether the nudge / apply controls should be enabled (active watch + a live session). */
    val canCalibrate: Boolean get() = hasActiveWatch && inProgress

    /** The current offset (0–359) for [hand]; defaults to the neutral 0° if unset. */
    fun offsetOf(hand: String): Int = offsets[hand] ?: HandDegrees.NEUTRAL
}

/**
 * WP16e — observes the WP4 active watch (for [CalibrationUiState.activeWatch] /
 * [CalibrationUiState.hasActiveWatch] ONLY) and holds the in-progress, IN-MEMORY calibration
 * session. Mirrors WP16b/c/d's active-watch half exactly (the active-watch [combine] over
 * `observeActiveWatch`; an injectable [CalibrationSync] seam; a production [factory]).
 *
 * The calibration session ([inProgress], [selectedHand], [offsets]) is plain in-memory state
 * combined into the same UiState — **NO DB writes, NO persistence**. "Apply" is a fire-and-forget
 * LIVE command via the deferred [CalibrationSync] seam ([ServiceCalibrationSync.CALIBRATION_WIRED]
 * = false until WP14 / WP F); there is nothing to save app-side. Degree handling is centralized in
 * [HandDegrees] (normalize to 0–359 with wrap-around); the hand vocabulary is [CalibrationHands].
 */
open class CalibrationViewModel(
    private val repo: WatchRepository,
    private val sync: CalibrationSync,
    // Tests inject a TestScope/real scope; production passes null → uses [viewModelScope].
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val coroutineScope: CoroutineScope = scope ?: viewModelScope

    /** The ephemeral, in-memory calibration session (NOT persisted). */
    private data class Session(
        val inProgress: Boolean = false,
        val selectedHand: String = CalibrationHands.DEFAULT,
        val offsets: Map<String, Int> = emptyMap(),
    )

    private val session = MutableStateFlow(Session())

    val uiState: StateFlow<CalibrationUiState> =
        combine(repo.observeActiveWatch(), session) { active, s ->
            CalibrationUiState(
                activeWatch = active,
                inProgress = s.inProgress,
                selectedHand = s.selectedHand,
                offsets = s.offsets,
            )
        }.stateIn(coroutineScope, SharingStarted.WhileSubscribed(5_000), CalibrationUiState())

    // ---- intents -------------------------------------------------------------

    /**
     * Start a FRESH, neutral calibration session: in-progress, the default hand selected, and all
     * offsets zeroed. Always starts neutral — it NEVER reloads a previous offset (calibration is
     * ephemeral; see [CalibrationViewModel]).
     */
    fun enterCalibration() {
        session.value = Session(
            inProgress = true,
            selectedHand = CalibrationHands.DEFAULT,
            offsets = emptyMap(),
        )
    }

    /**
     * Exit calibration: clear the in-memory session entirely (no persistence). Re-entering via
     * [enterCalibration] starts neutral again — the discarded offsets are gone for good.
     */
    fun exitCalibration() {
        session.value = Session()
    }

    /** Select which hand the nudge controls target. Unknown/blank ids fall back to the default. */
    fun selectHand(hand: String) {
        session.update { it.copy(selectedHand = CalibrationHands.normalize(hand)) }
    }

    /**
     * Nudge [hand] by [deltaDegrees] (may be negative), applying + normalizing onto the 0–359 ring
     * via [HandDegrees.nudge] (tolerates wrap past 0 and past 359). No-op unless a session is in
     * progress. The hand id is normalized so an unknown id can't poison the map.
     */
    fun nudge(hand: String, deltaDegrees: Int) {
        session.update { s ->
            if (!s.inProgress) return@update s
            val h = CalibrationHands.normalize(hand)
            val current = s.offsets[h] ?: HandDegrees.NEUTRAL
            s.copy(offsets = s.offsets + (h to HandDegrees.nudge(current, deltaDegrees)))
        }
    }

    /**
     * Set [hand]'s offset to an absolute [degrees] value (normalized to 0–359; tolerates negative
     * / out-of-range input). No-op unless a session is in progress.
     */
    fun setHand(hand: String, degrees: Int) {
        session.update { s ->
            if (!s.inProgress) return@update s
            val h = CalibrationHands.normalize(hand)
            s.copy(offsets = s.offsets + (h to HandDegrees.normalize(degrees)))
        }
    }

    /**
     * Apply the current session LIVE via the [CalibrationSync] seam (fire-and-forget; nothing is
     * persisted). No-op (returns false) unless a session is in progress with an active watch.
     * Returns whether the real move-hands/save-calibration pipeline is wired yet (false until
     * WP14 / WP F; the UI surfaces an "on-device-pending" note when false).
     */
    fun apply(): Boolean {
        val state = uiState.value
        if (!state.hasActiveWatch || !state.inProgress) return false
        return sync.apply(
            hourDegrees = state.offsets[CalibrationHands.HOUR],
            minuteDegrees = state.offsets[CalibrationHands.MINUTE],
            subDegrees = state.offsets[CalibrationHands.SUB],
        )
    }

    private inline fun MutableStateFlow<Session>.update(transform: (Session) -> Session) {
        value = transform(value)
    }

    companion object {
        /** Production factory: real [WatchRepository] + [ServiceCalibrationSync]. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CalibrationViewModel(
                        repo = WatchRepository(appContext),
                        sync = ServiceCalibrationSync(appContext),
                    ) as T
            }
        }
    }
}
