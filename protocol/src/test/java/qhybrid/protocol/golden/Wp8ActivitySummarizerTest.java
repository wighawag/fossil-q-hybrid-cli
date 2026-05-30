// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import qhybrid.protocol.ActivityParser;
import qhybrid.protocol.FossilController;
import qhybrid.protocol.activity.ActivitySummarizer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP8 golden/fixture tests for {@link ActivitySummarizer} (the Activity / Sleep /
 * Calorie parsing surface).
 *
 * <p>Locks the per-day step/calorie aggregation and the sleep-session mapping against
 * the repo fixtures (activity.bin / activity-test.bin), and proves <em>equivalence</em>
 * with {@link ActivityParser}: the summary totals equal the parser's own totals and the
 * sleep path returns exactly what {@link ActivityParser#detectSleep} returns. No parsing
 * math is added by WP8 — these tests guard that.
 */
public class Wp8ActivitySummarizerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Path fixture(String name) {
        String root = System.getProperty("fossilq.repoRoot", ".");
        return Path.of(root, "test-fixtures", "activity", name);
    }

    private static ActivityParser.ActivityData parse(String name) throws Exception {
        return ActivityParser.parse(Files.readAllBytes(fixture(name)));
    }

    /** Raw calorie sum directly off the parser records (the ground truth WP8 must match). */
    private static int rawCalorieSum(ActivityParser.ActivityData d) {
        int c = 0;
        for (ActivityParser.ActivityRecord r : d.records) c += r.calories;
        return c;
    }

    // ---------------------------------------------------------------- (a) per-day

    @Test
    void perDay_activityTestBin_stepsAndCalories() throws Exception {
        ActivityParser.ActivityData d = parse("activity-test.bin");
        List<ActivitySummarizer.DayActivity> days = ActivitySummarizer.summarizeByDay(d, UTC);

        // single segment, 17-minute span -> all records fall in one local day
        assertEquals(1, days.size());
        assertEquals(6, ActivitySummarizer.totalSteps(days));
        assertEquals(0, ActivitySummarizer.totalCalories(days));
        assertEquals(18, days.get(0).recordCount);
    }

    @Test
    void perDay_activityBin_stepsAndCalories() throws Exception {
        ActivityParser.ActivityData d = parse("activity.bin");
        List<ActivitySummarizer.DayActivity> days = ActivitySummarizer.summarizeByDay(d, UTC);

        // golden: 2 total steps, 0 calories (idle desk capture), 55 records total
        assertEquals(2, ActivitySummarizer.totalSteps(days));
        assertEquals(0, ActivitySummarizer.totalCalories(days));
        int records = 0;
        for (ActivitySummarizer.DayActivity da : days) records += da.recordCount;
        assertEquals(55, records);
    }

    @Test
    void perDay_totalsEqualParserTotals() throws Exception {
        // Equivalence: WP8 aggregation must reproduce the parser's own totals exactly.
        for (String f : new String[]{"activity.bin", "activity-test.bin"}) {
            ActivityParser.ActivityData d = parse(f);
            List<ActivitySummarizer.DayActivity> days = ActivitySummarizer.summarizeByDay(d, UTC);
            assertEquals(d.totalSteps(), ActivitySummarizer.totalSteps(days), f + " steps");
            assertEquals(rawCalorieSum(d), ActivitySummarizer.totalCalories(days), f + " calories");
        }
    }

    @Test
    void perDay_daysAreSortedAscending() throws Exception {
        ActivityParser.ActivityData d = parse("activity.bin");
        List<ActivitySummarizer.DayActivity> days = ActivitySummarizer.summarizeByDay(d, UTC);
        for (int i = 1; i < days.size(); i++) {
            assertTrue(days.get(i).date.isAfter(days.get(i - 1).date),
                    "day buckets must be sorted ascending");
        }
    }

    @Test
    void perDay_multiSegmentRecordCountsConsistent() throws Exception {
        // per-day record counts must sum to the parser's record count
        ActivityParser.ActivityData d = parse("activity.bin");
        assertEquals(1, d.segmentCount);
        List<ActivitySummarizer.DayActivity> days = ActivitySummarizer.summarizeByDay(d, UTC);
        int sum = 0;
        for (ActivitySummarizer.DayActivity da : days) sum += da.recordCount;
        assertEquals(d.records.size(), sum);
    }

    // ---------------------------------------------------------------- (b) sleep

    @Test
    void sleep_activityTestBin_noSessions() throws Exception {
        // 18 records < 30-minute minimum -> no sleep detected
        ActivityParser.ActivityData d = parse("activity-test.bin");
        List<ActivitySummarizer.SleepSession> sessions = ActivitySummarizer.detectSleepSessions(d);
        assertTrue(sessions.isEmpty(), "single short segment yields no sleep sessions");
    }

    @Test
    void sleep_activityBin_oneSession() throws Exception {
        ActivityParser.ActivityData d = parse("activity.bin");
        List<ActivitySummarizer.SleepSession> sessions = ActivitySummarizer.detectSleepSessions(d);
        assertEquals(1, sessions.size());
        ActivitySummarizer.SleepSession s = sessions.get(0);
        assertEquals(54, s.durationMinutes);
        assertEquals(1, s.restlessMinutes);
        assertEquals("good", s.quality);
    }

    @Test
    void sleep_delegatesToParser_exactly() throws Exception {
        // Equivalence: WP8 must return exactly what ActivityParser.detectSleep returns.
        for (String f : new String[]{"activity.bin", "activity-test.bin"}) {
            ActivityParser.ActivityData d = parse(f);
            List<ActivityParser.SleepPeriod> raw = ActivityParser.detectSleep(d);
            List<ActivitySummarizer.SleepSession> mapped = ActivitySummarizer.detectSleepSessions(d);
            assertEquals(raw.size(), mapped.size(), f + " session count");
            for (int i = 0; i < raw.size(); i++) {
                ActivityParser.SleepPeriod p = raw.get(i);
                ActivitySummarizer.SleepSession s = mapped.get(i);
                assertEquals(p.startTimestamp, s.startEpochSeconds, f + " start");
                assertEquals(p.endTimestamp, s.endEpochSeconds, f + " end");
                assertEquals(p.durationMinutes, s.durationMinutes, f + " duration");
                assertEquals(p.restlessMinutes, s.restlessMinutes, f + " restless");
                assertEquals(p.avgVariability, s.avgVariability, 1e-9, f + " avgVar");
                assertEquals(p.quality(), s.quality, f + " quality");
            }
        }
    }

    @Test
    void sleep_configurableThresholdsDelegate() throws Exception {
        ActivityParser.ActivityData d = parse("activity.bin");
        List<ActivityParser.SleepPeriod> raw = ActivityParser.detectSleep(d, 60, 10);
        List<ActivitySummarizer.SleepSession> mapped = ActivitySummarizer.detectSleepSessions(d, 60, 10);
        assertEquals(raw.size(), mapped.size());
    }

    // ---------------------------------------------------------------- edge cases

    @Test
    void emptyData_yieldsEmptyResults() {
        // An ActivityData with no records -> both surfaces return empty lists.
        byte[] minimal = new byte[44];
        minimal[2] = 22; // version 22 LE so parse() accepts it; no E2 segments -> 0 records
        ActivityParser.ActivityData d = ActivityParser.parse(minimal);
        assertTrue(d.records.isEmpty());
        assertTrue(ActivitySummarizer.summarizeByDay(d, UTC).isEmpty());
        assertTrue(ActivitySummarizer.detectSleepSessions(d).isEmpty());
        assertEquals(0, ActivitySummarizer.totalSteps(List.of()));
        assertEquals(0, ActivitySummarizer.totalCalories(List.of()));
    }

    // ---------------------------------------------------------------- façade

    @Test
    void facade_matchesHelper() throws Exception {
        ActivityParser.ActivityData d = parse("activity.bin");
        assertEquals(
                ActivitySummarizer.totalSteps(ActivitySummarizer.summarizeByDay(d, UTC)),
                ActivitySummarizer.totalSteps(FossilController.summarizeActivityByDay(d, UTC)));
        assertEquals(
                ActivitySummarizer.detectSleepSessions(d).size(),
                FossilController.detectSleepSessions(d).size());
    }
}
