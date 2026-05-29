// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.requests.fossil.button;

import qhybrid.protocol.ButtonConfigBuilder.ButtonEntry;
import qhybrid.protocol.buttonconfig.ConfigPayload;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * WP7 — pure, platform-neutral compiler for SETTINGS_BUTTONS (0x0600) files.
 *
 * <p>This is the single source of truth for button-config byte assembly. There are
 * <b>two distinct, real wire formats</b> and this class reproduces both 1:1:
 *
 * <ol>
 *   <li><b>Multi-entry</b> ({@link #compileMultiEntry}) — the official-app format used for
 *       mode-toggle files. One {@code 0x00} per entry header, payloads NOT deduplicated
 *       (one per button×entry), a customization section (header + constant 6-byte suffix
 *       per entry), and a CRC32 LE trailer. See FINDINGS.md #19/#21b/#22.</li>
 *   <li><b>Single-entry-per-button</b> ({@link #compileSingleEntryPerButton}) — the vendored
 *       GadgetBridge format: exactly one entry per button, payloads deduplicated, a single
 *       {@code 0x00} customization-count byte, optional CRC32 LE trailer.</li>
 * </ol>
 *
 * <p>File format (multi-entry):
 * <pre>
 *   [01 00 00]            version (3 bytes)
 *   [buttonCount]         1 byte
 *   For each button:
 *     [buttonIndex]       0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM
 *     [entryCount]        1 byte
 *     For each entry:
 *       [header(4)]       [type, variant, appId_lo, appId_hi]
 *       [0x00]            null byte PER ENTRY
 *   [payloadCount]        1 byte (one per button×entry, NOT deduplicated)
 *   For each payload:
 *     [payloadData]       variable length
 *   [customizationCount]  1 byte (one per button×entry)
 *   For each customization:
 *     [header(4)]         same 4-byte header as the entry
 *     [0a 00 01 02 01 00] constant 6-byte suffix
 *   [CRC32]               4 bytes LE (over all preceding bytes)
 * </pre>
 *
 * <p>The dial-mode availability hook ({@link #availableModes}/{@link #isModeAvailable})
 * is a pure lookup/guard — it never mutates any emitted byte.
 */
public final class ButtonCompiler {

    private ButtonCompiler() {}

    /** Button index bytes for TOP, MIDDLE, BOTTOM. */
    private static final byte[] BUTTON_INDICES = {0x10, 0x20, 0x30};

    /**
     * Customization suffix: constant 6 bytes appended after each entry's header in the
     * customization section. Captured from the official app (FINDINGS #19/#21b).
     */
    private static final byte[] CUSTOMIZATION_SUFFIX = {0x0a, 0x00, 0x01, 0x02, 0x01, 0x00};

    // ====================================================================== multi-entry

    /**
     * Compile a multi-entry button-config file (mode-toggle capable). Each button gets an
     * array of {@link ButtonEntry}. Payloads are NOT deduplicated (one per button×entry,
     * matching the official app). The customization section and CRC32 trailer are included.
     *
     * <p>Byte-for-byte identical to the historical {@code ButtonConfigBuilder.build(...)}.
     */
    public static byte[] compileMultiEntry(ButtonEntry[] topEntries,
                                           ButtonEntry[] midEntries,
                                           ButtonEntry[] botEntries) {
        ButtonEntry[][] allButtons = {topEntries, midEntries, botEntries};

        // Collect all payloads in order (one per button×entry, NOT deduplicated).
        List<ButtonEntry> allEntries = new ArrayList<>();
        for (ButtonEntry[] entries : allButtons) {
            for (ButtonEntry entry : entries) {
                allEntries.add(entry);
            }
        }
        int totalPayloads = allEntries.size();

        // Sizes.
        int headerSectionSize = 0;
        for (ButtonEntry[] entries : allButtons) {
            // buttonIndex(1) + entryCount(1) + (header(4) + null(1)) per entry
            headerSectionSize += 2 + (entries.length * 5);
        }

        int payloadSectionSize = 0;
        for (ButtonEntry entry : allEntries) {
            payloadSectionSize += entry.data().length;
        }

        // Customization: header(4) + suffix(6) = 10 bytes per entry.
        int customizationSize = totalPayloads * 10;

        int totalSize = 3                    // version
                + 1                          // button count
                + headerSectionSize
                + 1                          // payload count
                + payloadSectionSize
                + 1                          // customization count
                + customizationSize
                + 4;                         // CRC32

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Version.
        buf.put((byte) 0x01);
        buf.put((byte) 0x00);
        buf.put((byte) 0x00);

        // Button count.
        buf.put((byte) allButtons.length);

        // Button headers — each entry is header(4) + null(1).
        for (int b = 0; b < allButtons.length; b++) {
            buf.put(BUTTON_INDICES[b]);
            buf.put((byte) allButtons[b].length);
            for (ButtonEntry entry : allButtons[b]) {
                buf.put(entry.header());
                buf.put((byte) 0x00);
            }
        }

        // Payload count (one per button×entry, NOT deduplicated).
        buf.put((byte) totalPayloads);

        // Payloads in order.
        for (ButtonEntry entry : allEntries) {
            buf.put(entry.data());
        }

        // Customization count.
        buf.put((byte) totalPayloads);

        // Customization entries: header(4) + constant suffix(6).
        for (ButtonEntry entry : allEntries) {
            buf.put(entry.header());
            buf.put(CUSTOMIZATION_SUFFIX);
        }

        // CRC32 over everything before the CRC.
        int dataLen = buf.position();
        buf.putInt(computeCrc32(buf.array(), 0, dataLen));

        return buf.array();
    }

    // ============================================================ single-entry-per-button

    /**
     * Compile the vendored single-entry-per-button button-config file. Exactly one entry per
     * button, payloads deduplicated (by identity of the {@link ButtonEntry} data), a single
     * {@code 0x00} customization-count byte, and an optional CRC32 LE trailer.
     *
     * <p>Byte-for-byte identical to the historical
     * {@code buttonconfig.ConfigFileBuilder.build(appendChecksum)}.
     *
     * @param entries        one entry per button, in TOP/MIDDLE/BOTTOM order
     *                       (button index increments 0x10, 0x20, 0x30, ...).
     * @param appendChecksum whether to append the 4-byte CRC32 LE trailer.
     */
    public static byte[] compileSingleEntryPerButton(ButtonEntry[] entries, boolean appendChecksum) {
        int payloadSize = 0;
        for (ButtonEntry e : entries) {
            payloadSize += e.data().length;
        }

        int headerSize = 0;
        for (ButtonEntry e : entries) {
            headerSize += e.header().length + 3; // button + version + null
        }

        ByteBuffer buffer = ByteBuffer.allocate(
                3 // version bytes
                        + 1 // header count byte
                        + headerSize
                        + 1 // payload count byte
                        + payloadSize
                        + 1 // customization count byte
                        + (appendChecksum ? 4 : 0));
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(new byte[]{(byte) 0x01, (byte) 0x00, (byte) 0x00}); // version
        buffer.put((byte) entries.length);
        int buttonIndex = 0x00;
        for (ButtonEntry e : entries) {
            buffer.put((byte) (buttonIndex += 0x10));
            buffer.put((byte) 0x01);
            buffer.put(e.header());
            buffer.put((byte) 0x00);
        }

        // Deduplicate payloads by data identity (matches vendored ConfigPayload.equals(byte[])).
        List<ButtonEntry> distinctPayloads = new ArrayList<>(3);
        compareLoop:
        for (int payloadIndex = 0; payloadIndex < entries.length; payloadIndex++) {
            for (int compareTo = 0; compareTo < distinctPayloads.size(); compareTo++) {
                if (java.util.Arrays.equals(entries[payloadIndex].data(),
                        distinctPayloads.get(compareTo).data())) {
                    continue compareLoop;
                }
            }
            distinctPayloads.add(entries[payloadIndex]);
        }

        buffer.put((byte) distinctPayloads.size());
        for (ButtonEntry e : distinctPayloads) {
            buffer.put(e.data());
        }

        buffer.put((byte) 0x00); // customization count = 0

        ByteBuffer out = ByteBuffer.allocate(buffer.position() + (appendChecksum ? 4 : 0));
        out.order(ByteOrder.LITTLE_ENDIAN);
        out.put(buffer.array(), 0, buffer.position());

        if (!appendChecksum) return out.array();

        out.putInt(computeCrc32(buffer.array(), 0, buffer.position()));
        return out.array();
    }

    /** Convenience overload accepting vendored {@link ConfigPayload} values. */
    public static byte[] compileSingleEntryPerButton(ConfigPayload[] payloads, boolean appendChecksum) {
        ButtonEntry[] entries = new ButtonEntry[payloads.length];
        for (int i = 0; i < payloads.length; i++) {
            entries[i] = new ButtonEntry(payloads[i].getHeader(), payloads[i].getData());
        }
        return compileSingleEntryPerButton(entries, appendChecksum);
    }

    // ====================================================================== CRC

    /** CRC32 (java.util.zip) over {@code data[off..off+len)} as an int for an LE trailer. */
    public static int computeCrc32(byte[] data, int off, int len) {
        CRC32 crc = new CRC32();
        crc.update(data, off, len);
        return (int) crc.getValue();
    }

    // ====================================================== dial-mode availability hook

    /**
     * The real physical watch-face (sub-eye) display modes. These are the only modes a
     * dial can physically show. {@code music} is intentionally absent — it is a phone-side
     * action, NOT a dial mode (ANDROID-PLAN §4.E; FINDINGS #19/#21b/#22).
     */
    public enum DialMode {
        ALERT,
        TIMEZONE_2,
        ALARM,
        DATE,
        TWENTY_FOUR_HOUR
    }

    /** Watch-face dial layouts (number of labeled sub-eye positions). */
    public enum DialModel {
        /** Q Commuter and similar: A=TIMEZONE_2, B=DATE, C=ALARM (no ALERT/24HR labels). */
        THREE_POSITION,
        /** Q Activist and similar: TIMEZONE_2, DATE, ALARM, ALERT, 24HR. */
        FIVE_POSITION
    }

    private static final Set<DialMode> THREE_POS_MODES =
            EnumSet.of(DialMode.TIMEZONE_2, DialMode.DATE, DialMode.ALARM);

    private static final Set<DialMode> FIVE_POS_MODES =
            EnumSet.allOf(DialMode.class);

    /**
     * The set of dial modes a given watch-face model supports. Pure lookup; emits no bytes
     * and mutates nothing. Callers use this to guard/grey-out unsupported toggle entries.
     */
    public static Set<DialMode> availableModes(DialModel model) {
        return switch (model) {
            case THREE_POSITION -> EnumSet.copyOf(THREE_POS_MODES);
            case FIVE_POSITION -> EnumSet.copyOf(FIVE_POS_MODES);
        };
    }

    /** Whether {@code mode} is supported on {@code model}. Pure guard, no byte effect. */
    public static boolean isModeAvailable(DialModel model, DialMode mode) {
        return availableModes(model).contains(mode);
    }
}
