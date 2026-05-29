// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import qhybrid.protocol.BleTransport;
import qhybrid.protocol.file.FileHandle;
import qhybrid.protocol.requests.fossil.FossilRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Owned replacement for GadgetBridge's {@code FossilWatchAdapter} shim. The re-owned
 * request classes call:
 * <ul>
 *   <li>{@code adapter.queueWrite(FossilRequest, boolean)} (file lookup/get/close)</li>
 *   <li>{@code adapter.getMTU()} (chunking)</li>
 *   <li>{@code adapter.getSupportedFileVersion(FileHandle)} (file header version)</li>
 *   <li>{@code adapter.getDeviceSupport()} (to create WriteBatches / resolve UUIDs)</li>
 *   <li>{@code adapter.log(String)}</li>
 * </ul>
 *
 * <p>{@code queueWrite} is wired to {@link FossilQAdapter} via a delegate.
 */
public class FossilWatchAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(FossilWatchAdapter.class);

    private final DeviceSupport deviceSupport;
    private BiConsumer<FossilRequest, Boolean> queueWriteDelegate;
    private int mtu = 23;
    private final Map<Short, Short> fileVersions = new HashMap<>();

    public FossilWatchAdapter(BleTransport transport) {
        this.deviceSupport = new DeviceSupport(transport);
    }

    public DeviceSupport getDeviceSupport() {
        return deviceSupport;
    }

    // --- Called by re-owned request classes ---

    public void queueWrite(FossilRequest request, boolean prioritise) {
        if (queueWriteDelegate != null) {
            queueWriteDelegate.accept(request, prioritise);
        } else {
            LOG.warn("queueWrite called but no delegate set: {}", request.getClass().getSimpleName());
        }
    }

    public int getMTU() {
        return mtu;
    }

    public short getSupportedFileVersion(FileHandle handle) {
        return fileVersions.getOrDefault((short) handle.getMajorHandle(), (short) 0);
    }

    public void log(String message) {
        LOG.debug(message);
    }

    // --- Configured by FossilQAdapter ---

    public void setQueueWriteDelegate(BiConsumer<FossilRequest, Boolean> delegate) {
        this.queueWriteDelegate = delegate;
    }

    public void setMTU(int mtu) {
        this.mtu = mtu;
    }

    public void setSupportedFileVersion(short handle, short version) {
        fileVersions.put(handle, version);
    }
}
