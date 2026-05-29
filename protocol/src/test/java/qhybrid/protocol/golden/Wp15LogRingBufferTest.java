// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import qhybrid.protocol.log.LogRecord;
import qhybrid.protocol.log.LogRingBuffer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP15 — JVM unit tests for the pure log core: the {@link LogRingBuffer} ring/eviction,
 * the level {@link LogRingBuffer#filter filter}, and the stable {@link LogRingBuffer#export}
 * blob. Zero Android, zero hardware (mirrors the WP5–WP9 pure-logic test pattern).
 */
public class Wp15LogRingBufferTest {

    /** Fixed timestamps so the export blob is byte-stable across runs. */
    private static final long T0 = 1_700_000_000_000L; // 2023-11-14 22:13:20.000 UTC

    private static LogRecord rec(long ts, LogRecord.Level level, String tag, String msg) {
        return new LogRecord(ts, level, tag, msg);
    }

    // ---------------------------------------------------------------- filtering

    @Test
    void filter_mixedLevels_returnsExpectedSubset() {
        LogRingBuffer buf = new LogRingBuffer(100);
        buf.add(rec(T0 + 0, LogRecord.Level.TRACE, "ble", "raw 0x03 0x07 0x00"));
        buf.add(rec(T0 + 1, LogRecord.Level.DEBUG, "gatt", "write 3dda0002"));
        buf.add(rec(T0 + 2, LogRecord.Level.INFO, "ctl", "Connected! Battery 88%"));
        buf.add(rec(T0 + 3, LogRecord.Level.WARN, "ctl", "init may not have completed"));
        buf.add(rec(T0 + 4, LogRecord.Level.ERROR, "ctl", "connect/init failed"));

        // null threshold -> everything, oldest->newest
        assertEquals(5, buf.filter(null).size());

        // INFO threshold drops TRACE + DEBUG, keeps INFO/WARN/ERROR
        List<LogRecord> info = buf.filter(LogRecord.Level.INFO);
        assertEquals(3, info.size());
        assertEquals("Connected! Battery 88%", info.get(0).message());
        assertEquals(LogRecord.Level.WARN, info.get(1).level());
        assertEquals(LogRecord.Level.ERROR, info.get(2).level());

        // DEBUG threshold keeps DEBUG and above (everything except TRACE)
        assertEquals(4, buf.filter(LogRecord.Level.DEBUG).size());

        // ERROR threshold keeps only the single error
        List<LogRecord> err = buf.filter(LogRecord.Level.ERROR);
        assertEquals(1, err.size());
        assertEquals(LogRecord.Level.ERROR, err.get(0).level());

        // TRACE threshold keeps everything
        assertEquals(5, buf.filter(LogRecord.Level.TRACE).size());
    }

    @Test
    void level_severityOrdering_isStable() {
        assertTrue(LogRecord.Level.INFO.isAtLeast(LogRecord.Level.DEBUG));
        assertTrue(LogRecord.Level.ERROR.isAtLeast(LogRecord.Level.ERROR));
        assertFalse(LogRecord.Level.DEBUG.isAtLeast(LogRecord.Level.INFO));
        assertFalse(LogRecord.Level.TRACE.isAtLeast(LogRecord.Level.DEBUG));
    }

    // ---------------------------------------------------------------- eviction

    @Test
    void ring_evictsOldestAtCapacity() {
        LogRingBuffer buf = new LogRingBuffer(3);
        for (int i = 0; i < 5; i++) {
            buf.add(rec(T0 + i, LogRecord.Level.INFO, "n", "msg" + i));
        }
        // capacity 3 -> only the last 3 survive, oldest evicted
        assertEquals(3, buf.size());
        List<LogRecord> snap = buf.snapshot();
        assertEquals("msg2", snap.get(0).message());
        assertEquals("msg3", snap.get(1).message());
        assertEquals("msg4", snap.get(2).message());
    }

    @Test
    void ring_exactlyAtCapacity_keepsAll() {
        LogRingBuffer buf = new LogRingBuffer(3);
        buf.add(rec(T0, LogRecord.Level.INFO, "n", "a"));
        buf.add(rec(T0 + 1, LogRecord.Level.INFO, "n", "b"));
        buf.add(rec(T0 + 2, LogRecord.Level.INFO, "n", "c"));
        assertEquals(3, buf.size());
        assertEquals("a", buf.snapshot().get(0).message());
    }

    @Test
    void capacity_mustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new LogRingBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new LogRingBuffer(-1));
    }

    @Test
    void clear_emptiesBuffer() {
        LogRingBuffer buf = new LogRingBuffer(10);
        buf.add(rec(T0, LogRecord.Level.INFO, "n", "x"));
        assertEquals(1, buf.size());
        buf.clear();
        assertEquals(0, buf.size());
        assertEquals("", buf.export());
    }

    // ---------------------------------------------------------------- export

    @Test
    void export_isStableBlob() {
        LogRingBuffer buf = new LogRingBuffer(10);
        buf.add(rec(T0, LogRecord.Level.INFO, "ctl", "Connecting to Fossil Watch (D9:20:71:11:74:2A)..."));
        buf.add(rec(T0 + 12, LogRecord.Level.DEBUG, "gatt", "write 3dda0002 NO_RESPONSE"));
        buf.add(rec(T0 + 250, LogRecord.Level.WARN, "ctl", "Battery low"));

        String expected =
                "2023-11-14 22:13:20.000 INFO  ctl: Connecting to Fossil Watch (D9:20:71:11:74:2A)...\n" +
                "2023-11-14 22:13:20.012 DEBUG gatt: write 3dda0002 NO_RESPONSE\n" +
                "2023-11-14 22:13:20.250 WARN  ctl: Battery low\n";

        assertEquals(expected, buf.export());
    }

    @Test
    void export_withMinLevel_filtersBlob() {
        LogRingBuffer buf = new LogRingBuffer(10);
        buf.add(rec(T0, LogRecord.Level.DEBUG, "gatt", "raw"));
        buf.add(rec(T0 + 1, LogRecord.Level.INFO, "ctl", "Connected"));

        String infoOnly = buf.export(LogRecord.Level.INFO);
        assertEquals("2023-11-14 22:13:20.001 INFO  ctl: Connected\n", infoOnly);
    }

    @Test
    void formatLine_matchesExportLine() {
        LogRecord r = rec(T0, LogRecord.Level.ERROR, "svc", "boom");
        assertEquals("2023-11-14 22:13:20.000 ERROR svc: boom", LogRingBuffer.formatLine(r));
    }

    @Test
    void export_emptyBuffer_isEmptyString() {
        assertEquals("", new LogRingBuffer(5).export());
    }

    // ---------------------------------------------------------------- listeners

    @Test
    void changeListener_firesOnAdd() {
        LogRingBuffer buf = new LogRingBuffer(10);
        AtomicInteger count = new AtomicInteger();
        Runnable l = count::incrementAndGet;
        buf.addChangeListener(l);
        buf.add(rec(T0, LogRecord.Level.INFO, "n", "1"));
        buf.add(rec(T0 + 1, LogRecord.Level.INFO, "n", "2"));
        assertEquals(2, count.get());
        buf.removeChangeListener(l);
        buf.add(rec(T0 + 2, LogRecord.Level.INFO, "n", "3"));
        assertEquals(2, count.get()); // not incremented after removal
    }

    @Test
    void changeListener_throwing_doesNotBreakLogging() {
        LogRingBuffer buf = new LogRingBuffer(10);
        buf.addChangeListener(() -> { throw new RuntimeException("bad listener"); });
        buf.add(rec(T0, LogRecord.Level.INFO, "n", "still added"));
        assertEquals(1, buf.size());
    }

    // ---------------------------------------------------------------- record

    @Test
    void record_nullFieldsNormalized() {
        LogRecord r = new LogRecord(T0, LogRecord.Level.INFO, null, null);
        assertEquals("", r.tag());
        assertEquals("", r.message());
        assertThrows(NullPointerException.class,
                () -> new LogRecord(T0, null, "tag", "msg"));
    }

    @Test
    void shared_singletonIsStable() {
        assertSame(LogRingBuffer.shared(), LogRingBuffer.shared());
    }
}
