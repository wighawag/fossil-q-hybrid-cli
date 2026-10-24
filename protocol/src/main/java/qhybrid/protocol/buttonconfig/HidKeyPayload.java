// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.buttonconfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Builds a button-config entry that makes the watch emit a standard BLE HID Consumer Control key
 * press (HID-over-GATT report on char {@code 0x2a4d}) instead of a proprietary {@code 3dda0006}
 * event. See FINDINGS.md §26a/b/c.
 *
 * <p>The entry is the captured SELFIE / "take a picture" micro-app (declarationId 4097 =
 * {@code 0x1001}, header {@code 01 01 10 00}). Its body is byte-identical to
 * {@link ConfigPayload#VOLUME_UP} except the declarationId, and it carries the HID consumer usage
 * the button emits in two {@code 8c 01 <usage>} fields. This builder takes the VOLUME_UP template,
 * swaps in the SELFIE declarationId, writes the chosen usage into both fields and recomputes the
 * trailing per-entry CRC32 (LE, over every preceding byte of the entry).
 *
 * <p><b>Confirmation status:</b> {@link HidKey#VOLUME_UP} reproduces the official app's captured
 * take-picture entry exactly (on-wire confirmed). The other keys use the same template with a
 * different usage byte, which is the UNCONFIRMED hypothesis of FINDINGS §26c: the firmware may
 * ignore or reject a usage other than {@code E9} for the SELFIE declarationId. They are limited to
 * the usages the watch's own HID Report Map advertises (Report ID 2), so no invented usages.
 */
public final class HidKeyPayload {

    private HidKeyPayload() {}

    /** HID consumer keys the watch's Report Map (ID 2) advertises. */
    public enum HidKey {
        NEXT_TRACK(0xB5, "next track"),
        PREVIOUS_TRACK(0xB6, "previous track"),
        PLAY_PAUSE(0xCD, "play/pause"),
        VOLUME_UP(0xE9, "volume up"),
        VOLUME_DOWN(0xEA, "volume down"),
        MUTE(0xE2, "mute");

        private final int usage;
        private final String label;

        HidKey(int usage, String label) {
            this.usage = usage;
            this.label = label;
        }

        /** HID Consumer page (0x0C) usage id. */
        public int usage() { return usage; }

        public String label() { return label; }

        /** True only for the usage confirmed on the wire (the official take-picture entry). */
        public boolean confirmed() { return this == VOLUME_UP; }
    }

    /** SELFIE micro-app declarationId (4097). */
    public static final int SELFIE_DECLARATION_ID = 0x1001;

    /** Entry header: type 0x01 + declarationId LE + 0x00. */
    public static byte[] header() {
        return new byte[]{0x01, (byte) (SELFIE_DECLARATION_ID & 0xFF), (byte) (SELFIE_DECLARATION_ID >> 8), 0x00};
    }

    /** Offsets (in the entry data) of the two HID usage bytes ({@code 8c 01 <usage>}). */
    static final int USAGE_OFFSET_1 = 53;
    static final int USAGE_OFFSET_2 = 66;
    /** Offset of the declarationId (LE short) inside the entry data. */
    static final int DECL_ID_OFFSET = 3;

    /** The captured VOLUME_UP entry (FINDINGS §26b), used as the template. CRC is recomputed. */
    private static final String TEMPLATE_HEX =
            "0100010412" + "5e000000010006000101010300" + "05011d008501f600008501420200850143030085014804"
                    + "0008011e000100020d008c01e9000193000101" + "00030b008c01e90000930001040a008c0100000101"
                    + "00fe080093000201" + "00" + "c6b2cbac";

    /** Build the entry data for {@code key} with an explicit declarationId (used by tests). */
    static byte[] data(int declarationId, int usage) {
        byte[] d = hex(TEMPLATE_HEX);
        d[DECL_ID_OFFSET] = (byte) (declarationId & 0xFF);
        d[DECL_ID_OFFSET + 1] = (byte) (declarationId >> 8);
        d[USAGE_OFFSET_1] = (byte) usage;
        d[USAGE_OFFSET_2] = (byte) usage;
        CRC32 crc = new CRC32();
        crc.update(d, 0, d.length - 4);
        ByteBuffer.wrap(d, d.length - 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) crc.getValue());
        return d;
    }

    /** Entry data for a SELFIE button that emits {@code key} as a HID consumer key press. */
    public static byte[] data(HidKey key) {
        return data(SELFIE_DECLARATION_ID, key.usage());
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
