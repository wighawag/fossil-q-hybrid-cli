// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each NOTIFICATION_PLAY (buzz / notification-play) file-PUT must open a ROTATING handle —
 * 0x0900, 0x0901, 0x0902, ... wrapping (% 255, never 0xFF) — exactly like the official app's
 * {@code FileHandleManager.getFileHandleToPut} for {@code FileType.NOTIFICATION}. Reusing a FIXED
 * 0x0900 fills the watch's small notification-file ring and the watch then rejects the open with
 * status {@code 0x86 = FIRMWARE_INTERNAL_ERROR_NOT_ENOUGH_MEMORY}. See FINDINGS
 * "rotate the NOTIFICATION_PLAY handle low byte".
 */
public class NotificationPlayHandleRotationTest {

    /** Collect the 16-bit handles of every PUT_FILE(3) open frame written on the control channel. */
    private static List<Short> putOpenHandles(FakeBleTransport t) {
        List<Short> handles = new ArrayList<>();
        for (FakeBleTransport.Write w : t.writesTo(FakeBleTransport.UUID_CHAR_CONTROL)) {
            if (w.data.length >= 3 && (w.data[0] & 0x0F) == 3) { // PUT_FILE open
                handles.add(ByteBuffer.wrap(w.data).order(ByteOrder.LITTLE_ENDIAN).getShort(1));
            }
        }
        return handles;
    }

    /** Drive ONE play put to completion so the strictly-serial queue advances to the next. */
    private static void completePlayPut(FakeBleTransport t, short handle) throws InterruptedException {
        // Wait for the open to be written, then accept → (data chunks) → EOF → VERIFY-success.
        for (int i = 0; i < 200 && !putOpenHandles(t).contains(handle); i++) Thread.sleep(5);
        int dataBaseline = t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size();
        t.injectNotification(FileTransferResponder.CONTROL, FileTransferResponder.acceptFrame(handle));
        for (int i = 0; i < 200 && t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size() <= dataBaseline; i++) {
            Thread.sleep(5);
        }
        // Reassemble the uploaded payload so the EOF_REACH carries the right size + CRC.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        List<FakeBleTransport.Write> all = t.writesTo(FakeBleTransport.UUID_CHAR_DATA);
        for (int i = dataBaseline; i < all.size(); i++) out.write(all.get(i).data, 1, all.get(i).data.length - 1);
        byte[] payload = out.toByteArray();
        t.injectNotification(FileTransferResponder.CONTROL, FileTransferResponder.eofReachFrame(handle, payload));
        t.injectNotification(FileTransferResponder.CONTROL, FileTransferResponder.verifyFrame(handle));
    }

    @Test
    void consecutivePlayPuts_rotateHandleLowByte() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilController controller = new FossilController(t);
        controller.adapter().detectProtocol("HW0.0.2.9r.v3");
        assertTrue(controller.adapter().isFossilProtocol());

        // Three play-by-package puts (single put each, no filter), each driven to completion so the
        // serial queue advances. They must open 0x0900, 0x0901, 0x0902 — NOT three times 0x0900.
        for (int i = 0; i < 3; i++) {
            controller.adapter().playNotificationByPackageName("fossil-q");
            completePlayPut(t, (short) (0x0900 + i));
        }

        List<Short> opens = putOpenHandles(t);
        assertEquals(3, opens.size(), "expected exactly three NOTIFICATION_PLAY opens");
        assertEquals((short) 0x0900, opens.get(0), "1st play put opens 0x0900");
        assertEquals((short) 0x0901, opens.get(1), "2nd play put opens 0x0901 (rotated)");
        assertEquals((short) 0x0902, opens.get(2), "3rd play put opens 0x0902 (rotated)");
    }

    @Test
    void rotationWrapsAtLowByteFF_back_toZero() {
        // The low-byte counter mirrors the official app: increment % 255, so it cycles
        // 0x0900..0x09FE and wraps to 0x0900 (it never emits 0x09FF). Verify the pure arithmetic.
        int index = 0;
        short first = 0;
        short last = 0;
        for (int i = 0; i < 255; i++) {
            short handle = (short) ((0x09 << 8) | (index & 0xFF));
            if (i == 0) first = handle;
            if (i == 254) last = handle;
            assertNotEquals((short) 0x09FF, handle, "must never emit the 0xFF low byte");
            index = (index + 1) % 0xFF;
        }
        assertEquals((short) 0x0900, first, "starts at 0x0900");
        assertEquals((short) 0x09FE, last, "reaches 0x09FE before wrapping");
        assertEquals(0, index, "after 255 increments the index has wrapped back to 0 (next = 0x0900)");
    }
}
