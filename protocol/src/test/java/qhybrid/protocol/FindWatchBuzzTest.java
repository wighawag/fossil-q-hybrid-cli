// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP-BUZZ-DURATION-NOHANDS — contract test for the "find watch" continuous buzz.
 *
 * <p>{@link FossilController#findWatchBuzz()} / {@link FossilController#stopFindWatchBuzz()} drive
 * the call-vibration characteristic ({@code 3dda0005}) with a single tiny write each — NO
 * NOTIFICATION_FILTER (0x0C00) and NO NOTIFICATION_PLAY (0x0900) file-put. This is the buzz path
 * that moves no hands and is therefore not subject to the hand-return lockout.
 */
public class FindWatchBuzzTest {

    @Test
    void findWatchBuzz_writesStartFrameToCallChar_noFilePut() {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilController controller = new FossilController(t);

        int dataBefore = t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size();
        int ctrlBefore = t.writesTo(FakeBleTransport.UUID_CHAR_CONTROL).size();

        controller.findWatchBuzz();

        // The start buzz is a single write to the CALL characteristic (3dda0005): 01 04 30 75 00 00.
        List<FakeBleTransport.Write> callWrites = t.writesTo(FakeBleTransport.UUID_CHAR_CALL);
        assertFalse(callWrites.isEmpty(), "findWatchBuzz must write to the call characteristic");
        byte[] start = callWrites.get(callWrites.size() - 1).data;
        assertArrayEquals(
                new byte[]{(byte) 0x01, (byte) 0x04, (byte) 0x30, (byte) 0x75, (byte) 0x00, (byte) 0x00},
                start, "find-device start frame");

        // No file transfer happened (no data/control file-put traffic).
        assertEquals(dataBefore, t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size(),
                "findWatchBuzz must NOT do a file-put (no data-channel writes)");
        assertEquals(ctrlBefore, t.writesTo(FakeBleTransport.UUID_CHAR_CONTROL).size(),
                "findWatchBuzz must NOT open a file-put on the control channel");
    }

    @Test
    void stopFindWatchBuzz_writesStopFrameToCallChar() {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilController controller = new FossilController(t);

        controller.stopFindWatchBuzz();

        List<FakeBleTransport.Write> callWrites = t.writesTo(FakeBleTransport.UUID_CHAR_CALL);
        assertFalse(callWrites.isEmpty(), "stopFindWatchBuzz must write to the call characteristic");
        byte[] stop = callWrites.get(callWrites.size() - 1).data;
        assertArrayEquals(new byte[]{(byte) 0x02, (byte) 0x05, (byte) 0x04}, stop,
                "stop call vibration frame");
    }
}
