// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.requests.fossil.notification;

import qhybrid.protocol.model.NotificationFilterEntry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

/**
 * WP6 — Notification rule compile + play file (pure JVM logic).
 *
 * <p>Extracted, byte-for-byte, from the notification byte builders in
 * {@code FossilQAdapter} so the wire format has a single source of truth that is
 * golden-byte testable with zero Android / BLE / UI dependencies. The existing
 * {@code FossilQAdapter} methods delegate here, so every request/upload/init path
 * keeps producing identical bytes.
 *
 * <p><b>Filter file (FileHandle.NOTIFICATION_FILTER 0x0C,00)</b> — fixed
 * <b>32 bytes per entry</b>, little-endian (FINDINGS #17). Per-app vibe pattern +
 * hand-position table. {@link #compileFilter(List)} concatenates N entries:
 * <pre>
 *   packetLength(2 LE) = 30
 *   0x04 PACKAGE_NAME_CRC  len 4  -> int CRC (LE) = CRC32(packageName + '\0')
 *   0x80 GROUP_ID          len 1  -> 0
 *   0xC1 PRIORITY          len 1  -> 0
 *   0xC2 HAND_MOVEMENT     len 10 -> hourDeg(2) minDeg(2) subeye=-1(2) duration=10000(2) subeye2=-2(2)
 *   0xC4 DISPLAY_CONFIG    len 1  -> 0
 *   0xC3 VIBRATION         len 1  -> vibePattern (0-9)
 * </pre>
 *
 * <p><b>Play file (FileHandle.NOTIFICATION_PLAY 0x09,00)</b> — official-format
 * lbl=12 file (FINDINGS #21e: type=3 NOTIFICATION). Pushed when a notification
 * arrives. {@link #buildPlayFile} is <b>pure</b>: the timestamp and messageId are
 * injected so the bytes are deterministic and golden-lockable. The legacy impure
 * path in {@code FossilQAdapter} delegates here with {@code System.currentTimeMillis()}
 * so the live wire bytes stay identical.
 *
 * <p><b>Note (variable-length form):</b> FINDINGS #21d documents an observed
 * variable-length / SENDER_NAME (0x02) entry form used by the official app. That
 * form is NOT implemented here (possible future work); WP6 targets the fixed-32 form.
 *
 * <p><b>CLI wiring (future):</b> the CLI {@code notify} / {@code notify-config}
 * commands should be routed through this same pure helper (via
 * {@code FossilController.buildNotificationFilterFile} / {@code FossilController.buildPlayFile})
 * so there is exactly one notification-byte implementation.
 */
public final class NotificationCompiler {

    /** Fixed filter entry size in bytes (FINDINGS #17). */
    public static final int ENTRY_SIZE = 32;

    /** Default hand-hold duration in ms (official app default). */
    public static final short DEFAULT_HAND_DURATION_MS = 10000;

    /** "No move" sentinel for a hand-degree field (firmware ignores movement for this hand). */
    public static final short HAND_NO_MOVE = -1;

    /** Play-file type byte: NOTIFICATION (vibrates + animates hands) — FINDINGS #21e. */
    public static final byte PLAY_TYPE_NOTIFICATION = 3;

    private NotificationCompiler() {}

    // ============================================================ CRC

    /**
     * Compute {@code CRC32(packageName + '\0')}, matching the official Fossil app.
     * The trailing null terminator is required for the watch firmware to match the
     * filter entry. Reproduced 1:1 from {@code FossilQAdapter.computeNullTerminatedCrc}.
     */
    public static int computeNullTerminatedCrc(String packageName) {
        byte[] nameBytes = packageName.getBytes(StandardCharsets.UTF_8);
        byte[] withNull = new byte[nameBytes.length + 1];
        System.arraycopy(nameBytes, 0, withNull, 0, nameBytes.length);
        withNull[nameBytes.length] = 0;
        CRC32 crc = new CRC32();
        crc.update(withNull);
        return (int) crc.getValue();
    }

    // ============================================================ FILTER

    /**
     * Build a single fixed-32-byte filter entry for the given package name. The
     * package CRC is computed via {@link #computeNullTerminatedCrc(String)}.
     *
     * <p>Byte-identical to {@code FossilQAdapter.buildNotificationFilterData}. No
     * additional validation/clamping is applied beyond the existing casting behavior
     * ({@code hourDeg}/{@code minDeg} written as raw little-endian shorts, {@code vibe}
     * as a raw byte).
     */
    public static byte[] compileEntry(String packageName, byte vibe, short hourDeg, short minDeg) {
        return compileEntryWithCrc(computeNullTerminatedCrc(packageName), vibe, hourDeg, minDeg);
    }

    /**
     * Build a filter entry with a configurable hand-hold {@code durationMs} and an optional
     * {@code moveHands} flag. When {@code moveHands == false}, both hand-degree fields are written as
     * {@link #HAND_NO_MOVE} ({@code -1}) so the watch buzzes the pattern WITHOUT a hand excursion
     * (and therefore without the post-notification hand-return lockout — FINDINGS #23/#24). When
     * {@code moveHands == true}, {@code hourDeg}/{@code minDeg} are written as-is. The supplied
     * {@code durationMs} is always written to the duration field.
     *
     * <p>The 4-arg {@link #compileEntry(String, byte, short, short)} delegates here with
     * {@code durationMs = }{@link #DEFAULT_HAND_DURATION_MS} and {@code moveHands = true}, so its
     * bytes are unchanged.
     */
    public static byte[] compileEntry(String packageName, byte vibe, short hourDeg, short minDeg,
                                      short durationMs, boolean moveHands) {
        return compileEntryWithCrc(computeNullTerminatedCrc(packageName), vibe, hourDeg, minDeg,
                durationMs, moveHands);
    }

    /**
     * Build a single fixed-32-byte filter entry from an already-computed package CRC.
     *
     * <p>This is the low-level core that {@link #compileEntry(String, byte, short, short)}
     * delegates to. It exists primarily so tests can reproduce the raw on-wire entries
     * captured in FINDINGS #17 / #21d, where only the CRC (not the package string) is
     * known. Production callers should prefer {@link #compileEntry(String, byte, short, short)}.
     */
    public static byte[] compileEntryWithCrc(int crc, byte vibe, short hourDeg, short minDeg) {
        return compileEntryWithCrc(crc, vibe, hourDeg, minDeg, DEFAULT_HAND_DURATION_MS, true);
    }

    /**
     * Configurable-duration / optional-no-move variant of
     * {@link #compileEntryWithCrc(int, byte, short, short)}. See
     * {@link #compileEntry(String, byte, short, short, short, boolean)} for the {@code durationMs} /
     * {@code moveHands} semantics. When {@code moveHands == false} the hand-degree fields are
     * forced to {@link #HAND_NO_MOVE}, ignoring the passed {@code hourDeg}/{@code minDeg}.
     */
    public static byte[] compileEntryWithCrc(int crc, byte vibe, short hourDeg, short minDeg,
                                             short durationMs, boolean moveHands) {
        // Entry size: packetLength(2) + CRC(6) + GROUP(3) + PRIORITY(3) +
        //             MOVEMENT(12) + DISPLAY(3) + VIBRATION(3) = 32 total
        ByteBuffer buf = ByteBuffer.allocate(ENTRY_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        buf.putShort((short) 30); // packet length (excluding this 2-byte field)

        buf.put((byte) 0x04);     // PACKAGE_NAME_CRC
        buf.put((byte) 4);
        buf.putInt(crc);

        buf.put((byte) 0x80);     // GROUP_ID
        buf.put((byte) 1);
        buf.put((byte) 0);        // 0 = default group (official app uses 0)

        buf.put((byte) 0xC1);     // PRIORITY
        buf.put((byte) 1);
        buf.put((byte) 0);        // 0 = default priority (official app uses 0)

        short hour = moveHands ? hourDeg : HAND_NO_MOVE;
        short min = moveHands ? minDeg : HAND_NO_MOVE;
        buf.put((byte) 0xC2);     // HAND_MOVEMENT (10 bytes: hour, min, subeye, duration, subeye2)
        buf.put((byte) 10);
        buf.putShort(hour);       // hour hand degrees (-1 = no move)
        buf.putShort(min);        // minute hand degrees (-1 = no move)
        buf.putShort((short) -1); // subeye: no move
        buf.putShort(durationMs); // duration ms (default 10000 = official app default)
        buf.putShort((short) -2); // subeye2: device default (-2)

        buf.put((byte) 0xC4);     // DISPLAY_CONFIG
        buf.put((byte) 1);
        buf.put((byte) 0);

        buf.put((byte) 0xC3);     // VIBRATION
        buf.put((byte) 1);
        buf.put(vibe);

        return buf.array();
    }

    /**
     * Build the full multi-entry notification filter file: one fixed-32-byte entry
     * per rule, concatenated in input order. Empty list -> 0 bytes.
     *
     * <p>Byte-identical to {@code FossilQAdapter.buildNotificationFilterFile}.
     */
    public static byte[] compileFilter(List<NotificationFilterEntry> rules) {
        ByteBuffer buf = ByteBuffer.allocate(rules.size() * ENTRY_SIZE);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        for (NotificationFilterEntry e : rules) {
            buf.put(compileEntry(e.packageName, e.vibe, e.hourDeg, e.minDeg, e.durationMs, e.moveHands));
        }
        return buf.array();
    }

    // ============================================================ PLAY

    /**
     * Build the official-format lbl=12 NOTIFICATION_PLAY file for one package
     * (FINDINGS #21e: type=3 NOTIFICATION).
     *
     * <p><b>Pure:</b> the timestamp and messageId are injected (not read from the
     * clock) so the output is deterministic and golden-lockable. The legacy impure
     * builder in {@code FossilQAdapter} delegates here with {@code System.currentTimeMillis()}.
     *
     * <p>Byte-identical to {@code FossilQAdapter.buildOfficialNotificationFile} for
     * the same {@code nowEpochSeconds} / {@code messageId}.
     *
     * @param packageName     package whose CRC the watch matches against the filter
     * @param title           notification title
     * @param sender          notification sender
     * @param message         notification message
     * @param nowEpochSeconds Unix timestamp (seconds) written to the trailing 4-byte field
     * @param messageId       4-byte message id written as the first data int
     */
    public static byte[] buildPlayFile(String packageName, String title, String sender,
                                       String message, int nowEpochSeconds, int messageId) {
        java.nio.charset.Charset utf8 = StandardCharsets.UTF_8;
        byte[] titleBytes = (title + "\0").getBytes(utf8);
        byte[] senderBytes = (sender + "\0").getBytes(utf8);
        byte[] messageBytes = (message + "\0").getBytes(utf8);

        // Extra fields the official app adds (not in GB):
        byte[] sentinelBytes = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        byte[] timestampBytes = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN).putInt(nowEpochSeconds).array();

        byte lengthBufferLength = 12; // Official app uses 12, GB uses 10
        byte notificationType = PLAY_TYPE_NOTIFICATION; // 3 = NOTIFICATION
        byte flags = 0x02;
        byte uidLength = 4;
        byte appBundleCRCLength = 4;

        // CRC of package name — must match the CRC in the notification filter.
        int packageCrc = computeNullTerminatedCrc(packageName);

        short mainBufferLength = (short) (lengthBufferLength + uidLength + appBundleCRCLength
                + titleBytes.length + senderBytes.length + messageBytes.length
                + sentinelBytes.length + timestampBytes.length);

        ByteBuffer buf = ByteBuffer.allocate(mainBufferLength);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Length buffer header (12 bytes: mainBufLen(2)+lbl(1)+type(1)+flags(1)+uidl(1)+crcl(1)+5 field lengths)
        buf.putShort(mainBufferLength);
        buf.put(lengthBufferLength);
        buf.put(notificationType);
        buf.put(flags);
        buf.put(uidLength);
        buf.put(appBundleCRCLength);
        buf.put((byte) titleBytes.length);
        buf.put((byte) senderBytes.length);
        buf.put((byte) messageBytes.length);
        buf.put((byte) sentinelBytes.length);    // Extra field 1 length
        buf.put((byte) timestampBytes.length);   // Extra field 2 length

        // Data fields
        buf.putInt(messageId);
        buf.putInt(packageCrc);
        buf.put(titleBytes);
        buf.put(senderBytes);
        buf.put(messageBytes);
        buf.put(sentinelBytes);
        buf.put(timestampBytes);

        return buf.array();
    }
}
