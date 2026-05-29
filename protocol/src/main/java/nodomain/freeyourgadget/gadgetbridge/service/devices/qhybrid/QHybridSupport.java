package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import qhybrid.linux.BleTransport;

import java.util.UUID;

/**
 * Shim for QHybridSupport. The real class extends AbstractBTLEDeviceSupport and is
 * deeply embedded in GB's service lifecycle. We provide the subset that vendored
 * request/adapter code actually calls.
 */
public class QHybridSupport {
    // Constants used by ConfigurationGetRequest
    public static final String ITEM_STEP_COUNT = "STEP_COUNT: ";
    public static final String ITEM_TIMEZONE_OFFSET = "TIMEZONE_OFFSET_COUNT: ";
    public static final String ITEM_VIBRATION_STRENGTH = "VIBRATION_STRENGTH: ";
    public static final String ITEM_STEP_GOAL = "STEP_GOAL: ";

    private final BleTransport transport;
    private final GBDevice device;

    public QHybridSupport(BleTransport transport, GBDevice device) {
        this.transport = transport;
        this.device = device;
    }

    public TransactionBuilder createTransactionBuilder(String taskName) {
        return new TransactionBuilder(taskName, transport);
    }

    public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        // 3-arg form resolves identically against the JVM stub and real Android.
        return new BluetoothGattCharacteristic(uuid, 0, 0);
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    public GBDevice getDevice() {
        return device;
    }

    public Context getContext() {
        return null;
    }
}
