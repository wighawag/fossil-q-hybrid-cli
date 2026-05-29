package qhybrid.protocol;

import qhybrid.protocol.buttonconfig.ConfigPayload;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Builds button config binary files with support for multi-entry buttons.
 *
 * The vendored ConfigFileBuilder only supports 1 entry per button. This builder
 * handles arbitrary entry counts per button, following the format documented in
 * FINDINGS.md #19 and #21b.
 *
 * File format (from BLE capture analysis of official Fossil app):
 *   [01 00 00]          version (3 bytes)
 *   [buttonCount]       number of buttons (1 byte)
 *   For each button:
 *     [buttonIndex]     0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM
 *     [entryCount]      number of function entries for this button
 *     For each entry:
 *       [header(4)]     [type, variant, appId_lo, appId_hi]
 *     [0x00]            null terminator
 *   [payloadCount]      number of payloads (one per button×entry, NOT deduplicated)
 *   For each payload:
 *     [payloadData]     variable length (byte[5] = total payload length)
 *   [customizationCount] number of customization entries
 *   For each customization:
 *     [header(4)]       same 4-byte header as the corresponding entry
 *     [0a 00 01 02 01 00]  6 bytes of customization data (constant)
 *   [CRC32]            4 bytes, little-endian
 *
 * Zero patches to vendored GadgetBridge code.
 */
public class ButtonConfigBuilder {

    // ========== ALARM SEQUENCED (appId 0x001a, variant 0x02) ==========
    // Captured from official Fossil app BLE trace (bugreport5, t=169.6s).
    // This is ALARM (SEQUENCED) — used inside mode toggle to show next alarm.
    // MicroAppId: declarationId 6658 = ALARM SEQUENCED.
    // Previously misidentified as "STEP_GOAL_PROGRESS" — corrected 2026-05-22.
    // See FINDINGS.md #22.

    static final byte[] ALARM_SEQUENCED_HEADER = {0x01, 0x02, 0x1a, 0x00};

    static final byte[] ALARM_SEQUENCED_DATA = {
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x02,
            (byte) 0x1a, (byte) 0x36, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x08,
            (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x00,
            (byte) 0x07, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0x01, (byte) 0x1d, (byte) 0x00,
            (byte) 0x89, (byte) 0x02, (byte) 0x01, (byte) 0x04,
            (byte) 0xb0, (byte) 0x03, (byte) 0x00, (byte) 0x89,
            (byte) 0x05, (byte) 0x01, (byte) 0x07, (byte) 0xb0,
            (byte) 0x03, (byte) 0x00, (byte) 0xb0, (byte) 0x03,
            (byte) 0x00, (byte) 0xb0, (byte) 0x03, (byte) 0x00,
            (byte) 0x08, (byte) 0x01, (byte) 0x50, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0xa6, (byte) 0x79,
            (byte) 0x57, (byte) 0xcc
    };

    // DATE variant 0x02 — used inside mode toggle, NOT the same as ConfigPayload.DATE (variant 0x01).
    // Captured from official Fossil app BLE trace (bugreport5, t=169.6s).
    // Header: 01 02 14 00 (vs standalone DATE: 01 01 14 00)
    static final byte[] DATE_TOGGLE_HEADER = {0x01, 0x02, 0x14, 0x00};

    static final byte[] DATE_TOGGLE_DATA = {
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x02,
            (byte) 0x14, (byte) 0x34, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x06,
            (byte) 0x00, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x07, (byte) 0x00, (byte) 0x01, (byte) 0x01,
            (byte) 0x1d, (byte) 0x00, (byte) 0x89, (byte) 0x02,
            (byte) 0x01, (byte) 0x04, (byte) 0xb0, (byte) 0x00,
            (byte) 0x00, (byte) 0x89, (byte) 0x05, (byte) 0x01,
            (byte) 0x07, (byte) 0xb0, (byte) 0x00, (byte) 0x00,
            (byte) 0xb0, (byte) 0x00, (byte) 0x00, (byte) 0xb0,
            (byte) 0x00, (byte) 0x00, (byte) 0x08, (byte) 0x01,
            (byte) 0x50, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x77, (byte) 0x9c, (byte) 0x0c, (byte) 0x19
    };

    /** DATE entry for use inside mode toggle (variant 0x02). */
    public static final ButtonEntry DATE_TOGGLE_ENTRY =
            new ButtonEntry(DATE_TOGGLE_HEADER, DATE_TOGGLE_DATA);

    // GOAL_TRACKING (appId 0x0004) — captured from official Fossil app (bugreport6).
    // This is the "Goal Tracking" / custom task tracking button function.
    static final byte[] GOAL_TRACKING_HEADER = {0x01, 0x01, 0x04, 0x00};

    static final byte[] GOAL_TRACKING_DATA = {
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x01,
            (byte) 0x04, (byte) 0x21, (byte) 0x00, (byte) 0x0a,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x05,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x0b,
            (byte) 0x00, (byte) 0x8d, (byte) 0x00, (byte) 0xff,
            (byte) 0x93, (byte) 0x00, (byte) 0x01, (byte) 0x01,
            (byte) 0x00, (byte) 0x9d, (byte) 0xe0, (byte) 0x2b,
            (byte) 0x40
    };

    /** GOAL_TRACKING entry (custom task/water tracking). */
    public static final ButtonEntry GOAL_TRACKING_ENTRY =
            new ButtonEntry(GOAL_TRACKING_HEADER, GOAL_TRACKING_DATA);

    // ========== TWENTY_FOUR_HOUR STANDARD (appId 0x001E, variant 0x01) ==========
    // Constructed by analogy with SECOND_TIMEZONE (same 47-byte STANDARD pattern).
    // MicroAppId: declarationId 7681 = TWENTY_FOUR_HOUR STANDARD.
    // On 5-position dial watches (Q Activist), the sub-eye has a labeled "24HR" position.
    // On Q Commuter (3-position dial: A/B/C), there's no labeled 24HR position;
    // behavior depends on whether the firmware maps display mode 4 to a sub-eye position.
    // Display mode byte B0 04 00 chosen as next sequential after ALARM (B0 03 00).

    static final byte[] TWENTY_FOUR_HOUR_HEADER = {0x01, 0x01, 0x1E, 0x00};

    static final byte[] TWENTY_FOUR_HOUR_DATA = {
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x01,
            (byte) 0x1E, (byte) 0x2F, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x08,
            (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x00,
            (byte) 0x07, (byte) 0x02, (byte) 0x02, (byte) 0x00,
            (byte) 0x01, (byte) 0x01, (byte) 0x1E, (byte) 0x00,
            (byte) 0x89, (byte) 0x05, (byte) 0x01, (byte) 0x07,
            (byte) 0xB0, (byte) 0x04, (byte) 0x00, (byte) 0xB0,
            (byte) 0x04, (byte) 0x00, (byte) 0xB0, (byte) 0x04,
            (byte) 0x00, (byte) 0x08, (byte) 0x01, (byte) 0x50,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x8B,
            (byte) 0x95, (byte) 0x15, (byte) 0x80
    };

    /** TWENTY_FOUR_HOUR STANDARD entry — shows 24h time on sub-eye. */
    public static final ButtonEntry TWENTY_FOUR_HOUR_ENTRY =
            new ButtonEntry(TWENTY_FOUR_HOUR_HEADER, TWENTY_FOUR_HOUR_DATA);

    // ========== TWENTY_FOUR_HOUR SEQUENCED (appId 0x001E, variant 0x02) ==========
    // Constructed by analogy with ALARM_SEQUENCED (same 54-byte SEQUENCED pattern).
    // MicroAppId: declarationId 7682 = TWENTY_FOUR_HOUR SEQUENCED.
    // For use inside multi-entry toggle.

    static final byte[] TWENTY_FOUR_HOUR_SEQ_HEADER = {0x01, 0x02, 0x1E, 0x00};

    static final byte[] TWENTY_FOUR_HOUR_SEQ_DATA = {
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x02,
            (byte) 0x1E, (byte) 0x36, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x08,
            (byte) 0x00, (byte) 0x04, (byte) 0x00, (byte) 0x00,
            (byte) 0x07, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0x01, (byte) 0x1D, (byte) 0x00,
            (byte) 0x89, (byte) 0x02, (byte) 0x01, (byte) 0x04,
            (byte) 0xB0, (byte) 0x04, (byte) 0x00, (byte) 0x89,
            (byte) 0x05, (byte) 0x01, (byte) 0x07, (byte) 0xB0,
            (byte) 0x04, (byte) 0x00, (byte) 0xB0, (byte) 0x04,
            (byte) 0x00, (byte) 0xB0, (byte) 0x04, (byte) 0x00,
            (byte) 0x08, (byte) 0x01, (byte) 0x50, (byte) 0x00,
            (byte) 0x01, (byte) 0x00, (byte) 0xA9, (byte) 0x21,
            (byte) 0xD4, (byte) 0xC7
    };

    /** TWENTY_FOUR_HOUR SEQUENCED entry — for use in toggle. */
    public static final ButtonEntry TWENTY_FOUR_HOUR_SEQ_ENTRY =
            new ButtonEntry(TWENTY_FOUR_HOUR_SEQ_HEADER, TWENTY_FOUR_HOUR_SEQ_DATA);

    // LAST_NOTIFICATION payload — already in ConfigPayload, but note the
    // variant difference: standalone = variant 0x01 (01 01 18 00).
    // Inside toggle context it may behave differently (see FINDINGS testing).

    // Customization suffix: constant 6 bytes appended after each entry's header
    // in the customization section. Captured from official app.
    private static final byte[] CUSTOMIZATION_SUFFIX = {0x0a, 0x00, 0x01, 0x02, 0x01, 0x00};

    // ========== Button entry abstraction ==========

    /**
     * A button function entry: header (4 bytes) + payload data.
     */
    public record ButtonEntry(byte[] header, byte[] data) {}

    /** Convert a ConfigPayload to a ButtonEntry. */
    public static ButtonEntry entryFrom(ConfigPayload p) {
        return new ButtonEntry(p.getHeader(), p.getData());
    }

    /** ALARM (SEQUENCED) entry — used inside mode toggle to show next alarm on sub-eye C. */
    public static final ButtonEntry ALARM_SEQUENCED_ENTRY =
            new ButtonEntry(ALARM_SEQUENCED_HEADER, ALARM_SEQUENCED_DATA);

    /** @deprecated Renamed to ALARM_SEQUENCED_ENTRY. This is ALARM, not step goal progress. */
    public static final ButtonEntry STEP_GOAL_PROGRESS_ENTRY = ALARM_SEQUENCED_ENTRY;

    // ========== Mode toggle default ==========

    /**
     * Default mode toggle entries: SECOND_TIMEZONE → DATE (variant 0x02) → ALARM (SEQUENCED).
     * Cycles sub-eye through: A (timezone) → B (date) → C (alarm) → normal time.
     * Note: DATE inside mode toggle uses variant 0x02 (52 bytes), not variant 0x01 (45 bytes).
     * Note: ALARM needs at least one alarm set to display on indicator C.
     */
    public static final ButtonEntry[] MODE_TOGGLE_ENTRIES = {
            entryFrom(ConfigPayload.SECOND_TIMEZONE),
            DATE_TOGGLE_ENTRY,
            ALARM_SEQUENCED_ENTRY
    };

    // ========== Builder ==========

    /**
     * Build a button config file. Each button gets an array of ButtonEntry.
     * Payloads are NOT deduplicated (one per button×entry, matching official app).
     * Customization entries are included (one per button×entry).
     */
    public static byte[] build(ButtonEntry[] topEntries, ButtonEntry[] midEntries, ButtonEntry[] botEntries) {
        ButtonEntry[][] allButtons = {topEntries, midEntries, botEntries};
        byte[] buttonIndices = {0x10, 0x20, 0x30};

        // Collect all payloads in order (one per button×entry, NOT deduplicated)
        List<ButtonEntry> allEntries = new ArrayList<>();
        for (ButtonEntry[] entries : allButtons) {
            for (ButtonEntry entry : entries) {
                allEntries.add(entry);
            }
        }
        int totalPayloads = allEntries.size();

        // Calculate sizes
        int headerSectionSize = 0;
        for (ButtonEntry[] entries : allButtons) {
            // buttonIndex(1) + entryCount(1) + (header(4) + null(1)) per entry
            headerSectionSize += 2 + (entries.length * 5);
        }

        int payloadSectionSize = 0;
        for (ButtonEntry entry : allEntries) {
            payloadSectionSize += entry.data().length;
        }

        // Customization: header(4) + suffix(6) = 10 bytes per entry
        int customizationSize = totalPayloads * 10;

        int totalSize = 3                    // version
                + 1                           // button count
                + headerSectionSize
                + 1                           // payload count
                + payloadSectionSize
                + 1                           // customization count
                + customizationSize
                + 4;                          // CRC32

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Version
        buf.put((byte) 0x01);
        buf.put((byte) 0x00);
        buf.put((byte) 0x00);

        // Button count
        buf.put((byte) allButtons.length);

        // Button headers — each entry is header(4) + null(1)
        for (int b = 0; b < allButtons.length; b++) {
            buf.put(buttonIndices[b]);
            buf.put((byte) allButtons[b].length);
            for (ButtonEntry entry : allButtons[b]) {
                buf.put(entry.header());
                buf.put((byte) 0x00);
            }
        }

        // Payload count (one per button×entry, NOT deduplicated)
        buf.put((byte) totalPayloads);

        // Payloads in order
        for (ButtonEntry entry : allEntries) {
            buf.put(entry.data());
        }

        // Customization count
        buf.put((byte) totalPayloads);

        // Customization entries: header(4) + constant suffix(6)
        for (ButtonEntry entry : allEntries) {
            buf.put(entry.header());
            buf.put(CUSTOMIZATION_SUFFIX);
        }

        // CRC32 over everything before the CRC
        int dataLen = buf.position();
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, dataLen);
        buf.putInt((int) crc.getValue());

        return buf.array();
    }
}
