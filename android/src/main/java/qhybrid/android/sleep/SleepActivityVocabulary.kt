package qhybrid.android.sleep

import qhybrid.protocol.activity.ActivitySummarizer

/**
 * WP16f — centralized, **model-agnostic** display vocabulary for the Sleep/Activity charts screen:
 * the sleep-quality thresholds + labels and the small display helpers. Shared by the ViewModel /
 * adapter, the tests, and the Compose UI (same discipline as WP16b
 * [qhybrid.android.alarms.AlarmDays], WP16c [qhybrid.android.notifications.VibePatterns], WP16d
 * [qhybrid.android.buttons.ButtonModes], and WP16e [qhybrid.android.calibration.CalibrationHands])
 * so the chart labels and the domain math can never drift apart.
 *
 * **Design decision (WP16f): READ-ONLY + MODEL-AGNOSTIC.** This screen only *displays* parsed
 * activity/sleep data. There is no per-model branching — every watch emits the same minute-record
 * format that WP8 [ActivitySummarizer] / `ActivityParser` already decode. The quality labels here
 * mirror `ActivityParser.SleepPeriod.quality()` 1:1 (`good` / `fair` / `restless`) — we do NOT
 * re-implement the threshold math (that lives in the protocol layer, the single source of truth);
 * these are only the human labels + thresholds the UI uses to colour/sort.
 */
object SleepQuality {
    /** Quality id returned by `ActivityParser.SleepPeriod.quality()` for the best sleep. */
    const val GOOD = "good"

    /** Quality id for middling sleep. */
    const val FAIR = "fair"

    /** Quality id for the most restless sleep. */
    const val RESTLESS = "restless"

    /** Quality id used when no sleep was detected at all. */
    const val NONE = "none"

    /**
     * Restless-percentage thresholds, mirroring `ActivityParser.SleepPeriod.quality()` exactly:
     *   - restless% &lt; [GOOD_MAX_PCT]  → [GOOD]
     *   - restless% &lt; [FAIR_MAX_PCT]  → [FAIR]
     *   - otherwise                      → [RESTLESS]
     * These are display constants only — the protocol layer remains the source of truth.
     */
    const val GOOD_MAX_PCT = 10.0
    const val FAIR_MAX_PCT = 25.0

    private val LABELS = mapOf(
        GOOD to "Good",
        FAIR to "Fair",
        RESTLESS to "Restless",
        NONE to "No sleep detected",
    )

    /** Human label for a quality id; falls back gracefully (capitalised) for unknown ids. */
    fun label(quality: String): String =
        LABELS[quality] ?: quality.replaceFirstChar { it.uppercase() }

    /** Normalize an arbitrary quality string to a known id, defaulting to [NONE] when blank. */
    fun normalize(quality: String?): String {
        val q = quality?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return NONE
        return if (q in LABELS) q else q
    }

    /**
     * Classify a restless percentage into a quality id using the same thresholds the protocol
     * uses. Mirrors `ActivityParser.SleepPeriod.quality()` (display-side; the protocol value is
     * authoritative — this only exists for aggregate/empty cases the per-session value can't cover).
     */
    fun fromRestlessPercent(restlessPct: Double): String = when {
        restlessPct < GOOD_MAX_PCT -> GOOD
        restlessPct < FAIR_MAX_PCT -> FAIR
        else -> RESTLESS
    }
}

/** Small, model-agnostic display formatters shared by the UI + tests. */
object SleepActivityFormat {
    /** Format a minute count as `7h 30m` (or `0m`). */
    fun durationLabel(totalMinutes: Int): String {
        if (totalMinutes <= 0) return "0m"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** Restless percentage of a sleep session (0 when duration is 0; never divides by zero). */
    fun restlessPercent(restlessMinutes: Int, durationMinutes: Int): Double =
        if (durationMinutes <= 0) 0.0
        else restlessMinutes.toDouble() / durationMinutes.toDouble() * 100.0
}
