// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.log;

import java.util.Objects;

/**
 * WP15 — one immutable captured log line for the in-app log viewer.
 *
 * <p>Pure, platform-neutral: no Android, no SLF4J types leak in here. The SLF4J bridge
 * (in {@code :android}) maps each emitted event into one of these and pushes it onto a
 * {@link LogRingBuffer}; the Compose console then renders / filters / exports them.
 *
 * <p>Per the WP15 brief: {@link Level#INFO} (and above) carry friendly operational lines,
 * while {@link Level#DEBUG} (and {@link Level#TRACE}) carry the raw hex / GATT / DB detail.
 */
public final class LogRecord {

    /**
     * Severity, ordered ascending. The numeric {@link #severity} lets the level filter
     * keep everything "at or above" a threshold without depending on enum ordinals
     * (which would silently shift if the enum is ever reordered).
     */
    public enum Level {
        TRACE(0),
        DEBUG(10),
        INFO(20),
        WARN(30),
        ERROR(40);

        private final int severity;

        Level(int severity) {
            this.severity = severity;
        }

        /** Higher == more severe. Stable across enum reordering. */
        public int severity() {
            return severity;
        }

        /** True if this level is at least as severe as {@code threshold}. */
        public boolean isAtLeast(Level threshold) {
            return this.severity >= threshold.severity;
        }
    }

    private final long timestampMillis;
    private final Level level;
    private final String tag;
    private final String message;

    public LogRecord(long timestampMillis, Level level, String tag, String message) {
        this.timestampMillis = timestampMillis;
        this.level = Objects.requireNonNull(level, "level");
        this.tag = tag == null ? "" : tag;
        this.message = message == null ? "" : message;
    }

    /** Epoch milliseconds when the line was captured. */
    public long timestampMillis() {
        return timestampMillis;
    }

    public Level level() {
        return level;
    }

    /** SLF4J logger name / Android tag (never null; "" if unknown). */
    public String tag() {
        return tag;
    }

    /** Rendered message (arguments already substituted; never null). */
    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogRecord)) return false;
        LogRecord that = (LogRecord) o;
        return timestampMillis == that.timestampMillis
                && level == that.level
                && tag.equals(that.tag)
                && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestampMillis, level, tag, message);
    }

    @Override
    public String toString() {
        return "LogRecord{" + timestampMillis + " " + level + " " + tag + ": " + message + "}";
    }
}
