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
