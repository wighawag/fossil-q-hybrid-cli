// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.requests.fossil.notification;

import qhybrid.protocol.model.NotificationFilterEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * WP-NAV — single source of truth for the <b>reserved navigation-cue filter entries</b> that let a
 * turn cue (buzz + point BOTH hands in the turn direction) be a SINGLE play-file put — exactly the
 * same mechanism as {@link BuzzPatterns}, but the reserved entries carry the turn-direction hand
 * degrees (not just a buzz pattern).
 *
 * <p><b>Why reserved entries (the bug this fixes).</b> The watch picks a vibration pattern + hand
 * position by matching a play file's package-name CRC against an entry in the
 * {@code NOTIFICATION_FILTER 0x0C00} file. The earlier nav-cue path called
 * {@code FossilController.buzz(pattern, h, m)}, which does a self-contained two-put that REPLACES
 * the whole filter file with a single ad-hoc {@code qhybrid.linux} entry — racing/clobbering the
 * app's managed multi-entry filter (notification rules + reserved buzz entries). The fix: reserve a
 * small fixed set of nav-cue entries ONCE (folded into the managed filter, like the buzz entries),
 * so a cue is just a {@code NOTIFICATION_PLAY} put for the matching reserved package — no filter
 * upload, no clobber.
 *
 * <p><b>The reserved set</b> is exactly the distinct {@code (hourDeg, minDeg, vibe)} cues the pure
 * {@code TurnCueMapper} can emit:
 * <ul>
 *   <li>8 directions (0/45/90/135/180/225/270/315°) × the two directional stages
 *       (SOON = {@code TWO_SHORT}, NOW = {@code ONE_LONG}),</li>
 *   <li>ARRIVE (12 o'clock 0°, {@code THREE_SHORT}),</li>
 *   <li>OFF_ROUTE / "go back" (6 o'clock 180°, {@code CALL}).</li>
 * </ul>
 * Both hands always point the same degree (the app's design), so a single degree value keys each
 * entry. Package name: {@code qhybrid.linux.nav.<deg>.<vibe>} (e.g. {@code qhybrid.linux.nav.270.8}
 * for a "turn LEFT now"). ~18 entries × 32 bytes ≈ 576 bytes — well within the filter budget
 * (alongside the 7 buzz entries + user rules).
 *
 * <p>No new wire bytes: reserved entries are ordinary {@link NotificationCompiler} filter entries.
 */
public final class NavCuePatterns {

    private NavCuePatterns() {}

    /** Reserved package-name prefix; the degree + vibe are appended ({@code ...nav.270.8}). */
    public static final String RESERVED_PREFIX = "qhybrid.linux.nav.";

    // The vibe patterns a nav cue uses (mirror VibePatterns: 1=CALL, 6=TWO_SHORT, 7=THREE_SHORT,
    // 8=ONE_LONG). Kept as plain ints here (the protocol layer has no Android VibePatterns).
    private static final int VIBE_CALL = 1;
    private static final int VIBE_TWO_SHORT = 6;
    private static final int VIBE_THREE_SHORT = 7;
    private static final int VIBE_ONE_LONG = 8;

    /** The 8 directional clock degrees both hands point to. */
    private static final int[] DIRECTION_DEGREES = {0, 45, 90, 135, 180, 225, 270, 315};

    /** The two directional-stage vibes (SOON = double, NOW = long). */
    private static final int[] DIRECTION_VIBES = {VIBE_TWO_SHORT, VIBE_ONE_LONG};

    /** The reserved package name for a cue at [deg]/[deg] with vibe [vibe]. */
    public static String packageNameFor(int deg, int vibe) {
        return RESERVED_PREFIX + deg + "." + vibe;
    }

    /** Null-terminated package CRC for a cue (matches the play file's packageCrc). */
    public static int crcFor(int deg, int vibe) {
        return NotificationCompiler.computeNullTerminatedCrc(packageNameFor(deg, vibe));
    }

    /**
     * The full reserved nav-cue filter entry set (the distinct cues the mapper emits). Append these
     * to whatever the notification sync builds so they survive the whole-file filter upload (exactly
     * like {@link BuzzPatterns#reservedEntries()}).
     */
    public static List<NotificationFilterEntry> reservedEntries() {
        List<NotificationFilterEntry> entries = new ArrayList<>();
        // 8 directions × 2 stage vibes.
        for (int deg : DIRECTION_DEGREES) {
            for (int vibe : DIRECTION_VIBES) {
                entries.add(entry(deg, vibe));
            }
        }
        // ARRIVE (12 o'clock, triple) + OFF_ROUTE "go back" (6 o'clock, CALL triple).
        entries.add(entry(0, VIBE_THREE_SHORT));
        entries.add(entry(180, VIBE_CALL));
        return entries;
    }

    private static NotificationFilterEntry entry(int deg, int vibe) {
        return new NotificationFilterEntry(
                packageNameFor(deg, vibe), (byte) vibe, (short) deg, (short) deg);
    }
}
