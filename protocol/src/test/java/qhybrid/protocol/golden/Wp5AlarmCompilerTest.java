// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import qhybrid.protocol.requests.fossil.alarm.AlarmCompiler;
import qhybrid.protocol.requests.fossil.alarm.AlarmSlot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WP5 golden-byte tests for {@link AlarmCompiler} (FINDINGS.md #12).
 *
 * <p>Locks the 16/16 slot split, the three wire modes, the corrected weekday
 * bitmask (bit3=Wed, bit4=Thu), the daysMask 1:1 identity, and the 32-slot guard.
 * Hardware-verified values come from the Q Commuter table in FINDINGS #12.
 */
public class Wp5AlarmCompilerTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    // --- daysMask wire bits (== WatchAlarmEntity.daysMask: bit0=Sun..bit6=Sat) ---
    private static final int SUN = 1, MON = 2, TUE = 4, WED = 8, THU = 16, FRI = 32, SAT = 64;

    // ---------------------------------------------------------------- modes

    @Test
    void standardOneShot_11_01() {
        // [0xFF] [minute] [hour]
        AlarmSlot a = new AlarmSlot(0, 11, 1, 0, false, true, "t");
        assertArrayEquals(b(0xFF, 0x01, 0x0B), AlarmCompiler.encode(a));
    }

    @Test
    void standardRepeating_thursday_11_04() {
        // FINDINGS #12: repeat Thu(bit4=16) -> [0x80|days] [minute|0x80] [hour] = 90 84 0B
        AlarmSlot a = new AlarmSlot(0, 11, 4, THU, true, true, "t");
        assertArrayEquals(b(0x90, 0x84, 0x0B), AlarmCompiler.encode(a));
    }

    @Test
    void standardRepeating_monFri_shortcut_1239() {
        // FINDINGS #12 (line 1038) hardware capture: Mon-Fri repeat -> byte0 = 0xBE
        // = 0x80 | bits1-5 = 0x80 | (MON|TUE|WED|THU|FRI) = 0x80 | 0x3E.
        // Corrected bits: Mon(2)|Tue(4)|Wed(8)|Thu(16)|Fri(32) = 0x3E (62).
        // Repeating: [0x80|days] [minute|0x80] [hour]; 12:39 -> BE A7 0C.
        AlarmSlot a = new AlarmSlot(0, 12, 39, 0x3E, true, true, "t");
        assertArrayEquals(b(0xBE, 0xA7, 0x0C), AlarmCompiler.encode(a));
    }

    @Test
    void standardRepeating_legacyDays30_matchesExistingGolden() {
        // Regression parity with GoldenAlarmTest.repeating_monFri_0730_days30:
        // days=30 (0x1E) -> 9E 9E 07. (Note: 0x1E is Mon-Thu, not Mon-Fri; the
        // "--days 30 = Mon-Fri" CLI doc at FINDINGS line 357 is mislabeled. The true
        // Mon-Fri mask is 0x3E, asserted above.)
        AlarmSlot a = new AlarmSlot(0, 7, 30, 0x1E, true, true, "t");
        assertArrayEquals(b(0x9E, 0x9E, 0x07), AlarmCompiler.encode(a));
    }

    @Test
    void calendarNonRepeatWeekday_thursday_11_14() {
        // FINDINGS #12 hardware-verified: non-repeat Thu(0x80+bit4) 11:14 -> 90 0E 0B
        // Calendar slot (>=16): [0x80|days] [minute] [hour]  (byte1 has NO 0x80)
        AlarmSlot a = new AlarmSlot(16, 11, 14, THU, false, true, "cal");
        assertArrayEquals(b(0x90, 0x0E, 0x0B), AlarmCompiler.encode(a));
    }

    @Test
    void calendarNonRepeatWeekday_friday_11_17() {
        // FINDINGS #12: non-repeat Fri(0x80+bit5=0x20) 11:17 -> A0 11 0B
        AlarmSlot a = new AlarmSlot(20, 11, 17, FRI, false, true, "cal");
        assertArrayEquals(b(0xA0, 0x11, 0x0B), AlarmCompiler.encode(a));
    }

    // ---------------------------------------------- daysMask 1:1 identity

    @Test
    void daysMask_isPassedThroughAsWireDaysByte_noTranslation() {
        // Wed=bit3(8). Standard repeating Wed -> [0x80|8]=0x88, minute|0x80, hour.
        AlarmSlot wed = new AlarmSlot(0, 9, 0, WED, true, true, "t");
        assertArrayEquals(b(0x88, 0x80, 0x09), AlarmCompiler.encode(wed));

        // Calendar Wed (non-repeat) -> [0x80|8]=0x88, minute, hour.
        AlarmSlot calWed = new AlarmSlot(16, 9, 0, WED, false, true, "t");
        assertArrayEquals(b(0x88, 0x00, 0x09), AlarmCompiler.encode(calWed));
    }

    // ---------------------------------------------------- 16/16 split

    @Test
    void split_standardGoesFirst_calendarSecond_orderedBySlot() {
        AlarmSlot std = new AlarmSlot(0, 11, 1, 0, false, true, "s");      // FF 01 0B
        AlarmSlot cal = new AlarmSlot(16, 11, 14, THU, false, true, "c");  // 90 0E 0B
        byte[] file = AlarmCompiler.compile(List.of(std), List.of(cal));
        assertArrayEquals(b(0xFF, 0x01, 0x0B, 0x90, 0x0E, 0x0B), file);
    }

    @Test
    void split_rejectsStandardAlarmInCalendarRange() {
        AlarmSlot bad = new AlarmSlot(16, 11, 1, 0, false, true, "s");
        assertThrows(IllegalArgumentException.class,
                () -> AlarmCompiler.compile(List.of(bad), List.of()));
    }

    @Test
    void split_rejectsCalendarAlarmInStandardRange() {
        AlarmSlot bad = new AlarmSlot(0, 11, 1, THU, false, true, "c");
        assertThrows(IllegalArgumentException.class,
                () -> AlarmCompiler.compile(List.of(), List.of(bad)));
    }

    @Test
    void disabledAlarms_areSkipped() {
        AlarmSlot on = new AlarmSlot(0, 11, 1, 0, false, true, "on");
        AlarmSlot off = new AlarmSlot(1, 12, 0, 0, false, false, "off");
        byte[] file = AlarmCompiler.compile(List.of(on, off), List.of());
        assertArrayEquals(b(0xFF, 0x01, 0x0B), file);
    }

    @Test
    void empty_isZeroBytes() {
        assertEquals(0, AlarmCompiler.compile(List.of(), List.of()).length);
        assertEquals(0, AlarmCompiler.compile(null, null).length);
    }

    // ----------------------------------------------------- 32-slot guard

    @Test
    void thirtyTwoAlarms_accepted() {
        List<AlarmSlot> std = new ArrayList<>();
        for (int i = 0; i <= 15; i++) std.add(new AlarmSlot(i, 7, 0, 0, false, true, "s"));
        List<AlarmSlot> cal = new ArrayList<>();
        for (int i = 16; i <= 31; i++) cal.add(new AlarmSlot(i, 8, 0, THU, false, true, "c"));
        byte[] file = AlarmCompiler.compile(std, cal);
        assertEquals(32 * 3, file.length);
    }

    @Test
    void thirtyThreeAlarms_rejectedBeforeBytes() {
        List<AlarmSlot> std = new ArrayList<>();
        for (int i = 0; i <= 15; i++) std.add(new AlarmSlot(i, 7, 0, 0, false, true, "s"));
        List<AlarmSlot> cal = new ArrayList<>();
        // 17 calendar alarms — but only slots 16..31 valid; use 16..31 (16) + one more
        // out-of-range would trip range check first, so keep all in range to force the
        // count guard: 16 std + 16 cal = 32 is the max; add a 17th std to exceed.
        for (int i = 16; i <= 31; i++) cal.add(new AlarmSlot(i, 8, 0, THU, false, true, "c"));
        std.add(new AlarmSlot(0, 9, 0, 0, false, true, "extra")); // 17 std + 16 cal = 33
        assertThrows(IllegalArgumentException.class, () -> AlarmCompiler.compile(std, cal));
    }
}
