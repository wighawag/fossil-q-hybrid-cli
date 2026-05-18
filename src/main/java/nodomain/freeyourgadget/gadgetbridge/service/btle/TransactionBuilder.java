package nodomain.freeyourgadget.gadgetbridge.service.btle;

import android.bluetooth.BluetoothGattCharacteristic;
import qhybrid.linux.BleTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Key shim: accumulates BLE write operations, then flushes them all to BleTransport on queue().
 *
 * Vendored code (e.g. FilePutRawRequest) creates a TransactionBuilder via
 * adapter.getDeviceSupport().createTransactionBuilder(), adds writes, then calls queue().
 */
public class TransactionBuilder {
    private final String taskName;
    private final BleTransport transport;
    private final List<Runnable> ops = new ArrayList<>();

    public TransactionBuilder(String taskName, BleTransport transport) {
        this.taskName = taskName;
        this.transport = transport;
    }

    public TransactionBuilder write(BluetoothGattCharacteristic characteristic, byte... data) {
        UUID uuid = characteristic.getUuid();
        ops.add(() -> transport.writeCharacteristic(uuid, data));
        return this;
    }

    public TransactionBuilder write(UUID uuid, byte... data) {
        ops.add(() -> transport.writeCharacteristic(uuid, data));
        return this;
    }

    public TransactionBuilder requestMtu(int mtu) {
        ops.add(() -> transport.requestMtu(mtu));
        return this;
    }

    public void setProgress(int resId, boolean ongoing, int percent) {
        // no-op on Linux
    }

    public void queue() {
        for (Runnable op : ops) {
            op.run();
        }
    }

    public String getTaskName() {
        return taskName;
    }
}
