package qhybrid.android.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.protocol.ActivityParser
import qhybrid.protocol.activity.ActivitySummarizer
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

/**
 * WP16f — headless tests for the model-agnostic WP8→display adapter ([SleepActivityAdapter]) and
 * the centralized vocabulary ([SleepQuality] / [SleepActivityFormat]).
 *
 * Golden-style: reuses the REAL `ActivityParser` on the repo fixtures (`activity.bin` /
 * `activity-test.bin`) — the same files WP8's golden test locks — so the adapter is proven against
 * the single source of truth, plus hand-built cases for the aggregate/empty paths.
 *
 * **The adapter must add NO math:** it must reproduce `ActivitySummarizer` / `ActivityParser`
 * outputs exactly (steps/calories per day; sleep fields incl. quality), only reshaping them.
 */
class SleepActivityAdapterTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun fixture(name: String): Path {
        val root = System.getProperty("fossilq.repoRoot", "..")
        return Path.of(root, "test-fixtures", "activity", name)
    }

    private fun parse(name: String): ActivityParser.ActivityData =
        ActivityParser.parse(Files.readAllBytes(fixture(name)))

    // ---- per-day adaptation reproduces WP8 exactly ----------------------------

    @Test
    fun toDaySummaries_reproducesSummarizerExactly() {
        val data = parse("activity.bin")
        val wp8 = ActivitySummarizer.summarizeByDay(data, utc)
        val days = SleepActivityAdapter.toDaySummaries(wp8)

        assertEquals(wp8.size, days.size)
        for (i in wp8.indices) {
            assertEquals(wp8[i].date.toString(), days[i].date)
            assertEquals(wp8[i].steps, days[i].steps)
            assertEquals(wp8[i].calories, days[i].calories)
            assertEquals(wp8[i].activeMinutes, days[i].activeMinutes)
            assertEquals(wp8[i].recordCount, days[i].recordCount)
        }
        // Aggregate totals equal the parser's own totals (no math drift).
        val chart = SleepActivityAdapter.toChartData(wp8, emptyList())
        assertEquals(data.totalSteps(), chart.totalSteps)
        assertEquals(ActivitySummarizer.totalSteps(wp8), chart.totalSteps)
        assertEquals(ActivitySummarizer.totalCalories(wp8), chart.totalCalories)
    }

    // ---- sleep adaptation reproduces detectSleep exactly ----------------------

    @Test
    fun toSleepSegments_reproducesDetectSleepExactly() {
        val data = parse("activity.bin")
        val raw = ActivityParser.detectSleep(data)
        val wp8 = ActivitySummarizer.detectSleepSessions(data)
        val segs = SleepActivityAdapter.toSleepSegments(wp8)

        assertEquals(raw.size, segs.size)
        for (i in raw.indices) {
            assertEquals(raw[i].startTimestamp, segs[i].startEpochSeconds)
            assertEquals(raw[i].endTimestamp, segs[i].endEpochSeconds)
            assertEquals(raw[i].durationMinutes, segs[i].durationMinutes)
            assertEquals(raw[i].restlessMinutes, segs[i].restlessMinutes)
            assertEquals(raw[i].avgVariability, segs[i].avgVariability, 1e-9)
            // Quality must equal the protocol's own quality() value 1:1.
            assertEquals(SleepQuality.normalize(raw[i].quality()), segs[i].quality)
        }
    }

    @Test
    fun activityBin_oneGoodSession_54m_restless1() {
        // Golden values locked to the activity.bin fixture: one session, 54m, restless=1, good.
        val data = parse("activity.bin")
        val chart = SleepActivityAdapter.fromParsed(data, utc)
        assertEquals(1, chart.sleep.size)
        val s = chart.sleep[0]
        assertEquals(54, s.durationMinutes)
        assertEquals(1, s.restlessMinutes)
        assertEquals(SleepQuality.GOOD, s.quality)
        assertEquals(53, s.restfulMinutes) // 54 - 1

        // Aggregate sleep summary mirrors the single session.
        val sum = chart.sleepSummary
        assertTrue(sum.hasSleep)
        assertEquals(1, sum.sessionCount)
        assertEquals(54, sum.totalMinutes)
        assertEquals(1, sum.restlessMinutes)
        assertEquals(53, sum.deepMinutes)
        assertEquals(SleepQuality.GOOD, sum.quality)
    }

    @Test
    fun activityTestBin_noSleep_emptyButHasDays() {
        // 18 records < 30-minute minimum → no sleep, but the day buckets still exist.
        val data = parse("activity-test.bin")
        val chart = SleepActivityAdapter.fromParsed(data, utc)
        assertTrue(chart.sleep.isEmpty())
        assertFalse(chart.sleepSummary.hasSleep)
        assertEquals(SleepQuality.NONE, chart.sleepSummary.quality)
        assertTrue(chart.days.isNotEmpty())
        assertTrue(chart.hasData)
    }

    // ---- empty / partial tolerance -------------------------------------------

    @Test
    fun emptyInputs_yieldEmptyChart() {
        val chart = SleepActivityAdapter.toChartData(emptyList(), emptyList())
        assertEquals(ActivityChartData.EMPTY, chart)
        assertFalse(chart.hasData)
        assertEquals(0, chart.totalSteps)
        assertEquals(0, chart.totalCalories)
        assertEquals(SleepSummary.EMPTY, chart.sleepSummary)
    }

    @Test
    fun summarizeSleep_aggregatesAndClassifies() {
        // Hand-built segments: total 600m, restless 90m → 15% → fair.
        val segs = listOf(
            SleepSegment(0, 0, durationMinutes = 400, restlessMinutes = 40, avgVariability = 1.0, quality = SleepQuality.GOOD),
            SleepSegment(0, 0, durationMinutes = 200, restlessMinutes = 50, avgVariability = 2.0, quality = SleepQuality.RESTLESS),
        )
        val sum = SleepActivityAdapter.summarizeSleep(segs)
        assertEquals(2, sum.sessionCount)
        assertEquals(600, sum.totalMinutes)
        assertEquals(90, sum.restlessMinutes)
        assertEquals(510, sum.deepMinutes)
        assertEquals(SleepQuality.FAIR, sum.quality) // 90/600 = 15% < 25%
    }

    @Test
    fun summarizeSleep_goodAndRestlessThresholds() {
        // < 10% restless → good.
        val good = SleepActivityAdapter.summarizeSleep(
            listOf(SleepSegment(0, 0, 100, 5, 0.0, SleepQuality.GOOD)),
        )
        assertEquals(SleepQuality.GOOD, good.quality)
        // >= 25% restless → restless.
        val restless = SleepActivityAdapter.summarizeSleep(
            listOf(SleepSegment(0, 0, 100, 30, 0.0, SleepQuality.RESTLESS)),
        )
        assertEquals(SleepQuality.RESTLESS, restless.quality)
    }

    @Test
    fun summarizeSleep_zeroDurationDoesNotDivideByZero() {
        val sum = SleepActivityAdapter.summarizeSleep(
            listOf(SleepSegment(0, 0, durationMinutes = 0, restlessMinutes = 0, avgVariability = 0.0, quality = SleepQuality.NONE)),
        )
        // No crash; zero-duration aggregate falls back to NONE.
        assertEquals(0, sum.totalMinutes)
        assertEquals(SleepQuality.NONE, sum.quality)
    }

    // ---- vocabulary sanity ----------------------------------------------------

    @Test
    fun qualityThresholdsMirrorProtocol() {
        // Mirror ActivityParser.SleepPeriod.quality() exactly: <10 good, <25 fair, else restless.
        assertEquals(SleepQuality.GOOD, SleepQuality.fromRestlessPercent(0.0))
        assertEquals(SleepQuality.GOOD, SleepQuality.fromRestlessPercent(9.999))
        assertEquals(SleepQuality.FAIR, SleepQuality.fromRestlessPercent(10.0))
        assertEquals(SleepQuality.FAIR, SleepQuality.fromRestlessPercent(24.999))
        assertEquals(SleepQuality.RESTLESS, SleepQuality.fromRestlessPercent(25.0))
        assertEquals(SleepQuality.RESTLESS, SleepQuality.fromRestlessPercent(100.0))
        assertEquals(10.0, SleepQuality.GOOD_MAX_PCT, 0.0)
        assertEquals(25.0, SleepQuality.FAIR_MAX_PCT, 0.0)
    }

    @Test
    fun qualityLabelsAndNormalize() {
        assertEquals("Good", SleepQuality.label(SleepQuality.GOOD))
        assertEquals("Fair", SleepQuality.label(SleepQuality.FAIR))
        assertEquals("Restless", SleepQuality.label(SleepQuality.RESTLESS))
        assertEquals("No sleep detected", SleepQuality.label(SleepQuality.NONE))
        assertEquals(SleepQuality.GOOD, SleepQuality.normalize("  GOOD "))
        assertEquals(SleepQuality.NONE, SleepQuality.normalize(null))
        assertEquals(SleepQuality.NONE, SleepQuality.normalize(""))
    }

    @Test
    fun durationLabelFormatsHoursAndMinutes() {
        assertEquals("0m", SleepActivityFormat.durationLabel(0))
        assertEquals("0m", SleepActivityFormat.durationLabel(-5))
        assertEquals("45m", SleepActivityFormat.durationLabel(45))
        assertEquals("1h", SleepActivityFormat.durationLabel(60))
        assertEquals("7h 30m", SleepActivityFormat.durationLabel(450))
        assertEquals("14h 15m", SleepActivityFormat.durationLabel(855))
    }

    @Test
    fun restlessPercentNeverDividesByZero() {
        assertEquals(0.0, SleepActivityFormat.restlessPercent(5, 0), 0.0)
        assertEquals(50.0, SleepActivityFormat.restlessPercent(50, 100), 1e-9)
    }
}
