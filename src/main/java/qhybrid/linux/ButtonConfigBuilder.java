package qhybrid.linux;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.buttonconfig.ConfigPayload;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Builds button config binary files with support for multi-entry buttons (mode_toggle).
 *
 * The vendored ConfigFileBuilder only supports 1 entry per button. This builder
 * handles arbitrary entry counts per button, following the format documented in
 * FINDINGS.md #19 and #21b.
 *
 * File format:
 *   [01 00 00]          version (3 bytes)
 *   [buttonCount]       number of buttons (1 byte)
 *   For each button:
 *     [buttonIndex]     0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM
 *     [entryCount]      number of function entries for this button
 *     For each entry:
 *       [header bytes]  4 bytes: [type, variant, appId_lo, appId_hi]
 *     [0x00]            null terminator
 *   [payloadCount]      number of distinct payloads
 *   For each distinct payload:
 *     [payload bytes]   variable length
 *   [customizationCount] (1 byte, usually 0x00)
 *   [CRC32]            4 bytes, little-endian
 *
 * Zero patches to vendored GadgetBridge code.
 */
public class ButtonConfigBuilder {

    // Mode toggle sub-functions: SECOND_TIMEZONE → DATE → STEP_GOAL_PROGRESS
    // Headers (4 bytes each):
    private static final byte[] HEADER_SECOND_TIMEZONE = {0x01, 0x01, 0x16, 0x00};
    private static final byte[] HEADER_DATE            = {0x01, 0x02, 0x14, 0x00};  // NOTE: variant=0x02 in mode toggle context
    private static final byte[] HEADER_STEP_GOAL_PROGRESS = {0x01, 0x02, 0x1a, 0x00};

    // STEP_GOAL_PROGRESS payload (appId 0x001a) — captured from official Fossil app
    // BLE trace (bugreport5, t=169.6s). See FINDINGS.md #21b.
    // This is NOT the same as STEP_GOAL_COMPLETION (appId 0x001c).
    private static final byte[] STEP_GOAL_PROGRESS_DATA = {
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

    /** Mode toggle headers (3 entries). */
    private static final byte[][] MODE_TOGGLE_HEADERS = {
            HEADER_SECOND_TIMEZONE,
            HEADER_DATE,
            HEADER_STEP_GOAL_PROGRESS
    };

    /** Mode toggle payloads (3 entries, matching the headers). */
    private static final byte[][] MODE_TOGGLE_PAYLOADS = {
            ConfigPayload.SECOND_TIMEZONE.getData(),
            ConfigPayload.DATE.getData(),
            STEP_GOAL_PROGRESS_DATA
    };

    /**
     * Represents one button's configuration: 1+ (header, payload) entries.
     */
    private static class ButtonEntry {
        final byte buttonIndex;       // 0x10, 0x20, 0x30
        final byte[][] headers;       // 4 bytes each
        final byte[][] payloads;      // variable length each

        ButtonEntry(byte buttonIndex, byte[][] headers, byte[][] payloads) {
            this.buttonIndex = buttonIndex;
            this.headers = headers;
            this.payloads = payloads;
        }
    }

    /**
     * Build a button config file supporting mode_toggle on any button.
     */
    public static byte[] buildWithModeToggle(
            boolean topIsToggle, boolean midIsToggle, boolean botIsToggle,
            ConfigPayload topElse, ConfigPayload midElse, ConfigPayload botElse) {

        ButtonEntry[] buttons = new ButtonEntry[3];
        buttons[0] = makeEntry((byte) 0x10, topIsToggle, topElse);
        buttons[1] = makeEntry((byte) 0x20, midIsToggle, midElse);
        buttons[2] = makeEntry((byte) 0x30, botIsToggle, botElse);

        return buildFile(buttons);
    }

    private static ButtonEntry makeEntry(byte buttonIndex, boolean isToggle, ConfigPayload single) {
        if (isToggle) {
            return new ButtonEntry(buttonIndex, MODE_TOGGLE_HEADERS, MODE_TOGGLE_PAYLOADS);
        } else {
            return new ButtonEntry(buttonIndex,
                    new byte[][]{single.getHeader()},
                    new byte[][]{single.getData()});
        }
    }

    private static byte[] buildFile(ButtonEntry[] buttons) {
        // Collect all distinct payloads (by content)
        List<byte[]> distinctPayloads = new ArrayList<>();
        for (ButtonEntry btn : buttons) {
            for (byte[] payload : btn.payloads) {
                boolean found = false;
                for (byte[] existing : distinctPayloads) {
                    if (Arrays.equals(payload, existing)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    distinctPayloads.add(payload);
                }
            }
        }

        // Calculate header section size
        int headerSectionSize = 0;
        for (ButtonEntry btn : buttons) {
            // buttonIndex(1) + entryCount(1) + (header_bytes * entryCount) + null(1)
            headerSectionSize += 1 + 1;
            for (byte[] h : btn.headers) {
                headerSectionSize += h.length;
            }
            headerSectionSize += 1; // null terminator
        }

        // Calculate payload section size
        int payloadSectionSize = 0;
        for (byte[] p : distinctPayloads) {
            payloadSectionSize += p.length;
        }

        int totalSize = 3          // version
                + 1                 // button count
                + headerSectionSize
                + 1                 // payload count
                + payloadSectionSize
                + 1                 // customization count
                + 4;               // CRC32

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Version
        buf.put((byte) 0x01);
        buf.put((byte) 0x00);
        buf.put((byte) 0x00);

        // Button count
        buf.put((byte) buttons.length);

        // Button headers
        for (ButtonEntry btn : buttons) {
            buf.put(btn.buttonIndex);
            buf.put((byte) btn.headers.length); // entryCount
            for (byte[] header : btn.headers) {
                buf.put(header);
            }
            buf.put((byte) 0x00); // null terminator
        }

        // Payload count
        buf.put((byte) distinctPayloads.size());

        // Payloads
        for (byte[] payload : distinctPayloads) {
            buf.put(payload);
        }

        // Customization count (0 = none)
        buf.put((byte) 0x00);

        // CRC32 over everything before the CRC
        int dataLen = buf.position();
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, dataLen);
        buf.putInt((int) crc.getValue());

        return buf.array();
    }
}
