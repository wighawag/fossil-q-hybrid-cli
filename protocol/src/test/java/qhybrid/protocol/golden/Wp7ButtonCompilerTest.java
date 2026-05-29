// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import qhybrid.protocol.ButtonConfigBuilder;
import qhybrid.protocol.ButtonConfigBuilder.ButtonEntry;
import qhybrid.protocol.FossilController;
import qhybrid.protocol.buttonconfig.ConfigFileBuilder;
import qhybrid.protocol.buttonconfig.ConfigPayload;
import qhybrid.protocol.requests.fossil.button.ButtonCompiler;
import qhybrid.protocol.requests.fossil.button.ButtonCompiler.DialMode;
import qhybrid.protocol.requests.fossil.button.ButtonCompiler.DialModel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP7 golden-byte tests for {@link ButtonCompiler} (SETTINGS_BUTTONS, 0x0600).
 *
 * <p>Locks the two real wire formats 1:1:
 * <ul>
 *   <li><b>Multi-entry</b> mode-toggle files: per-entry {@code 0x00}, non-deduplicated
 *       payloads, customization section, and the CRC32 LE trailer (FINDINGS #19/#21b/#22).</li>
 *   <li><b>Single-entry-per-button</b> vendored files: dedup payloads, customization count 0,
 *       optional CRC32 trailer.</li>
 * </ul>
 *
 * <p>The captured payloads embedded in {@code ButtonConfigBuilder} (ALARM_SEQUENCED,
 * DATE_TOGGLE, 24H/24H_SEQ, GOAL_TRACKING) are the golden vectors and are asserted directly.
 * The assembled file is verified structurally + by independent CRC recomputation, plus a
 * regression equality assertion that the old paths emit identical bytes.
 */
public class Wp7ButtonCompilerTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    /** Little-endian int as 4 bytes (for asserting the CRC trailer). */
    private static int[] le32(int v) {
        return new int[]{v & 0xFF, (v >>> 8) & 0xFF, (v >>> 16) & 0xFF, (v >>> 24) & 0xFF};
    }

    private static int crc32(byte[] data, int off, int len) {
        CRC32 c = new CRC32();
        c.update(data, off, len);
        return (int) c.getValue();
    }

    // ============================================================ captured golden payloads

    @Test
    void golden_alarmSequenced_header_and_data() {
        assertArrayEquals(b(0x01, 0x02, 0x1a, 0x00),
                ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY.header());
        assertEquals(54, ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY.data().length);
        assertArrayEquals(b(
                0x01, 0x00, 0x01, 0x02, 0x1a, 0x36, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x08, 0x00, 0x04, 0x00, 0x00,
                0x07, 0x02, 0x00, 0x00, 0x01, 0x01, 0x1d, 0x00,
                0x89, 0x02, 0x01, 0x04, 0xb0, 0x03, 0x00, 0x89,
                0x05, 0x01, 0x07, 0xb0, 0x03, 0x00, 0xb0, 0x03,
                0x00, 0xb0, 0x03, 0x00, 0x08, 0x01, 0x50, 0x00,
                0x01, 0x00, 0xa6, 0x79, 0x57, 0xcc),
                ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY.data());
    }

    @Test
    void golden_dateToggle_header_and_data() {
        assertArrayEquals(b(0x01, 0x02, 0x14, 0x00),
                ButtonConfigBuilder.DATE_TOGGLE_ENTRY.header());
        assertEquals(52, ButtonConfigBuilder.DATE_TOGGLE_ENTRY.data().length);
        assertArrayEquals(b(
                0x01, 0x00, 0x01, 0x02, 0x14, 0x34, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x06, 0x00, 0x02, 0x00, 0x00,
                0x07, 0x00, 0x01, 0x01, 0x1d, 0x00, 0x89, 0x02,
                0x01, 0x04, 0xb0, 0x00, 0x00, 0x89, 0x05, 0x01,
                0x07, 0xb0, 0x00, 0x00, 0xb0, 0x00, 0x00, 0xb0,
                0x00, 0x00, 0x08, 0x01, 0x50, 0x00, 0x01, 0x00,
                0x77, 0x9c, 0x0c, 0x19),
                ButtonConfigBuilder.DATE_TOGGLE_ENTRY.data());
    }

    @Test
    void golden_goalTracking_header_and_data() {
        assertArrayEquals(b(0x01, 0x01, 0x04, 0x00),
                ButtonConfigBuilder.GOAL_TRACKING_ENTRY.header());
        assertEquals(33, ButtonConfigBuilder.GOAL_TRACKING_ENTRY.data().length);
        assertArrayEquals(b(
                0x01, 0x00, 0x01, 0x01, 0x04, 0x21, 0x00, 0x0a,
                0x00, 0x01, 0x00, 0x05, 0x00, 0x01, 0x00, 0x01,
                0x00, 0x01, 0x01, 0x0b, 0x00, 0x8d, 0x00, 0xff,
                0x93, 0x00, 0x01, 0x01, 0x00, 0x9d, 0xe0, 0x2b,
                0x40),
                ButtonConfigBuilder.GOAL_TRACKING_ENTRY.data());
    }

    @Test
    void golden_twentyFourHour_standard_header_and_data() {
        assertArrayEquals(b(0x01, 0x01, 0x1E, 0x00),
                ButtonConfigBuilder.TWENTY_FOUR_HOUR_ENTRY.header());
        assertEquals(47, ButtonConfigBuilder.TWENTY_FOUR_HOUR_ENTRY.data().length);
        assertArrayEquals(b(
                0x01, 0x00, 0x01, 0x01, 0x1E, 0x2F, 0x00, 0x00,
                0x00, 0x01, 0x00, 0x08, 0x00, 0x04, 0x00, 0x00,
                0x07, 0x02, 0x02, 0x00, 0x01, 0x01, 0x1E, 0x00,
                0x89, 0x05, 0x01, 0x07, 0xB0, 0x04, 0x00, 0xB0,
                0x04, 0x00, 0xB0, 0x04, 0x00, 0x08, 0x01, 0x50,
                0x00, 0x01, 0x00, 0x8B, 0x95, 0x15, 0x80),
                ButtonConfigBuilder.TWENTY_FOUR_HOUR_ENTRY.data());
    }

    @Test
    void golden_twentyFourHour_sequenced_header_and_data() {
        assertArrayEquals(b(0x01, 0x02, 0x1E, 0x00),
                ButtonConfigBuilder.TWENTY_FOUR_HOUR_SEQ_ENTRY.header());
        assertEquals(54, ButtonConfigBuilder.TWENTY_FOUR_HOUR_SEQ_ENTRY.data().length);
    }

    // ===================================================== single-entry assignment golden

    @Test
    void singleEntry_topMiddleBottom_fullFileBytes() {
        ButtonEntry[] top = {ButtonConfigBuilder.entryFrom(ConfigPayload.STOPWATCH)};
        ButtonEntry[] mid = {ButtonConfigBuilder.entryFrom(ConfigPayload.DATE)};
        ButtonEntry[] bot = {ButtonConfigBuilder.entryFrom(ConfigPayload.SECOND_TIMEZONE)};

        byte[] file = ButtonCompiler.compileMultiEntry(top, mid, bot);

        byte[] stopwatch = ConfigPayload.STOPWATCH.getData();
        byte[] date = ConfigPayload.DATE.getData();
        byte[] tz = ConfigPayload.SECOND_TIMEZONE.getData();

        // Build the expected file explicitly (independent of the compiler's loops).
        java.io.ByteArrayOutputStream exp = new java.io.ByteArrayOutputStream();
        // version + button count
        exp.writeBytes(b(0x01, 0x00, 0x00, 0x03));
        // TOP: index 0x10, 1 entry, header(4) + null
        exp.writeBytes(b(0x10, 0x01));
        exp.writeBytes(ConfigPayload.STOPWATCH.getHeader());
        exp.write(0x00);
        // MIDDLE
        exp.writeBytes(b(0x20, 0x01));
        exp.writeBytes(ConfigPayload.DATE.getHeader());
        exp.write(0x00);
        // BOTTOM
        exp.writeBytes(b(0x30, 0x01));
        exp.writeBytes(ConfigPayload.SECOND_TIMEZONE.getHeader());
        exp.write(0x00);
        // payload count = 3 (non-dedup) + payloads in order
        exp.write(0x03);
        exp.writeBytes(stopwatch);
        exp.writeBytes(date);
        exp.writeBytes(tz);
        // customization count = 3 + each header + suffix
        exp.write(0x03);
        exp.writeBytes(ConfigPayload.STOPWATCH.getHeader());
        exp.writeBytes(b(0x0a, 0x00, 0x01, 0x02, 0x01, 0x00));
        exp.writeBytes(ConfigPayload.DATE.getHeader());
        exp.writeBytes(b(0x0a, 0x00, 0x01, 0x02, 0x01, 0x00));
        exp.writeBytes(ConfigPayload.SECOND_TIMEZONE.getHeader());
        exp.writeBytes(b(0x0a, 0x00, 0x01, 0x02, 0x01, 0x00));
        byte[] body = exp.toByteArray();
        int crc = crc32(body, 0, body.length);
        for (int x : le32(crc)) exp.write(x);

        assertArrayEquals(exp.toByteArray(), file);
    }

    // ======================================================= multi-entry mode-toggle golden

    @Test
    void modeToggle_threeEntries_structureAndOrder() {
        ButtonEntry[] toggle = {
                ButtonConfigBuilder.entryFrom(ConfigPayload.SECOND_TIMEZONE),
                ButtonConfigBuilder.DATE_TOGGLE_ENTRY,
                ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY
        };
        byte[] file = ButtonCompiler.compileMultiEntry(
                toggle, new ButtonEntry[]{}, new ButtonEntry[]{});

        // version + button count
        assertArrayEquals(b(0x01, 0x00, 0x00, 0x03), Arrays.copyOfRange(file, 0, 4));

        // TOP button: index 0x10, entry count 3, then 3 × (header(4)+null)
        int p = 4;
        assertEquals(0x10, file[p++] & 0xFF);
        assertEquals(3, file[p++] & 0xFF); // entry count
        assertArrayEquals(b(0x01, 0x01, 0x16, 0x00), Arrays.copyOfRange(file, p, p + 4)); // TZ
        assertEquals(0x00, file[p + 4] & 0xFF);
        p += 5;
        assertArrayEquals(b(0x01, 0x02, 0x14, 0x00), Arrays.copyOfRange(file, p, p + 4)); // DATE toggle
        assertEquals(0x00, file[p + 4] & 0xFF);
        p += 5;
        assertArrayEquals(b(0x01, 0x02, 0x1a, 0x00), Arrays.copyOfRange(file, p, p + 4)); // ALARM seq
        assertEquals(0x00, file[p + 4] & 0xFF);
        p += 5;

        // MIDDLE: index 0x20, 0 entries
        assertEquals(0x20, file[p++] & 0xFF);
        assertEquals(0, file[p++] & 0xFF);
        // BOTTOM: index 0x30, 0 entries
        assertEquals(0x30, file[p++] & 0xFF);
        assertEquals(0, file[p++] & 0xFF);

        // payload count = 3 (non-dedup)
        assertEquals(3, file[p++] & 0xFF);
        // payloads in order: TZ, DATE_TOGGLE, ALARM_SEQ
        byte[] tz = ConfigPayload.SECOND_TIMEZONE.getData();
        byte[] dateTog = ButtonConfigBuilder.DATE_TOGGLE_ENTRY.data();
        byte[] alarm = ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY.data();
        assertArrayEquals(tz, Arrays.copyOfRange(file, p, p + tz.length));
        p += tz.length;
        assertArrayEquals(dateTog, Arrays.copyOfRange(file, p, p + dateTog.length));
        p += dateTog.length;
        assertArrayEquals(alarm, Arrays.copyOfRange(file, p, p + alarm.length));
        p += alarm.length;

        // customization count = 3
        assertEquals(3, file[p++] & 0xFF);
    }

    @Test
    void modeToggle_crc32Trailer_isCorrect() {
        ButtonEntry[] toggle = ButtonConfigBuilder.MODE_TOGGLE_ENTRIES;
        byte[] file = ButtonCompiler.compileMultiEntry(
                toggle, new ButtonEntry[]{}, new ButtonEntry[]{});

        // Recompute CRC32 over the body (all but the last 4 bytes); assert LE trailer matches.
        int bodyLen = file.length - 4;
        int expectedCrc = crc32(file, 0, bodyLen);
        byte[] trailer = Arrays.copyOfRange(file, bodyLen, file.length);
        assertArrayEquals(b(le32(expectedCrc)[0], le32(expectedCrc)[1],
                le32(expectedCrc)[2], le32(expectedCrc)[3]), trailer);
    }

    // ============================================================= regression: delegation

    @Test
    void regression_buttonConfigBuilder_delegatesToCompiler() {
        ButtonEntry[] top = ButtonConfigBuilder.MODE_TOGGLE_ENTRIES;
        ButtonEntry[] mid = {ButtonConfigBuilder.entryFrom(ConfigPayload.STOPWATCH)};
        ButtonEntry[] bot = {ButtonConfigBuilder.GOAL_TRACKING_ENTRY};

        assertArrayEquals(
                ButtonCompiler.compileMultiEntry(top, mid, bot),
                ButtonConfigBuilder.build(top, mid, bot));
        // Façade routes through the same compiler.
        assertArrayEquals(
                ButtonConfigBuilder.build(top, mid, bot),
                FossilController.compileButtons(top, mid, bot));
    }

    @Test
    void regression_configFileBuilder_delegatesToCompiler_withAndWithoutChecksum() {
        ConfigPayload[] payloads = {
                ConfigPayload.STOPWATCH, ConfigPayload.DATE, ConfigPayload.SECOND_TIMEZONE
        };

        for (boolean checksum : new boolean[]{true, false}) {
            byte[] viaVendored = new ConfigFileBuilder(payloads).build(checksum);
            byte[] viaCompiler = ButtonCompiler.compileSingleEntryPerButton(payloads, checksum);
            assertArrayEquals(viaCompiler, viaVendored,
                    "ConfigFileBuilder must equal ButtonCompiler (checksum=" + checksum + ")");
        }

        // Façade routes through the same compiler (with checksum).
        assertArrayEquals(
                new ConfigFileBuilder(payloads).build(true),
                FossilController.compileButtonsSingleEntry(payloads, true));
    }

    @Test
    void singleEntryPerButton_dedups_duplicatePayloads() {
        // Two buttons with the SAME function: vendored format dedups payloads but keeps both headers.
        ConfigPayload[] payloads = {ConfigPayload.SECOND_TIMEZONE, ConfigPayload.SECOND_TIMEZONE};
        byte[] file = ButtonCompiler.compileSingleEntryPerButton(payloads, false);

        // version(3) + count(1) + 2×(idx+ver+header(4)+null) = 4 + 2*7 = 18
        // header section starts at offset 4
        assertEquals(0x02, file[3] & 0xFF); // button count
        assertEquals(0x10, file[4] & 0xFF); // button 1 index
        assertEquals(0x20, file[11] & 0xFF); // button 2 index (4 + 7)
        // payload count byte right after header section = 1 (deduplicated)
        int payloadCountOffset = 4 + 2 * 7;
        assertEquals(0x01, file[payloadCountOffset] & 0xFF);
    }

    // ====================================================== dial-mode availability hook

    @Test
    void dialModes_doNotIncludeMusic() {
        // music is a phone-side action, NOT a dial mode: it must not be a DialMode value.
        for (DialMode m : DialMode.values()) {
            assertFalse(m.name().toUpperCase().contains("MUSIC"),
                    "music must not be a DialMode");
        }
        assertEquals(5, DialMode.values().length);
    }

    @Test
    void dialModes_threePosition_supportsTzDateAlarmOnly() {
        var modes = ButtonCompiler.availableModes(DialModel.THREE_POSITION);
        assertEquals(3, modes.size());
        assertTrue(modes.contains(DialMode.TIMEZONE_2));
        assertTrue(modes.contains(DialMode.DATE));
        assertTrue(modes.contains(DialMode.ALARM));
        assertFalse(modes.contains(DialMode.ALERT));
        assertFalse(modes.contains(DialMode.TWENTY_FOUR_HOUR));

        assertTrue(ButtonCompiler.isModeAvailable(DialModel.THREE_POSITION, DialMode.ALARM));
        assertFalse(ButtonCompiler.isModeAvailable(DialModel.THREE_POSITION, DialMode.ALERT));
    }

    @Test
    void dialModes_fivePosition_supportsAll() {
        var modes = ButtonCompiler.availableModes(DialModel.FIVE_POSITION);
        assertEquals(5, modes.size());
        for (DialMode m : DialMode.values()) {
            assertTrue(modes.contains(m));
            assertTrue(ButtonCompiler.isModeAvailable(DialModel.FIVE_POSITION, m));
        }
    }

    @Test
    void availableModes_returnsDefensiveCopy() {
        var modes = ButtonCompiler.availableModes(DialModel.THREE_POSITION);
        modes.clear(); // must not affect subsequent calls
        assertEquals(3, ButtonCompiler.availableModes(DialModel.THREE_POSITION).size());
    }
}
