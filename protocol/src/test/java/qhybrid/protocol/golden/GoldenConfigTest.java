// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.configuration.ConfigurationPutRequest.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-byte tests for configuration items (FINDINGS.md #21a/#21f/#22).
 *
 * <p>Locks the TLV content bytes (little-endian) each ConfigItem emits. These feed
 * the CONFIGURATION file (0x0800). Regression gate for the re-own.
 */
public class GoldenConfigTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    @Test
    void dailyStepGoal_10000() {
        DailyStepGoalConfigItem item = new DailyStepGoalConfigItem(10000);
        assertEquals((short) 3, item.getId());
        assertEquals(4, item.getItemSize());
        assertArrayEquals(b(0x10, 0x27, 0x00, 0x00), item.getContent()); // 10000 LE
    }

    @Test
    void vibrationStrength_50() {
        VibrationStrengthConfigItem item = new VibrationStrengthConfigItem((byte) 50);
        assertEquals((short) 10, item.getId());
        assertEquals(1, item.getItemSize());
        assertArrayEquals(b(0x32), item.getContent());
    }

    @Test
    void time_epochMillisOffset() {
        // id=12, 8 bytes: epoch(4 LE) millis(2 LE) offsetMinutes(2 LE)
        TimeConfigItem item = new TimeConfigItem(1700000000, (short) 0, (short) 60);
        assertEquals((short) 12, item.getId());
        assertEquals(8, item.getItemSize());
        assertArrayEquals(b(0x00, 0xF1, 0x53, 0x65, 0x00, 0x00, 0x3C, 0x00), item.getContent());
    }

    @Test
    void inactivityWarning_9to17_30min_enabled() {
        // id=9, 6 bytes: fromH fromM toH toM minutes enabled
        InactivityWarningItem item = new InactivityWarningItem(9, 0, 17, 0, 30, true);
        assertEquals((short) 9, item.getId());
        assertEquals(6, item.getItemSize());
        assertArrayEquals(b(0x09, 0x00, 0x11, 0x00, 0x1E, 0x01), item.getContent());
    }

    @Test
    void secondTimezoneOffset_330() {
        // id=17, 2 bytes LE: 330 = 0x014A
        TimezoneOffsetConfigItem item = new TimezoneOffsetConfigItem((short) 330);
        assertEquals((short) 17, item.getId());
        assertEquals(2, item.getItemSize());
        assertArrayEquals(b(0x4A, 0x01), item.getContent());
    }

    @Test
    void goalTracking_genericInt_0x17() {
        GenericConfigItem<Integer> item = new GenericConfigItem<>((short) 0x17, 5000);
        assertEquals((short) 0x17, item.getId());
        assertEquals(4, item.getItemSize());
        assertArrayEquals(b(0x88, 0x13, 0x00, 0x00), item.getContent()); // 5000 LE
    }
}
