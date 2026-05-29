package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.fossil;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.WatchAdapter;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.file.FileHandle;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.FossilRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Shim for FossilWatchAdapter.
 *
 * The vendored request classes call:
 * - adapter.queueWrite(FossilRequest, boolean)     [FileCloseAndPutRequest, FileLookupAndGetRequest]
 * - adapter.getMTU()                                [FilePutRawRequest]
 * - adapter.getSupportedFileVersion(FileHandle)     [FilePutRequest, FileGetRequest, etc.]
 * - adapter.getDeviceSupport()                      [FilePutRawRequest for TransactionBuilder]
 * - adapter.log(String)                             [various]
 *
 * queueWrite is wired to FossilQAdapter via a delegate callback.
 */
public class FossilWatchAdapter extends WatchAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(FossilWatchAdapter.class);

    private BiConsumer<FossilRequest, Boolean> queueWriteDelegate;
    private int mtu = 23;
    private final Map<Short, Short> fileVersions = new HashMap<>();

    public FossilWatchAdapter(QHybridSupport support) {
        super(support);
    }

    // --- Called by vendored request classes ---

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

    // --- Called by FossilQAdapter to configure ---

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
