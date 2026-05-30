package qhybrid.protocol;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Interface for BLE transport. Implemented by BluezTransport (D-Bus / bluetoothctl).
 */
public interface BleTransport {
    boolean connect(String macAddress);
    void disconnect();
    boolean isConnected();

    /** Return the MAC address of the currently connected device, or null. */
    String getConnectedMac();

    void writeCharacteristic(UUID uuid, byte[] data);

    /**
     * Write a characteristic <b>fire-and-forget</b>: submit the write but do NOT block waiting for
     * its completion/ack. Default delegates to {@link #writeCharacteristic(UUID, byte[])}.
     *
     * <p>WP-BUZZTEST: used for the file-PUT type-4 "close" frame. The close targets an INDICATE
     * control characteristic (write-with-response), but this watch firmware sends no ack to it, so
     * on Android's GATT stack a normal write blocks the BLE callback thread for the full op-timeout
     * (~10s) — which stalled the strictly-serial request queue and prevented the next file-PUT (the
     * buzz's play file) from running. The close is a courtesy the watch needs to finalise a
     * transfer, but we never need to <em>wait</em> on it. Transports without this hazard (BlueZ /
     * D-Bus) can keep the default blocking behaviour.
     */
    default void writeCharacteristicNoWait(UUID uuid, byte[] data) {
        writeCharacteristic(uuid, data);
    }

    /**
     * Initiate BLE pairing (creates a bond with the device).
     * Must be called after Fossil auth succeeds — the official app does
     * BLE pairing immediately after the user presses the auth button.
     * The bond ties the auth state to the BLE link key.
     * @return true if pairing succeeded or already paired
     */
    boolean pair();
    byte[] readCharacteristic(UUID uuid);
    void enableNotifications(UUID uuid);
    void requestMtu(int mtu);
    int getMtu();

    // Callbacks
    void setNotificationCallback(BiConsumer<UUID, byte[]> callback);
    void setConnectionCallback(Consumer<Boolean> callback);
    void setMtuCallback(Consumer<Integer> callback);
}
