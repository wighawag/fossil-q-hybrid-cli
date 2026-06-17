// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug 3 regression lock: when a NOTIFICATION_PLAY (buzz) file-PUT open is rejected with status
 * {@code 0x86 = FIRMWARE_INTERNAL_ERROR_NOT_ENOUGH_MEMORY} (the watch's notification-file area is
 * FULL of orphaned play files), the adapter must NOT fail fast. It must reclaim the area by
 * DELETE-SWEEPING the PRIOR play minors (the orphans that actually exist) — a bounded run of
 * {@code DELETE_FILE(0x0B)} frames for the minors just below the current one on major 0x09 — then
 * RE-OPEN the original (rotated) play handle once. If the watch then accepts the re-opened put, the
 * buzz self-heals with no manual re-provision.
 *
 * <p>On-device (2026-06-17) the watch returns NOT_FOUND for the about-to-open rotated index AND for
 * the 0x09FF area handle, but SUCCESS for a handle that exists (e.g. the activity file 0x0101). So
 * the recovery targets the PRIOR USED minors, not the fresh index or 0x09FF. See FINDINGS "Bug 3".
 */
public class NotificationPlayMemoryRecoveryTest {

    private static final short PLAY_HANDLE = (short) 0x0900;
    private static final int PLAY_MAJOR = 0x09;
    /** Must match FilePutRawRequest.MEMORY_RECOVERY_SWEEP. */
    private static final int SWEEP = 16;

    /** Every control-channel write whose op (low nibble of byte 0) equals [op]. */
    private static List<FakeBleTransport.Write> controlWritesOfOp(FakeBleTransport t, int op) {
        List<FakeBleTransport.Write> out = new ArrayList<>();
        for (FakeBleTransport.Write w : t.writesTo(FakeBleTransport.UUID_CHAR_CONTROL)) {
            if (w.data.length >= 1 && (w.data[0] & 0x0F) == op) out.add(w);
        }
        return out;
    }

    private static short handleOf(FakeBleTransport.Write w) {
        return ByteBuffer.wrap(w.data).order(ByteOrder.LITTLE_ENDIAN).getShort(1);
    }

    @Test
    void playPutWedgedWith0x86_deletesThenReopensAndSucceeds() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilController controller = new FossilController(t);
        controller.adapter().detectProtocol("HW0.0.2.9r.v3");
        assertTrue(controller.adapter().isFossilProtocol());

        // Fire one buzz (single play put, no filter) → it opens 0x0900.
        controller.adapter().playNotificationByPackageName("fossil-q");

        // Wait for the first PUT_FILE(3) open on the play handle.
        for (int i = 0; i < 200 && controlWritesOfOp(t, 3).isEmpty(); i++) Thread.sleep(5);
        assertFalse(controlWritesOfOp(t, 3).isEmpty(), "first play put must open the handle");
        assertEquals(PLAY_HANDLE, handleOf(controlWritesOfOp(t, 3).get(0)), "first open is 0x0900");

        // The watch rejects the open: area FULL → status 0x86 (NOT_ENOUGH_MEMORY).
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(PLAY_HANDLE, (byte) 0x86));

        // RECOVERY: the adapter must DELETE-SWEEP the prior play minors, then re-open PUT(3).
        for (int i = 0; i < 200 && controlWritesOfOp(t, 0x0B).size() < SWEEP; i++) Thread.sleep(5);
        List<FakeBleTransport.Write> deletes = controlWritesOfOp(t, 0x0B);
        assertEquals(SWEEP, deletes.size(), "a bounded sweep of prior minors is deleted on 0x86 recovery");
        for (FakeBleTransport.Write d : deletes) {
            short h = handleOf(d);
            assertEquals(PLAY_MAJOR, (h >> 8) & 0xFF, "every sweep delete is on the play major 0x09");
            assertNotEquals(PLAY_HANDLE, h, "the sweep must NOT delete the about-to-open index (0x0900)");
            assertNotEquals((short) 0x09FF, h, "the sweep must NOT target the inert area handle 0x09FF");
        }

        // The re-opened PUT(3): wait for a SECOND open frame on the play handle.
        for (int i = 0; i < 200 && controlWritesOfOp(t, 3).size() < 2; i++) Thread.sleep(5);
        List<FakeBleTransport.Write> opens = controlWritesOfOp(t, 3);
        assertEquals(2, opens.size(), "the open is retried exactly once after the delete");
        assertEquals(PLAY_HANDLE, handleOf(opens.get(1)), "re-open uses the same play handle");

        // The watch acks our sweep deletes (0x8B per minor) — must be ignored (suppressed during
        // recovery). Then the re-opened put is ACCEPTED (area reclaimed) → drive to VERIFY.
        for (FakeBleTransport.Write d : deletes) {
            t.injectNotification(FileTransferResponder.CONTROL, deleteAckFrame(handleOf(d)));
        }

        int dataBaseline = t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size();
        t.injectNotification(FileTransferResponder.CONTROL, FileTransferResponder.acceptFrame(PLAY_HANDLE));
        for (int i = 0; i < 200 && t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size() <= dataBaseline; i++) {
            Thread.sleep(5);
        }
        assertTrue(t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size() > dataBaseline,
                "after the reclaimed re-open is accepted, the data chunks must flow");

        // Reassemble the uploaded payload so EOF_REACH carries the right size + CRC, then VERIFY.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        List<FakeBleTransport.Write> all = t.writesTo(FakeBleTransport.UUID_CHAR_DATA);
        for (int i = dataBaseline; i < all.size(); i++) out.write(all.get(i).data, 1, all.get(i).data.length - 1);
        byte[] payload = out.toByteArray();
        t.injectNotification(FileTransferResponder.CONTROL, FileTransferResponder.eofReachFrame(PLAY_HANDLE, payload));
        t.injectNotification(FileTransferResponder.CONTROL, FileTransferResponder.verifyFrame(PLAY_HANDLE));
    }

    @Test
    void recoveryIsBoundedToOneAttempt_secondMemoryRejectFailsFast() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilController controller = new FossilController(t);
        controller.adapter().detectProtocol("HW0.0.2.9r.v3");
        assertTrue(controller.adapter().isFossilProtocol());

        controller.adapter().playNotificationByPackageName("fossil-q");
        for (int i = 0; i < 200 && controlWritesOfOp(t, 3).isEmpty(); i++) Thread.sleep(5);

        // First 0x86 → one sweep (SWEEP deletes) + re-open.
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(PLAY_HANDLE, (byte) 0x86));
        for (int i = 0; i < 200 && controlWritesOfOp(t, 3).size() < 2; i++) Thread.sleep(5);
        assertEquals(SWEEP, controlWritesOfOp(t, 0x0B).size(), "first 0x86 triggers one bounded sweep");

        // SECOND 0x86 on the re-opened put → recovery is exhausted; must NOT sweep again.
        t.injectNotification(FileTransferResponder.CONTROL,
                FileTransferResponder.acceptFrame(PLAY_HANDLE, (byte) 0x86));
        Thread.sleep(60);
        assertEquals(SWEEP, controlWritesOfOp(t, 0x0B).size(),
                "recovery is bounded: a second 0x86 must NOT trigger another sweep");
        assertEquals(2, controlWritesOfOp(t, 3).size(),
                "recovery is bounded: no third open after the second 0x86 (fails fast)");
    }

    /** DELETE_FILE/erase ack {@code 0x8B} (4 bytes): [0x8B][handleLo][handleHi][status=0]. */
    private static byte[] deleteAckFrame(short handle) {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x8B);
        b.putShort(handle);
        b.put((byte) 0x00);
        return b.array();
    }
}
