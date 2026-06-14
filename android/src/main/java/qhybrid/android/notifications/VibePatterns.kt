package qhybrid.android.notifications

/**
 * WP16c — centralized notification vibe-pattern constants + human labels, shared by the
 * ViewModel, the tests, and the Compose UI so the vibe-pattern dropdown and the stored
 * [qhybrid.android.db.NotificationRuleEntity.vibePattern] value cannot drift apart.
 *
 * The convention is **identical to WP6** ([qhybrid.protocol.requests.fossil.notification.NotificationCompiler]
 * VIBRATION field 0xC3) and the hardware results in FINDINGS #23: the stored value IS the
 * on-wire vibe byte (0–9) 1:1 — NO translation. Do not invent a different numbering anywhere.
 *
 * ```
 * 0 AUTO              No vibration (silent, hands move only)
 * 1 CALL              Triple vibration
 * 2 TEXT              Double vibration
 * 3 EMAIL             Single vibration
 * 4 DEFAULT           Single vibration (same as EMAIL)
 * 5 ONE_SHORT_VIBE    Strong single vibration
 * 6 TWO_SHORT_VIBES   Strong double vibration
 * 7 THREE_SHORT_VIBES Strong triple vibration
 * 8 ONE_LONG_VIBE     Long vibration
 * 9 NO_VIBE           No vibration (silent, hands move only)
 * ```
 */
object VibePatterns {
    const val AUTO = 0
    const val CALL = 1
    const val TEXT = 2
    const val EMAIL = 3
    const val DEFAULT = 4
    const val ONE_SHORT = 5
    const val TWO_SHORT = 6
    const val THREE_SHORT = 7
    const val ONE_LONG = 8
    const val NO_VIBE = 9

    const val MIN = 0
    const val MAX = 9

    /** Hand-degree bounds (FINDINGS #24): hour/minute hands take 0–359 degrees. */
    const val DEG_MIN = 0
    const val DEG_MAX = 359

    /**
     * Short labels indexed 1:1 by pattern value 0..9.
     *
     * These describe the ACTUAL buzz the watch plays, NOT the firmware's legacy preset SOURCE name
     * (the presets 1/2/3 are named CALL/TEXT/EMAIL after the notification type they were meant for,
     * but what the user feels is triple/double/single). Labelling them by the source name was
     * confusing in the picker, so we surface the felt pattern instead (FINDINGS #23). The named
     * source is kept in parentheses for those who recognise the official-app naming.
     */
    val LABELS = arrayOf(
        "Auto",
        "Triple (Call)",
        "Double (Text)",
        "Single (Email)",
        "Single (Default)",
        "Strong single",
        "Strong double",
        "Strong triple",
        "Long",
        "Silent",
    )

    /** All valid pattern values in display order (0..9). */
    val ALL = (MIN..MAX).toList()

    /** True if [pattern] is a valid vibe value (0..9). */
    fun isValid(pattern: Int): Boolean = pattern in MIN..MAX

    /** Clamp an arbitrary int into the valid 0..9 vibe range. */
    fun clamp(pattern: Int): Int = pattern.coerceIn(MIN, MAX)

    /** Human label for a pattern value; falls back gracefully for out-of-range input. */
    fun label(pattern: Int): String = LABELS.getOrElse(pattern) { "Pattern $pattern" }

    /** Clamp an arbitrary int into the valid 0..359 hand-degree range. */
    fun clampDegrees(deg: Int): Int = deg.coerceIn(DEG_MIN, DEG_MAX)

    /** Human summary for a hand position, e.g. "Hands 90°/180°". */
    fun handSummary(hourDeg: Int, minuteDeg: Int): String = "Hands $hourDeg°/$minuteDeg°"
}
