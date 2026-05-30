// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import qhybrid.protocol.ActivityParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden parsing assertions against the repo's activity fixtures
 * (activity.bin / activity-test.bin). Locks ActivityParser output so the
 * re-own cannot silently change step/segment/version decoding.
 */
public class ActivityParseTest {

    private static Path fixture(String name) {
        String root = System.getProperty("fossilq.repoRoot", ".");
        return Path.of(root, "test-fixtures", "activity", name);
    }

    private static byte[] read(String name) throws Exception {
        return Files.readAllBytes(fixture(name));
    }

    @Test
    void parseActivityBin() throws Exception {
        ActivityParser.ActivityData d = ActivityParser.parse(read("activity.bin"));
        assertEquals(22, d.fileVersion);
        assertEquals(0x0060, d.fileId);
        assertEquals(39, d.timezoneOffsetMinutes);
        assertEquals(60, d.intervalSeconds);
        assertEquals(1, d.segmentCount);
        assertEquals(55, d.records.size());
        assertEquals(2, d.totalSteps());
    }

    @Test
    void parseActivityTestBin() throws Exception {
        ActivityParser.ActivityData d = ActivityParser.parse(read("activity-test.bin"));
        assertEquals(22, d.fileVersion);
        assertEquals(0x0018, d.fileId);
        assertEquals(76, d.timezoneOffsetMinutes);
        assertEquals(60, d.intervalSeconds);
        assertEquals(1, d.segmentCount);
        assertEquals(18, d.records.size());
        assertEquals(6, d.totalSteps());
    }

    @Test
    void recordsAreChronological() throws Exception {
        ActivityParser.ActivityData d = ActivityParser.parse(read("activity.bin"));
        long prev = Long.MIN_VALUE;
        for (ActivityParser.ActivityRecord r : d.records) {
            assertTrue(r.timestamp >= prev, "records must be sorted by timestamp");
            prev = r.timestamp;
        }
    }

    @Test
    void tooShortFileRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ActivityParser.parse(new byte[10]));
    }
}
