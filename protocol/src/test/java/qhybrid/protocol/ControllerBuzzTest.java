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

    /** Reconstruct the uploaded file payload from the recorded 3dda0004 chunks. */
    private static byte[] reassemble(List<FakeBleTransport.Write> chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (FakeBleTransport.Write w : chunks) {
            out.write(w.data, 1, w.data.length - 1);
        }
        return out.toByteArray();
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

        // Accept the filter put, reassemble the uploaded filter bytes + CRC-confirm + close.
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(NOTIFICATION_FILTER_HANDLE));
        byte[] filterPayload = reassemble(t.writesTo(FakeBleTransport.UUID_CHAR_DATA));
        assertTrue(filterPayload.length > 0, "expected filter data chunks on 3dda0004");

        // The 32-byte filter entry's last byte is the vibration pattern (see AdapterFilePutTest).
        int fileLen = filterPayload.length - 12 - 4; // [12-byte header][file][crc32 = 4]
        assertEquals(32, fileLen, "one filter entry = 32 bytes");
        assertEquals(pattern, filterPayload[12 + 31], "filter carries the requested vibe pattern");

        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.crcConfirmFrame(NOTIFICATION_FILTER_HANDLE, filterPayload));
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.closeFrame(NOTIFICATION_FILTER_HANDLE));

        // 2. The play file (NOTIFICATION_PLAY 0x0900) is now queued. Clear the filter chunks so we
        //    can isolate the play payload, then drive its put to completion.
        t.clearWrites();
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(NOTIFICATION_PLAY_HANDLE));
        byte[] playPayload = reassemble(t.writesTo(FakeBleTransport.UUID_CHAR_DATA));
        assertTrue(playPayload.length > 0, "expected a NOTIFICATION_PLAY file-put (the buzz trigger)");

        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.crcConfirmFrame(NOTIFICATION_PLAY_HANDLE, playPayload));
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.closeFrame(NOTIFICATION_PLAY_HANDLE));
    }

    /** Flip the adapter into Fossil protocol mode without a live handshake. */
    private static void forceFossilProtocol(FossilQAdapter adapter) {
        adapter.detectProtocol("HW0.0.2.9r.v3");
        assertTrue(adapter.isFossilProtocol());
    }
}
