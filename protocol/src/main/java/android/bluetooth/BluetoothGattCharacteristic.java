package android.bluetooth;

import java.util.UUID;

public class BluetoothGattCharacteristic {
    private final UUID uuid;

    public BluetoothGattCharacteristic(UUID uuid) {
        this.uuid = uuid;
    }

    // Mirror the REAL Android signature so :protocol code can use the same
    // constructor on both the JVM (this stub) and Android (the real class).
    public BluetoothGattCharacteristic(UUID uuid, int properties, int permissions) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }
}
