package qhybrid.protocol;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Interface for BLE transport. Implemented by BluezTransport (D-Bus / bluetoothctl).
 */
public interface BleTransport {
    boolean connect(String macAddress);

    /**
     * Connect with an explicit BLE auto-connect preference.
     *
     * <p>This is a connection-management hint, NOT a protocol change. {@code autoConnect=false}
     * (the default, via the single-arg {@link #connect(String)}) requests a fast, bounded,
     * directly-initiated connect — used for user-initiated / first connects where an unreachable
     * watch should fail honestly and quickly. {@code autoConnect=true} requests a
     * controller-managed background connect that does NOT time out and stays pending until the
     * peripheral appears (ideal for a directed-advertising watch that we want to keep alive across
     * drops); the result of such a connect is delivered via the connection callback, not this
     * boolean return.
     *
     * <p>Backward-compatible default: implementations that do not distinguish the two paths (the
     * CLI BlueZ/D-Bus transports, the test fake) simply delegate to {@link #connect(String)}.
     *
     * @param macAddress  the device MAC to connect to
     * @param autoConnect platform BLE auto-connect preference (see above)
     * @return for the {@code autoConnect=false} (blocking) path, whether the link came up; for the
     *         {@code autoConnect=true} (deferred) path, whether the connect was successfully
     *         REGISTERED (link-up is then reported asynchronously via the connection callback)
     */
    default boolean connect(String macAddress, boolean autoConnect) {
        return connect(macAddress);
    }

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
