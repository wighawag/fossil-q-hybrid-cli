// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.alarm.Alarm;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.alarm.AlarmsSetRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden-byte tests for the alarm wire format (FINDINGS.md #12).
 *
 * <p>Locks the exact 3-byte-per-alarm encoding and the corrected weekday bitmask
 * (bit3=Wed, bit4=Thu) that this project deliberately diverged on. These values
 * are hardware-verified on a Q Commuter (HW.0.0, firmware HW0.0.2.9r.v3).
 *
 * <p>This is a REGRESSION GATE for the protocol re-own: any change to these bytes
 * is a bug.
 */
public class GoldenAlarmTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    @Test
    void oneShot_11_01() {
        // One-shot (repeat=false): [0xFF] [minute] [hour]
        Alarm a = new Alarm((byte) 1, (byte) 11, "t", "");
        assertArrayEquals(b(0xFF, 0x01, 0x0B), a.getData());
    }

    @Test
    void repeating_thursday_11_04() {
        // FINDINGS #12: repeat Thu (bit4=16) 11:04 -> 90 84 0B
        // repeat=true: byte0 = 0x80|days, byte1 = minute|0x80, byte2 = hour
        Alarm a = new Alarm((byte) 4, (byte) 11, (byte) 16, "t", "");
        assertArrayEquals(b(0x90, 0x84, 0x0B), a.getData());
    }

    @Test
    void repeating_monFri_0730_days30() {
        // Mon-Fri shortcut with corrected bits = 2+4+8+16 = 30
        // byte0 = 0x80|30 = 0x9E, byte1 = 30|0x80 = 0x9E, byte2 = 7
        Alarm a = new Alarm((byte) 30, (byte) 7, (byte) 30, "t", "");
        assertArrayEquals(b(0x9E, 0x9E, 0x07), a.getData());
    }

    @Test
    void alarmFile_legacyFormat_concatenates3BytesEach() {
        // fileFormat != 0x03 -> raw 3 bytes per alarm, in order
        Alarm oneShot = new Alarm((byte) 1, (byte) 11, "t", "");
        Alarm repThu = new Alarm((byte) 4, (byte) 11, (byte) 16, "t", "");
        byte[] file = AlarmsSetRequest.createFileFromAlarms(new Alarm[]{oneShot, repThu}, (short) 0);
        assertArrayEquals(b(0xFF, 0x01, 0x0B, 0x90, 0x84, 0x0B), file);
    }

    @Test
    void emptyAlarmFile_isZeroBytes() {
        byte[] file = AlarmsSetRequest.createFileFromAlarms(new Alarm[0], (short) 0);
        assertEquals(0, file.length);
    }

    @Test
    void fromBytes_roundTrip_oneShot() {
        Alarm a = Alarm.fromBytes(b(0xFF, 0x01, 0x0B));
        assertArrayEquals(b(0xFF, 0x01, 0x0B), a.getData());
    }

    @Test
    void fromBytes_roundTrip_repeating() {
        Alarm a = Alarm.fromBytes(b(0x90, 0x84, 0x0B));
        assertArrayEquals(b(0x90, 0x84, 0x0B), a.getData());
    }
}
