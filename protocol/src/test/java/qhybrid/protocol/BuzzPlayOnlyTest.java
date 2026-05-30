// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import qhybrid.protocol.requests.fossil.notification.BuzzPatterns;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP-BUZZ-PLAYONLY (sub-part 1) — contract test: {@link FossilController#buzzPlayOnly(int)} performs
 * EXACTLY ONE file-put, a {@code NOTIFICATION_PLAY} (0x0900) carrying the reserved pattern's package
 * CRC, and NO {@code NOTIFICATION_FILTER} (0x0C00) put. This is the single-put buzz that the reserved
 * filter (uploaded once at connect) makes possible.
 */
public class BuzzPlayOnlyTest {

    private static final short NOTIFICATION_FILTER_HANDLE = 0x0C00;
    private static final short NOTIFICATION_PLAY_HANDLE = 0x0900;

    private static byte[] reassembleFrom(FakeBleTransport t, int fromIndex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<FakeBleTransport.Write> all = t.writesTo(FakeBleTransport.UUID_CHAR_DATA);
        for (int i = fromIndex; i < all.size(); i++) {
            byte[] d = all.get(i).data;
            out.write(d, 1, d.length - 1);
        }
        return out.toByteArray();
    }

    private static boolean awaitChunks(FakeBleTransport t, int baseline) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size() > baseline) return true;
            Thread.sleep(10);
        }
        return false;
    }

    @Test
    void buzzPlayOnly_sendsSinglePlayPut_noFilterPut() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilController controller = new FossilController(t);
        forceFossilProtocol(controller.adapter());

        int baseline = t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size();
        controller.buzzPlayOnly(5); // strong single

        // The ONLY put is a NOTIFICATION_PLAY (0x0900). Accept it, drive it to VERIFY-success.
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(NOTIFICATION_PLAY_HANDLE));
        assertTrue(awaitChunks(t, baseline), "expected a NOTIFICATION_PLAY put");
        byte[] playPayload = reassembleFrom(t, baseline);
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.eofReachFrame(NOTIFICATION_PLAY_HANDLE, playPayload));
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.verifyFrame(NOTIFICATION_PLAY_HANDLE));

        // No filter (0x0C00) put was opened on the control channel: assert no PUT_FILE open carries
        // the filter handle.
        for (FakeBleTransport.Write w : t.writesTo(FakeBleTransport.UUID_CHAR_CONTROL)) {
            if (w.data.length >= 3 && (w.data[0] & 0x0F) == 3) { // PUT_FILE open frame
                short h = ByteBuffer.wrap(w.data).order(ByteOrder.LITTLE_ENDIAN).getShort(1);
                assertNotEquals(NOTIFICATION_FILTER_HANDLE, h,
                        "buzzPlayOnly must NOT upload a NOTIFICATION_FILTER (0x0C00)");
            }
        }

        // The play file's packageCrc (at payload offset 12 + 16, the second data int) == reserved
        // pattern-5 CRC. Layout: [12-byte file header][play file...]. The play file's data ints are
        // messageId then packageCrc, after its own 12-byte length-buffer header.
        // play file starts at payload[12]; packageCrc is the 2nd int after the 12-byte lbl header.
        ByteBuffer pb = ByteBuffer.wrap(playPayload).order(ByteOrder.LITTLE_ENDIAN);
        int playFileStart = 12;                 // after FilePutRequest's [handle+ver+0+len] header
        int packageCrc = pb.getInt(playFileStart + 12 + 4); // lbl header (12) + messageId (4)
        assertEquals(BuzzPatterns.crcForPattern(5), packageCrc,
                "play file packageCrc must match reserved buzz5 package CRC");
    }

    private static void forceFossilProtocol(FossilQAdapter adapter) {
        adapter.detectProtocol("HW0.0.2.9r.v3");
        assertTrue(adapter.isFossilProtocol());
    }
}
