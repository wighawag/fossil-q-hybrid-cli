package qhybrid.android.sleep

import qhybrid.protocol.activity.ActivitySummarizer
import java.time.ZoneId

/**
 * WP16f — chart-ready, immutable view models for the Sleep/Activity screen, plus the
 * model-agnostic [SleepActivityAdapter] that maps WP8 [ActivitySummarizer] output into them.
 *
 * **The adapter adds ZERO parsing/sleep math.** It only *adapts for display*: it reuses
 * [ActivitySummarizer.summarizeByDay] (per-day steps/calories/active-minutes) and
 * [ActivitySummarizer.detectSleepSessions] (which itself delegates 1:1 to
 * `ActivityParser.detectSleep`) and reshapes the results into immutable Kotlin view models with a
 * stable, chart-friendly shape. `ActivityParser` / `ActivitySummarizer` remain the single source
 * of truth for byte decoding + sleep detection.
 */

/** One calendar day's activity totals (chart bar / summary row). */
data class DaySummary(
    /** ISO date (`yyyy-MM-dd`) — stringified so the view model carries no `java.time` into Compose. */
    val date: String,
    val steps: Int,
    val calories: Int,
    val activeMinutes: Int,
    val recordCount: Int,
)

/** One detected sleep session (timeline segment + per-session readout). */
data class SleepSegment(
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val durationMinutes: Int,
    val restlessMinutes: Int,
    val avgVariability: Double,
    /** Quality id (`good` / `fair` / `restless`) straight from `SleepPeriod.quality()`. */
    val quality: String,
) {
    /** Restful (non-restless) minutes within the session. */
    val restfulMinutes: Int get() = (durationMinutes - restlessMinutes).coerceAtLeast(0)

    /** Restless percentage of the session (0 when duration is 0). */
    val restlessPercent: Double
        get() = SleepActivityFormat.restlessPercent(restlessMinutes, durationMinutes)
}

/** Aggregate sleep summary across all detected sessions (the quality headline + totals). */
data class SleepSummary(
    val sessionCount: Int,
    val totalMinutes: Int,
    val restlessMinutes: Int,
    val deepMinutes: Int,
    /** Overall quality id; [SleepQuality.NONE] when no sessions were detected. */
    val quality: String,
) {
    val hasSleep: Boolean get() = sessionCount > 0

    companion object {
        /** The empty/zero summary used when there are no records or no detected sleep. */
        val EMPTY = SleepSummary(
            sessionCount = 0,
            totalMinutes = 0,
            restlessMinutes = 0,
            deepMinutes = 0,
            quality = SleepQuality.NONE,
        )
    }
}

/**
 * The full parsed-activity payload the screen renders: per-day summaries + sleep timeline +
 * the aggregate sleep summary. Immutable + free of `java.time` / protocol types so it can flow
 * straight into Compose and be trivially faked in tests.
 */
data class ActivityChartData(
    val days: List<DaySummary> = emptyList(),
    val sleep: List<SleepSegment> = emptyList(),
    val sleepSummary: SleepSummary = SleepSummary.EMPTY,
) {
    val totalSteps: Int get() = days.sumOf { it.steps }
    val totalCalories: Int get() = days.sumOf { it.calories }
    val totalActiveMinutes: Int get() = days.sumOf { it.activeMinutes }
    val hasData: Boolean get() = days.isNotEmpty() || sleep.isNotEmpty()

    companion object {
        /** The empty payload (no records parsed yet, or zero-record file). */
        val EMPTY = ActivityChartData()
    }
}

/**
 * WP16f — the model-agnostic adapter from WP8 [ActivitySummarizer] output to [ActivityChartData].
 * Pure + side-effect-free; reuses the protocol surface verbatim (no re-implemented math).
 */
object SleepActivityAdapter {

    /** Map a per-day list ([ActivitySummarizer.DayActivity]) into [DaySummary] view models. */
    fun toDaySummaries(days: List<ActivitySummarizer.DayActivity>): List<DaySummary> =
        days.map {
            DaySummary(
                date = it.date.toString(),
                steps = it.steps,
                calories = it.calories,
                activeMinutes = it.activeMinutes,
                recordCount = it.recordCount,
            )
        }

    /** Map a sleep-session list ([ActivitySummarizer.SleepSession]) into [SleepSegment] view models. */
    fun toSleepSegments(sessions: List<ActivitySummarizer.SleepSession>): List<SleepSegment> =
        sessions.map {
            SleepSegment(
                startEpochSeconds = it.startEpochSeconds,
                endEpochSeconds = it.endEpochSeconds,
                durationMinutes = it.durationMinutes,
                restlessMinutes = it.restlessMinutes,
                avgVariability = it.avgVariability,
                quality = SleepQuality.normalize(it.quality),
            )
        }

    /**
     * Aggregate a list of sleep segments into a single [SleepSummary]. The overall quality is
     * derived from the *aggregate* restless percentage using the same thresholds the protocol uses
     * per-session ([SleepQuality.fromRestlessPercent]); [SleepQuality.NONE] when there are none.
     */
    fun summarizeSleep(segments: List<SleepSegment>): SleepSummary {
        if (segments.isEmpty()) return SleepSummary.EMPTY
        val total = segments.sumOf { it.durationMinutes }
        val restless = segments.sumOf { it.restlessMinutes }
        val deep = (total - restless).coerceAtLeast(0)
        val pct = SleepActivityFormat.restlessPercent(restless, total)
        return SleepSummary(
            sessionCount = segments.size,
            totalMinutes = total,
            restlessMinutes = restless,
            deepMinutes = deep,
            quality = if (total <= 0) SleepQuality.NONE else SleepQuality.fromRestlessPercent(pct),
        )
    }

    /**
     * Build the full [ActivityChartData] straight from WP8's per-day + sleep lists (already
     * computed via [ActivitySummarizer]). Pure adaptation — no parsing.
     */
    fun toChartData(
        days: List<ActivitySummarizer.DayActivity>,
        sessions: List<ActivitySummarizer.SleepSession>,
    ): ActivityChartData {
        val sleep = toSleepSegments(sessions)
        return ActivityChartData(
            days = toDaySummaries(days),
            sleep = sleep,
            sleepSummary = summarizeSleep(sleep),
        )
    }

    /**
     * Convenience: build [ActivityChartData] directly from parsed [ActivityParser.ActivityData]
     * by running the WP8 summarizer + sleep detection (the single source of truth) and adapting
     * the results. The day-bucketing [zone] is injected (no system clock), exactly like WP8.
     */
    fun fromParsed(
        data: qhybrid.protocol.ActivityParser.ActivityData,
        zone: ZoneId,
    ): ActivityChartData = toChartData(
        days = ActivitySummarizer.summarizeByDay(data, zone),
        sessions = ActivitySummarizer.detectSleepSessions(data),
    )
}
