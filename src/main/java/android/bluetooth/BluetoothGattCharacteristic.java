package android.bluetooth;

import java.util.UUID;

public class BluetoothGattCharacteristic {
    private final UUID uuid;

    public BluetoothGattCharacteristic(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid() {
        return uuid;
    }
}
