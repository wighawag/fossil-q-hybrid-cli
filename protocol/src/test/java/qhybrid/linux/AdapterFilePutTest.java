// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.linux;

import qhybrid.protocol.FakeBleTransport;
import qhybrid.protocol.FileTransferResponder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end file-PUT regression through {@link FakeBleTransport}, exercising the
 * FilePutRawRequest state machine: type-3 accept -> chunked data on 3dda0004 ->
 * type-8 CRC confirm -> type-4 close -> onFilePut(true).
 *
 * <p>Also locks the notification-filter 32-byte entry layout and the
 * null-terminated package CRC (FINDINGS #17), driven through the real adapter
 * code path, so the re-own can't silently change either.
 */
public class AdapterFilePutTest {

    private static final short NOTIFICATION_FILTER_HANDLE = 0x0C00;

    /** Reconstruct the uploaded file payload from the recorded 3dda0004 chunks. */
    private static byte[] reassemble(List<FakeBleTransport.Write> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (FakeBleTransport.Write w : chunks) {
            // each chunk = [seqByte][payload...]
            out.write(w.data, 1, w.data.length - 1);
        }
        return out.toByteArray();
    }

    @Test
    void notificationFilterUpload_fullSequence() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);
        forceFossilProtocol(adapter);

        CompletableFuture<Boolean> done = new CompletableFuture<>();
        // Use the pattern-based upload (single 32-byte entry, deterministic).
        adapter.uploadNotificationFilterWithPattern((byte) 4, (short) 90, (short) 90);

        // The put request was written to 3dda0003. Inject the type-3 accept.
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(NOTIFICATION_FILTER_HANDLE));

        // Adapter has now written the data chunks to 3dda0004. Reassemble + CRC-confirm.
        byte[] payload = reassemble(t.writesTo(FakeBleTransport.UUID_CHAR_DATA));
        assertTrue(payload.length > 0, "expected data chunks on 3dda0004");

        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.crcConfirmFrame(NOTIFICATION_FILTER_HANDLE, payload));
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.closeFrame(NOTIFICATION_FILTER_HANDLE));

        // ---- Assertions on the uploaded payload (FilePutRequest framing) ----
        // payload = [handle(2 LE)][version(2 LE)][0(4)][len(4 LE)][file...][crc32c(4)]
        // file = single 32-byte notification filter entry.
        int fileLen = payload.length - 12 - 4;
        assertEquals(32, fileLen, "one filter entry = 32 bytes");

        byte[] entry = new byte[32];
        System.arraycopy(payload, 12, entry, 0, 32);
        assertFilterEntry(entry, "qhybrid.linux", (byte) 4, (short) 90, (short) 90);
    }

    /** Verify the 32-byte notification filter entry layout + null-terminated CRC. */
    private static void assertFilterEntry(byte[] e, String pkg, byte vibe, short hourDeg, short minDeg) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(e).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals((short) 30, b.getShort(0), "packet length field");
        assertEquals((byte) 0x04, e[2], "PACKAGE_NAME_CRC tag");
        assertEquals((byte) 4, e[3], "crc field length");
        int crc = b.getInt(4);
        assertEquals(nullTerminatedCrc(pkg), crc, "null-terminated package CRC");
        assertEquals((byte) 0x80, e[8], "GROUP_ID tag");
        assertEquals((byte) 0x00, e[10], "group=0");
        assertEquals((byte) 0xC1, e[11], "PRIORITY tag");
        assertEquals((byte) 0x00, e[13], "priority=0");
        assertEquals((byte) 0xC2, e[14], "HAND_MOVEMENT tag");
        assertEquals((byte) 10, e[15], "movement length");
        assertEquals(hourDeg, b.getShort(16), "hour degrees");
        assertEquals(minDeg, b.getShort(18), "minute degrees");
        assertEquals((short) -1, b.getShort(20), "subeye no-move");
        assertEquals((short) 10000, b.getShort(22), "duration 10000ms");
        assertEquals((short) -2, b.getShort(24), "subeye2 device-default");
        assertEquals((byte) 0xC4, e[26], "DISPLAY_CONFIG tag");
        assertEquals((byte) 0xC3, e[29], "VIBRATION tag");
        assertEquals(vibe, e[31], "vibe pattern");
    }

    private static int nullTerminatedCrc(String name) {
        byte[] n = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] w = new byte[n.length + 1];
        System.arraycopy(n, 0, w, 0, n.length);
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(w);
        return (int) c.getValue();
    }

    @Test
    void packageCrc_isDeterministic_andNullTerminated() {
        // Hardware/golden value captured from current code (FINDINGS #17 format).
        assertEquals(0x0F1E3BE9, nullTerminatedCrc("qhybrid.linux"));
        assertEquals(0xBDC750F4, nullTerminatedCrc("qhybrid.linux.call"));
        assertEquals(0x79511FC4, nullTerminatedCrc("qhybrid.linux.text"));
    }

    /** Flip the adapter into Fossil protocol mode without a live handshake. */
    private static void forceFossilProtocol(FossilQAdapter adapter) {
        adapter.detectProtocol("HW0.0.2.9r.v3");
        assertTrue(adapter.isFossilProtocol());
    }
}
