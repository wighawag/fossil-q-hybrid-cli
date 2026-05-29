package qhybrid.android.calibration

/**
 * WP16e — centralized, **model-agnostic** calibration vocabulary: the hand identifiers + labels
 * and the degree/step conventions. Shared by the ViewModel, the tests, and the Compose UI (same
 * discipline as WP16b [qhybrid.android.alarms.AlarmDays], WP16c
 * [qhybrid.android.notifications.VibePatterns], and WP16d
 * [qhybrid.android.buttons.ButtonModes]) so the controls and the in-memory session can never
 * drift apart.
 *
 * **Design decision (WP16e): MODEL-AGNOSTIC.** Different watches expose different hands/sub-dials
 * (2-hand, 3-hand, sub-eye). We deliberately do NOT hard-code per-model hand counts or sub-eye
 * layouts. [CalibrationHands.ALL] is the flat catalog of hands the protocol calibration flow can
 * nudge (mirroring the CLI `calibrate` command's hour/minute/sub-eye); the UI lets the user nudge
 * whichever it wants without a model lookup table. A watch that physically lacks one of these
 * hands simply ignores a move for it (hardware behaviour is on-device-pending / WP14 / WP F).
 *
 * Hand ids mirror the CLI `calibrate` command's vocabulary 1:1 (`Main.CalibrateCmd`):
 *   - `HOUR`   — the hour hand,
 *   - `MINUTE` — the minute hand,
 *   - `SUB`    — the sub-eye (activity) hand.
 */
object CalibrationHands {
    const val HOUR = "HOUR"
    const val MINUTE = "MINUTE"
    const val SUB = "SUB"

    /** All calibratable hands in display order. */
    val ALL = listOf(HOUR, MINUTE, SUB)

    /** Default selected hand when a calibration session starts. */
    const val DEFAULT = HOUR

    private val LABELS = mapOf(
        HOUR to "Hour hand",
        MINUTE to "Minute hand",
        SUB to "Sub-eye",
    )

    /** Human label for a hand id; falls back gracefully for unknown ids. */
    fun label(hand: String): String = LABELS[hand] ?: hand

    /** True if [hand] is one we know; unknown ids are still tolerated (rendered raw). */
    fun isKnown(hand: String): Boolean = hand in LABELS

    /** Normalize a hand id, defaulting blank/null/unknown to [DEFAULT] (never throws). */
    fun normalize(hand: String?): String {
        val h = hand?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
        return if (h in LABELS) h else DEFAULT
    }
}

/**
 * WP16e — the degree/step conventions for hand calibration. Kept deliberately simple and robust:
 * all offsets are normalized to 0–359 with wrap-around (e.g. -1° → 359°, 360° → 0°), exactly like
 * the CLI `calibrate` command's `wrap()` helper. Two step sizes match the CLI:
 *   - [COARSE] = 6° (one minute mark on the dial, 360°/60),
 *   - [FINE]   = 1° (precise nudge).
 *
 * This is the single source of truth for the degree math so the ViewModel, tests, and UI all
 * agree. There is NO per-model degree mapping — degrees are a plain 0–359 ring for every watch.
 */
object HandDegrees {
    /** Full circle. */
    const val FULL = 360

    /** Coarse nudge step: one minute mark (360°/60). Matches the CLI default. */
    const val COARSE = 6

    /** Fine nudge step: one degree. Matches the CLI `f` (fine) mode. */
    const val FINE = 1

    /** Neutral offset for a fresh, just-entered calibration session. */
    const val NEUTRAL = 0

    /**
     * Normalize any (possibly negative or >359) degree value to the canonical 0–359 ring.
     * Equivalent to the CLI `wrap()` helper: `((deg % 360) + 360) % 360`. Tolerates arbitrary
     * out-of-range input (e.g. -370, 725) and never throws.
     */
    fun normalize(degrees: Int): Int = ((degrees % FULL) + FULL) % FULL

    /** Apply a (possibly negative) delta to [degrees] and re-normalize onto the 0–359 ring. */
    fun nudge(degrees: Int, delta: Int): Int = normalize(degrees + delta)
}
