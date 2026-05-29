// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.activity;

import qhybrid.protocol.ActivityParser;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * WP8 — Activity / Sleep / Calorie parsing <em>surface</em>.
 *
 * <p>Pure, platform-neutral aggregation over the output of {@link ActivityParser}.
 * This class adds <strong>no parsing math</strong>: it only groups the records
 * {@link ActivityParser} already decoded into clean domain objects for the UI/DB,
 * and the sleep path <em>delegates</em> 1:1 to {@link ActivityParser#detectSleep}.
 * {@link ActivityParser} remains the single source of truth for byte decoding and
 * sleep detection; WP8 is the stable API + tests around it.
 *
 * <p>No Android, no BLE, no UI, no system clock — the day-bucketing zone is injected.
 */
public final class ActivitySummarizer {

    private ActivitySummarizer() {}

    // ------------------------------------------------------------------ (a) per-day

    /** Per-calendar-day aggregate of steps / calories / active minutes. */
    public static final class DayActivity {
        public final LocalDate date;
        public final int steps;
        public final int calories;
        public final int activeMinutes;
        public final int recordCount;

        public DayActivity(LocalDate date, int steps, int calories,
                           int activeMinutes, int recordCount) {
            this.date = date;
            this.steps = steps;
            this.calories = calories;
            this.activeMinutes = activeMinutes;
            this.recordCount = recordCount;
        }

        @Override
        public String toString() {
            return date + " steps=" + steps + " cal=" + calories
                    + " active=" + activeMinutes + "m records=" + recordCount;
        }
    }

    /**
     * Group already-parsed activity records into per-local-day totals.
     *
     * <p>Pure aggregation — sums {@code steps}/{@code calories}, counts active and total
     * minutes per local calendar day. Adds no decoding; the records and their fields come
     * straight from {@link ActivityParser#parse}.
     *
     * @param data parsed activity data (from {@link ActivityParser#parse})
     * @param zone time zone used to assign each record's UTC timestamp to a calendar day
     * @return per-day totals sorted ascending by date (empty if no records)
     */
    public static List<DayActivity> summarizeByDay(ActivityParser.ActivityData data, ZoneId zone) {
        TreeMap<LocalDate, int[]> byDay = new TreeMap<>();
        for (ActivityParser.ActivityRecord r : data.records) {
            LocalDate day = Instant.ofEpochSecond(r.timestamp).atZone(zone).toLocalDate();
            int[] acc = byDay.computeIfAbsent(day, k -> new int[4]);
            acc[0] += r.steps;
            acc[1] += r.calories;
            if (r.isActive) acc[2] += 1;
            acc[3] += 1;
        }
        List<DayActivity> out = new ArrayList<>(byDay.size());
        for (var e : byDay.entrySet()) {
            int[] a = e.getValue();
            out.add(new DayActivity(e.getKey(), a[0], a[1], a[2], a[3]));
        }
        return out;
    }

    /** Sum of steps across a per-day list (convenience). */
    public static int totalSteps(List<DayActivity> days) {
        int s = 0;
        for (DayActivity d : days) s += d.steps;
        return s;
    }

    /** Sum of calories across a per-day list (convenience). */
    public static int totalCalories(List<DayActivity> days) {
        int c = 0;
        for (DayActivity d : days) c += d.calories;
        return c;
    }

    // ------------------------------------------------------------------ (b) sleep

    /** A detected sleep session — clean domain mirror of {@link ActivityParser.SleepPeriod}. */
    public static final class SleepSession {
        public final long startEpochSeconds;
        public final long endEpochSeconds;
        public final int durationMinutes;
        public final int restlessMinutes;
        public final double avgVariability;
        public final String quality;

        public SleepSession(long startEpochSeconds, long endEpochSeconds, int durationMinutes,
                            int restlessMinutes, double avgVariability, String quality) {
            this.startEpochSeconds = startEpochSeconds;
            this.endEpochSeconds = endEpochSeconds;
            this.durationMinutes = durationMinutes;
            this.restlessMinutes = restlessMinutes;
            this.avgVariability = avgVariability;
            this.quality = quality;
        }

        @Override
        public String toString() {
            return "sleep " + durationMinutes + "m restless=" + restlessMinutes
                    + " quality=" + quality;
        }
    }

    /**
     * Detect sleep sessions from parsed activity data (default thresholds).
     *
     * <p><strong>Delegates</strong> to {@link ActivityParser#detectSleep(ActivityParser.ActivityData)}
     * and maps each {@link ActivityParser.SleepPeriod} 1:1 into a {@link SleepSession}.
     * No detection logic is re-implemented here.
     */
    public static List<SleepSession> detectSleepSessions(ActivityParser.ActivityData data) {
        return map(ActivityParser.detectSleep(data));
    }

    /**
     * Detect sleep sessions with configurable thresholds.
     *
     * <p><strong>Delegates</strong> to
     * {@link ActivityParser#detectSleep(ActivityParser.ActivityData, int, int)}.
     */
    public static List<SleepSession> detectSleepSessions(ActivityParser.ActivityData data,
                                                         int minSleepMinutes, int gapTolerance) {
        return map(ActivityParser.detectSleep(data, minSleepMinutes, gapTolerance));
    }

    private static List<SleepSession> map(List<ActivityParser.SleepPeriod> periods) {
        List<SleepSession> out = new ArrayList<>(periods.size());
        for (ActivityParser.SleepPeriod p : periods) {
            out.add(new SleepSession(
                    p.startTimestamp, p.endTimestamp, p.durationMinutes,
                    p.restlessMinutes, p.avgVariability, p.quality()));
        }
        return out;
    }
}
