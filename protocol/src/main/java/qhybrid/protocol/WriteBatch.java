// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import qhybrid.protocol.BleTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owned replacement for GadgetBridge's {@code TransactionBuilder}: accumulates BLE
 * characteristic writes and flushes them to the {@link BleTransport} on {@link #queue()}.
 *
 * <p>Platform-neutral — talks only in {@link UUID} + {@code byte[]}. The former
 * {@code BluetoothGattCharacteristic} overload is gone; callers pass the UUID directly.
 */
public class WriteBatch {
    private final String taskName;
    private final BleTransport transport;
    private final List<Runnable> ops = new ArrayList<>();

    public WriteBatch(String taskName, BleTransport transport) {
        this.taskName = taskName;
        this.transport = transport;
    }

    public WriteBatch write(UUID uuid, byte... data) {
        ops.add(() -> transport.writeCharacteristic(uuid, data));
        return this;
    }

    /**
     * WP-BUZZTEST: queue a <b>fire-and-forget</b> write (submit, do not block on completion). Used
     * for the file-PUT type-4 close frame so a watch that never acks the close cannot stall the
     * serial request queue. See {@link BleTransport#writeCharacteristicNoWait(UUID, byte[])}.
     */
    public WriteBatch writeNoWait(UUID uuid, byte... data) {
        ops.add(() -> transport.writeCharacteristicNoWait(uuid, data));
        return this;
    }

    public WriteBatch requestMtu(int mtu) {
        ops.add(() -> transport.requestMtu(mtu));
        return this;
    }

    public void queue() {
        for (Runnable op : ops) op.run();
    }

    public String getTaskName() {
        return taskName;
    }

    /**
     * Max BLE write chunk for a given MTU (formerly
     * AbstractBTLEDeviceSupport.calcMaxWriteChunk): MTU minus 3 ATT header bytes,
     * clamped to [20, 512].
     */
    public static int calcMaxWriteChunk(int mtu) {
        int safeMtu = Math.max(23, mtu);
        return Math.min(512, safeMtu - 3);
    }
}
