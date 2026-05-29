// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import qhybrid.protocol.requests.fossil.alarm.AlarmCompiler;
import qhybrid.protocol.requests.fossil.alarm.AlarmSlot;
import qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper;
import qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper.CalendarEvent;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP9 tests for {@link CalendarAlarmMapper} — calendar events → calendar-slot
 * {@link AlarmSlot} objects (slots 16..31, non-repeating weekday).
 *
 * <p>Deterministic: every test injects a fixed "now" + fixed {@link ZoneId}, so no
 * system clock is consulted. Weekday→daysMask uses the 1:1 wire convention
 * (bit0=Sun..bit6=Sat; bit3=Wed, bit4=Thu per FINDINGS #12), and the final byte
 * round-trip is verified through {@link AlarmCompiler#encode}.
 */
public class Wp9CalendarAlarmMapperTest {

    // Fixed test zone (no DST surprises for the core asserts).
    private static final ZoneId UTC = ZoneId.of("UTC");

    // --- daysMask wire bits (== AlarmSlot.daysMask: bit0=Sun..bit6=Sat) ---
    private static final int SUN = 1, MON = 2, TUE = 4, WED = 8, THU = 16, FRI = 32, SAT = 64;

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    /** Local wall-clock time in {@link #UTC} → epoch millis. */
    private static long at(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), UTC)
                .toInstant().toEpochMilli();
    }

    // ---------------------------------------------------------- weekday → bit

    @Test
    void weekdayBit_matchesWireConvention_bit3Wed_bit4Thu() {
        assertEquals(SUN, CalendarAlarmMapper.weekdayBit(DayOfWeek.SUNDAY));
        assertEquals(MON, CalendarAlarmMapper.weekdayBit(DayOfWeek.MONDAY));
        assertEquals(TUE, CalendarAlarmMapper.weekdayBit(DayOfWeek.TUESDAY));
        assertEquals(WED, CalendarAlarmMapper.weekdayBit(DayOfWeek.WEDNESDAY)); // bit3=8
        assertEquals(THU, CalendarAlarmMapper.weekdayBit(DayOfWeek.THURSDAY));  // bit4=16
        assertEquals(FRI, CalendarAlarmMapper.weekdayBit(DayOfWeek.FRIDAY));    // bit5=32
        assertEquals(SAT, CalendarAlarmMapper.weekdayBit(DayOfWeek.SATURDAY));
    }

    // ------------------------------------------------ acceptance: Friday 10:15

    @Test
    void fridayEvent_mapsToFridayBit_andCompilesTo_A0_0F_0A() {
        // 2026-05-29 is a Friday. now = Mon 2026-05-25 09:00 UTC.
        long now = at(2026, 5, 25, 9, 0);
        CalendarEvent fri = new CalendarEvent("Standup", at(2026, 5, 29, 10, 15)); // Fri 10:15

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(fri), now, UTC);

        assertEquals(1, slots.size());
        AlarmSlot s = slots.get(0);
        assertTrue(s.getSlotId() >= AlarmCompiler.CALENDAR_SLOT_MIN
                && s.getSlotId() <= AlarmCompiler.CALENDAR_SLOT_MAX);
        assertEquals(AlarmCompiler.CALENDAR_SLOT_MIN, s.getSlotId()); // first → slot 16
        assertEquals(10, s.getHour());
        assertEquals(15, s.getMinute());
        assertEquals(FRI, s.getDaysMask()); // bit5 = 0x20
        assertFalse(s.isRepeating());
        assertTrue(s.isEnabled());
        assertEquals("Standup", s.getLabel());

        // Round-trip through the compiler: non-repeat Fri 10:15 -> A0 0F 0A.
        assertArrayEquals(b(0xA0, 0x0F, 0x0A), AlarmCompiler.encode(s));
    }

    // ------------------------------------- deterministic order + slot assignment

    @Test
    void deterministicOrder_assignsSlots16Upward_byStartTime() {
        // now = Mon 2026-05-25 00:00 UTC. Events given OUT of order on purpose.
        long now = at(2026, 5, 25, 0, 0);
        CalendarEvent wed = new CalendarEvent("Wed",  at(2026, 5, 27, 14, 0));  // 3rd
        CalendarEvent mon = new CalendarEvent("Mon",  at(2026, 5, 25, 8, 30));  // 1st
        CalendarEvent tue = new CalendarEvent("Tue",  at(2026, 5, 26, 9, 5));   // 2nd

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(wed, mon, tue), now, UTC);

        assertEquals(3, slots.size());

        assertEquals(16, slots.get(0).getSlotId());
        assertEquals("Mon", slots.get(0).getLabel());
        assertEquals(MON, slots.get(0).getDaysMask());
        assertEquals(8, slots.get(0).getHour());
        assertEquals(30, slots.get(0).getMinute());

        assertEquals(17, slots.get(1).getSlotId());
        assertEquals("Tue", slots.get(1).getLabel());
        assertEquals(TUE, slots.get(1).getDaysMask());

        assertEquals(18, slots.get(2).getSlotId());
        assertEquals("Wed", slots.get(2).getLabel());
        assertEquals(WED, slots.get(2).getDaysMask());
    }

    // --------------------------------------------------- window filtering

    @Test
    void eventBeyond7Days_isExcluded() {
        long now = at(2026, 5, 25, 9, 0);
        CalendarEvent inside = new CalendarEvent("inside", at(2026, 5, 31, 9, 0)); // +6d
        CalendarEvent outside = new CalendarEvent("outside", at(2026, 6, 2, 9, 0)); // +8d

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(inside, outside), now, UTC);

        assertEquals(1, slots.size());
        assertEquals("inside", slots.get(0).getLabel());
    }

    @Test
    void pastEvent_isExcluded_butNowItselfIsIncluded() {
        long now = at(2026, 5, 25, 9, 0);
        CalendarEvent past = new CalendarEvent("past", at(2026, 5, 25, 8, 59));
        CalendarEvent atNow = new CalendarEvent("atNow", at(2026, 5, 25, 9, 0));

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(past, atNow), now, UTC);

        assertEquals(1, slots.size());
        assertEquals("atNow", slots.get(0).getLabel());
    }

    @Test
    void exactlySevenDaysOut_isExcluded_windowIsHalfOpen() {
        long now = at(2026, 5, 25, 9, 0);
        CalendarEvent edge = new CalendarEvent("edge", at(2026, 6, 1, 9, 0)); // now + exactly 7d
        CalendarEvent justInside = new CalendarEvent("in", at(2026, 6, 1, 8, 59));

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(edge, justInside), now, UTC);

        assertEquals(1, slots.size());
        assertEquals("in", slots.get(0).getLabel());
    }

    // --------------------------------------------------- 16-cap (nearest 16)

    @Test
    void twentyEvents_onlyNearest16Kept_inSlots16to31() {
        long now = at(2026, 5, 25, 0, 0);
        List<CalendarEvent> events = new ArrayList<>();
        // 20 distinct events at distinct minute offsets across the next few days,
        // created in REVERSE start order to prove sorting picks the nearest 16.
        for (int i = 20; i >= 1; i--) {
            // distinct day/hour/minute combos to avoid de-dup collapsing them.
            int day = 25 + (i % 6);          // 25..30 (all within +7d of the 25th)
            int hour = (i % 12);
            int minute = i;                   // 1..20 distinct
            events.add(new CalendarEvent("e" + i, at(2026, 5, day, hour, minute)));
        }

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(events, now, UTC);

        assertEquals(16, slots.size());
        assertEquals(16, slots.get(0).getSlotId());
        assertEquals(31, slots.get(15).getSlotId());

        // Slots are contiguous 16..31 in ascending order; compile() must accept them.
        for (int i = 1; i < slots.size(); i++) {
            assertEquals(slots.get(i - 1).getSlotId() + 1, slots.get(i).getSlotId());
        }

        byte[] file = AlarmCompiler.compile(List.of(), slots);
        assertEquals(16 * 3, file.length); // 16 enabled calendar alarms, 3 bytes each
    }

    // --------------------------------------------------- de-dup on wire identity

    @Test
    void duplicateWireIdentity_collapsed_earliestWins() {
        // Two events on the SAME weekday at the SAME HH:MM but different weeks would
        // be out of the 7-day window; instead use two events the SAME day+HH:MM.
        long now = at(2026, 5, 25, 0, 0);
        CalendarEvent first = new CalendarEvent("first", at(2026, 5, 27, 14, 0));
        CalendarEvent dupLater = new CalendarEvent("dupLater", at(2026, 5, 27, 14, 0));
        CalendarEvent other = new CalendarEvent("other", at(2026, 5, 27, 14, 1));

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(dupLater, first, other), now, UTC);

        // first & dupLater share (Wed,14,00) → one slot; earliest-by-sort wins.
        // first and dupLater have identical start; tie-break by title: "dupLater" < "first"?
        // "dupLater" vs "first": 'd' < 'f' so dupLater sorts first → it wins.
        assertEquals(2, slots.size());
        assertEquals(WED, slots.get(0).getDaysMask());
        assertEquals(14, slots.get(0).getHour());
        assertEquals(0, slots.get(0).getMinute());
        assertEquals("dupLater", slots.get(0).getLabel());

        assertEquals(1, slots.get(1).getMinute()); // the 14:01 "other"
        assertEquals("other", slots.get(1).getLabel());
    }

    @Test
    void duplicateWireIdentity_sameInstant_collapses_titleTieBreak() {
        // Two events at the identical instant (so identical weekday/HH:MM wire bytes)
        // collapse to one slot; the tie-break is title ("earlier" < "zzz").
        long now = at(2026, 5, 25, 0, 0);
        CalendarEvent earlier = new CalendarEvent("earlier", at(2026, 5, 27, 9, 0));
        CalendarEvent same = new CalendarEvent("zzz", at(2026, 5, 27, 9, 0));

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(same, earlier), now, UTC);

        assertEquals(1, slots.size());
        assertEquals("earlier", slots.get(0).getLabel());
    }

    @Test
    void duplicateWireIdentity_acrossDifferentWeekdaysSameHHMM_notCollapsed() {
        // Same HH:MM but DIFFERENT weekdays differ in the days byte -> distinct wire
        // identity -> kept as separate slots.
        long now = at(2026, 5, 25, 0, 0);
        CalendarEvent wed = new CalendarEvent("wed", at(2026, 5, 27, 9, 0)); // Wed 09:00
        CalendarEvent thu = new CalendarEvent("thu", at(2026, 5, 28, 9, 0)); // Thu 09:00

        List<AlarmSlot> slots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(wed, thu), now, UTC);

        assertEquals(2, slots.size());
        assertEquals(WED, slots.get(0).getDaysMask());
        assertEquals(THU, slots.get(1).getDaysMask());
    }

    // --------------------------------------------------- empty / null inputs

    @Test
    void emptyOrNullInput_returnsEmpty() {
        long now = at(2026, 5, 25, 9, 0);
        assertTrue(CalendarAlarmMapper.mapEventsToAlarmSlots(null, now, UTC).isEmpty());
        assertTrue(CalendarAlarmMapper.mapEventsToAlarmSlots(List.of(), now, UTC).isEmpty());
    }

    @Test
    void allEventsOutOfWindow_returnsEmpty() {
        long now = at(2026, 5, 25, 9, 0);
        CalendarEvent far = new CalendarEvent("far", at(2026, 7, 1, 9, 0));
        assertTrue(CalendarAlarmMapper.mapEventsToAlarmSlots(List.of(far), now, UTC).isEmpty());
    }

    // ----------------------------------------- zone affects local weekday/time

    @Test
    void zoneIsHonored_localWeekdayAndTimeShift() {
        // An instant that is Friday 23:30 UTC but Saturday 01:30 in Paris (UTC+2 DST).
        ZoneId paris = ZoneId.of("Europe/Paris");
        long now = at(2026, 5, 25, 0, 0); // Mon 00:00 UTC, safely before the event
        long eventUtc = ZonedDateTime.of(
                LocalDateTime.of(2026, 5, 29, 23, 30), UTC).toInstant().toEpochMilli();
        CalendarEvent e = new CalendarEvent("ev", eventUtc);

        List<AlarmSlot> utcSlots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(e), now, UTC);
        assertEquals(FRI, utcSlots.get(0).getDaysMask()); // Friday in UTC
        assertEquals(23, utcSlots.get(0).getHour());
        assertEquals(30, utcSlots.get(0).getMinute());

        List<AlarmSlot> parisSlots = CalendarAlarmMapper.mapEventsToAlarmSlots(
                List.of(e), now, paris);
        assertEquals(SAT, parisSlots.get(0).getDaysMask()); // Saturday in Paris (+2)
        assertEquals(1, parisSlots.get(0).getHour());
        assertEquals(30, parisSlots.get(0).getMinute());
    }
}
