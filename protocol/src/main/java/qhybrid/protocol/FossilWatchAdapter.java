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
        seedDefaultFileVersions();
    }

    /**
     * Seed the file-header VERSIONS with the known defaults for this firmware (HW0.0.2.9r.v3),
     * BEFORE init reads the watch's SupportedFileVersions (which overwrites these with the
     * authoritative values). This makes a file PUT ship the CORRECT header version even when init
     * could not complete the version read (e.g. a failed firmware-version read aborted init) —
     * instead of defaulting to version 0, which the watch ACCEPTS at open + data but REJECTS at
     * VERIFY (0x05 VERIFICATION_FAIL, then 0x83 NOT_FOUND), the "wedged watch / cannot save"
     * symptom. Values observed on-device: NOTIFICATION_PLAY(0x09)=2, ALARMS(0x0A)=2,
     * SETTINGS/activity(0x08)=2. See FINDINGS "ROOT CAUSE PROVEN".
     */
    private void seedDefaultFileVersions() {
        // Authoritative values decoded from the watch's SupportedFileVersions response
        // (HW0.0.2.9r.v3): every file handle this app PUTs is version 2. Seed exactly those majors;
        // init overwrites them with the live values when it completes. (We deliberately do NOT seed
        // 0x13/0x15, which the static SupportedFileVersionsInfo defaults set to 2/3 and which this
        // app does not PUT on the normal paths.)
        short[] majors = {
            0x04, // MUSIC_INFO
            0x06, // HAND_ACTIONS / SETTINGS_BUTTONS
            0x08, // CONFIGURATION
            0x09, // NOTIFICATION_PLAY
            0x0A, // ALARMS
            0x0C, // NOTIFICATION_FILTER
            0x0E, // WATCH_PARAMETERS
        };
        for (short major : majors) {
            fileVersions.put(major, (short) 2);
        }
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
