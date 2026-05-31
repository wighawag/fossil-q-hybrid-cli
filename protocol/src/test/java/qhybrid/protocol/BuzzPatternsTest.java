// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import qhybrid.protocol.model.NotificationFilterEntry;
import qhybrid.protocol.requests.fossil.notification.BuzzPatterns;
import qhybrid.protocol.requests.fossil.notification.NotificationCompiler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP-BUZZ-PLAYONLY (sub-part 1) — golden lock for the reserved buzz pattern mapping and the
 * reserved-filter bytes. The pattern↔package↔CRC mapping must be STABLE: it is what links a buzz's
 * play file (matched by CRC) to the reserved filter entry uploaded at connect.
 */
public class BuzzPatternsTest {

    @Test
    void reservedPackageNames_areStable() {
        assertEquals("qhybrid.linux.buzz1", BuzzPatterns.packageNameForPattern(1));
        assertEquals("qhybrid.linux.buzz5", BuzzPatterns.packageNameForPattern(5));
        assertEquals("qhybrid.linux.buzz8", BuzzPatterns.packageNameForPattern(8));
    }

    @Test
    void reservedPatternSet_skipsSilentAndDuplicate() {
        // Useful patterns only: 1,2,3,5,6,7,8 (no silent 0/9, no 4≡3 duplicate).
        assertArrayEquals(new int[]{1, 2, 3, 5, 6, 7, 8}, BuzzPatterns.RESERVED_PATTERNS);
        assertTrue(BuzzPatterns.isReservedPattern(1));
        assertTrue(BuzzPatterns.isReservedPattern(5));
        assertFalse(BuzzPatterns.isReservedPattern(0));
        assertFalse(BuzzPatterns.isReservedPattern(4));
        assertFalse(BuzzPatterns.isReservedPattern(9));
    }

    @Test
    void reservedCrc_matchesNullTerminatedCrc_andIsGolden() {
        // CRC the watch uses to match a buzz play file to its reserved filter entry.
        assertEquals(NotificationCompiler.computeNullTerminatedCrc("qhybrid.linux.buzz5"),
                BuzzPatterns.crcForPattern(5));
        // Golden values (regression lock — these must never silently change).
        assertEquals(0x9DD72C0C, BuzzPatterns.crcForPattern(1));
        assertEquals(0xF9BBE908, BuzzPatterns.crcForPattern(5));
    }

    @Test
    void reservedEntries_oneFixed32ByteEntryPerPattern_withCorrectVibeByte() {
        List<NotificationFilterEntry> entries = BuzzPatterns.reservedEntries();
        assertEquals(BuzzPatterns.RESERVED_PATTERNS.length, entries.size());

        byte[] file = BuzzPatterns.reservedFilterFile();
        assertEquals(BuzzPatterns.RESERVED_PATTERNS.length * NotificationCompiler.ENTRY_SIZE, file.length,
                "one fixed 32-byte entry per reserved pattern");

        for (int i = 0; i < BuzzPatterns.RESERVED_PATTERNS.length; i++) {
            int pattern = BuzzPatterns.RESERVED_PATTERNS[i];
            int base = i * NotificationCompiler.ENTRY_SIZE;
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(file, base, NotificationCompiler.ENTRY_SIZE)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            // CRC at entry offset 4 == the reserved package CRC for this pattern.
            assertEquals(BuzzPatterns.crcForPattern(pattern), b.getInt(base + 4),
                    "entry CRC matches reserved package CRC for pattern " + pattern);
            // VIBRATION byte (tag 0xC3) is the last byte of the 32-byte entry.
            assertEquals((byte) pattern, file[base + 31], "entry vibe byte == pattern " + pattern);
            // HAND_MOVEMENT (tag 0xC2): hour degrees at entry offset 16, minute at 18.
            // Each reserved pattern points to its own N-o'clock mark (N*30 degrees), so a buzz is
            // visually identifiable, not just felt.
            short expectedDeg = (short) (pattern * 30); // patterns 1..8 -> 30..240, all < 360
            assertEquals(expectedDeg, b.getShort(base + 16),
                    "hour-hand degrees == N*30 for pattern " + pattern);
            assertEquals(expectedDeg, b.getShort(base + 18),
                    "minute-hand degrees == N*30 for pattern " + pattern);
            assertEquals(expectedDeg, BuzzPatterns.hourDegForPattern(pattern));
            assertEquals(expectedDeg, BuzzPatterns.minDegForPattern(pattern));
        }
    }

    @Test
    void hourDegForPattern_pointsToTheNOClockMark() {
        // buzz1 -> 1 o'clock (30), buzz2 -> 2 o'clock (60), ... buzz8 -> 8 o'clock (240).
        assertEquals((short) 30, BuzzPatterns.hourDegForPattern(1));
        assertEquals((short) 60, BuzzPatterns.hourDegForPattern(2));
        assertEquals((short) 90, BuzzPatterns.hourDegForPattern(3));
        assertEquals((short) 150, BuzzPatterns.hourDegForPattern(5));
        assertEquals((short) 240, BuzzPatterns.hourDegForPattern(8));
        // Each reserved pattern has a DISTINCT position (no two share a hand pose).
        java.util.Set<Short> seen = new java.util.HashSet<>();
        for (int p : BuzzPatterns.RESERVED_PATTERNS) {
            assertTrue(seen.add(BuzzPatterns.hourDegForPattern(p)),
                    "distinct hand position per reserved pattern (" + p + ")");
        }
    }
}
