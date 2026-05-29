// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import qhybrid.protocol.FossilController;
import qhybrid.protocol.model.NotificationFilterEntry;
import qhybrid.protocol.requests.fossil.notification.NotificationCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * WP6 golden-byte tests for {@link NotificationCompiler}.
 *
 * <p>Locks the fixed-32-byte filter entry layout (FINDINGS #17 capture: per-app
 * vibe + hand position + null-terminated package CRC), the multi-entry length
 * invariant, the deterministic input ordering, and the pure (time/messageId-injected)
 * NOTIFICATION_PLAY file (FINDINGS #21e: type=3). The known package CRCs
 * (com.whatsapp = 0x40C7ED7C, com.google.android.calendar = 0xBA3DC156) validate
 * the null-terminated CRC against FINDINGS #21d.
 */
public class Wp6NotificationCompilerTest {

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    /** Little-endian int as 4 bytes (for asserting CRC/timestamp fields). */
    private static int[] le32(int v) {
        return new int[]{v & 0xFF, (v >>> 8) & 0xFF, (v >>> 16) & 0xFF, (v >>> 24) & 0xFF};
    }

    /** Little-endian short as 2 bytes. */
    private static int[] le16(int v) {
        return new int[]{v & 0xFF, (v >>> 8) & 0xFF};
    }

    // Known CRCs from FINDINGS #21d (null-terminated CRC32).
    private static final int CRC_WHATSAPP = 0x40C7ED7C;
    private static final int CRC_CALENDAR = 0xBA3DC156;

    // ============================================================ CRC

    @Test
    void crc_knownPackages_matchFindings21d() {
        assertEquals(CRC_WHATSAPP, NotificationCompiler.computeNullTerminatedCrc("com.whatsapp"));
        assertEquals(CRC_CALENDAR,
                NotificationCompiler.computeNullTerminatedCrc("com.google.android.calendar"));
    }

    @Test
    void crc_isDeterministic_sameNameSameCrc() {
        assertEquals(NotificationCompiler.computeNullTerminatedCrc("com.whatsapp"),
                NotificationCompiler.computeNullTerminatedCrc("com.whatsapp"));
    }

    @Test
    void crc_differentNamesDifferentCrcs() {
        assertNotEquals(NotificationCompiler.computeNullTerminatedCrc("com.whatsapp"),
                NotificationCompiler.computeNullTerminatedCrc("com.google.android.calendar"));
    }

    @Test
    void crc_nullTerminated_differsFromUnterminated() {
        // Sanity: the trailing '\0' actually changes the CRC (regression guard
        // against accidentally dropping the null terminator).
        int withNull = NotificationCompiler.computeNullTerminatedCrc("com.whatsapp");
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update("com.whatsapp".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNotEquals((int) c.getValue(), withNull);
    }

    // ============================================================ FILTER ENTRY

    /** Build the expected 32-byte entry bytes for a given CRC/vibe/hands. */
    private static byte[] expectedEntry(int crc, int vibe, int hourDeg, int minDeg) {
        int[] c = le32(crc);
        int[] h = le16(hourDeg);
        int[] m = le16(minDeg);
        int[] subeye = le16(0xFFFF);   // -1
        int[] dur = le16(10000);
        int[] subeye2 = le16(0xFFFE);  // -2
        return b(
                30, 0x00,                                  // packetLength = 30
                0x04, 4, c[0], c[1], c[2], c[3],           // PACKAGE_NAME_CRC
                0x80, 1, 0,                                 // GROUP_ID = 0
                0xC1, 1, 0,                                 // PRIORITY = 0
                0xC2, 10, h[0], h[1], m[0], m[1],          // HAND_MOVEMENT
                subeye[0], subeye[1], dur[0], dur[1], subeye2[0], subeye2[1],
                0xC4, 1, 0,                                 // DISPLAY_CONFIG = 0
                0xC3, 1, vibe                               // VIBRATION
        );
    }

    @Test
    void filterEntry_whatsapp_goldenLayout_90deg_default() {
        // FINDINGS #17 entry 5 / #21d: WhatsApp 0x40C7ED7C, 90°/90°, DEFAULT(4).
        byte[] entry = NotificationCompiler.compileEntry("com.whatsapp", (byte) 4, (short) 90, (short) 90);
        assertEquals(32, entry.length);
        assertArrayEquals(expectedEntry(CRC_WHATSAPP, 4, 90, 90), entry);
    }

    @Test
    void filterEntry_calendar_goldenLayout_300deg_default() {
        // FINDINGS #21d: Google Calendar 0xBA3DC156, 300°/300°, DEFAULT(4).
        byte[] entry = NotificationCompiler.compileEntry(
                "com.google.android.calendar", (byte) 4, (short) 300, (short) 300);
        assertEquals(32, entry.length);
        assertArrayEquals(expectedEntry(CRC_CALENDAR, 4, 300, 300), entry);
    }

    @Test
    void filterEntry_crcAtCorrectOffset() {
        byte[] entry = NotificationCompiler.compileEntry("com.whatsapp", (byte) 4, (short) 90, (short) 90);
        // CRC field: bytes 4..7 (after packetLength(2) + 0x04 + len(1)).
        assertArrayEquals(b(0x7C, 0xED, 0xC7, 0x40),
                new byte[]{entry[4], entry[5], entry[6], entry[7]});
        // HAND_MOVEMENT: tag/len at 14/15 (packetLength(2)+CRC(6)+GROUP(3)+PRIORITY(3)=14).
        assertEquals((byte) 0xC2, entry[14]);
        assertEquals((byte) 10, entry[15]);
        assertArrayEquals(b(le16(90)[0], le16(90)[1]), new byte[]{entry[16], entry[17]});
        assertArrayEquals(b(le16(90)[0], le16(90)[1]), new byte[]{entry[18], entry[19]});
        // vibe: last byte after 0xC3 + len.
        assertEquals((byte) 0xC3, entry[29]);
        assertEquals((byte) 1, entry[30]);
        assertEquals((byte) 4, entry[31]);
    }

    // -------------------------------------------- CRC-injected raw reproduction

    @Test
    void compileEntryWithCrc_matchesNameBasedEntry() {
        byte[] viaName = NotificationCompiler.compileEntry("com.whatsapp", (byte) 4, (short) 90, (short) 90);
        byte[] viaCrc = NotificationCompiler.compileEntryWithCrc(CRC_WHATSAPP, (byte) 4, (short) 90, (short) 90);
        assertArrayEquals(viaName, viaCrc);
    }

    @Test
    void officialApp7EntryFilter_reproduces224Bytes() {
        // FINDINGS #17: the official app's 7-entry initial-setup filter (224 bytes).
        // Only the on-wire CRCs are known (not the package strings), so reproduce the
        // raw capture via the CRC-injected builder. Columns: CRC, vibe, hand°.
        int[][] cap = {
                {0xBA3DC156, 4, 359}, // 1 catch-all
                {0xD1BE8F35, 4, 30},  // 2
                {0xB7590080, 1, 60},  // 3 CALL
                {0x8B56BE06, 2, 60},  // 4 TEXT
                {0x40C7ED7C, 4, 90},  // 5
                {0xA515ECD5, 4, 120}, // 6
                {0x5B2EB595, 4, 330}, // 7
        };
        byte[] file = new byte[cap.length * 32];
        int off = 0;
        for (int[] row : cap) {
            byte[] e = NotificationCompiler.compileEntryWithCrc(
                    row[0], (byte) row[1], (short) row[2], (short) row[2]);
            System.arraycopy(e, 0, file, off, 32);
            off += 32;
            // each entry's CRC + hand positions + vibe asserted via expectedEntry
            assertArrayEquals(expectedEntry(row[0], row[1], row[2], row[2]), e);
        }
        assertEquals(224, file.length);
    }

    // ============================================================ FILTER FILE

    @Test
    void compileFilter_lengthIsNtimes32() {
        var rules = List.of(
                new NotificationFilterEntry("com.whatsapp", (byte) 4, (short) 90, (short) 90),
                new NotificationFilterEntry("com.google.android.calendar", (byte) 4, (short) 300, (short) 300),
                new NotificationFilterEntry("com.example.three", (byte) 2, (short) 120, (short) 120));
        assertEquals(3 * 32, NotificationCompiler.compileFilter(rules).length);
    }

    @Test
    void compileFilter_empty_isZeroBytes() {
        assertEquals(0, NotificationCompiler.compileFilter(List.of()).length);
    }

    @Test
    void compileFilter_preservesInputOrder() {
        var rules = List.of(
                new NotificationFilterEntry("com.whatsapp", (byte) 4, (short) 90, (short) 90),
                new NotificationFilterEntry("com.google.android.calendar", (byte) 4, (short) 300, (short) 300));
        byte[] file = NotificationCompiler.compileFilter(rules);
        // entry 0 CRC == whatsapp; entry 1 CRC == calendar
        assertArrayEquals(b(le32(CRC_WHATSAPP)[0], le32(CRC_WHATSAPP)[1], le32(CRC_WHATSAPP)[2], le32(CRC_WHATSAPP)[3]),
                new byte[]{file[4], file[5], file[6], file[7]});
        int o = 32;
        assertArrayEquals(b(le32(CRC_CALENDAR)[0], le32(CRC_CALENDAR)[1], le32(CRC_CALENDAR)[2], le32(CRC_CALENDAR)[3]),
                new byte[]{file[o + 4], file[o + 5], file[o + 6], file[o + 7]});
    }

    @Test
    void compileFilter_concatenationEqualsPerEntry() {
        var rules = List.of(
                new NotificationFilterEntry("com.whatsapp", (byte) 4, (short) 90, (short) 90),
                new NotificationFilterEntry("com.google.android.calendar", (byte) 4, (short) 300, (short) 300));
        byte[] file = NotificationCompiler.compileFilter(rules);
        byte[] e0 = NotificationCompiler.compileEntry("com.whatsapp", (byte) 4, (short) 90, (short) 90);
        byte[] e1 = NotificationCompiler.compileEntry("com.google.android.calendar", (byte) 4, (short) 300, (short) 300);
        byte[] expected = new byte[64];
        System.arraycopy(e0, 0, expected, 0, 32);
        System.arraycopy(e1, 0, expected, 32, 32);
        assertArrayEquals(expected, file);
    }

    // ============================================================ PLAY FILE

    // Golden play file for ("com.whatsapp","Notification","fossil-q","Notification",
    //   now=0x11223344, messageId=0x55667788). Deterministic (time/id injected).
    private static final byte[] PLAY_GOLDEN = b(
            0x3F, 0x00,             // mainBufferLength = 63
            0x0C,                   // lengthBufferLength = 12
            0x03,                   // type = 3 NOTIFICATION
            0x02,                   // flags
            0x04,                   // uidLength
            0x04,                   // appBundleCRCLength
            0x0D, 0x09, 0x0D,       // field lengths: title(13), sender(9), message(13)
            0x04, 0x04,             // sentinel len(4), timestamp len(4)
            0x88, 0x77, 0x66, 0x55, // messageId = 0x55667788 LE
            0x7C, 0xED, 0xC7, 0x40, // packageCrc = 0x40C7ED7C LE (com.whatsapp)
            // "Notification\0"
            0x4E, 0x6F, 0x74, 0x69, 0x66, 0x69, 0x63, 0x61, 0x74, 0x69, 0x6F, 0x6E, 0x00,
            // "fossil-q\0"
            0x66, 0x6F, 0x73, 0x73, 0x69, 0x6C, 0x2D, 0x71, 0x00,
            // "Notification\0"
            0x4E, 0x6F, 0x74, 0x69, 0x66, 0x69, 0x63, 0x61, 0x74, 0x69, 0x6F, 0x6E, 0x00,
            0xFF, 0xFF, 0xFF, 0xFF, // sentinel
            0x44, 0x33, 0x22, 0x11  // timestamp = 0x11223344 LE
    );

    @Test
    void buildPlayFile_golden_injectedTimeAndId() {
        byte[] play = NotificationCompiler.buildPlayFile(
                "com.whatsapp", "Notification", "fossil-q", "Notification",
                0x11223344, 0x55667788);
        assertArrayEquals(PLAY_GOLDEN, play);
    }

    @Test
    void buildPlayFile_headerFields() {
        byte[] play = NotificationCompiler.buildPlayFile(
                "com.whatsapp", "Notification", "fossil-q", "Notification",
                0x11223344, 0x55667788);
        assertEquals((byte) 0x0C, play[2]); // lbl = 12
        assertEquals(NotificationCompiler.PLAY_TYPE_NOTIFICATION, play[3]); // type = 3
        assertEquals((byte) 0x02, play[4]); // flags
    }

    @Test
    void buildPlayFile_timeDependentFieldsAtCorrectOffsets() {
        int now = 0x0A0B0C0D;
        int mid = 0x01020304;
        byte[] play = NotificationCompiler.buildPlayFile(
                "com.whatsapp", "Notification", "fossil-q", "Notification", now, mid);
        // messageId: 4 bytes at offset 12 (right after the 12-byte length header).
        int[] midLe = le32(mid);
        assertArrayEquals(b(midLe[0], midLe[1], midLe[2], midLe[3]),
                new byte[]{play[12], play[13], play[14], play[15]});
        // packageCrc: 4 bytes at offset 16.
        int[] crcLe = le32(CRC_WHATSAPP);
        assertArrayEquals(b(crcLe[0], crcLe[1], crcLe[2], crcLe[3]),
                new byte[]{play[16], play[17], play[18], play[19]});
        // timestamp: trailing 4 bytes.
        int n = play.length;
        int[] nowLe = le32(now);
        assertArrayEquals(b(nowLe[0], nowLe[1], nowLe[2], nowLe[3]),
                new byte[]{play[n - 4], play[n - 3], play[n - 2], play[n - 1]});
    }

    @Test
    void buildPlayFile_timeIndependentRemainder_stableAcrossTimes() {
        // Everything except messageId (offset 12..15) and timestamp (last 4 bytes)
        // must be identical regardless of injected time/id.
        byte[] a = NotificationCompiler.buildPlayFile(
                "com.whatsapp", "Notification", "fossil-q", "Notification", 1, 2);
        byte[] c = NotificationCompiler.buildPlayFile(
                "com.whatsapp", "Notification", "fossil-q", "Notification", 999, 888);
        assertEquals(a.length, c.length);
        for (int i = 0; i < a.length; i++) {
            boolean isMessageId = (i >= 12 && i <= 15);
            boolean isTimestamp = (i >= a.length - 4);
            if (isMessageId || isTimestamp) continue;
            assertEquals(a[i], c[i], "byte " + i + " should be time-independent");
        }
    }

    // ============================================================ DELEGATION / REGRESSION

    @Test
    void controllerFacade_filter_delegatesToHelper() {
        var rules = List.of(
                new NotificationFilterEntry("com.whatsapp", (byte) 4, (short) 90, (short) 90),
                new NotificationFilterEntry("com.google.android.calendar", (byte) 4, (short) 300, (short) 300));
        assertArrayEquals(NotificationCompiler.compileFilter(rules),
                FossilController.buildNotificationFilterFile(rules));
    }

    @Test
    void controllerFacade_play_delegatesToHelper() {
        assertArrayEquals(
                NotificationCompiler.buildPlayFile(
                        "com.whatsapp", "Notification", "fossil-q", "Notification", 0x11223344, 0x55667788),
                FossilController.buildPlayFile(
                        "com.whatsapp", "Notification", "fossil-q", "Notification", 0x11223344, 0x55667788));
    }
}
