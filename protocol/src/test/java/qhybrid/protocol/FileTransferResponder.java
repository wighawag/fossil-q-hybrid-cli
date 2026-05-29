// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Test helper that emulates the watch's side of a file-PUT transfer over
 * 3dda0003 (control) / 3dda0004 (data), so the adapter's FilePutRawRequest
 * state machine can be driven to completion headlessly.
 *
 * <p>Usage: after a put request is written, call {@link #respondToPut} with the
 * full file payload that was uploaded (the 12-byte-header + data + CRC32C frame
 * FilePutRequest builds) — this synthesises the type-3 accept, then the type-8
 * CRC confirmation (with the correct full-file CRC32 the adapter expects), then
 * the type-4 close ack, in the right order, injecting each into the transport.
 */
public final class FileTransferResponder {

    private FileTransferResponder() {}

    /** Build a type-3 "accept" frame (5 bytes): [0x03][handleLo][handleHi][status=0][?]. */
    public static byte[] acceptFrame(short handle) {
        ByteBuffer b = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x03);
        b.putShort(handle);
        b.put((byte) 0x00); // status SUCCESS
        b.put((byte) 0x00);
        return b.array();
    }

    /**
     * Build a type-8 "CRC confirm" frame. The adapter checks handle at offset 1,
     * status at offset 3, and the full-file CRC32 at offset 8.
     *
     * @param handle      file handle (FileHandle.getHandle())
     * @param fullPayload the exact bytes uploaded via 3dda0004 chunks (i.e. the
     *                    FilePutRequest payload: header+data+crc32c)
     */
    public static byte[] crcConfirmFrame(short handle, byte[] fullPayload) {
        CRC32 crc = new CRC32();
        crc.update(fullPayload);
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x08);
        b.putShort(handle);
        b.put((byte) 0x00);     // status SUCCESS at offset 3
        b.putShort((short) 0);  // offsets 4-5
        b.putShort((short) 0);  // offsets 6-7
        b.putInt((int) crc.getValue()); // offset 8: full CRC
        return b.array();
    }

    /** Build a type-4 "close ack" frame (4 bytes): [0x04][handleLo][handleHi][status=0]. */
    public static byte[] closeFrame(short handle) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x04);
        b.putShort(handle);
        b.put((byte) 0x00); // status SUCCESS
        return b.array();
    }

    public static final UUID CONTROL = UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d");

    /**
     * Drive a complete put: inject accept, wait for the data chunks + close write,
     * then inject CRC-confirm and close-ack. Caller passes the exact uploaded
     * payload so the CRC matches what the adapter computed.
     */
    public static void respondToPut(FakeBleTransport t, short handle, byte[] fullPayload) {
        t.injectNotification(CONTROL, acceptFrame(handle));
        // After accept, the adapter writes the data chunks then waits for type-8.
        t.injectNotification(CONTROL, crcConfirmFrame(handle, fullPayload));
        // After type-8, the adapter writes the close (type 4) and waits for type-4 ack.
        t.injectNotification(CONTROL, closeFrame(handle));
    }
}
