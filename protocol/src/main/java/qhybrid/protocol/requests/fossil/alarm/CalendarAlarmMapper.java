// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.requests.fossil.alarm;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WP9 — Calendar → Alarm mapping (pure JVM logic).
 *
 * <p>Turns a list of calendar events ({@code (title, startEpochMillis)}) into
 * {@code <= 16} non-repeating weekday {@link AlarmSlot} objects for the calendar
 * slot range (16..31), which {@link AlarmCompiler} then compiles to the watch's
 * undocumented non-repeat-weekday wire bytes ({@code [0x80|days][minute][hour]},
 * FINDINGS #12).
 *
 * <p><b>No Android, no BLE, no UI, no system clock.</b> The "now" instant and the
 * {@link ZoneId} are injected so the mapping is fully deterministic and JVM-testable.
 * WP13 ({@code :android}) reads {@code CalendarContract} and feeds plain
 * {@code (title, DTSTART)} pairs here; this class never touches Android calendar APIs.
 *
 * <p><b>Pipeline:</b>
 * <ol>
 *   <li>Filter to the forward 7-day window {@code [now, now + 7 days)} (local zone).</li>
 *   <li>De-dup events that map to the SAME wire identity (weekday-bit, hour, minute)
 *       — the watch cannot distinguish two alarms with identical bytes; the
 *       earliest-starting event wins (and supplies the label).</li>
 *   <li>Sort by ascending start time (tie-break: title, then original index).</li>
 *   <li>Take the nearest {@code <= 16}, assign slots 16, 17, ... in order.</li>
 *   <li>Emit one non-repeating, enabled {@link AlarmSlot} per kept event.</li>
 * </ol>
 *
 * <p><b>Weekday → daysMask:</b> the local {@link DayOfWeek} maps to the wire bit
 * (bit0=Sun..bit6=Sat) with NO translation — the same 1:1 {@code daysMask}
 * convention as {@link AlarmSlot}/{@link AlarmCompiler} (FINDINGS #12:
 * bit3=Wed, bit4=Thu). E.g. Friday -&gt; bit5 -&gt; {@code 0x20}.
 */
public final class CalendarAlarmMapper {

    /** Number of days forward (from injected "now") that events are considered. */
    public static final int WINDOW_DAYS = 7;

    /** Maximum calendar alarms (slots 16..31 inclusive == 16 entries). */
    public static final int MAX_CALENDAR_ALARMS =
            AlarmCompiler.CALENDAR_SLOT_MAX - AlarmCompiler.CALENDAR_SLOT_MIN + 1; // 16

    private CalendarAlarmMapper() {}

    /** Immutable calendar event input: a title plus a UTC epoch-millis start time. */
    public static final class CalendarEvent {
        private final String title;
        private final long startEpochMillis;

        public CalendarEvent(String title, long startEpochMillis) {
            this.title = title;
            this.startEpochMillis = startEpochMillis;
        }

        public String getTitle() { return title; }
        public long getStartEpochMillis() { return startEpochMillis; }

        @Override
        public String toString() {
            return "CalendarEvent{title=" + title + ", start=" + startEpochMillis + "}";
        }
    }

    /**
     * Map calendar events to calendar-slot {@link AlarmSlot} objects (slots 16..31).
     *
     * @param events           the calendar events (may be null/empty)
     * @param nowEpochMillis   the injected "now" (UTC epoch millis); window start
     * @param zone             the local zone for weekday/hour/minute computation
     * @return up to {@value #MAX_CALENDAR_ALARMS} non-repeating, enabled alarm slots,
     *         ordered by ascending start time, assigned slots 16, 17, ...
     */
    public static List<AlarmSlot> mapEventsToAlarmSlots(
            List<CalendarEvent> events, long nowEpochMillis, ZoneId zone) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        if (zone == null) {
            throw new IllegalArgumentException("zone must not be null");
        }

        Instant now = Instant.ofEpochMilli(nowEpochMillis);
        Instant windowEnd = now.plus(java.time.Duration.ofDays(WINDOW_DAYS));

        // --- 1. Filter to [now, now + 7 days) and pre-compute local fields ---
        List<Mapped> inWindow = new ArrayList<>();
        int originalIndex = 0;
        for (CalendarEvent e : events) {
            int idx = originalIndex++;
            if (e == null) continue;
            Instant start = Instant.ofEpochMilli(e.getStartEpochMillis());
            // Strict forward window: include now itself, exclude now+7d.
            if (start.isBefore(now) || !start.isBefore(windowEnd)) {
                continue;
            }
            ZonedDateTime local = start.atZone(zone);
            int daysMask = weekdayBit(local.getDayOfWeek());
            inWindow.add(new Mapped(
                    e.getTitle(),
                    e.getStartEpochMillis(),
                    idx,
                    local.getHour(),
                    local.getMinute(),
                    daysMask));
        }
        if (inWindow.isEmpty()) {
            return List.of();
        }

        // --- 2. Sort by start (tie-break: title, then original index) for stable order ---
        inWindow.sort(Comparator
                .comparingLong((Mapped m) -> m.startEpochMillis)
                .thenComparing(m -> m.title == null ? "" : m.title)
                .thenComparingInt(m -> m.originalIndex));

        // --- 3. De-dup on wire identity (daysMask, hour, minute); earliest wins ---
        //     LinkedHashMap preserves the (already start-sorted) first-seen order.
        Map<Long, Mapped> unique = new LinkedHashMap<>();
        for (Mapped m : inWindow) {
            long key = wireKey(m.daysMask, m.hour, m.minute);
            unique.putIfAbsent(key, m);
        }

        // --- 4. Take nearest <= 16, assign slots 16, 17, ... ---
        List<AlarmSlot> out = new ArrayList<>();
        int slot = AlarmCompiler.CALENDAR_SLOT_MIN;
        for (Mapped m : unique.values()) {
            if (out.size() >= MAX_CALENDAR_ALARMS) break;
            out.add(new AlarmSlot(
                    slot++, m.hour, m.minute, m.daysMask,
                    /* repeating */ false, /* enabled */ true, m.title));
        }
        return out;
    }

    /**
     * Map a {@link DayOfWeek} to the wire days-byte bit (bit0=Sun..bit6=Sat).
     * No translation — identical to {@link AlarmSlot}'s {@code daysMask} convention
     * (FINDINGS #12: bit3=Wed, bit4=Thu).
     */
    public static int weekdayBit(DayOfWeek day) {
        switch (day) {
            case SUNDAY:    return 1;       // bit0 = 0x01
            case MONDAY:    return 1 << 1;  // bit1 = 0x02
            case TUESDAY:   return 1 << 2;  // bit2 = 0x04
            case WEDNESDAY: return 1 << 3;  // bit3 = 0x08
            case THURSDAY:  return 1 << 4;  // bit4 = 0x10
            case FRIDAY:    return 1 << 5;  // bit5 = 0x20
            case SATURDAY:  return 1 << 6;  // bit6 = 0x40
            default: throw new IllegalArgumentException("Unknown day: " + day);
        }
    }

    private static long wireKey(int daysMask, int hour, int minute) {
        return ((long) (daysMask & 0xFF) << 16) | ((long) (hour & 0xFF) << 8) | (minute & 0xFF);
    }

    /** Internal: an event projected into local wire fields, with provenance for ordering. */
    private static final class Mapped {
        final String title;
        final long startEpochMillis;
        final int originalIndex;
        final int hour;
        final int minute;
        final int daysMask;

        Mapped(String title, long startEpochMillis, int originalIndex,
               int hour, int minute, int daysMask) {
            this.title = title;
            this.startEpochMillis = startEpochMillis;
            this.originalIndex = originalIndex;
            this.hour = hour;
            this.minute = minute;
            this.daysMask = daysMask;
        }
    }
}
