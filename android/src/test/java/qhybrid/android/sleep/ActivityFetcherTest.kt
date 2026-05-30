package qhybrid.android.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.protocol.ActivityParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

/**
 * WP-ACTIVITY (sub-part 1) — headless tests for the **pure fetch→parse core** [ActivityFetcher].
 *
 * Golden-style: reuses the REAL `ActivityParser` on the repo fixtures (`activity.bin` /
 * `activity-test.bin`) — the same files WP8 / WP16f lock — plus a fake byte source for the
 * no-watch / empty / partial / malformed tolerance paths. The fetcher must add **NO math**: its
 * output must reproduce `SleepActivityAdapter.fromParsed` exactly, and its step total must equal
 * `ActivityData.totalSteps()` (the Dashboard step total).
 */
class ActivityFetcherTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** A trivial fake "byte source" standing in for the BLE read of the activity file. */
    private fun fixtureBytes(name: String): ByteArray {
        val root = System.getProperty("fossilq.repoRoot", "..")
        return Files.readAllBytes(Path.of(root, "test-fixtures", "activity", name))
    }

    // ---- fetch→parse reproduces WP8 exactly -----------------------------------

    @Test
    fun parse_activityBin_reproducesAdapterExactly() {
        val raw = fixtureBytes("activity.bin")
        val expected = SleepActivityAdapter.fromParsed(ActivityParser.parse(raw), utc)
        val actual = ActivityFetcher.parse(raw, utc)
        assertEquals(expected, actual)
    }

    @Test
    fun parse_activityBin_goldenValues() {
        // Golden values locked to the activity.bin fixture: totalSteps=2, one session 54m, restless=1, good.
        val raw = fixtureBytes("activity.bin")
        val chart = ActivityFetcher.parse(raw, utc)
        assertEquals(2, chart.totalSteps)
        assertEquals(2, ActivityFetcher.totalSteps(raw, utc)) // convenience matches
        assertEquals(ActivityParser.parse(raw).totalSteps(), chart.totalSteps) // no math drift
        assertEquals(1, chart.sleep.size)
        assertEquals(54, chart.sleep[0].durationMinutes)
        assertEquals(1, chart.sleep[0].restlessMinutes)
        assertEquals(SleepQuality.GOOD, chart.sleep[0].quality)
        assertTrue(chart.hasData)
    }

    @Test
    fun parse_activityTestBin_daysButNoSleep() {
        // 18 records < 30-minute minimum → totalSteps=6, no sleep, but day buckets exist.
        val raw = fixtureBytes("activity-test.bin")
        val chart = ActivityFetcher.parse(raw, utc)
        assertEquals(6, chart.totalSteps)
        assertEquals(6, ActivityFetcher.totalSteps(raw, utc))
        assertTrue(chart.sleep.isEmpty())
        assertFalse(chart.sleepSummary.hasSleep)
        assertTrue(chart.days.isNotEmpty())
        assertTrue(chart.hasData)
    }

    // ---- no-watch / empty / partial / malformed tolerance ---------------------

    @Test
    fun parse_null_isEmptyNeverThrows() {
        assertSame(ActivityChartData.EMPTY, ActivityFetcher.parse(null, utc))
        assertEquals(0, ActivityFetcher.totalSteps(null, utc))
    }

    @Test
    fun parse_empty_isEmptyNeverThrows() {
        // The watch returns byte[0] when there is no activity data (FILE_EMPTY path).
        assertSame(ActivityChartData.EMPTY, ActivityFetcher.parse(ByteArray(0), utc))
        assertEquals(0, ActivityFetcher.totalSteps(ByteArray(0), utc))
    }

    @Test
    fun parse_tooShort_isEmptyNeverThrows() {
        // ActivityParser.parse throws on <44-byte files; the fetcher must swallow it.
        val chart = ActivityFetcher.parse(byteArrayOf(1, 2, 3, 4, 5), utc)
        assertEquals(ActivityChartData.EMPTY, chart)
        assertFalse(chart.hasData)
    }

    @Test
    fun parse_malformedFullLength_isEmptyNeverThrows() {
        // 64 bytes of garbage (wrong version) → IllegalArgumentException → empty, no crash.
        val chart = ActivityFetcher.parse(ByteArray(64) { 0x55 }, utc)
        assertEquals(ActivityChartData.EMPTY, chart)
        assertEquals(0, chart.totalSteps)
    }

    @Test
    fun parse_isPureFunctionOfBytesAndZone() {
        // Calling twice with the same bytes yields equal results (no hidden state).
        val raw = fixtureBytes("activity.bin")
        assertEquals(ActivityFetcher.parse(raw, utc), ActivityFetcher.parse(raw, utc))
    }
}
