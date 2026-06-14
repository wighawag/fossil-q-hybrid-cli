// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.model;

/**
 * Platform-neutral description of a single notification-filter entry: which
 * package CRC to match, which vibration pattern to play, and where to move the
 * hands. The watch matches an incoming play file's package CRC against these
 * entries to pick the vibe + hand position.
 *
 * <p>This decouples the protocol layer from the CLI's {@code NotificationConfig}
 * disk format — callers translate their own settings into these entries.
 */
public final class NotificationFilterEntry {
    /** Official-app default hand-hold duration in ms (kept in sync with NotificationCompiler). */
    public static final short DEFAULT_DURATION_MS = 10000;

    public final String packageName;
    public final byte vibe;        // 0-9 (NotificationVibePattern)
    public final short hourDeg;    // 0-359
    public final short minDeg;     // 0-359
    public final short durationMs; // hand-hold duration (ms)
    public final boolean moveHands; // false => hands written -1/-1 (no excursion, no lockout)

    /** Backward-compatible entry: 10000ms hold + hands move (the original behaviour). */
    public NotificationFilterEntry(String packageName, byte vibe, short hourDeg, short minDeg) {
        this(packageName, vibe, hourDeg, minDeg, DEFAULT_DURATION_MS, true);
    }

    /**
     * Full entry with a configurable {@code durationMs} and {@code moveHands} flag. When
     * {@code moveHands == false} the compiler writes the hand-degree fields as {@code -1} (no move).
     */
    public NotificationFilterEntry(String packageName, byte vibe, short hourDeg, short minDeg,
                                   short durationMs, boolean moveHands) {
        this.packageName = packageName;
        this.vibe = vibe;
        this.hourDeg = hourDeg;
        this.minDeg = minDeg;
        this.durationMs = durationMs;
        this.moveHands = moveHands;
    }

    @Override
    public String toString() {
        return packageName + " vibe=" + vibe + " hands=" + hourDeg + "/" + minDeg
                + " dur=" + durationMs + " move=" + moveHands;
    }
}
