// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HYBRID-AUTOCONNECT — the {@code connect(mac, autoConnect)} seam.
 *
 * <p>Verifies the autoConnect flag is plumbed platform-neutrally through {@link FossilController}
 * to the {@link BleTransport}, that the single-arg {@link FossilController#connect(String)} keeps
 * the fast bounded (autoConnect=false) contract, and that the {@link BleTransport} default overload
 * is backward-compatible (delegates to the single-arg connect). This is the seam the Android service
 * uses to choose the FAST user/first-connect path (false) vs the BACKGROUND keep-alive after a drop
 * (true) — invents NO wire bytes (purely connection management).
 */
public class ControllerAutoConnectTest {

    @Test
    public void singleArgConnect_usesFastBoundedPath_autoConnectFalse() {
        FakeBleTransport fake = new FakeBleTransport();
        FossilController controller = new FossilController(fake);

        assertNull(fake.lastAutoConnect, "no connect yet");
        boolean ok = controller.connect("AA:BB:CC:DD:EE:FF");

        assertTrue(ok);
        assertEquals(1, fake.connectCount);
        assertFalse(fake.lastAutoConnect,
                "single-arg connect must be the bounded autoConnect=false path");
    }

    @Test
    public void twoArgConnect_true_drivesBackgroundAutoConnect() {
        FakeBleTransport fake = new FakeBleTransport();
        FossilController controller = new FossilController(fake);

        boolean ok = controller.connect("AA:BB:CC:DD:EE:FF", true);

        assertTrue(ok);
        assertEquals(1, fake.connectCount);
        assertTrue(fake.lastAutoConnect,
                "connect(mac, true) must request the controller-managed auto-connect path");
    }

    @Test
    public void twoArgConnect_false_isTheBoundedPath() {
        FakeBleTransport fake = new FakeBleTransport();
        FossilController controller = new FossilController(fake);

        controller.connect("AA:BB:CC:DD:EE:FF", false);

        assertFalse(fake.lastAutoConnect);
    }

    /**
     * A transport that does NOT override the two-arg overload (only the single-arg connect) must
     * still work via the {@link BleTransport} default method — the backward-compatible contract the
     * CLI BlueZ/D-Bus transports rely on. The default delegates to the single-arg connect, so the
     * autoConnect hint is simply ignored (those platforms have their own connect semantics).
     */
    @Test
    public void defaultOverload_isBackwardCompatible_delegatesToSingleArg() {
        final boolean[] singleArgCalled = {false};
        BleTransport legacy = new MinimalTransport() {
            @Override
            public boolean connect(String macAddress) {
                singleArgCalled[0] = true;
                return true;
            }
        };

        // Calling the two-arg overload on a transport that only implements the single-arg connect
        // must route through the default method to the single-arg connect (no compile/abstract error).
        boolean ok = legacy.connect("AA:BB:CC:DD:EE:FF", true);

        assertTrue(ok);
        assertTrue(singleArgCalled[0], "default connect(mac, auto) must delegate to connect(mac)");
    }

    /** Minimal {@link BleTransport} stub that implements ONLY the abstract members (no two-arg connect). */
    private static abstract class MinimalTransport implements BleTransport {
        @Override public void disconnect() {}
        @Override public boolean isConnected() { return false; }
        @Override public String getConnectedMac() { return null; }
        @Override public void writeCharacteristic(java.util.UUID uuid, byte[] data) {}
        @Override public boolean pair() { return true; }
        @Override public byte[] readCharacteristic(java.util.UUID uuid) { return new byte[0]; }
        @Override public void enableNotifications(java.util.UUID uuid) {}
        @Override public void requestMtu(int mtu) {}
        @Override public int getMtu() { return 23; }
        @Override public void setNotificationCallback(java.util.function.BiConsumer<java.util.UUID, byte[]> cb) {}
        @Override public void setConnectionCallback(java.util.function.Consumer<Boolean> cb) {}
        @Override public void setMtuCallback(java.util.function.Consumer<Integer> cb) {}
    }
}
