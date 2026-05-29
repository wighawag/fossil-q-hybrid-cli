// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import qhybrid.linux.BleTransport;

import java.util.UUID;

/**
 * Owned, minimal replacement for the GadgetBridge {@code QHybridSupport} seam that
 * the re-owned request classes talk to: it hands out {@link WriteBatch}es bound to
 * the {@link BleTransport} and resolves characteristic UUIDs (now plain {@link UUID},
 * no {@code BluetoothGattCharacteristic}).
 */
public class DeviceSupport {
    private final BleTransport transport;

    public DeviceSupport(BleTransport transport) {
        this.transport = transport;
    }

    public WriteBatch createWriteBatch(String taskName) {
        return new WriteBatch(taskName, transport);
    }

    /** Characteristic handle is just its UUID in the platform-neutral protocol. */
    public UUID getCharacteristic(UUID uuid) {
        return uuid;
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    public BleTransport getTransport() {
        return transport;
    }
}
