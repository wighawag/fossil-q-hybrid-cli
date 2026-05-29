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
    public final String packageName;
    public final byte vibe;       // 0-9 (NotificationVibePattern)
    public final short hourDeg;   // 0-359
    public final short minDeg;    // 0-359

    public NotificationFilterEntry(String packageName, byte vibe, short hourDeg, short minDeg) {
        this.packageName = packageName;
        this.vibe = vibe;
        this.hourDeg = hourDeg;
        this.minDeg = minDeg;
    }

    @Override
    public String toString() {
        return packageName + " vibe=" + vibe + " hands=" + hourDeg + "/" + minDeg;
    }
}
