// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Test helper emulating the watch's side of a file-GET transfer over
 * 3dda0003 (control) / 3dda0004 (data), driving FileGetRawRequest to completion.
 *
 * <p>Sequence: type-1 header (with size) on 3dda0003 -> data chunk(s) on 3dda0004
 * (last chunk's seq byte has bit7 set) -> type-8 footer (with full-file CRC32) on
 * 3dda0003.
 */
public final class FileGetResponder {

    private FileGetResponder() {}

    public static final UUID CONTROL = FileTransferResponder.CONTROL;
    public static final UUID DATA = UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d");

    /**
     * Respond to a pending file-get for {@code handle} by delivering {@code fileData}
     * (the full raw file: 12-byte header + payload + 4-byte trailing CRC slot).
     *
     * @param minor file minor handle (e.g. 0x00)
     * @param major file major handle (e.g. 0x0b for DEVICE_INFO)
     */
    public static void respondToGet(FakeBleTransport t, byte minor, byte major, byte[] fileData) {
        // type-1 header: [0x01][minor][major][status=0][size(4 LE)]...
        ByteBuffer h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        h.put((byte) 0x01);
        h.put(minor);
        h.put(major);
        h.put((byte) 0x00);          // status SUCCESS
        h.putInt(fileData.length);   // size
        t.injectNotification(CONTROL, h.array());

        // single data chunk: [seq|0x80][fileData...]
        byte[] chunk = new byte[fileData.length + 1];
        chunk[0] = (byte) 0x80; // seq 0 + last-packet bit
        System.arraycopy(fileData, 0, chunk, 1, fileData.length);
        t.injectNotification(DATA, chunk);

        // type-8 footer: [0x08][handle(2 LE)][...][crc(4 LE)@offset8]
        CRC32 crc = new CRC32();
        crc.update(fileData);
        ByteBuffer f = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        f.put((byte) 0x08);
        f.putShort((short) (((major & 0xFF) << 8) | (minor & 0xFF)));
        f.put((byte) 0x00);
        f.putShort((short) 0);
        f.putShort((short) 0);
        f.putInt((int) crc.getValue());
        t.injectNotification(CONTROL, f.array());
    }

    /** Build an empty (16-byte) raw file: 12-byte header + 0 payload + 4-byte slot. */
    public static byte[] emptyRawFile() {
        return new byte[16];
    }
}
