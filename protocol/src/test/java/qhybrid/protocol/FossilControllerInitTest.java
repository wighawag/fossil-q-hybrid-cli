// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import qhybrid.protocol.model.NotificationFilterEntry;
import qhybrid.protocol.model.SyncSettings;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives a FULL init through the {@link FossilController} façade with
 * {@link SyncSettings} <em>passed in</em> (no disk loading), and asserts the
 * config-sync and notification-filter file bytes are exactly what the adapter
 * produced before the decoupling — the WP's "same bytes when settings are
 * injected" regression gate.
 */
public class FossilControllerInitTest {

    private static final byte CONFIG_MAJOR = 0x08;   // CONFIGURATION 0x0800
    private static final short CONFIG_HANDLE = 0x0800;
    private static final byte FILTER_MAJOR = 0x0C;   // NOTIFICATION_FILTER 0x0C00
    private static final short FILTER_HANDLE = 0x0C00;

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    private static boolean awaitCond(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return c.getAsBoolean();
    }

    /** Reassemble a put payload from the 3dda0004 chunks captured since {@code fromIndex}. */
    private static byte[] reassembleFrom(FakeBleTransport t, int fromIndex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<FakeBleTransport.Write> all = t.writesTo(FakeBleTransport.UUID_CHAR_DATA);
        for (int i = fromIndex; i < all.size(); i++) {
            byte[] d = all.get(i).data;
            out.write(d, 1, d.length - 1);
        }
        return out.toByteArray();
    }

    /**
     * Respond to a single put: accept -> (chunks arrive) -> crc confirm. The put COMPLETES on the
     * type-8 CRC-confirm (WP-BUZZTEST: this firmware never sends a type-4 close-ack), which
     * advances the queue to the next put. We deliberately do NOT inject a type-4 closeFrame: it
     * would now land on the NEXT put (already current after the cascade) and be mis-read as that
     * put's close with the wrong handle.
     */
    private static byte[] completePut(FakeBleTransport t, short handle, int dataChunkBaseline) throws InterruptedException {
        t.injectNotification(FileTransferResponder.CONTROL, FileTransferResponder.acceptFrame(handle));
        // wait for chunks to be written
        assertTrue(awaitCond(() -> t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size() > dataChunkBaseline, 2000),
                "expected data chunks for handle 0x" + Integer.toHexString(handle & 0xFFFF));
        byte[] payload = reassembleFrom(t, dataChunkBaseline);
        t.injectNotification(FileTransferResponder.CONTROL, FileTransferResponder.crcConfirmFrame(handle, payload));
        return payload;
    }

    @Test
    void fullInit_withInjectedSettings_producesExpectedConfigAndFilterBytes() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilController c = new FossilController(t);

        SyncSettings settings = new SyncSettings()
                .stepGoal(10000)
                .vibrationStrength(100)
                .secondTimezone(330);
        settings.addFilterEntry(new NotificationFilterEntry("qhybrid.linux", (byte) 4, (short) 90, (short) 90));
        c.setSyncSettings(settings);

        AtomicBoolean configSynced = new AtomicBoolean(false);
        c.onConfigSynced(() -> configSynced.set(true));

        Thread initThread = new Thread(() -> c.init(true), "test-fullinit");
        initThread.setDaemon(true);
        initThread.start();

        // 1) GetDeviceInfoRequest -> respond empty device-info file (handle 0x0b00).
        assertTrue(awaitCond(() -> !t.writesTo(FakeBleTransport.UUID_CHAR_CONTROL).isEmpty(), 2000));
        FileGetResponder.respondToGet(t, (byte) 0x00, (byte) 0x0b, FileGetResponder.emptyRawFile());

        // 2) Auth handshake: 01 07 -> inject already-authorized (03 07 01) to skip button press.
        assertTrue(awaitCond(() -> !t.writesTo(FakeBleTransport.UUID_CHAR_CALL).isEmpty(), 2000));
        assertArrayEquals(b(0x01, 0x07), t.writesTo(FakeBleTransport.UUID_CHAR_CALL).get(0).data);
        t.injectNotification(FakeBleTransport.UUID_CHAR_CALL, b(0x03, 0x07, 0x01));

        // 3) Config sync put (CONFIGURATION 0x0800).
        int chunksBefore = t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size();
        // wait for the config put request (a new control write of type-3 put) — accept it.
        Thread.sleep(50);
        byte[] configPayload = completePut(t, CONFIG_HANDLE, chunksBefore);

        // 4) Notification filter put (NOTIFICATION_FILTER 0x0C00).
        int chunksBefore2 = t.writesTo(FakeBleTransport.UUID_CHAR_DATA).size();
        byte[] filterPayload = completePut(t, FILTER_HANDLE, chunksBefore2);

        // init should finish
        assertTrue(awaitCond(() -> configSynced.get(), 2000), "onConfigSynced must fire");

        // ---- Assert config file bytes (strip 12-byte header + 4-byte CRC32C) ----
        byte[] configFile = stripFileFrame(configPayload);
        // TLV: stepGoal(0x03,4,10000) vibe(0x0A,1,100) time(0x0C,8,...) secondTz(0x11,2,330)
        // We assert the deterministic items; the time item's epoch is dynamic so check its id+len only.
        assertConfigContains(configFile, b(0x03, 0x00, 0x04, 0x10, 0x27, 0x00, 0x00)); // step goal 10000
        assertConfigContains(configFile, b(0x0A, 0x00, 0x01, 0x64));                   // vibe 100
        assertConfigContains(configFile, b(0x11, 0x00, 0x02, 0x4A, 0x01));             // secondTz 330
        // time item present: id 0x0C, len 8
        assertConfigContains(configFile, b(0x0C, 0x00, 0x08));

        // ---- Assert filter file bytes ----
        byte[] filterFile = stripFileFrame(filterPayload);
        assertEquals(32, filterFile.length, "one filter entry");
        ByteBuffer fb = ByteBuffer.wrap(filterFile).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x0F1E3BE9, fb.getInt(4), "qhybrid.linux null-terminated CRC");
        assertEquals((byte) 4, filterFile[31], "vibe pattern DEFAULT");
    }

    private static byte[] stripFileFrame(byte[] payload) {
        // payload = [handle(2)][version(2)][0(4)][len(4)][file...][crc32c(4)]
        int fileLen = payload.length - 12 - 4;
        byte[] file = new byte[fileLen];
        System.arraycopy(payload, 12, file, 0, fileLen);
        return file;
    }

    /** Assert that {@code needle} appears as a contiguous run in {@code haystack}. */
    private static void assertConfigContains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return; // found
        }
        fail("config file missing TLV " + FakeBleTransport.hex(needle)
                + " in " + FakeBleTransport.hex(haystack));
    }
}
