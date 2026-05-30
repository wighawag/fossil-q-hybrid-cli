// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Test helper that emulates the watch's side of a file-PUT transfer over
 * 3dda0003 (control) / 3dda0004 (data), so the adapter's {@code FilePutRawRequest}
 * state machine can be driven to completion headlessly.
 *
 * <p>WP-FILEPUT-RELIABLE: this now mirrors the REAL firmware handshake (decoded from the official
 * Fossil app):
 * <ol>
 *   <li><b>PUT accept</b> {@code 0x83} (5 bytes).</li>
 *   <li>(adapter writes the data chunks on 3dda0004)</li>
 *   <li><b>EOF_REACH</b> {@code 0x88} (12 bytes) carrying {@code sizeWritten} at offset 4 and the
 *       watch's {@code CRC32} of the received payload at offset 8.</li>
 *   <li>(adapter sends VERIFY_FILE(4))</li>
 *   <li><b>VERIFY response</b> {@code 0x84} (4 bytes) with SUCCESS status — THIS completes the put.</li>
 * </ol>
 *
 * <p>The put completes ONLY on the {@code 0x84} VERIFY-success — NOT on the {@code 0x88} EOF_REACH.
 */
public final class FileTransferResponder {

    private FileTransferResponder() {}

    /** Build a PUT-accept frame {@code 0x83} (5 bytes): [0x83][handleLo][handleHi][status=0][socket?]. */
    public static byte[] acceptFrame(short handle) {
        ByteBuffer b = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x83);
        b.putShort(handle);
        b.put((byte) 0x00); // status SUCCESS
        b.put((byte) 0x00); // proposed socket/characteristic id
        return b.array();
    }

    /**
     * Build an EOF_REACH frame {@code 0x88} (12 bytes). The adapter reads handle at offset 1,
     * status at offset 3, {@code sizeWritten} at offset 4, and the watch's CRC32 at offset 8.
     *
     * @param handle      file handle (FileHandle.getHandle())
     * @param fullPayload the exact bytes uploaded via 3dda0004 chunks (i.e. the
     *                    {@code FilePutRequest} payload: header + file + crc32c). The watch reports
     *                    {@code sizeWritten == fullPayload.length} and {@code CRC32(fullPayload)}.
     */
    public static byte[] eofReachFrame(short handle, byte[] fullPayload) {
        return eofReachFrame(handle, fullPayload.length, crc32(fullPayload), (byte) 0x88);
    }

    /**
     * Build an EOF_REACH/WAITING_REQUEST frame with explicit sizeWritten + crc (for the
     * partial / CRC-mismatch resume tests). {@code op} is the raw response byte (0x88 or 0x8A).
     */
    public static byte[] eofReachFrame(short handle, long sizeWritten, int crc, byte op) {
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.put(op);
        b.putShort(handle);
        b.put((byte) 0x00);              // status SUCCESS at offset 3
        b.putInt((int) sizeWritten);     // sizeWritten at offset 4
        b.putInt(crc);                   // watch CRC32 at offset 8
        return b.array();
    }

    /** Build a VERIFY-success frame {@code 0x84} (4 bytes): [0x84][handleLo][handleHi][status=0]. */
    public static byte[] verifyFrame(short handle) {
        return verifyFrame(handle, (byte) 0x00);
    }

    /** Build a VERIFY response frame {@code 0x84} (4 bytes) with an explicit status byte. */
    public static byte[] verifyFrame(short handle, byte status) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x84);
        b.putShort(handle);
        b.put(status);
        return b.array();
    }

    /** Build an ABORT_FILE frame {@code 0x89} (4 bytes): [0x89][handleLo][handleHi][status]. */
    public static byte[] abortFrame(short handle) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x89);
        b.putShort(handle);
        b.put((byte) 0x01); // any non-SUCCESS status
        return b.array();
    }

    public static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    public static final UUID CONTROL = UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d");

    /**
     * Drive a complete put the way the real watch does: inject accept, wait for the data chunks,
     * then inject EOF_REACH (correct sizeWritten + CRC32) and finally the VERIFY-success {@code 0x84}.
     * Caller passes the exact uploaded payload so the CRC matches what the adapter computed.
     */
    public static void respondToPut(FakeBleTransport t, short handle, byte[] fullPayload) {
        t.injectNotification(CONTROL, acceptFrame(handle));
        // After accept, the adapter writes the data chunks then waits for EOF_REACH.
        t.injectNotification(CONTROL, eofReachFrame(handle, fullPayload));
        // After EOF_REACH, the adapter sends VERIFY_FILE(4) and waits for its 0x84 success.
        t.injectNotification(CONTROL, verifyFrame(handle));
    }
}
