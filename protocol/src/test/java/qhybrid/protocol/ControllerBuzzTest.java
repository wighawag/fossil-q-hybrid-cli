// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP-BUZZTEST (sub-part 1) — contract test for the new {@link FossilController#buzz(int)} /
 * {@link FossilController#buzz(int, int, int)} one-shot "vibrate the watch now" passthrough.
 *
 * <p>The buzz passthrough forwards to {@link FossilQAdapter#playNotificationWithPattern} which the
 * existing {@link AdapterFilePutTest} already exercises at the byte level. This test drives the
 * full play path through the {@link FakeBleTransport} / {@link FileTransferResponder} golden
 * harness to prove that calling {@code controller.buzz(pattern)} actually performs the real buzz
 * wire sequence:
 *   <ol>
 *     <li>a NOTIFICATION_FILTER (0x0C00) file-put carrying the <b>requested vibration pattern</b>
 *         (so the watch buzzes with that pattern), then</li>
 *     <li>a NOTIFICATION_PLAY (0x0900) file-put that triggers the vibration.</li>
 *   </ol>
 *
 * <p>Invents NO new wire bytes — same path as the CLI {@code notify}/{@code notify-test} commands.
 * Pattern bytes (hardware-tested): {@code 1 = CALL (triple)}, {@code 5 = ONE_SHORT_VIBE (single)}.
 */
public class ControllerBuzzTest {

    private static final short NOTIFICATION_FILTER_HANDLE = 0x0C00;
    private static final short NOTIFICATION_PLAY_HANDLE = 0x0900;

    /** Reassemble the uploaded payload from the 3dda0004 data chunks recorded since [fromIndex]. */
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
    void buzzSingle_uploadsFilterWithPattern5_thenPlays() throws Exception {
        assertBuzzPerformsFilterThenPlay((byte) 5); // ONE_SHORT_VIBE (strong single)
    }

    @Test
    void buzzTriple_uploadsFilterWithPattern1_thenPlays() throws Exception {
        assertBuzzPerformsFilterThenPlay((byte) 1); // CALL (triple)
    }

    /** Drive the full buzz wire sequence and assert filter-then-play with the requested pattern. */
    private void assertBuzzPerformsFilterThenPlay(byte pattern) throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilController controller = new FossilController(t);
        forceFossilProtocol(controller.adapter());

        // 1. buzz() kicks the filter file-put first (NOTIFICATION_FILTER 0x0C00).
        controller.buzz(pattern);

        // Accept the filter put, reassemble the uploaded filter bytes.
        int filterBaseline = t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size();
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(NOTIFICATION_FILTER_HANDLE));
        assertTrue(awaitChunks(t, filterBaseline), "expected filter data chunks on 3dda0004");
        byte[] filterPayload = reassembleFrom(t, filterBaseline);

        // The 32-byte filter entry's last byte is the vibration pattern (see AdapterFilePutTest).
        int fileLen = filterPayload.length - 12 - 4; // [12-byte header][file][crc32 = 4]
        assertEquals(32, fileLen, "one filter entry = 32 bytes");
        assertEquals(pattern, filterPayload[12 + 31], "filter carries the requested vibe pattern");

        // 2. The CRC-confirm COMPLETES the filter put (WP-BUZZTEST: no type-4 close-ack on this
        //    firmware) and the strictly-serial queue advances to the NOTIFICATION_PLAY put. Capture
        //    the play chunks AFTER this cascade (do NOT inject a closeFrame — it would land on the
        //    play put with the wrong handle).
        int playBaseline = t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size();
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.crcConfirmFrame(NOTIFICATION_FILTER_HANDLE, filterPayload));

        // 3. The play file (NOTIFICATION_PLAY 0x0900) is now the current put. Accept it + confirm.
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(NOTIFICATION_PLAY_HANDLE));
        assertTrue(awaitChunks(t, playBaseline),
                "expected a NOTIFICATION_PLAY file-put (the buzz trigger) after the filter completed");
        byte[] playPayload = reassembleFrom(t, playBaseline);

        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.crcConfirmFrame(NOTIFICATION_PLAY_HANDLE, playPayload));
    }

    /** Flip the adapter into Fossil protocol mode without a live handshake. */
    private static void forceFossilProtocol(FossilQAdapter adapter) {
        adapter.detectProtocol("HW0.0.2.9r.v3");
        assertTrue(adapter.isFossilProtocol());
    }
}
