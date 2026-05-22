package qhybrid.linux;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Fossil Q Hybrid activity files (file handle 0x0100).
 *
 * File format (version 22, no-HR coin-cell variant):
 * <pre>
 *   [0-1]   File marker (01 01)
 *   [2-3]   Version (22 LE)
 *   [4-7]   Reserved (FFFFFFFF)
 *   [8-11]  Timestamp (LE Unix epoch, UTC)
 *   [12-13] Timezone offset (LE, minutes)
 *   [14-15] Interval (LE, seconds — always 60)
 *   [16-17] File ID (LE, incrementing)
 *   [18-31] Device metadata (14 bytes, constant per device)
 *   [32+]   Segments:
 *             [E2] [type(1)] [timestamp(4 LE)] [tz_offset(2 LE)] [interval(2 LE)] [FE FE]
 *             [records: 4 bytes each until byte[2] != 0xFF]
 *   Trailing bytes after last valid record are ignored (not a separate CRC).
 * </pre>
 *
 * Each 4-byte record:
 * <pre>
 *   [b0] [b1] [b2=0xFF] [b3]
 *   b2 must be 0xFF (no-HR marker; non-0xFF terminates the record stream).
 *   b3: bits 0-5 = calories, bit 6 = isActive flag
 *
 *   If b0 bit 0 == 1 (low-step / variability mode):
 *     steps = b0 & 0x0E  (bits 1-3, values 0/2/4/6/8/10/12/14)
 *     variability decoded from remaining bits of b0 + b1
 *   If b0 bit 0 == 0 (high-step mode):
 *     steps = b0 & 0xFE  (bits 1-7, values 0/2/4/.../254)
 *     variability = b1² × 64
 * </pre>
 *
 * Based on GadgetBridge's ActivityFileParser (AGPL-3.0) with adaptations
 * for multi-segment files and standalone use (no Android/entity dependencies).
 */
public class ActivityParser {

    /** One minute of activity data. */
    public static class ActivityRecord {
        public final long timestamp;      // Unix epoch (UTC)
        public final int steps;
        public final int calories;
        public final boolean isActive;
        public final int variability;
        public final int maxVariability;

        ActivityRecord(long timestamp, int steps, int calories, boolean isActive,
                       int variability, int maxVariability) {
            this.timestamp = timestamp;
            this.steps = steps;
            this.calories = calories;
            this.isActive = isActive;
            this.variability = variability;
            this.maxVariability = maxVariability;
        }

        @Override
        public String toString() {
            LocalDateTime dt = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(timestamp), ZoneOffset.systemDefault());
            return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    + " steps=" + steps
                    + (isActive ? " ACTIVE" : "")
                    + (calories > 0 ? " cal=" + calories : "");
        }
    }

    /** Summary of a parsed activity file. */
    public static class ActivityData {
        public final int fileVersion;
        public final int fileId;
        public final short timezoneOffsetMinutes;
        public final int intervalSeconds;
        public final List<ActivityRecord> records;
        public final int segmentCount;

        ActivityData(int fileVersion, int fileId, short timezoneOffsetMinutes,
                     int intervalSeconds, List<ActivityRecord> records, int segmentCount) {
            this.fileVersion = fileVersion;
            this.fileId = fileId;
            this.timezoneOffsetMinutes = timezoneOffsetMinutes;
            this.intervalSeconds = intervalSeconds;
            this.records = records;
            this.segmentCount = segmentCount;
        }

        /** Total step count across all records. */
        public int totalSteps() {
            return records.stream().mapToInt(r -> r.steps).sum();
        }

        /** Earliest timestamp in the data. */
        public long startTimestamp() {
            return records.isEmpty() ? 0 : records.get(0).timestamp;
        }

        /** Latest timestamp in the data. */
        public long endTimestamp() {
            return records.isEmpty() ? 0 : records.get(records.size() - 1).timestamp;
        }
    }

    /**
     * Parse a raw activity file from the watch.
     *
     * @param file raw bytes from ACTIVITY_FILE (0x0100)
     * @return parsed activity data
     * @throws IllegalArgumentException if file is too short or version unsupported
     */
    public static ActivityData parse(byte[] file) {
        if (file.length < 44) {
            throw new IllegalArgumentException("Activity file too short: " + file.length + " bytes");
        }

        ByteBuffer header = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        int version = header.getShort(2) & 0xFFFF;
        if (version != 22) {
            throw new IllegalArgumentException("Unsupported activity file version: " + version + " (expected 22)");
        }

        short tzOffset = header.getShort(12);
        int interval = header.getShort(14) & 0xFFFF;
        int fileId = header.getShort(16) & 0xFFFF;

        List<ActivityRecord> allRecords = new ArrayList<>();
        int segmentCount = 0;

        // Walk through segments starting at offset 32
        int pos = 32;
        while (pos < file.length - 12) {
            if (file[pos] != (byte) 0xE2) {
                pos++;
                continue;
            }

            byte segType = file[pos + 1];
            // Accept segment types 0x03 and 0x04 (both observed in real data)
            if (segType != 0x03 && segType != 0x04) {
                pos++;
                continue;
            }

            // Segment header layout (12 bytes):
            //   [E2] [type] [timestamp(4 LE)] [tz_offset(2 LE)] [interval(2 LE)] [FEFE]
            //   pos+0 pos+1  pos+2             pos+6             pos+8             pos+10
            ByteBuffer buf = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
            long segTimestamp = buf.getInt(pos + 2) & 0xFFFFFFFFL;
            // pos+6..7: timezone offset for this segment (not used in record parsing)
            int segInterval = buf.getShort(pos + 8) & 0xFFFF;
            if (segInterval == 0) segInterval = 60; // sanity

            // Expect FEFE marker
            int fefePos = pos + 10;
            if (fefePos + 1 >= file.length ||
                    file[fefePos] != (byte) 0xFE || file[fefePos + 1] != (byte) 0xFE) {
                pos++;
                continue;
            }

            int recStart = fefePos + 2;
            segmentCount++;

            // Read 4-byte records until byte[2] != 0xFF or end of data
            long currentTs = segTimestamp;
            int recPos = recStart;
            while (recPos + 3 < file.length) {
                int b0 = file[recPos] & 0xFF;
                int b1 = file[recPos + 1] & 0xFF;
                int b2 = file[recPos + 2] & 0xFF;
                int b3 = file[recPos + 3] & 0xFF;

                if (b2 != 0xFF) break; // end of records

                int steps, variability, maxVariability;
                if ((b0 & 1) == 1) {
                    // Low-step / variability mode (matches GB ActivityFileParser)
                    steps = b0 & 0x0E;  // bits 1-3: 0,2,4,6,8,10,12,14
                    maxVariability = (b1 & 0x03) * 25 + 1;
                    if ((b0 & 0x80) == 0x80) {
                        int factor = (b0 >> 4) & 0x07;
                        variability = 512 + factor * 64 + ((b1 >> 2) & 0x3F);
                    } else {
                        variability = (b0 & 0x70) << 2;
                        variability |= (b1 >> 2) & 0x3F;
                    }
                } else {
                    // High-step mode
                    steps = b0 & 0xFE;  // bits 1-7: 0,2,4,...,254
                    variability = b1 * b1 * 64;
                    maxVariability = 10000;
                }

                boolean isActive = (b3 & 0x40) == 0x40;
                int calories = b3 & 0x3F;

                allRecords.add(new ActivityRecord(
                        currentTs, steps, calories, isActive, variability, maxVariability));
                currentTs += segInterval;
                recPos += 4;
            }

            pos = recPos;
        }

        // Sort by timestamp (segments may be out of order)
        allRecords.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));

        return new ActivityData(version, fileId, tzOffset, interval, allRecords, segmentCount);
    }

    /**
     * Format activity data as a human-readable summary.
     */
    public static String formatSummary(ActivityData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Activity data: %d records across %d segment(s), file v%d (id=0x%04X)%n",
                data.records.size(), data.segmentCount, data.fileVersion, data.fileId));

        if (data.records.isEmpty()) {
            sb.append("  No activity records.\n");
            return sb.toString();
        }

        LocalDateTime start = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(data.startTimestamp()), ZoneOffset.systemDefault());
        LocalDateTime end = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(data.endTimestamp()), ZoneOffset.systemDefault());

        sb.append(String.format("  Time range: %s — %s%n",
                start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                end.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
        sb.append(String.format("  Total steps: %d%n", data.totalSteps()));

        // Per-hour breakdown (only hours with steps)
        java.util.TreeMap<String, int[]> hourly = new java.util.TreeMap<>();
        for (ActivityRecord r : data.records) {
            LocalDateTime dt = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(r.timestamp), ZoneOffset.systemDefault());
            String hourKey = dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH':00'"));
            hourly.computeIfAbsent(hourKey, k -> new int[2]);
            hourly.get(hourKey)[0] += r.steps;
            hourly.get(hourKey)[1]++;
        }

        boolean hasSteps = data.totalSteps() > 0;
        if (hasSteps) {
            sb.append("  Hourly breakdown:\n");
            for (var entry : hourly.entrySet()) {
                if (entry.getValue()[0] > 0) {
                    sb.append(String.format("    %s  %5d steps (%d min)%n",
                            entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
                }
            }
        }

        return sb.toString();
    }

    // ========== Sleep detection ==========

    /**
     * A detected sleep period.
     */
    public static class SleepPeriod {
        public final long startTimestamp;
        public final long endTimestamp;
        public final int durationMinutes;
        public final int restlessMinutes;  // minutes with some movement during sleep
        public final double avgVariability;

        SleepPeriod(long startTimestamp, long endTimestamp, int durationMinutes,
                    int restlessMinutes, double avgVariability) {
            this.startTimestamp = startTimestamp;
            this.endTimestamp = endTimestamp;
            this.durationMinutes = durationMinutes;
            this.restlessMinutes = restlessMinutes;
            this.avgVariability = avgVariability;
        }

        /** Sleep quality based on restless percentage. */
        public String quality() {
            double restlessPct = (double) restlessMinutes / durationMinutes * 100;
            if (restlessPct < 10) return "good";
            if (restlessPct < 25) return "fair";
            return "restless";
        }

        @Override
        public String toString() {
            LocalDateTime start = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(startTimestamp), ZoneOffset.systemDefault());
            LocalDateTime end = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(endTimestamp), ZoneOffset.systemDefault());
            int hours = durationMinutes / 60;
            int mins = durationMinutes % 60;
            return String.format("%s \u2014 %s  (%dh %02dm)  quality: %s",
                    start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                    end.format(DateTimeFormatter.ofPattern("HH:mm")),
                    hours, mins, quality());
        }
    }

    /**
     * Detect sleep periods from activity data.
     *
     * Algorithm:
     * 1. A minute is "asleep" if: steps == 0, isActive == false, and variability < threshold
     * 2. Consecutive asleep minutes form a sleep block
     * 3. Small gaps of movement (< GAP_TOLERANCE minutes) within a sleep block are merged
     *    (restless periods, e.g. rolling over)
     * 4. Sleep blocks shorter than MIN_SLEEP_MINUTES are discarded (naps vs real sleep)
     *
     * The variability threshold is adaptive: we use the median variability of all
     * non-active, zero-step records as the baseline, then set threshold at 2x that.
     * This handles different watch wearing tightness and individual movement patterns.
     *
     * @param data parsed activity data
     * @return list of detected sleep periods, sorted chronologically
     */
    public static List<SleepPeriod> detectSleep(ActivityData data) {
        return detectSleep(data, 30, 15);
    }

    /**
     * Detect sleep periods with configurable parameters.
     *
     * @param data parsed activity data
     * @param minSleepMinutes minimum duration to count as sleep (default: 30)
     * @param gapTolerance max awake gap in minutes to merge into one sleep period (default: 15)
     * @return list of detected sleep periods
     */
    public static List<SleepPeriod> detectSleep(ActivityData data, int minSleepMinutes, int gapTolerance) {
        if (data.records.size() < minSleepMinutes) {
            return List.of();
        }

        // Step 1: Compute adaptive variability threshold
        // Collect variability values from all quiet minutes (no steps, not active)
        List<Integer> quietVariabilities = new ArrayList<>();
        for (ActivityRecord r : data.records) {
            if (r.steps == 0 && !r.isActive) {
                quietVariabilities.add(r.variability);
            }
        }

        int variabilityThreshold;
        if (quietVariabilities.isEmpty()) {
            // No quiet minutes at all — use a reasonable default
            variabilityThreshold = 100;
        } else {
            // Use the 75th percentile of quiet variability as threshold
            // This means ~75% of quiet minutes will be classified as "asleep"
            quietVariabilities.sort(Integer::compareTo);
            int p75Index = Math.min(quietVariabilities.size() - 1,
                    (int) (quietVariabilities.size() * 0.75));
            variabilityThreshold = Math.max(quietVariabilities.get(p75Index), 50);
        }

        // Step 2: Classify each minute as asleep or awake
        boolean[] asleep = new boolean[data.records.size()];
        for (int i = 0; i < data.records.size(); i++) {
            ActivityRecord r = data.records.get(i);
            asleep[i] = (r.steps == 0 && !r.isActive && r.variability <= variabilityThreshold);
        }

        // Step 3: Find contiguous sleep blocks, merging small gaps
        List<SleepPeriod> periods = new ArrayList<>();
        int i = 0;
        while (i < asleep.length) {
            // Find start of a sleep block
            if (!asleep[i]) {
                i++;
                continue;
            }

            int blockStart = i;
            int restless = 0;
            long variabilitySum = 0;
            int sleepMinuteCount = 0;

            while (i < asleep.length) {
                if (asleep[i]) {
                    variabilitySum += data.records.get(i).variability;
                    sleepMinuteCount++;
                    i++;
                } else {
                    // Count how long the gap is
                    int gapStart = i;
                    while (i < asleep.length && !asleep[i] && (i - gapStart) < gapTolerance) {
                        i++;
                    }
                    if (i < asleep.length && asleep[i]) {
                        // Gap was short enough — merge (count as restless)
                        restless += (i - gapStart);
                    } else {
                        // Gap too long — end of this sleep block
                        // Back up to end of last asleep minute
                        i = gapStart;
                        break;
                    }
                }
            }

            int blockEnd = i - 1;
            if (blockEnd < blockStart) blockEnd = blockStart;

            int totalMinutes = blockEnd - blockStart + 1;
            if (totalMinutes >= minSleepMinutes) {
                double avgVar = sleepMinuteCount > 0
                        ? (double) variabilitySum / sleepMinuteCount : 0;
                periods.add(new SleepPeriod(
                        data.records.get(blockStart).timestamp,
                        data.records.get(blockEnd).timestamp + data.intervalSeconds,
                        totalMinutes,
                        restless,
                        avgVar));
            }

            i++;
        }

        return periods;
    }

    /**
     * Format sleep detection results as human-readable text.
     */
    public static String formatSleepSummary(List<SleepPeriod> periods) {
        if (periods.isEmpty()) {
            return "  No sleep periods detected.\n";
        }

        StringBuilder sb = new StringBuilder();
        int totalSleep = 0;
        for (SleepPeriod p : periods) {
            sb.append(String.format("    %s%n", p));
            totalSleep += p.durationMinutes;
        }
        if (periods.size() > 1) {
            sb.append(String.format("    Total: %dh %02dm across %d period(s)%n",
                    totalSleep / 60, totalSleep % 60, periods.size()));
        }
        return sb.toString();
    }

    /**
     * Format sleep periods as NDJSON.
     */
    public static String formatSleepNdjson(List<SleepPeriod> periods) {
        StringBuilder sb = new StringBuilder();
        for (SleepPeriod p : periods) {
            sb.append(String.format(
                    "{\"type\":\"sleep\",\"start\":\"%s\",\"end\":\"%s\"," +
                    "\"duration_min\":%d,\"restless_min\":%d,\"quality\":\"%s\"," +
                    "\"avg_variability\":%.1f}%n",
                    Instant.ofEpochSecond(p.startTimestamp).toString(),
                    Instant.ofEpochSecond(p.endTimestamp).toString(),
                    p.durationMinutes, p.restlessMinutes, p.quality(),
                    p.avgVariability));
        }
        return sb.toString();
    }

    /**
     * Format activity data as NDJSON (one JSON object per record).
     */
    public static String formatNdjson(ActivityData data) {
        StringBuilder sb = new StringBuilder();
        for (ActivityRecord r : data.records) {
            sb.append(String.format(
                    "{\"timestamp\":%d,\"time\":\"%s\",\"steps\":%d,\"calories\":%d,\"active\":%s,\"variability\":%d}%n",
                    r.timestamp,
                    Instant.ofEpochSecond(r.timestamp).toString(),
                    r.steps, r.calories, r.isActive, r.variability));
        }
        return sb.toString();
    }

    /**
     * Format activity data as NDJSON, but only records with non-zero steps.
     */
    public static String formatNdjsonStepsOnly(ActivityData data) {
        StringBuilder sb = new StringBuilder();
        for (ActivityRecord r : data.records) {
            if (r.steps > 0) {
                sb.append(String.format(
                        "{\"timestamp\":%d,\"time\":\"%s\",\"steps\":%d,\"calories\":%d,\"active\":%s,\"variability\":%d}%n",
                        r.timestamp,
                        Instant.ofEpochSecond(r.timestamp).toString(),
                        r.steps, r.calories, r.isActive, r.variability));
            }
        }
        return sb.toString();
    }
}
