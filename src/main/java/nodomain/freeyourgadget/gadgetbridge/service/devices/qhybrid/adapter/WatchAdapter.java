package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter;

import android.content.Context;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport;

/**
 * Shim for WatchAdapter. The real class is abstract with ~30 abstract methods.
 * We provide only what vendored code actually calls: getDeviceSupport() and getContext().
 */
public abstract class WatchAdapter {
    private final QHybridSupport deviceSupport;

    public WatchAdapter(QHybridSupport deviceSupport) {
        this.deviceSupport = deviceSupport;
    }

    public QHybridSupport getDeviceSupport() {
        return deviceSupport;
    }

    public Context getContext() {
        return getDeviceSupport().getContext();
    }

    /**
     * Used by Request.java for logging byte arrays.
     */
    public static String arrayToString(byte[] array) {
        if (array == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("0x%02X", array[i]));
        }
        return sb.append("]").toString();
    }
}
