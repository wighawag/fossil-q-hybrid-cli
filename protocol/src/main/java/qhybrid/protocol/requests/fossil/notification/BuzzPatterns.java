// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.requests.fossil.notification;

import qhybrid.protocol.model.NotificationFilterEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * WP-BUZZ-PLAYONLY — single source of truth for the <b>reserved buzz filter entries</b> that let a
 * manual "Vibrate" buzz be a SINGLE play-file put.
 *
 * <p>The watch picks a vibration pattern by matching a play file's package-name CRC against an entry
 * in the notification filter ({@code FileHandle.NOTIFICATION_FILTER 0x0C00}). To buzz with a given
 * pattern WITHOUT re-uploading a filter every time, we upload a small set of reserved entries ONCE
 * at connect — one per distinct useful pattern, each under a stable package name
 * ({@code qhybrid.linux.buzzN} where {@code N} == the vibration pattern byte). A buzz then only sends
 * the {@code NOTIFICATION_PLAY} file for {@code qhybrid.linux.buzzN}; the reserved entry already on
 * the watch supplies the pattern.
 *
 * <p>Distinct <em>useful</em> patterns (hardware-tested — FINDINGS.md "vibration field (0xC3)"):
 * {@code 1=CALL(triple)}, {@code 2=TEXT(double)}, {@code 3=EMAIL(single)},
 * {@code 5=ONE_SHORT_VIBE(strong single)}, {@code 6=TWO_SHORT_VIBES(strong double)},
 * {@code 7=THREE_SHORT_VIBES(strong triple)}, {@code 8=ONE_LONG_VIBE(long)}. Silent {@code 0/9} and
 * the {@code 4≡3} duplicate are skipped.
 *
 * <p>No new wire bytes: reserved entries are ordinary {@link NotificationCompiler} filter entries.
 */
public final class BuzzPatterns {

    private BuzzPatterns() {}

    /** Reserved package-name prefix. The pattern byte is appended ({@code qhybrid.linux.buzz5}). */
    public static final String RESERVED_PREFIX = "qhybrid.linux.buzz";

    /** Distinct useful vibration patterns that get a reserved entry (silent 0/9 + 4≡3 skipped). */
    public static final int[] RESERVED_PATTERNS = {1, 2, 3, 5, 6, 7, 8};

    /** Neutral hand position used by the reserved entries (3 o'clock). */
    public static final short NEUTRAL_HOUR_DEG = 90;
    public static final short NEUTRAL_MIN_DEG = 90;

    /** The reserved package name for a given vibration pattern, e.g. {@code 5 -> qhybrid.linux.buzz5}. */
    public static String packageNameForPattern(int pattern) {
        return RESERVED_PREFIX + pattern;
    }

    /** True if {@code pattern} has a reserved buzz entry (i.e. it is a distinct useful pattern). */
    public static boolean isReservedPattern(int pattern) {
        for (int p : RESERVED_PATTERNS) {
            if (p == pattern) return true;
        }
        return false;
    }

    /** Null-terminated package CRC for the reserved pattern (matches the play file's packageCrc). */
    public static int crcForPattern(int pattern) {
        return NotificationCompiler.computeNullTerminatedCrc(packageNameForPattern(pattern));
    }

    /**
     * The reserved buzz filter entries (one per {@link #RESERVED_PATTERNS}), each with neutral hands.
     * Append these to whatever notification-rule entries a sync builds so the reserved entries
     * survive a later filter upload (the filter is whole-file).
     */
    public static List<NotificationFilterEntry> reservedEntries() {
        List<NotificationFilterEntry> entries = new ArrayList<>(RESERVED_PATTERNS.length);
        for (int p : RESERVED_PATTERNS) {
            entries.add(new NotificationFilterEntry(
                    packageNameForPattern(p), (byte) p, NEUTRAL_HOUR_DEG, NEUTRAL_MIN_DEG));
        }
        return entries;
    }

    /** The reserved buzz filter as a ready-to-put NOTIFICATION_FILTER file (multi-entry). */
    public static byte[] reservedFilterFile() {
        return NotificationCompiler.compileFilter(reservedEntries());
    }
}
