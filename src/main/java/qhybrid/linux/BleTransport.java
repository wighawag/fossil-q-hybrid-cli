package qhybrid.linux;

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

    void writeCharacteristic(UUID uuid, byte[] data);
    byte[] readCharacteristic(UUID uuid);
    void enableNotifications(UUID uuid);
    void requestMtu(int mtu);
    int getMtu();

    // Callbacks
    void setNotificationCallback(BiConsumer<UUID, byte[]> callback);
    void setConnectionCallback(Consumer<Boolean> callback);
    void setMtuCallback(Consumer<Integer> callback);
}
