// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import qhybrid.protocol.BleTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * In-memory {@link BleTransport} for headless protocol tests (WP1 deliverable).
 *
 * <p>It records every {@link #writeCharacteristic} (UUID + exact bytes) so tests can
 * assert the precise wire output, and lets a test synthesise inbound notification/
 * indication frames via {@link #injectNotification} to drive the adapter's request
 * state machine without any real BLE.
 *
 * <p>Canned device-info reads (battery / firmware / model) are configurable so the
 * adapter's {@code initialize()} path can run; defaults describe the hardware-verified
 * Q Commuter (HW.0.0, firmware {@code HW0.0.2.9r.v3}, Fossil 2.x protocol).
 */
public class FakeBleTransport implements BleTransport {

    /** A single recorded characteristic write. */
    public static final class Write {
        public final UUID uuid;
        public final byte[] data;

        Write(UUID uuid, byte[] data) {
            this.uuid = uuid;
            this.data = data;
        }

        @Override
        public String toString() {
            return uuid + " <- " + hex(data);
        }
    }

    // Fossil characteristic UUIDs (mirrors the adapter's constants).
    public static final UUID UUID_CHAR_MISFIT  = UUID.fromString("3dda0002-957f-7d4a-34a6-74696673696d");
    public static final UUID UUID_CHAR_CONTROL = UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d");
    public static final UUID UUID_CHAR_DATA    = UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d");
    public static final UUID UUID_CHAR_CALL    = UUID.fromString("3dda0005-957f-7d4a-34a6-74696673696d");
    public static final UUID UUID_CHAR_BUTTON  = UUID.fromString("3dda0006-957f-7d4a-34a6-74696673696d");
    public static final UUID UUID_CHAR_UPLOAD  = UUID.fromString("3dda0007-957f-7d4a-34a6-74696673696d");
    public static final UUID UUID_BATTERY      = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_FIRMWARE     = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_MODEL        = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");

    private final List<Write> writes = new ArrayList<>();
    private final List<UUID> notificationsEnabled = new ArrayList<>();

    private boolean connected = false;
    private String connectedMac = "AA:BB:CC:DD:EE:FF";
    private int mtu = 247;

    private BiConsumer<UUID, byte[]> notificationCallback;
    private Consumer<Boolean> connectionCallback;
    private Consumer<Integer> mtuCallback;

    // Canned device-info reads.
    private byte[] batteryValue  = new byte[]{(byte) 22};                 // 22%
    private byte[] firmwareValue = "HW0.0.2.9r.v3".getBytes();           // Fossil 2.x
    private byte[] modelValue    = "HW.0.0".getBytes();
    private int pairCount = 0;

    // ----- test-facing configuration -----

    public FakeBleTransport setConnected(boolean c) {
        this.connected = c;
        return this;
    }

    public FakeBleTransport setFirmware(String fw) {
        this.firmwareValue = fw.getBytes();
        return this;
    }

    public FakeBleTransport setBattery(int percent) {
        this.batteryValue = new byte[]{(byte) percent};
        return this;
    }

    public FakeBleTransport setMtuValue(int mtu) {
        this.mtu = mtu;
        return this;
    }

    /** Synthesise an inbound notification/indication frame on the given characteristic. */
    public void injectNotification(UUID uuid, byte[] value) {
        if (notificationCallback != null) {
            notificationCallback.accept(uuid, value);
        }
    }

    public void injectNotification(UUID uuid, int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) b[i] = (byte) bytes[i];
        injectNotification(uuid, b);
    }

    // ----- recorded output accessors -----

    public List<Write> writes() {
        return writes;
    }

    public List<Write> writesTo(UUID uuid) {
        List<Write> out = new ArrayList<>();
        for (Write w : writes) if (w.uuid.equals(uuid)) out.add(w);
        return out;
    }

    public Write lastWriteTo(UUID uuid) {
        for (int i = writes.size() - 1; i >= 0; i--) {
            if (writes.get(i).uuid.equals(uuid)) return writes.get(i);
        }
        return null;
    }

    public List<UUID> notificationsEnabled() {
        return notificationsEnabled;
    }

    public int pairCount() {
        return pairCount;
    }

    public void clearWrites() {
        writes.clear();
    }

    // ----- BleTransport implementation -----

    @Override
    public boolean connect(String macAddress) {
        this.connectedMac = macAddress;
        this.connected = true;
        if (connectionCallback != null) connectionCallback.accept(true);
        return true;
    }

    @Override
    public void disconnect() {
        this.connected = false;
        if (connectionCallback != null) connectionCallback.accept(false);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getConnectedMac() {
        return connectedMac;
    }

    @Override
    public void writeCharacteristic(UUID uuid, byte[] data) {
        writes.add(new Write(uuid, data.clone()));
    }

    @Override
    public boolean pair() {
        pairCount++;
        return true;
    }

    @Override
    public byte[] readCharacteristic(UUID uuid) {
        if (uuid.equals(UUID_BATTERY)) return batteryValue;
        if (uuid.equals(UUID_FIRMWARE)) return firmwareValue;
        if (uuid.equals(UUID_MODEL)) return modelValue;
        return new byte[0];
    }

    @Override
    public void enableNotifications(UUID uuid) {
        notificationsEnabled.add(uuid);
    }

    @Override
    public void requestMtu(int mtu) {
        this.mtu = mtu;
        if (mtuCallback != null) mtuCallback.accept(mtu);
    }

    @Override
    public int getMtu() {
        return mtu;
    }

    @Override
    public void setNotificationCallback(BiConsumer<UUID, byte[]> callback) {
        this.notificationCallback = callback;
    }

    @Override
    public void setConnectionCallback(Consumer<Boolean> callback) {
        this.connectionCallback = callback;
    }

    @Override
    public void setMtuCallback(Consumer<Integer> callback) {
        this.mtuCallback = callback;
    }

    // ----- helpers -----

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}
