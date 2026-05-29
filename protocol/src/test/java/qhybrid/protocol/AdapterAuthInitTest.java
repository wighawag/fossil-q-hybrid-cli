// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the Fossil init + auth handshake byte sequence (FINDINGS #14),
 * driven entirely through {@link FakeBleTransport}.
 *
 * <p>Minimal init ({@code fullInit=false}) on a Fossil-protocol firmware:
 * AnimationRequest? (no, minimal skips it) -> MTU (local, no write) ->
 * GetDeviceInfoRequest (file-get on DEVICE_INFO) -> on its response, a background
 * "fossil-auth" thread writes {@code 01 07} (GET_USER_AUTHORIZATION_STATUS) to
 * 3dda0005, then on a "needs auth" indication ({@code 03 07 00}) writes
 * {@code 02 06 30 75 00 00 01} (PROCESS_USER_AUTHORIZATION_V2), then on
 * {@code 03 06 00 01} (ACCEPTED) pairs and finishes.
 */
public class AdapterAuthInitTest {

    private static final UUID_HELPER U = new UUID_HELPER();

    private static final class UUID_HELPER {
        final java.util.UUID call = FakeBleTransport.UUID_CHAR_CALL;
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    /** Wait until predicate true or timeout (poll). */
    private static boolean awaitCond(java.util.function.BooleanSupplier cond, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return cond.getAsBoolean();
    }

    @Test
    void minimalInit_freshAuth_writesCheckThenRequest_thenPairs() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);

        AtomicBoolean authRequiredFired = new AtomicBoolean(false);
        adapter.setOnAuthRequired(() -> authRequiredFired.set(true));

        CountDownLatch initialized = new CountDownLatch(1);
        // run init on a background thread (it blocks on reads/queue + waits for auth)
        Thread initThread = new Thread(() -> adapter.initialize(false), "test-init");
        initThread.setDaemon(true);
        initThread.start();

        // The adapter queues GetDeviceInfoRequest; respond with an empty device-info file.
        // Wait for the file-get request to be written on 3dda0003 first.
        assertTrue(awaitCond(() -> !t.writesTo(FakeBleTransport.UUID_CHAR_CONTROL).isEmpty(), 2000),
                "expected a file-get request on 3dda0003");
        FileGetResponder.respondToGet(t, (byte) 0x00, (byte) 0x0b, FileGetResponder.emptyRawFile());

        // Auth thread should now write 01 07 to 3dda0005.
        assertTrue(awaitCond(() -> t.lastWriteTo(U.call) != null, 2000),
                "expected auth status check on 3dda0005");
        FakeBleTransport.Write statusCheck = t.writesTo(U.call).get(0);
        assertArrayEquals(b(0x01, 0x07), statusCheck.data, "GET_USER_AUTHORIZATION_STATUS");

        // Inject "needs auth": 03 07 00
        t.injectNotification(U.call, b(0x03, 0x07, 0x00));

        // Adapter should fire onAuthRequired and write 02 06 30 75 00 00 01.
        assertTrue(awaitCond(() -> t.writesTo(U.call).size() >= 2, 2000),
                "expected auth request write on 3dda0005");
        assertTrue(authRequiredFired.get(), "onAuthRequired callback must fire on fresh auth");
        FakeBleTransport.Write authReq = t.writesTo(U.call).get(1);
        assertArrayEquals(b(0x02, 0x06, 0x30, 0x75, 0x00, 0x00, 0x01), authReq.data,
                "PROCESS_USER_AUTHORIZATION_V2 (30s, removeOtherPhones=1)");

        // Inject ACCEPTED: 03 06 00 01 -> adapter pairs.
        t.injectNotification(U.call, b(0x03, 0x06, 0x00, 0x01));

        assertTrue(awaitCond(() -> t.pairCount() >= 1, 2000),
                "adapter must pair after auth accepted");
    }

    @Test
    void minimalInit_alreadyAuthorized_skipsRequest_noAuthRequiredCallback() throws Exception {
        FakeBleTransport t = new FakeBleTransport();
        t.connect("AA:BB:CC:DD:EE:FF");
        FossilQAdapter adapter = new FossilQAdapter(t);

        AtomicBoolean authRequiredFired = new AtomicBoolean(false);
        adapter.setOnAuthRequired(() -> authRequiredFired.set(true));

        Thread initThread = new Thread(() -> adapter.initialize(false), "test-init");
        initThread.setDaemon(true);
        initThread.start();

        assertTrue(awaitCond(() -> !t.writesTo(FakeBleTransport.UUID_CHAR_CONTROL).isEmpty(), 2000));
        FileGetResponder.respondToGet(t, (byte) 0x00, (byte) 0x0b, FileGetResponder.emptyRawFile());

        assertTrue(awaitCond(() -> t.lastWriteTo(U.call) != null, 2000));
        assertArrayEquals(b(0x01, 0x07), t.writesTo(U.call).get(0).data);

        // Inject "already authorized": 03 07 01
        t.injectNotification(U.call, b(0x03, 0x07, 0x01));

        // Adapter should NOT write a second auth-request frame, and should pair.
        assertTrue(awaitCond(() -> t.pairCount() >= 1, 2000),
                "already-authorized path still pairs");
        Thread.sleep(100); // give any erroneous extra write a chance to appear
        List<FakeBleTransport.Write> callWrites = t.writesTo(U.call);
        assertEquals(1, callWrites.size(), "no PROCESS_USER_AUTHORIZATION when already authorized");
        assertFalse(authRequiredFired.get(), "onAuthRequired must NOT fire when already authorized");
    }
}
