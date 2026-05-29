// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.log;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WP15 — bounded, thread-safe in-memory ring buffer of {@link LogRecord}s for the
 * in-app log viewer.
 *
 * <p><b>Pure core (provable layer).</b> No Android, no SLF4J. The SLF4J bridge in
 * {@code :android} {@link #add(LogRecord) adds} records here as the app + {@code :protocol}
 * emit them; logcat routing is unaffected (the bridge tees — it does not replace the
 * logcat appender). The Compose console reads {@link #snapshot()} / {@link #filter} and
 * shares logs via {@link #export}.
 *
 * <p>When {@link #capacity()} is exceeded the oldest record is evicted (FIFO).
 *
 * <p>A process-wide {@link #shared()} singleton is provided so the SLF4J bridge and the UI
 * observe the same buffer without threading a reference through every layer (mirrors the
 * {@code WatchState} singleton pattern WP3 uses for connection state).
 */
public final class LogRingBuffer {

    /** Default ring size: enough to cover a full connect→auth→sync session at DEBUG. */
    public static final int DEFAULT_CAPACITY = 2000;

    /** Stable export timestamp format: UTC ISO-ish, millisecond precision, no zone suffix. */
    private static final DateTimeFormatter EXPORT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final int capacity;
    private final ArrayDeque<LogRecord> records;

    /** Listeners notified after every successful {@link #add}. Copy-on-iterate for safety. */
    private final List<Runnable> changeListeners = new ArrayList<>();

    public LogRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public LogRingBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
        this.records = new ArrayDeque<>(capacity);
    }

    // ---- process-wide singleton ---------------------------------------------

    private static final LogRingBuffer SHARED = new LogRingBuffer();

    /** The process-wide buffer the SLF4J bridge feeds and the UI reads. */
    public static LogRingBuffer shared() {
        return SHARED;
    }

    // ---- mutation ------------------------------------------------------------

    public int capacity() {
        return capacity;
    }

    /** Current number of buffered records (&le; {@link #capacity()}). */
    public synchronized int size() {
        return records.size();
    }

    /**
     * Append a record, evicting the oldest if at capacity. Notifies change listeners
     * (outside the lock) so a UI can refresh.
     */
    public void add(LogRecord record) {
        if (record == null) return;
        synchronized (this) {
            if (records.size() >= capacity) {
                records.pollFirst(); // evict oldest
            }
            records.addLast(record);
        }
        fireChanged();
    }

    /** Convenience: build + add a record. */
    public void add(long timestampMillis, LogRecord.Level level, String tag, String message) {
        add(new LogRecord(timestampMillis, level, tag, message));
    }

    public synchronized void clear() {
        records.clear();
    }

    // ---- reads ---------------------------------------------------------------

    /** Immutable oldest→newest copy of the current contents. */
    public synchronized List<LogRecord> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    /**
     * All records at or above {@code minLevel}, oldest→newest. {@code null} means no
     * filtering (returns everything). This is the level-filter the console toggle drives.
     */
    public synchronized List<LogRecord> filter(LogRecord.Level minLevel) {
        if (minLevel == null) {
            return snapshot();
        }
        List<LogRecord> out = new ArrayList<>();
        for (LogRecord r : records) {
            if (r.level().isAtLeast(minLevel)) {
                out.add(r);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ---- export --------------------------------------------------------------

    /**
     * Stable, copy/share-friendly text blob of every record (oldest→newest).
     * One record per line: {@code "<UTC time> <LEVEL> <tag>: <message>"}.
     */
    public String export() {
        return export(null);
    }

    /** Like {@link #export()} but only records at or above {@code minLevel}. */
    public String export(LogRecord.Level minLevel) {
        List<LogRecord> recs = filter(minLevel);
        StringBuilder sb = new StringBuilder(recs.size() * 64);
        for (LogRecord r : recs) {
            appendLine(sb, r);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Render a single record exactly as {@link #export} does for one line (no trailing newline). */
    public static String formatLine(LogRecord r) {
        StringBuilder sb = new StringBuilder(64);
        appendLine(sb, r);
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, LogRecord r) {
        sb.append(EXPORT_TIME.format(Instant.ofEpochMilli(r.timestampMillis())));
        sb.append(' ');
        // Fixed 5-char level column keeps the blob aligned/diff-stable.
        sb.append(padLevel(r.level()));
        sb.append(' ');
        sb.append(r.tag());
        sb.append(": ");
        sb.append(r.message());
    }

    private static String padLevel(LogRecord.Level level) {
        String name = level.name();
        // longest is TRACE/DEBUG/ERROR = 5; INFO/WARN = 4 -> pad to 5.
        if (name.length() >= 5) return name;
        StringBuilder b = new StringBuilder(5).append(name);
        while (b.length() < 5) b.append(' ');
        return b.toString();
    }

    // ---- change notification (for the UI) -----------------------------------

    /** Register a listener fired after every {@link #add}. */
    public void addChangeListener(Runnable listener) {
        if (listener == null) return;
        synchronized (changeListeners) {
            changeListeners.add(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        synchronized (changeListeners) {
            changeListeners.remove(listener);
        }
    }

    private void fireChanged() {
        List<Runnable> copy;
        synchronized (changeListeners) {
            if (changeListeners.isEmpty()) return;
            copy = new ArrayList<>(changeListeners);
        }
        for (Runnable r : copy) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // A misbehaving listener must never break logging.
            }
        }
    }
}
