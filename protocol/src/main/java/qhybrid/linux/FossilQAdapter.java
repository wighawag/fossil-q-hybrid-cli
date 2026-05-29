package qhybrid.linux;

import android.bluetooth.BluetoothGattCharacteristic;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.fossil.FossilWatchAdapter;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.buttonconfig.ConfigFileBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.buttonconfig.ConfigPayload;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.file.FileHandle;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.Request;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.FossilRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.RequestMtuRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.SetDeviceStateRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.alarm.Alarm;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.alarm.AlarmsSetRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.configuration.ConfigurationPutRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.device_info.DeviceInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.device_info.DeviceSecurityVersionInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.device_info.GetDeviceInfoRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.device_info.SupportedFileVersionsInfo;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.file.FileDeleteRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.file.FileLookupAndGetRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.file.FilePutRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.file.FilePutRawRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.notification.NotificationFilterPutRequest;
import nodomain.freeyourgadget.gadgetbridge.devices.qhybrid.NotificationConfiguration;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.AnimationRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GetCurrentStepCountRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GetStepGoalRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.GetVibrationStrengthRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.MoveHandsRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.PlayNotificationRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.ReleaseHandsControlRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.RequestHandControlRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SaveCalibrationRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SetStepGoalRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SetTimeRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SetVibrationStrengthRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.ActivityPointGetRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.SetCurrentStepCountRequest;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Our adapter — bridges vendored request classes to BluezTransport.
 *
 * Handles both Misfit (firmware 0.x/1.x) and Fossil (firmware 2.x) protocols.
 * Detects which to use based on firmware version string.
 */
public class FossilQAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(FossilQAdapter.class);

    // BLE characteristic UUIDs
    private static final UUID UUID_CHAR_MISFIT    = UUID.fromString("3dda0002-957f-7d4a-34a6-74696673696d");
    private static final UUID UUID_CHAR_CONTROL   = UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d");
    private static final UUID UUID_CHAR_DATA      = UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d");
    private static final UUID UUID_CHAR_CALL      = UUID.fromString("3dda0005-957f-7d4a-34a6-74696673696d");
    private static final UUID UUID_CHAR_BUTTON    = UUID.fromString("3dda0006-957f-7d4a-34a6-74696673696d");
    private static final UUID UUID_CHAR_UPLOAD    = UUID.fromString("3dda0007-957f-7d4a-34a6-74696673696d");
    private static final UUID UUID_BATTERY        = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FIRMWARE       = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_MODEL          = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");

    private final BleTransport transport;
    private final GBDevice device;
    private final QHybridSupport shimSupport;
    private final FossilWatchAdapter shimAdapter;

    private boolean useFossilProtocol = false;
    private String firmwareVersion;
    private String modelNumber;
    private int batteryLevel;

    // Fossil protocol request queue
    private FossilRequest currentFossilRequest;
    private final List<Request> requestQueue = Collections.synchronizedList(new ArrayList<>());
    private int mtu = 23;

    // Timeout handling
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fossil-timeout");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> timeoutFuture;
    private static final int REQUEST_TIMEOUT_SECS = 300; // 5 minutes

    // Init mode: full (animation + config sync + filter upload) vs minimal (auth only)
    private boolean fullInit = false;

    // Authentication handshake
    private CompletableFuture<byte[]> pendingAuthResponse;

    // Button gesture detection for FORWARD_TO_PHONE buttons
    private final ButtonGestureDetector gestureDetector = new ButtonGestureDetector();

    // Callbacks for CLI
    private Runnable onInitialized;
    private java.util.function.Consumer<byte[]> onActivityData;
    private java.util.function.Consumer<String> onEventJson;
    // Fired exactly when the watch is asked to authorize (i.e. the moment it
    // vibrates and the user must confirm with the TOP button / cancel with the
    // BOTTOM button). NOT fired when the watch is already authorized. Lets a UI
    // show the confirm/cancel prompt ONLY when it is actually needed.
    private Runnable onAuthRequired;
    // Optional hook fired after a successful full-init config sync (lets the CLI
    // clear its on-disk syncNeeded flag without coupling the adapter to DeviceConfig).
    private Runnable onConfigSynced;
    // Caller-supplied settings for full init (replaces adapter disk-loading).
    private qhybrid.protocol.model.SyncSettings syncSettings;

    /**
     * Provide the settings used during full init (step goal, vibration strength,
     * second timezone, notification filter entries). When set, the adapter does
     * NOT read ~/.config/fossil-q from disk. The FossilController façade / CLI
     * populates this.
     */
    public void setSyncSettings(qhybrid.protocol.model.SyncSettings settings) {
        this.syncSettings = settings;
    }

    /** Hook fired once after a successful full-init config sync. */
    public void setOnConfigSynced(Runnable callback) {
        this.onConfigSynced = callback;
    }

    public FossilQAdapter(BleTransport transport) {
        this.transport = transport;
        this.device = new GBDevice();
        this.shimSupport = new QHybridSupport(transport, device);
        this.shimAdapter = new FossilWatchAdapter(shimSupport);

        // Wire up queueWrite delegate: vendored request classes call
        // adapter.queueWrite(FossilRequest, boolean) which hits this
        this.shimAdapter.setQueueWriteDelegate(this::queueWrite);

        // Set up BLE notification callback
        transport.setNotificationCallback(this::onCharacteristicChanged);
        transport.setMtuCallback(this::onMtuChanged);

        // Handle reconnection: reset request state on disconnect
        transport.setConnectionCallback(isConnected -> {
            if (!isConnected) {
                LOG.info("Watch disconnected — clearing in-flight request");
                currentFossilRequest = null;
                requestQueue.clear();
                stopTimeout();
            } else {
                LOG.info("Watch reconnected — init will re-run");
            }
        });
    }

    // ========== Public API (called by CLI commands) ==========

    /**
     * Initialize the watch with full init (animation + config sync + filter upload).
     */
    public void initialize() {
        initialize(true);
    }

    /**
     * Initialize the watch. Reads device info, detects protocol, runs init sequence.
     * @param fullInit true = animation + config sync + filter upload; false = minimal (auth only)
     */
    public void initialize(boolean fullInit) {
        this.fullInit = fullInit;
        LOG.info("Initializing watch...");

        // Notifications are enabled by BluezTransport.connect() — no need to do it here

        // Read device info
        byte[] batteryData = transport.readCharacteristic(UUID_BATTERY);
        if (batteryData != null && batteryData.length > 0) {
            batteryLevel = batteryData[0] & 0xFF;
            device.setBatteryLevel(batteryLevel);
            LOG.info("Battery: {}%", batteryLevel);
        }

        byte[] fwData = transport.readCharacteristic(UUID_FIRMWARE);
        if (fwData != null) {
            firmwareVersion = new String(fwData).trim();
            device.setFirmwareVersion(firmwareVersion);
            LOG.info("Firmware: {}", firmwareVersion);
        }

        byte[] modelData = transport.readCharacteristic(UUID_MODEL);
        if (modelData != null) {
            modelNumber = new String(modelData).trim();
            LOG.info("Model: {}", modelNumber);
        }

        if (firmwareVersion == null || firmwareVersion.length() < 7) {
            LOG.error("Cannot read firmware version — aborting init");
            return;
        }

        // Protocol detection (same as WatchAdapterFactory)
        detectProtocol(firmwareVersion);

        if (useFossilProtocol) {
            initFossilProtocol();
        } else {
            initMisfitProtocol();
        }
    }

    /**
     * Block until initialization completes or times out.
     */
    public boolean waitForInit(long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        this.onInitialized = latch::countDown;
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void syncTime() {
        if (useFossilProtocol) {
            queueWrite(new ConfigurationPutRequest(generateTimeConfigItem(), shimAdapter), false);
        } else {
            sendMisfitRequest(prepareSetTimeRequest());
        }
    }

    /**
     * Upload a notification filter for our package name.
     * This tells the watch what vibration + hand movement to use when
     * a notification from "qhybrid.linux" is played.
     */
    public void setNotificationFilter(PlayNotificationRequest.VibrationType vibration, int hourDeg, int minDeg) {
        if (!useFossilProtocol) {
            LOG.warn("Notification filter not supported on Misfit protocol");
            return;
        }
        String packageName = "qhybrid.linux";
        NotificationConfiguration config = new NotificationConfiguration(
                (short) minDeg, (short) hourDeg, (short) -1, vibration);
        config.setPackageName(packageName);
        queueWrite(new NotificationFilterPutRequest(
                new NotificationConfiguration[]{config}, shimAdapter), false);
    }

    /**
     * Upload a notification filter with a specific vibration pattern byte.
     * Pattern values (from official Fossil app NotificationVibePattern.java):
     *   0 = AUTO, 1 = CALL, 2 = TEXT, 3 = EMAIL, 4 = DEFAULT_OTHER_APPS,
     *   5 = ONE_SHORT_VIBE, 6 = TWO_SHORT_VIBES, 7 = THREE_SHORT_VIBES,
     *   8 = ONE_LONG_VIBE, 9 = NO_VIBE
     */
    public void uploadNotificationFilterWithPattern(byte vibePattern) {
        uploadNotificationFilterWithPattern(vibePattern, (short) 90, (short) 90);
    }

    /**
     * Upload a notification filter with a specific vibration pattern and hand position.
     * Pattern values: 0=AUTO, 1=CALL, 2=TEXT, 3=EMAIL, 4=DEFAULT, 5-8=strong/long, 9=NO_VIBE
     * Hand position: hourDeg/minDeg in degrees (0-359). Both hands move to the specified position.
     * Official Fossil app positions: 30°/30°=1:00, 60°/60°=2:00, 90°/90°=3:00, etc.
     */
    public void uploadNotificationFilterWithPattern(byte vibePattern, short hourDeg, short minDeg) {
        if (!useFossilProtocol) {
            LOG.warn("Notification filter not supported on Misfit protocol");
            return;
        }
        String packageName = "qhybrid.linux";
        byte[] filter = buildNotificationFilterData(packageName, vibePattern, hourDeg, minDeg);
        LOG.info("Uploading notification filter: vibe={} (0x{}), hands={}°/{}°",
                vibePattern, String.format("%02X", vibePattern), hourDeg, minDeg);
        queueWrite(new FilePutRequest(FileHandle.NOTIFICATION_FILTER, filter, shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Notification filter (vibe={}, hands={}°/{}°) sync: {}",
                        vibePattern, hourDeg, minDeg, success ? "success" : "FAILED");
            }
        }, false);
    }

    /**
     * Upload a multi-entry notification filter from a NotificationConfig.
     * Each configured type gets its own filter entry with a unique CRC
     * derived from "qhybrid.linux.<name>". The watch matches the play file's
     * CRC to the filter entry to determine hand position + vibe pattern.
     */
    public void uploadNotificationFilter(NotificationConfig config) {
        var types = config.getTypes();
        if (types.isEmpty()) {
            LOG.warn("No notification types configured — skipping filter upload");
            return;
        }
        java.util.List<qhybrid.protocol.model.NotificationFilterEntry> entries = new java.util.ArrayList<>();
        for (var type : types) {
            entries.add(new qhybrid.protocol.model.NotificationFilterEntry(
                    type.packageName(), (byte) type.vibe,
                    (short) type.hourDeg, (short) type.minDeg));
        }
        uploadNotificationFilterEntries(entries);
    }

    /**
     * Upload a multi-entry notification filter from platform-neutral entries.
     * Byte-identical to the former NotificationConfig path; this is the form the
     * FossilController façade and decoupled init use (no disk loading).
     */
    public void uploadNotificationFilterEntries(
            java.util.List<qhybrid.protocol.model.NotificationFilterEntry> entries) {
        if (!useFossilProtocol) {
            LOG.warn("Notification filter not supported on Misfit protocol");
            return;
        }
        if (entries.isEmpty()) {
            LOG.warn("No notification filter entries — skipping filter upload");
            return;
        }

        byte[] filter = buildNotificationFilterFile(entries);
        LOG.info("Uploading notification filter with {} entries ({} bytes)",
                entries.size(), filter.length);
        for (var e : entries) {
            LOG.info("  {} → CRC=0x{}", e,
                    String.format("%08X", computeNullTerminatedCrc(e.packageName)));
        }

        final int count = entries.size();
        queueWrite(new FilePutRequest(FileHandle.NOTIFICATION_FILTER, filter, shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Notification filter ({} entries) sync: {}",
                        count, success ? "success" : "FAILED");
            }
        }, false);
    }

    /**
     * Play a notification using a specific package name for CRC matching.
     * The package name must match one of the filter entries uploaded by
     * uploadNotificationFilter(). The watch looks up the matching entry
     * to determine hand position and vibration pattern.
     */
    public void playNotificationByPackageName(String packageName) {
        if (!useFossilProtocol) {
            LOG.warn("Notification play requires Fossil protocol");
            return;
        }
        byte[] notifData = buildOfficialNotificationFile(
                "Notification", "fossil-q", "Notification", packageName);
        queueWrite(new FilePutRequest(
                FileHandle.NOTIFICATION_PLAY, notifData, shimAdapter), false);
    }

    /**
     * Build notification file data matching the official Fossil app format.
     * Key difference from GB: lengthBufferLength=12 (not 10), with 2 extra fields
     * (0xFFFFFFFF sentinel + Unix timestamp). The HW.0.0 firmware requires this
     * format for vibration to work.
     */
    private byte[] buildOfficialNotificationFile(String title, String sender, String message) {
        return buildOfficialNotificationFile(title, sender, message, "qhybrid.linux");
    }

    private byte[] buildOfficialNotificationFile(String title, String sender, String message, String packageName) {
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        byte[] titleBytes = (title + "\0").getBytes(utf8);
        byte[] senderBytes = (sender + "\0").getBytes(utf8);
        byte[] messageBytes = (message + "\0").getBytes(utf8);

        // Extra fields the official app adds (not in GB):
        byte[] sentinelBytes = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        int timestamp = (int) (System.currentTimeMillis() / 1000);
        byte[] timestampBytes = java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(timestamp).array();

        byte lengthBufferLength = 12; // Official app uses 12, GB uses 10
        byte notificationType = 3;    // NOTIFICATION
        byte flags = 0x02;
        byte uidLength = 4;
        byte appBundleCRCLength = 4;

        int messageId = (int) System.currentTimeMillis();

        // CRC of package name — must match the CRC in the notification filter.
        // Use null-terminated CRC to match the official Fossil app format.
        int packageCrc = computeNullTerminatedCrc(packageName);
        LOG.info("Notification CRC: 0x{} (package: {})", String.format("%08X", packageCrc), packageName);

        short mainBufferLength = (short) (lengthBufferLength + uidLength + appBundleCRCLength
                + titleBytes.length + senderBytes.length + messageBytes.length
                + sentinelBytes.length + timestampBytes.length);

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(mainBufferLength);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);

        // Length buffer header (12 bytes: mainBufLen(2)+lbl(1)+type(1)+flags(1)+uidl(1)+crcl(1)+5 field lengths)
        buf.putShort(mainBufferLength);
        buf.put(lengthBufferLength);
        buf.put(notificationType);
        buf.put(flags);
        buf.put(uidLength);
        buf.put(appBundleCRCLength);
        buf.put((byte) titleBytes.length);
        buf.put((byte) senderBytes.length);
        buf.put((byte) messageBytes.length);
        buf.put((byte) sentinelBytes.length);    // Extra field 1 length
        buf.put((byte) timestampBytes.length);   // Extra field 2 length

        // Data fields
        buf.putInt(messageId);
        buf.putInt(packageCrc);
        buf.put(titleBytes);
        buf.put(senderBytes);
        buf.put(messageBytes);
        buf.put(sentinelBytes);
        buf.put(timestampBytes);

        return buf.array();
    }

    public void playNotification(PlayNotificationRequest.VibrationType vibration, int hourDeg, int minDeg) {
        if (useFossilProtocol) {
            // Send lbl=12 notification file (matching official Fossil app format).
            // This triggers hand animation + vibration IF the watch has been
            // authenticated by the official Fossil app. Without authentication,
            // the file is accepted but silently ignored.
            byte[] notifData = buildOfficialNotificationFile(
                    "Notification", "fossil-q", "Notification");
            queueWrite(new FilePutRequest(
                    FileHandle.NOTIFICATION_PLAY, notifData, shimAdapter), false);

            // Note: if auth handshake was completed during init, the lbl=12 file
            // above is sufficient — the watch will vibrate AND move hands per the
            // notification filter. No findDevice() workaround needed.
        } else {
            sendMisfitRequest(new PlayNotificationRequest(vibration, hourDeg, minDeg));
        }
    }

    /**
     * Play a notification with a specific vibration pattern.
     * First uploads the notification filter with the desired pattern, then sends the play file.
     *
     * Pattern values (from official Fossil app NotificationVibePattern.java):
     *   0 = AUTO, 1 = CALL, 2 = TEXT, 3 = EMAIL, 4 = DEFAULT_OTHER_APPS,
     *   5 = ONE_SHORT_VIBE, 6 = TWO_SHORT_VIBES, 7 = THREE_SHORT_VIBES,
     *   8 = ONE_LONG_VIBE, 9 = NO_VIBE
     */
    public void playNotificationWithPattern(byte vibePattern) {
        playNotificationWithPattern(vibePattern, (short) 90, (short) 90);
    }

    /**
     * Play a notification with a specific vibration pattern and hand position.
     * First uploads the notification filter with the desired pattern + position,
     * then sends the play file.
     *
     * @param vibePattern vibration pattern byte (0-9)
     * @param hourDeg hour hand position in degrees (0-359)
     * @param minDeg minute hand position in degrees (0-359)
     */
    public void playNotificationWithPattern(byte vibePattern, short hourDeg, short minDeg) {
        if (!useFossilProtocol) {
            LOG.warn("Vibration pattern selection requires Fossil protocol");
            return;
        }
        // 1. Upload filter with the specified pattern + hand position
        uploadNotificationFilterWithPattern(vibePattern, hourDeg, minDeg);
        // 2. Send the notification play file (the filter determines the vibration pattern + hand position)
        byte[] notifData = buildOfficialNotificationFile(
                "Notification", "fossil-q", "Notification");
        queueWrite(new FilePutRequest(
                FileHandle.NOTIFICATION_PLAY, notifData, shimAdapter), false);
    }

    private long getVibrationDuration(PlayNotificationRequest.VibrationType vibration) {
        switch (vibration) {
            case SINGLE_SHORT: return 500;
            case DOUBLE_SHORT: return 1000;
            case TRIPLE_SHORT: return 1500;
            case SINGLE_NORMAL: return 1000;
            case DOUBLE_NORMAL: return 2000;
            case TRIPLE_NORMAL: return 3000;
            case SINGLE_LONG: return 2000;
            default: return 500;
        }
    }

    /**
     * Send a misfit-style notification request directly (vibration type + hand degrees).
     * On Fossil protocol watches this may or may not produce vibration depending on firmware.
     */
    public void playMisfitNotification(PlayNotificationRequest.VibrationType vibration, int hourDeg, int minDeg) {
        sendMisfitRequest(new PlayNotificationRequest(vibration, hourDeg, minDeg));
    }

    public void setAlarms(Alarm[] alarms) {
        if (!useFossilProtocol) {
            LOG.warn("Alarms not supported on Misfit protocol firmware");
            return;
        }
        queueWrite(new AlarmsSetRequest(alarms, shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                super.onFilePut(success);
                LOG.info("Alarm set: {}", success ? "success" : "FAILED");
            }
        }, false);
    }

    /**
     * Set alarms with a CompletableFuture result for success/failure reporting.
     * Reports detailed error codes from the watch firmware.
     */
    public void setAlarmsWithResult(Alarm[] alarms, CompletableFuture<Boolean> result) {
        if (!useFossilProtocol) {
            LOG.warn("Alarms not supported on Misfit protocol firmware");
            result.complete(false);
            return;
        }
        LOG.info("Setting {} alarm(s), file size {} bytes", alarms.length, alarms.length * 3);
        queueWrite(new AlarmsSetRequest(alarms, shimAdapter) {
            private boolean errorOccurred = false;

            @Override
            public void onFilePut(boolean success) {
                super.onFilePut(success);
                LOG.info("Alarm upload: {}", success ? "SUCCESS" : "FAILED");
                if (!result.isDone()) result.complete(success);
            }

            @Override
            public void handleResponse(BluetoothGattCharacteristic characteristic, byte[] value) {
                try {
                    super.handleResponse(characteristic, value);
                } catch (RuntimeException e) {
                    LOG.warn("Alarm upload error: {}", e.getMessage());
                    errorOccurred = true;
                    if (!result.isDone()) result.complete(false);
                }
            }

            @Override
            public boolean isFinished() {
                return errorOccurred || super.isFinished();
            }
        }, false);
    }

    /**
     * Read current alarms from the watch.
     * Uses FileLookupAndGetRequest (type 0x02, file lookup) instead of direct
     * FileGetRequest (type 0x01) — direct get returns INVALID_OPERATION_DATA
     * on HW.0.0 firmware. File lookup works (same approach as activity/config).
     */
    public void getAlarms(CompletableFuture<Alarm[]> result) {
        if (!useFossilProtocol) {
            LOG.warn("Alarms not supported on Misfit protocol firmware");
            result.complete(new Alarm[0]);
            return;
        }
        queueWrite(new FileLookupAndGetRequest(FileHandle.ALARMS, shimAdapter) {
            private boolean errorOccurred = false;

            @Override
            public void handleFileData(byte[] fileData) {
                // Raw file data includes 12-byte header + payload + 4-byte CRC
                LOG.info("Alarm file (raw): {} bytes", fileData.length);
                if (fileData.length < 16) {
                    LOG.warn("Alarm file too short: {} bytes", fileData.length);
                    result.complete(new Alarm[0]);
                    return;
                }
                // Strip 12-byte header and 4-byte trailing CRC
                int payloadLen = fileData.length - 12 - 4;
                byte[] alarmData = new byte[payloadLen];
                System.arraycopy(fileData, 12, alarmData, 0, payloadLen);
                LOG.info("Alarm payload: {} bytes ({} alarms)", payloadLen, payloadLen / 3);

                // Parse 3-byte alarm entries
                int count = payloadLen / 3;
                Alarm[] alarms = new Alarm[count];
                for (int i = 0; i < count; i++) {
                    byte[] ab = new byte[]{alarmData[i*3], alarmData[i*3+1], alarmData[i*3+2]};
                    alarms[i] = Alarm.fromBytes(ab);
                }
                LOG.info("Read {} alarm(s) from watch", alarms.length);
                result.complete(alarms);
            }

            @Override
            public void handleFileLookupError(FILE_LOOKUP_ERROR error) {
                if (error == FILE_LOOKUP_ERROR.FILE_EMPTY) {
                    LOG.info("No alarms on watch (file empty)");
                    result.complete(new Alarm[0]);
                } else {
                    LOG.warn("Alarm file lookup error: {}", error);
                    result.complete(new Alarm[0]);
                }
            }

            @Override
            public void handleResponse(BluetoothGattCharacteristic characteristic, byte[] value) {
                try {
                    super.handleResponse(characteristic, value);
                } catch (RuntimeException e) {
                    LOG.warn("Alarm read failed: {}", e.getMessage());
                    errorOccurred = true;
                    if (!result.isDone()) result.complete(new Alarm[0]);
                }
            }

            @Override
            public boolean isFinished() {
                return errorOccurred || super.isFinished();
            }
        }, false);
    }

    /**
     * Set alarms using raw 3-byte-per-alarm data.
     * Each alarm is [byte0] [byte1] [byte2] in the wire format.
     * This allows testing non-standard byte combinations that the Alarm class can't produce.
     */
    public void setAlarmsRaw(byte[] rawAlarmData, CompletableFuture<Boolean> result) {
        if (!useFossilProtocol) {
            LOG.warn("Alarms not supported on Misfit protocol firmware");
            result.complete(false);
            return;
        }
        LOG.info("Setting raw alarm data: {} bytes (hex: {})", rawAlarmData.length, bytesToHex(rawAlarmData));
        queueWrite(new FilePutRequest(FileHandle.ALARMS, rawAlarmData, shimAdapter) {
            private boolean errorOccurred = false;

            @Override
            public void onFilePut(boolean success) {
                super.onFilePut(success);
                LOG.info("Raw alarm upload: {}", success ? "SUCCESS" : "FAILED");
                if (!result.isDone()) result.complete(success);
            }

            @Override
            public void handleResponse(BluetoothGattCharacteristic characteristic, byte[] value) {
                try {
                    super.handleResponse(characteristic, value);
                } catch (RuntimeException e) {
                    LOG.warn("Raw alarm upload error: {}", e.getMessage());
                    errorOccurred = true;
                    if (!result.isDone()) result.complete(false);
                }
            }

            @Override
            public boolean isFinished() {
                return errorOccurred || super.isFinished();
            }
        }, false);
    }

    /**
     * Clear all alarms by uploading an empty alarm file (0 alarms = 0 bytes).
     */
    public void clearAlarms(CompletableFuture<Boolean> result) {
        if (!useFossilProtocol) {
            LOG.warn("Alarms not supported on Misfit protocol firmware");
            result.complete(false);
            return;
        }
        LOG.info("Clearing all alarms (uploading empty alarm file)");
        setAlarmsWithResult(new Alarm[0], result);
    }

    public void fetchActivity() {
        fetchActivity(false);
    }

    public void fetchActivity(boolean keep) {
        if (!useFossilProtocol) {
            LOG.info("Activity fetch for Misfit protocol — requesting step count");
            sendMisfitRequest(new ActivityPointGetRequest());
            return;
        }

        queueWrite(new FileLookupAndGetRequest(FileHandle.ACTIVITY_FILE, shimAdapter) {
            @Override
            public void handleFileData(byte[] fileData) {
                LOG.info("Received activity data: {} bytes", fileData.length);
                if (onActivityData != null) {
                    onActivityData.accept(fileData);
                }
                if (!keep) {
                    // Delete the file after reading (official app does this too)
                    queueWrite(new FileDeleteRequest(getHandle()), false);
                } else {
                    LOG.info("Keeping activity data on watch (--keep)");
                }
            }

            @Override
            public void handleFileLookupError(FILE_LOOKUP_ERROR error) {
                if (error == FILE_LOOKUP_ERROR.FILE_EMPTY) {
                    LOG.info("No activity data on watch");
                    if (onActivityData != null) {
                        onActivityData.accept(new byte[0]);
                    }
                } else {
                    LOG.error("Activity file lookup error: {}", error);
                }
            }
        }, false);
    }

    public void setStepGoal(int steps) {
        if (useFossilProtocol) {
            queueWrite(new ConfigurationPutRequest(
                    new ConfigurationPutRequest.DailyStepGoalConfigItem(steps), shimAdapter) {
                @Override
                public void onFilePut(boolean success) {
                    LOG.info("Step goal set to {}: {}", steps, success ? "success" : "FAILED");
                }
            }, false);
        } else {
            sendMisfitRequest(new SetStepGoalRequest(steps));
        }
    }

    public void getStepCount(CompletableFuture<Integer> result) {
        if (!useFossilProtocol) {
            LOG.warn("Step count read not supported on Misfit protocol firmware");
            result.complete(null);
            return;
        }
        CompletableFuture<java.util.List<ConfigEntry>> configFuture = new CompletableFuture<>();
        readConfig(configFuture);
        configFuture.thenAccept(entries -> {
            for (ConfigEntry entry : entries) {
                if (entry.id == 0x0002) {
                    try {
                        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(entry.rawData).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        result.complete(buf.getInt());
                        return;
                    } catch (Exception e) {
                        LOG.warn("Failed to parse DAILY_STEP config item: {}", e.getMessage());
                    }
                }
            }
            result.complete(null);
        }).exceptionally(ex -> {
            result.completeExceptionally(ex);
            return null;
        });
    }

    public void setStepCount(int steps) {
        if (useFossilProtocol) {
            queueWrite(new ConfigurationPutRequest(
                    new ConfigurationPutRequest.CurrentStepCountConfigItem(steps), shimAdapter) {
                @Override
                public void onFilePut(boolean success) {
                    LOG.info("Step count set to {}: {}", steps, success ? "success" : "FAILED");
                }
            }, false);
        } else {
            sendMisfitRequest(new SetCurrentStepCountRequest(steps));
        }
    }

    public void setVibrationStrength(short strength) {
        if (useFossilProtocol) {
            queueWrite(new ConfigurationPutRequest(
                    new ConfigurationPutRequest.VibrationStrengthConfigItem((byte) strength), shimAdapter), false);
        } else {
            sendMisfitRequest(new SetVibrationStrengthRequest(strength));
        }
    }

    public void setTimezoneOffset(short minutes) {
        if (!useFossilProtocol) {
            LOG.warn("Timezone offset not supported on Misfit protocol firmware");
            return;
        }
        // Config 0x000C (TIME) carries the TZ offset the watch uses for display.
        // Config 0x0011 is SECOND timezone — don't touch it here.
        // See FINDINGS.md #21a.
        long millis = System.currentTimeMillis();
        queueWrite(new ConfigurationPutRequest(
                new ConfigurationPutRequest.TimeConfigItem(
                        (int) (millis / 1000),
                        (short) (millis % 1000),
                        minutes),
                shimAdapter), false);
    }

    /**
     * Set the goal tracking target and optionally current value.
     * Config 0x0017 (23) = DAILY_TASK_TRACKING_GOAL (int32 LE) — target count
     * Config 0x0018 (24) = DAILY_TASK_TRACKING_VALUE (int32 LE) — current count
     *
     * The GOAL_TRACKING button increments an on-watch counter. Setting a goal target
     * tells the watch the denominator so it can show progress on the sub-eye.
     * See FINDINGS.md #22.
     */
    public void setGoalConfig(int target, int current) {
        if (!useFossilProtocol) {
            LOG.warn("Goal config not supported on Misfit protocol firmware");
            return;
        }
        // Send both configs in one request (same pattern as DailyStepGoalConfigItem)
        ConfigurationPutRequest.ConfigItem[] items = new ConfigurationPutRequest.ConfigItem[]{
                new ConfigurationPutRequest.GenericConfigItem<>((short) 0x17, target),
                new ConfigurationPutRequest.GenericConfigItem<>((short) 0x18, current)
        };
        queueWrite(new ConfigurationPutRequest(items, shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Goal config (target={}, current={}): {}", target, current, success ? "success" : "FAILED");
            }
        }, false);
    }

    /**
     * Set the second timezone offset (config 0x0011 = SECOND_TIMEZONE_OFFSET).
     * This is displayed when the SECOND_TIMEZONE button function is activated.
     *
     * @param offsetMinutes offset from UTC in minutes (e.g. -300 for EST, 330 for IST).
     *                      Use 1024 to disable the second timezone.
     *                      Valid range: -720 to 840, or 1024.
     * See FINDINGS.md #21a.
     */
    public void setSecondTimezone(short offsetMinutes) {
        if (!useFossilProtocol) {
            LOG.warn("Second timezone not supported on Misfit protocol firmware");
            return;
        }
        queueWrite(new ConfigurationPutRequest(
                new ConfigurationPutRequest.TimezoneOffsetConfigItem(offsetMinutes),
                shimAdapter), false);
    }

    /**
     * Set the inactivity warning (config 0x0009 = INACTIVE_NUDGE).
     * The watch vibrates if idle for the specified minutes between the given times.
     *
     * @param fromHour start hour (0-23)
     * @param fromMinute start minute (0-59)
     * @param toHour end hour (0-23)
     * @param toMinute end minute (0-59)
     * @param inactiveMinutes minutes of inactivity before nudge (e.g. 30, 60)
     * @param enabled true to enable, false to disable
     */
    public void setInactivityNudge(int fromHour, int fromMinute,
                                    int toHour, int toMinute,
                                    int inactiveMinutes, boolean enabled) {
        if (!useFossilProtocol) {
            LOG.warn("Inactivity nudge not supported on Misfit protocol firmware");
            return;
        }
        queueWrite(new ConfigurationPutRequest(
                new ConfigurationPutRequest.InactivityWarningItem(
                        fromHour, fromMinute, toHour, toMinute, inactiveMinutes, enabled),
                shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                if (enabled) {
                    LOG.info("Inactivity nudge ENABLED (every {} min, {}:{}-{}:{}): {}",
                            inactiveMinutes, fromHour, fromMinute, toHour, toMinute,
                            success ? "success" : "FAILED");
                } else {
                    LOG.info("Inactivity nudge DISABLED: {}", success ? "success" : "FAILED");
                }
            }
        }, false);
    }

    public void overwriteButtons(ConfigPayload[] payloads) {
        if (!useFossilProtocol) {
            LOG.warn("Button config not supported on Misfit protocol firmware");
            return;
        }
        ConfigFileBuilder builder = new ConfigFileBuilder(payloads);
        setButtonsRaw(builder.build(true));
    }

    /**
     * Upload a prebuilt button-config file (SETTINGS_BUTTONS, 0x0600). The caller
     * (CLI / FossilController) builds the bytes via ConfigFileBuilder /
     * ButtonConfigBuilder; this just performs the file put.
     */
    public void setButtonsRaw(byte[] buttonConfigFile) {
        if (!useFossilProtocol) {
            LOG.warn("Button config not supported on Misfit protocol firmware");
            return;
        }
        queueWrite(new FilePutRequest(FileHandle.SETTINGS_BUTTONS, buttonConfigFile, shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Button config: {}", success ? "success" : "FAILED");
            }
        }, false);
    }

    /**
     * Build and upload a button config file with multi-entry support.
     * ConfigFileBuilder only supports 1 entry per button, so we build the binary manually
     * when any button has multiple entries.
     *
     * Each button gets an array of ButtonEntry (header+data pairs). Single-entry buttons
     * work normally; multi-entry buttons cycle through their entries on press (mode toggle).
     * See FINDINGS.md #21b.
     */
    public void overwriteButtonsMultiEntry(
            ButtonConfigBuilder.ButtonEntry[] topEntries,
            ButtonConfigBuilder.ButtonEntry[] midEntries,
            ButtonConfigBuilder.ButtonEntry[] botEntries) {
        if (!useFossilProtocol) {
            LOG.warn("Button config not supported on Misfit protocol firmware");
            return;
        }
        byte[] file = ButtonConfigBuilder.build(topEntries, midEntries, botEntries);
        LOG.info("Button config file: {} bytes (multi-entry: top={}, mid={}, bot={})",
                file.length, topEntries.length, midEntries.length, botEntries.length);
        queueWrite(new FilePutRequest(FileHandle.SETTINGS_BUTTONS, file, shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Button config (multi-entry): {}", success ? "success" : "FAILED");
            }
        }, false);
    }

    /**
     * Upload a hand animation choreography to the watch (file handle HAND_ACTIONS = 0x0600).
     * WARNING: This writes to the same file handle as SETTINGS_BUTTONS!
     * The payload format is different from button config (magic 01 00 08 instead of 01 00 00).
     *
     * Based on GadgetBridge's PlayCrazyShitRequest + MicroAppCommand system.
     * Available commands:
     *   StartCritical (03 00) — begin critical section
     *   Close (01 00) — end
     *   Vibrate (93 00 type) — type=04 normal
     *   Delay (08 01 LE_short) — delay in 0.1s units
     *   Animation (09 04 ...) — move hands to absolute position
     *   RepeatStart (86 00 count) — loop start
     *   RepeatStop (07 00) — loop end
     *   Stream (8B 00 mask) — streaming mode
     *
     * @param commands raw concatenated MicroAppCommand bytes
     */
    public void playHandAnimation(byte[] commands) {
        if (!useFossilProtocol) {
            LOG.warn("Hand animations not supported on Misfit protocol firmware");
            return;
        }
        // Build the HAND_ACTIONS file payload
        // Format: [01 00 08] [buttonHeaderContext(8)] [FF] [payloadLen(2 LE)] [commands...] [CRC32]
        //
        // The 8-byte button header context tells firmware which button/app context.
        // We use a minimal context from FORWARD_TO_PHONE (appData[3:11]).
        byte[] headerContext = {
            0x01, 0x0C, 0x2E, 0x00, 0x00, 0x00, 0x01, 0x00
        };

        int totalLen = 3 + 8 + 1 + 2 + commands.length + 4;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(totalLen);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x01);
        buf.put((byte) 0x00);
        buf.put((byte) 0x08);
        buf.put(headerContext);
        buf.put((byte) 0xFF);
        buf.putShort((short)(commands.length + 3));
        buf.put(commands);

        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(buf.array(), 0, buf.position());
        buf.putInt((int) crc.getValue());

        byte[] payload = buf.array();
        LOG.info("Hand animation payload: {} bytes, {} command bytes", payload.length, commands.length);

        queueWrite(new FilePutRequest(FileHandle.HAND_ACTIONS, payload, shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Hand animation: {}", success ? "success" : "FAILED");
            }
        }, false);
    }

    public void setHands(int hourDeg, int minDeg, int subDeg) {
        MoveHandsRequest.MovementConfiguration movement =
                new MoveHandsRequest.MovementConfiguration(false);
        movement.setHourDegrees(hourDeg);
        movement.setMinuteDegrees(minDeg);
        movement.setSubDegrees(subDeg);
        if (useFossilProtocol) {
            queueWrite(new MoveHandsRequest(movement, false), false);
        } else {
            sendMisfitRequest(new MoveHandsRequest(movement, false));
        }
    }

    public void requestHandsControl() {
        sendMisfitRequest(new RequestHandControlRequest());
    }

    public void releaseHandsControl() {
        sendMisfitRequest(new ReleaseHandsControlRequest());
    }

    public void saveCalibration() {
        sendMisfitRequest(new SaveCalibrationRequest());
    }

    public void findDevice() {
        // Use call vibration characteristic
        try {
            shimSupport.createTransactionBuilder("vibrate call")
                    .write(UUID_CHAR_CALL,
                            (byte) 0x01, (byte) 0x04, (byte) 0x30, (byte) 0x75,
                            (byte) 0x00, (byte) 0x00)
                    .queue();
        } catch (Exception e) {
            LOG.error("Error triggering find-device vibration", e);
        }
    }

    public void stopFindDevice() {
        try {
            shimSupport.createTransactionBuilder("stop call vibration")
                    .write(UUID_CHAR_CALL,
                            (byte) 0x02, (byte) 0x05, (byte) 0x04)
                    .queue();
        } catch (Exception e) {
            LOG.error("Error stopping find-device vibration", e);
        }
    }

    /**
     * Read the watch's current configuration (file handle 0x0800).
     * Returns parsed config items via the CompletableFuture.
     *
     * The config file uses a TLV format: [id(2 LE)] [length(1)] [data(length)]...
     * Known config IDs from official Fossil app (DeviceConfigKey.java):
     *   0x01=BIOMETRIC_PROFILE, 0x02=DAILY_STEP, 0x03=DAILY_STEP_GOAL,
     *   0x04=DAILY_CALORIE, 0x05=DAILY_CALORIE_GOAL, 0x06=DAILY_TOTAL_ACTIVE_MIN,
     *   0x07=DAILY_ACTIVE_MIN_GOAL, 0x08=DAILY_DISTANCE, 0x09=INACTIVE_NUDGE,
     *   0x0A=VIBE_STRENGTH, 0x0B=DO_NOT_DISTURB, 0x0C=TIME, 0x0D=BATTERY,
     *   0x0E=HEART_RATE_MODE, 0x0F=DAILY_SLEEP, 0x10=DISPLAY_UNIT,
     *   0x11=SECOND_TIMEZONE_OFFSET, 0x12=CURRENT_HEART_RATE,
     *   0x14=AUTO_WORKOUT_DETECTION, 0x15=CYCLING_CADENCE, 0x16=DAILY_SLEEP_GOAL,
     *   0x17=DAILY_TASK_TRACKING_GOAL, 0x18=DAILY_TASK_TRACKING_VALUE
     * See FINDINGS.md #21f.
     */
    public void readConfig(CompletableFuture<java.util.List<ConfigEntry>> result) {
        if (!useFossilProtocol) {
            LOG.warn("Config read not supported on Misfit protocol firmware");
            result.complete(java.util.Collections.emptyList());
            return;
        }
        LOG.info("Reading configuration from watch...");
        // Use FileLookupAndGetRequest (type 0x02, file lookup by major handle)
        // instead of ConfigurationGetRequest (type 0x01, direct file get).
        // Direct file get returns INVALID_OPERATION_DATA on HW.0.0 firmware.
        // File lookup works (same approach as activity fetch).
        queueWrite(new FileLookupAndGetRequest(FileHandle.CONFIGURATION, shimAdapter) {
            private boolean errorOccurred = false;

            @Override
            public void handleFileData(byte[] fileData) {
                // FileLookupAndGetRequest returns raw file data (no header stripping).
                // The raw data has: [file header (12 bytes)] [config TLV data] [CRC (4 bytes)]
                // FileGetRawRequest.handleFileRawData -> FileGetRequest.handleFileData strips these.
                // But FileLookupAndGetRequest gives us the raw bytes, so we must strip manually.
                LOG.info("Configuration file (raw): {} bytes", fileData.length);
                if (fileData.length < 16) {
                    LOG.warn("Config file too short: {} bytes", fileData.length);
                    result.complete(java.util.Collections.emptyList());
                    return;
                }
                // Strip 12-byte header and 4-byte trailing CRC
                byte[] configData = new byte[fileData.length - 12 - 4];
                System.arraycopy(fileData, 12, configData, 0, configData.length);
                LOG.info("Configuration payload: {} bytes", configData.length);
                java.util.List<ConfigEntry> entries = parseConfigEntries(configData);
                result.complete(entries);
            }

            @Override
            public void handleFileLookupError(FILE_LOOKUP_ERROR error) {
                if (error == FILE_LOOKUP_ERROR.FILE_EMPTY) {
                    LOG.info("Configuration file is empty");
                    result.complete(java.util.Collections.emptyList());
                } else {
                    LOG.warn("Configuration file lookup error: {}", error);
                    result.complete(java.util.Collections.emptyList());
                }
            }

            @Override
            public void handleResponse(BluetoothGattCharacteristic characteristic, byte[] value) {
                try {
                    super.handleResponse(characteristic, value);
                } catch (RuntimeException e) {
                    LOG.warn("Config read failed: {}", e.getMessage());
                    errorOccurred = true;
                    if (!result.isDone()) result.complete(java.util.Collections.emptyList());
                }
            }

            @Override
            public boolean isFinished() {
                return errorOccurred || super.isFinished();
            }
        }, false);
    }

    /**
     * A parsed config entry from the watch.
     */
    public static class ConfigEntry {
        public final int id;
        public final String name;
        public final byte[] rawData;
        public final String formattedValue;

        public ConfigEntry(int id, String name, byte[] rawData, String formattedValue) {
            this.id = id;
            this.name = name;
            this.rawData = rawData;
            this.formattedValue = formattedValue;
        }

        @Override
        public String toString() {
            return String.format("0x%04X %-30s %s", id, name, formattedValue);
        }
    }

    // Config ID → name mapping (from official Fossil app DeviceConfigKey.java)
    private static final java.util.Map<Integer, String> CONFIG_NAMES = java.util.Map.ofEntries(
        java.util.Map.entry(0x01, "BIOMETRIC_PROFILE"),
        java.util.Map.entry(0x02, "DAILY_STEP"),
        java.util.Map.entry(0x03, "DAILY_STEP_GOAL"),
        java.util.Map.entry(0x04, "DAILY_CALORIE"),
        java.util.Map.entry(0x05, "DAILY_CALORIE_GOAL"),
        java.util.Map.entry(0x06, "DAILY_TOTAL_ACTIVE_MIN"),
        java.util.Map.entry(0x07, "DAILY_ACTIVE_MIN_GOAL"),
        java.util.Map.entry(0x08, "DAILY_DISTANCE"),
        java.util.Map.entry(0x09, "INACTIVE_NUDGE"),
        java.util.Map.entry(0x0A, "VIBE_STRENGTH"),
        java.util.Map.entry(0x0B, "DO_NOT_DISTURB"),
        java.util.Map.entry(0x0C, "TIME"),
        java.util.Map.entry(0x0D, "BATTERY"),
        java.util.Map.entry(0x0E, "HEART_RATE_MODE"),
        java.util.Map.entry(0x0F, "DAILY_SLEEP"),
        java.util.Map.entry(0x10, "DISPLAY_UNIT"),
        java.util.Map.entry(0x11, "SECOND_TIMEZONE_OFFSET"),
        java.util.Map.entry(0x12, "CURRENT_HEART_RATE"),
        java.util.Map.entry(0x14, "AUTO_WORKOUT_DETECTION"),
        java.util.Map.entry(0x15, "CYCLING_CADENCE"),
        java.util.Map.entry(0x16, "DAILY_SLEEP_GOAL"),
        java.util.Map.entry(0x17, "DAILY_TASK_TRACKING_GOAL"),
        java.util.Map.entry(0x18, "DAILY_TASK_TRACKING_VALUE")
    );

    /**
     * Parse raw config file data into ConfigEntry list.
     * TLV format: [id(2 LE)] [length(1)] [data(length)]...
     */
    private java.util.List<ConfigEntry> parseConfigEntries(byte[] data) {
        java.util.List<ConfigEntry> entries = new java.util.ArrayList<>();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);

        while (buf.remaining() >= 3) {
            int id = buf.getShort() & 0xFFFF;
            int length = buf.get() & 0xFF;

            if (buf.remaining() < length) {
                LOG.warn("Config entry 0x{} claims {} bytes but only {} remain",
                        String.format("%04X", id), length, buf.remaining());
                break;
            }

            byte[] payload = new byte[length];
            buf.get(payload);

            String name = CONFIG_NAMES.getOrDefault(id, "UNKNOWN_" + String.format("0x%04X", id));
            String formatted = formatConfigValue(id, length, payload);

            entries.add(new ConfigEntry(id, name, payload, formatted));
        }

        return entries;
    }

    /**
     * Format a config value for human-readable display.
     */
    private String formatConfigValue(int id, int length, byte[] data) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);

        try {
            switch (id) {
                case 0x01: { // BIOMETRIC_PROFILE (7 bytes)
                    if (length == 7) {
                        // Format: age(1) gender(1) height_cm(2 LE) weight_kg(2 LE) unknown(1)
                        int age = data[0] & 0xFF;
                        int gender = data[1] & 0xFF;
                        int heightCm = buf.getShort(2) & 0xFFFF;
                        int weightKg = buf.getShort(4) & 0xFFFF;
                        String genderStr = gender == 1 ? "male" : gender == 0 ? "female" : "gender=" + gender;
                        return String.format("age=%d, %s, %dcm, %dkg", age, genderStr, heightCm, weightKg);
                    }
                    break;
                }

                case 0x02: // DAILY_STEP (current step count)
                    if (length == 4) return String.format("%d steps", buf.getInt());
                    break;

                case 0x03: // DAILY_STEP_GOAL
                    if (length == 4) return String.format("%d steps", buf.getInt());
                    break;

                case 0x04: // DAILY_CALORIE
                    if (length == 4) return String.format("%d cal", buf.getInt());
                    break;

                case 0x05: // DAILY_CALORIE_GOAL
                    if (length == 4) return String.format("%d cal", buf.getInt());
                    break;

                case 0x06: // DAILY_TOTAL_ACTIVE_MIN
                    if (length == 2) return String.format("%d min", buf.getShort());
                    break;

                case 0x07: // DAILY_ACTIVE_MIN_GOAL
                    if (length == 2) return String.format("%d min", buf.getShort());
                    break;

                case 0x09: { // INACTIVE_NUDGE (6 bytes)
                    if (length == 6) {
                        int fromH = data[0] & 0xFF, fromM = data[1] & 0xFF;
                        int toH = data[2] & 0xFF, toM = data[3] & 0xFF;
                        int mins = data[4] & 0xFF;
                        boolean enabled = data[5] == 0x01;
                        return String.format("%s (every %d min, %02d:%02d-%02d:%02d)",
                                enabled ? "ENABLED" : "disabled", mins, fromH, fromM, toH, toM);
                    }
                    break;
                }

                case 0x0A: // VIBE_STRENGTH
                    if (length == 1) return String.format("%d%%", data[0] & 0xFF);
                    break;

                case 0x0C: { // TIME (8 bytes: epoch(4) + millis(2) + offset(2))
                    if (length == 8) {
                        long epoch = buf.getInt() & 0xFFFFFFFFL;
                        int millis = buf.getShort() & 0xFFFF;
                        short offset = buf.getShort();
                        java.time.Instant instant = java.time.Instant.ofEpochSecond(epoch);
                        java.time.ZoneOffset zo = java.time.ZoneOffset.ofTotalSeconds(offset * 60);
                        java.time.ZonedDateTime zdt = instant.atZone(zo);
                        return String.format("%s (UTC epoch=%d, offset=%+d min)",
                                zdt.toLocalDateTime().toString(), epoch, offset);
                    }
                    break;
                }

                case 0x0D: { // BATTERY (3-4 bytes: voltage(2) + percent(1) [+ state(1)])
                    if (length >= 3) {
                        int voltage = buf.getShort() & 0xFFFF;
                        int percent = data[2] & 0xFF;
                        if (length >= 4) {
                            int state = data[3] & 0xFF;
                            String stateStr = switch (state) {
                                case 0 -> "DISCHARGING";
                                case 1 -> "CHARGING";
                                case 2 -> "FULL";
                                default -> "state=" + state;
                            };
                            return String.format("%d%% (voltage=%d mV, %s)", percent, voltage, stateStr);
                        }
                        return String.format("%d%% (voltage=%d mV)", percent, voltage);
                    }
                    break;
                }

                case 0x0E: // HEART_RATE_MODE
                    if (length == 1) {
                        int mode = data[0] & 0xFF;
                        return switch (mode) {
                            case 0 -> "OFF (0)";
                            case 1 -> "CONTINUOUS (1)";
                            case 2 -> "INTERVAL (2)";
                            default -> String.format("mode=%d", mode);
                        };
                    }
                    break;

                case 0x10: // DISPLAY_UNIT
                    if (length == 4) {
                        int unit = buf.getInt();
                        return unit == 0 ? "METRIC (0)" : unit == 1 ? "IMPERIAL (1)" : String.format("unit=%d", unit);
                    }
                    break;

                case 0x11: { // SECOND_TIMEZONE_OFFSET
                    if (length == 2) {
                        short offset = buf.getShort();
                        if (offset == 1024) return "DISABLED (1024)";
                        return String.format("UTC%+.1f (%d min)", offset / 60.0, offset);
                    }
                    break;
                }

                case 0x17: // DAILY_TASK_TRACKING_GOAL
                    if (length == 4) return String.format("%d", buf.getInt());
                    break;

                case 0x18: // DAILY_TASK_TRACKING_VALUE
                    if (length == 4) return String.format("%d", buf.getInt());
                    break;

                case 0x14: // AUTO_WORKOUT_DETECTION (30 bytes)
                    if (length >= 24) {
                        return String.format("%d bytes [fitness config]", length);
                    }
                    break;
            }
        } catch (Exception e) {
            LOG.debug("Error formatting config 0x{}: {}", String.format("%04X", id), e.getMessage());
        }

        // Fallback: hex dump + numeric interpretation
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d bytes [", length));
        sb.append(bytesToHex(data));
        sb.append("]");
        if (length == 1) sb.append(String.format(" = %d", data[0] & 0xFF));
        else if (length == 2) sb.append(String.format(" = %d", java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort()));
        else if (length == 4) sb.append(String.format(" = %d", java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()));
        return sb.toString();
    }

    public void setOnActivityData(java.util.function.Consumer<byte[]> callback) {
        this.onActivityData = callback;
    }

    /**
     * Set callback for async events from the watch (button presses, heartbeats, JSON messages, etc.).
     * The callback receives one JSON string per event, suitable for outputting as NDJSON.
     */
    public void setOnEventJson(java.util.function.Consumer<String> callback) {
        this.onEventJson = callback;
    }

    /**
     * Set a callback fired only when the watch actively requests authorization
     * (it vibrates; user must hold the TOP button to confirm or BOTTOM to cancel).
     * Not fired when the watch is already authorized.
     */
    public void setOnAuthRequired(Runnable callback) {
        this.onAuthRequired = callback;
    }

    // ========== Device info ==========

    public String getFirmwareVersion() { return firmwareVersion; }
    public String getModelNumber() { return modelNumber; }
    public int getBatteryLevel() { return batteryLevel; }
    public boolean isFossilProtocol() { return useFossilProtocol; }
    public BleTransport getTransport() { return transport; }

    // ========== Protocol detection ==========

    void detectProtocol(String fwVersion) {
        // Format: XX0.0.M.mm — XX = model code, M = major version
        // Examples: HW0.0.2.13, HL0.0.1.05
        if (fwVersion.length() < 7) {
            LOG.warn("Firmware string too short: '{}', defaulting to Misfit protocol", fwVersion);
            useFossilProtocol = false;
            return;
        }

        char hwVersion = fwVersion.charAt(2); // '0' for coin-cell, '1' for HR
        char major = fwVersion.charAt(6);      // '0', '1', or '2'

        if (hwVersion == '1') {
            throw new UnsupportedOperationException("Hybrid HR watches are not supported (use GadgetBridge)");
        }

        useFossilProtocol = (major == '2');
        LOG.info("Protocol: {} (firmware major={})", useFossilProtocol ? "Fossil" : "Misfit", major);
    }

    // ========== Fossil protocol init ==========

    private void initFossilProtocol() {
        LOG.info("Initializing Fossil protocol ({})...", fullInit ? "full" : "minimal");
        device.setState(GBDevice.State.INITIALIZING);

        // 1. Pairing animation (full init only — cosmetic)
        if (fullInit) {
            queueWrite(new AnimationRequest(), false);
        }

        // 2. Request MTU (TransactionBuilder handles the BLE call)
        queueWrite(new RequestMtuRequest(512), false);

        // 3. Get device info (file versions, security version)
        queueWrite(new GetDeviceInfoRequest(shimAdapter) {
            @Override
            public void handleDeviceInfos(DeviceInfo[] deviceInfos) {
                for (DeviceInfo info : deviceInfos) {
                    if (info instanceof SupportedFileVersionsInfo) {
                        SupportedFileVersionsInfo supportedVersions = (SupportedFileVersionsInfo) info;
                        // Store all supported file versions in shimAdapter
                        // so FilePutRequest/FileGetRequest use correct versions
                        for (FileHandle fh : FileHandle.values()) {
                            try {
                                short version = supportedVersions.getSupportedFileVersion(fh);
                                LOG.debug("File handle {} (0x{}) → version {}", fh, String.format("%02X", fh.getMajorHandle() & 0xFF), version);
                                if (version != 0) {
                                    shimAdapter.setSupportedFileVersion(fh.getMajorHandle(), version);
                                }
                            } catch (NullPointerException e) {
                                // Handle not in supported versions map — skip
                                LOG.debug("File handle {} not in supported versions map", fh);
                            }
                        }
                        LOG.info("Got supported file versions");
                    } else if (info instanceof DeviceSecurityVersionInfo) {
                        device.addDeviceInfo(new GenericItem("DEVICE_SECURITY_VERSION", info.toString()));
                    }
                }

                // 4. Authenticate + continue init on a separate thread.
                // Auth blocks waiting for indications on 3dda0005 — can't block
                // the bluez-monitor thread (which delivers those indications).
                new Thread(() -> {
                    try {
                        performAuthentication();

                        if (fullInit) {
                            // 5. Sync configuration (time, step goal, vibe strength)
                            syncConfiguration();

                            // 6. Sync notification filter
                            syncNotificationSettings();
                        }

                        // 7. Set initialized
                        device.setState(GBDevice.State.INITIALIZED);
                        LOG.info("Watch initialized (Fossil protocol)");
                        if (onInitialized != null) onInitialized.run();
                    } catch (Exception e) {
                        LOG.error("Init failed during auth/sync", e);
                    }
                }, "fossil-auth").start();
            }
        }, false);
    }

    // ========== Authentication handshake ==========

    /**
     * Perform the PROCESS_USER_AUTHORIZATION_V2 handshake on 3dda0005.
     * Must be called BEFORE syncNotificationSettings() — without auth,
     * the watch silently ignores notification filters.
     *
     * Flow:
     *   1. Write 01 07 (GET_USER_AUTHORIZATION_STATUS)
     *   2. Read indication: 03 07 XX (XX=0x00 needs auth, XX=0x01 already authorized)
     *   3. If needs auth: write 02 06 30 75 00 00 01 (30s timeout, removeOtherPhones=true)
     *      Watch vibrates — user must press TOP button within 30s
     *   4. Read indication: 03 06 00 XX (XX=0x01 accepted, XX=0x00 rejected)
     */
    private void performAuthentication() {
        LOG.info("=== Authentication handshake ===");

        // Step 1: Check authorization status
        pendingAuthResponse = new CompletableFuture<>();
        byte[] checkAuth = {0x01, 0x07};
        LOG.info("Writing GET_USER_AUTHORIZATION_STATUS: {}", bytesToHex(checkAuth));
        transport.writeCharacteristic(UUID_CHAR_CALL, checkAuth);

        byte[] statusResponse;
        try {
            statusResponse = pendingAuthResponse.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warn("No response to auth status check (timed out after 10s) — skipping auth");
            pendingAuthResponse = null;
            return;
        } catch (Exception e) {
            LOG.error("Auth status check failed", e);
            pendingAuthResponse = null;
            return;
        }
        pendingAuthResponse = null;

        // Parse response: expect 03 07 XX (or just 03 07 for status=0x00)
        if (statusResponse.length < 2 || statusResponse[0] != 0x03 || statusResponse[1] != 0x07) {
            LOG.info("Unexpected auth status response: {} — skipping auth", bytesToHex(statusResponse));
            return;
        }

        // Status byte: 0x00 = needs auth, 0x01 = already authorized
        // If only 2 bytes received, treat as 0x00 (the null byte may be
        // omitted in the gdbus b'...' format or by the watch itself)
        byte authStatus = (statusResponse.length >= 3) ? statusResponse[2] : 0x00;
        if (authStatus == 0x01) {
            LOG.info("Already authorized (status=0x01) — no button press needed");
            // Still attempt pairing if not yet bonded (provides encrypted link + faster reconnect)
            try {
                transport.pair();
            } catch (Exception e) {
                LOG.debug("Pairing on reconnect: {}", e.getMessage());
            }
            return;
        }

        LOG.info("Authorization required (status=0x{}) — requesting user confirmation",
                String.format("%02X", authStatus));
        if (onAuthRequired != null) {
            try { onAuthRequired.run(); } catch (Exception e) { LOG.warn("onAuthRequired callback failed", e); }
        }

        // Step 2: Request user authorization
        // 02 06 = SET + PROCESS_USER_AUTHORIZATION_V2
        // 30 75 00 00 = 30000ms timeout (little-endian)
        // 01 = removeOtherLinkedPhones
        pendingAuthResponse = new CompletableFuture<>();
        byte[] confirmAuth = {0x02, 0x06, 0x30, 0x75, 0x00, 0x00, 0x01};
        LOG.info("Writing PROCESS_USER_AUTHORIZATION_V2: {}", bytesToHex(confirmAuth));
        LOG.info(">>> PRESS THE TOP BUTTON ON YOUR WATCH WITHIN 30 SECONDS <<<");
        System.out.println("\n  *** Press the TOP button on your watch to authorize ***\n");
        transport.writeCharacteristic(UUID_CHAR_CALL, confirmAuth);

        byte[] confirmResponse;
        try {
            // 35s timeout — 30s watch timeout + 5s margin
            confirmResponse = pendingAuthResponse.get(35, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warn("User did not press button within 30 seconds — authorization failed");
            pendingAuthResponse = null;
            return;
        } catch (Exception e) {
            LOG.error("Authorization confirmation failed", e);
            pendingAuthResponse = null;
            return;
        }
        pendingAuthResponse = null;

        // Parse response: expect 03 06 00 XX
        if (confirmResponse.length < 4 || confirmResponse[0] != 0x03 || confirmResponse[1] != 0x06) {
            LOG.warn("Unexpected auth confirmation response: {}", bytesToHex(confirmResponse));
            return;
        }

        byte userAction = confirmResponse[confirmResponse.length - 1];
        if (userAction == 0x01) {
            LOG.info("Authorization ACCEPTED — notification filters will now take effect!");

            // Initiate BLE pairing immediately after auth succeeds.
            // The official Fossil app does this — it creates a BLE bond (link key)
            // that ties the auth state to the connection. When the bond is later
            // removed (bluetoothctl remove / Android unpair), the watch clears
            // its auth state, requiring re-auth on next connect.
            try {
                transport.pair();
            } catch (Exception e) {
                LOG.warn("BLE pairing failed: {} — continuing without bond", e.getMessage());
            }
        } else {
            LOG.warn("Authorization REJECTED (action=0x{}) — notification filters will be ignored",
                    String.format("%02X", userAction));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }

    private void syncConfiguration() {
        // Resolve settings: prefer caller-injected (FossilController / CLI), else
        // fall back to disk-loading for backward compatibility.
        String mac = transport.getConnectedMac();
        int stepGoal;
        byte vibrationStrength;
        Integer secondTz;
        if (syncSettings != null) {
            stepGoal = syncSettings.stepGoal != null ? syncSettings.stepGoal : 10000;
            vibrationStrength = (byte) (syncSettings.vibrationStrength != null ? syncSettings.vibrationStrength : 100);
            secondTz = syncSettings.secondTimezone;
        } else {
            DeviceConfig deviceConfig = (mac != null) ? DeviceConfig.load(mac) : new DeviceConfig("");
            stepGoal = deviceConfig.getStepGoal();
            vibrationStrength = (byte) deviceConfig.getVibrationStrength();
            secondTz = deviceConfig.getSecondTimezone();
        }

        // Do NOT send TimezoneOffsetConfigItem (0x0011) here — that's the SECOND
        // timezone, not primary. The watch gets primary TZ from TimeConfigItem
        // (0x000C) offset field. Sending 0x0011 overwrites the user's second
        // timezone setting. See FINDINGS.md #21a.
        java.util.List<ConfigurationPutRequest.ConfigItem> items = new java.util.ArrayList<>();
        items.add(new ConfigurationPutRequest.DailyStepGoalConfigItem(stepGoal));
        items.add(new ConfigurationPutRequest.VibrationStrengthConfigItem(vibrationStrength));
        items.add(generateTimeConfigItem());

        // Sync second timezone if configured
        if (secondTz != null) {
            items.add(new ConfigurationPutRequest.TimezoneOffsetConfigItem(secondTz.shortValue()));
        }

        final int fStepGoal = stepGoal;
        final byte fVibe = vibrationStrength;
        LOG.info("Syncing config: stepGoal={}, vibeStrength={}, secondTz={}",
                stepGoal, vibrationStrength & 0xFF,
                secondTz != null ? secondTz : "disabled");
        queueWrite(new ConfigurationPutRequest(
                items.toArray(new ConfigurationPutRequest.ConfigItem[0]), shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Config sync (stepGoal={}, vibeStrength={}): {}",
                        fStepGoal, fVibe & 0xFF,
                        success ? "success" : "FAILED");
                if (success && onConfigSynced != null) {
                    try { onConfigSynced.run(); }
                    catch (Exception e) { LOG.warn("onConfigSynced callback failed: {}", e.getMessage()); }
                }
            }
        }, false);

        // NOTE: Do NOT overwrite button settings during init.
        // The watch persists button config across connections. Overwriting
        // would reset user's stopwatch/music/etc. button assignments.
        // Button config is only written when the user explicitly requests it
        // via the CLI (e.g. future `buttons` command).
    }

    /**
     * Compute CRC32 of packageName + null byte (0x00), matching the official Fossil app.
     * The vendored GB code computes CRC without null terminator, which may cause
     * the watch firmware to not match the filter entry.
     */
    static int computeNullTerminatedCrc(String packageName) {
        byte[] nameBytes = packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withNull = new byte[nameBytes.length + 1];
        System.arraycopy(nameBytes, 0, withNull, 0, nameBytes.length);
        withNull[nameBytes.length] = 0;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(withNull);
        return (int) crc.getValue();
    }

    /**
     * Build the full multi-entry notification filter file (one 32-byte entry per
     * spec) — the platform-neutral entry point used by FossilController and the CLI
     * so callers can construct the bytes without disk-loading config.
     * Byte-identical to the per-entry builder used during init.
     */
    public static byte[] buildNotificationFilterFile(java.util.List<qhybrid.protocol.model.NotificationFilterEntry> entries) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(entries.size() * 32);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (qhybrid.protocol.model.NotificationFilterEntry e : entries) {
            buf.put(buildNotificationFilterData(e.packageName, e.vibe, e.hourDeg, e.minDeg));
        }
        return buf.array();
    }

    /**
     * Build notification filter raw bytes manually, using null-terminated CRC
     * to match the official Fossil app's format.
     */
    /**
     * Build a notification filter entry matching the official Fossil app format.
     * Key fields that differ from GadgetBridge's format:
     * - GROUP_ID = 0 (not 2)
     * - PRIORITY = 0 (not 0xFF)
     * - HAND_MOVEMENT = 10 bytes (includes subeye2Degree)
     * - DISPLAY_CONFIG (0xC4) = present (value 0)
     * - Uses null-terminated CRC
     */
    static byte[] buildNotificationFilterData(String packageName, byte vibePattern,
                                                short hourDeg, short minDeg) {
        int crc = computeNullTerminatedCrc(packageName);
        LOG.info("Filter CRC for '{}': 0x{}", packageName, String.format("%08X", crc));

        // Entry size: packetLength(2) + CRC(6) + GROUP(3) + PRIORITY(3) +
        //             MOVEMENT(12) + DISPLAY(3) + VIBRATION(3) = 32 total
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(32);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);

        buf.putShort((short) 30); // packet length (excluding this 2-byte field)

        buf.put((byte) 0x04);     // PACKAGE_NAME_CRC
        buf.put((byte) 4);
        buf.putInt(crc);

        buf.put((byte) 0x80);     // GROUP_ID
        buf.put((byte) 1);
        buf.put((byte) 0);        // 0 = default group (official app uses 0)

        buf.put((byte) 0xC1);     // PRIORITY
        buf.put((byte) 1);
        buf.put((byte) 0);        // 0 = default priority (official app uses 0)

        buf.put((byte) 0xC2);     // HAND_MOVEMENT (10 bytes: hour, min, subeye, duration, subeye2)
        buf.put((byte) 10);
        buf.putShort(hourDeg);    // hour hand degrees
        buf.putShort(minDeg);     // minute hand degrees
        buf.putShort((short) -1); // subeye: no move
        buf.putShort((short) 10000); // duration 10000ms (official app default)
        buf.putShort((short) -2); // subeye2: device default (-2)

        buf.put((byte) 0xC4);     // DISPLAY_CONFIG
        buf.put((byte) 1);
        buf.put((byte) 0);

        buf.put((byte) 0xC3);     // VIBRATION
        buf.put((byte) 1);
        buf.put(vibePattern);

        return buf.array();
    }

    private void syncNotificationSettings() {
        LOG.info("Syncing notification filter...");
        // Prefer caller-injected settings (FossilController / CLI). Only fall back
        // to disk-loading if no settings were provided, for backward compatibility.
        if (syncSettings != null) {
            var entries = syncSettings.notificationFilter();
            if (entries.isEmpty()) {
                LOG.warn("No notification filter entries supplied — skipping filter upload");
                return;
            }
            uploadNotificationFilterEntries(entries);
            return;
        }
        // Load notification config from disk (device-specific or defaults)
        String mac = transport.getConnectedMac();
        NotificationConfig config = (mac != null) ? NotificationConfig.load(mac) : NotificationConfig.load();
        uploadNotificationFilter(config);
    }

    // ========== Misfit protocol init ==========

    private void initMisfitProtocol() {
        LOG.info("Initializing Misfit protocol...");
        device.setState(GBDevice.State.INITIALIZING);

        // Misfit protocol: queue requests, then trigger the queue by sending a request
        // that expects a response
        sendMisfitRequest(new GetStepGoalRequest());
        sendMisfitRequest(new GetVibrationStrengthRequest());
        sendMisfitRequest(new ActivityPointGetRequest());
        sendMisfitRequest(prepareSetTimeRequest());
        sendMisfitRequest(new AnimationRequest());
        sendMisfitRequest(new SetCurrentStepCountRequest(0));
        sendMisfitRequest(new GetCurrentStepCountRequest());

        device.setState(GBDevice.State.INITIALIZED);
        LOG.info("Watch initialized (Misfit protocol)");
        if (onInitialized != null) onInitialized.run();
    }

    // ========== Time helpers ==========

    /**
     * Generate a time config item for the Fossil protocol.
     *
     * Send UTC epoch. The watch uses the offset field in TimeConfigItem
     * (0x000C) to shift displayed time — NOT config 0x0011, which is for
     * the SECOND timezone only.
     *
     * See FINDINGS.md #4 and #21a for full analysis.
     */
    private ConfigurationPutRequest.TimeConfigItem generateTimeConfigItem() {
        long millis = System.currentTimeMillis();
        TimeZone zone = GregorianCalendar.getInstance().getTimeZone();
        short offsetMinutes = (short) (zone.getOffset(millis) / 60000);
        return new ConfigurationPutRequest.TimeConfigItem(
                (int) (millis / 1000),   // UTC epoch
                (short) (millis % 1000),
                offsetMinutes            // metadata offset
        );
    }

    /**
     * Prepare a Misfit SetTime request. UTC epoch + offset.
     */
    private SetTimeRequest prepareSetTimeRequest() {
        long millis = System.currentTimeMillis();
        TimeZone zone = GregorianCalendar.getInstance().getTimeZone();
        short offsetMinutes = (short) (zone.getOffset(millis) / 60000);
        return new SetTimeRequest(
                (int) (millis / 1000),   // UTC epoch
                (short) (millis % 1000),
                offsetMinutes);
    }

    // ========== Misfit protocol: simple request send ==========

    private void sendMisfitRequest(Request request) {
        shimSupport.createTransactionBuilder(request.getClass().getSimpleName())
                .write(request.getRequestUUID(), request.getRequestData())
                .queue();
    }

    // ========== Fossil protocol: request queue ==========

    /**
     * Queue a FossilRequest. This is also the delegate target for
     * shimAdapter.queueWrite(FossilRequest, boolean).
     */
    public void queueWrite(FossilRequest request, boolean prioritise) {
        if (!transport.isConnected()) {
            LOG.warn("Dropping request {} — not connected", request.getName());
            return;
        }

        // Special handling for RequestMtuRequest — don't write to BLE,
        // just read the current MTU from BlueZ (auto-negotiated)
        if (request instanceof RequestMtuRequest) {
            synchronized (requestQueue) {
                if (currentFossilRequest != null && !currentFossilRequest.isFinished()) {
                    if (prioritise) requestQueue.add(0, request);
                    else requestQueue.add(request);
                    return;
                }
            }
            LOG.debug("Handling RequestMtuRequest (BlueZ auto-negotiates)");
            mtu = transport.getMtu();
            shimAdapter.setMTU(mtu);
            ((RequestMtuRequest) request).setFinished(true);
            LOG.debug("MTU set to {}", mtu);
            queueNextRequest();
            return;
        }

        // Special handling for SetDeviceStateRequest — no BLE write needed
        if (request instanceof SetDeviceStateRequest) {
            GBDevice.State state = ((SetDeviceStateRequest) request).getDeviceState();
            LOG.debug("Setting device state: {}", state);
            device.setState(state);
            queueNextRequest();
            return;
        }

        synchronized (requestQueue) {
            if (currentFossilRequest != null && !currentFossilRequest.isFinished()) {
                LOG.debug("Queuing request: {}", request.getName());
                if (prioritise) {
                    requestQueue.add(0, request);
                } else {
                    requestQueue.add(request);
                }
                return;
            }
        }

        executeRequest(request);
    }

    /**
     * Queue a non-Fossil request (e.g. AnimationRequest, MoveHandsRequest).
     * FossilRequest instances are routed to queueWrite(FossilRequest, boolean).
     */
    private void queueWrite(Request request, boolean prioritise) {
        if (request instanceof FossilRequest) {
            queueWrite((FossilRequest) request, prioritise);
            return;
        }

        // Simple request (misfit-style) — just send it directly
        sendMisfitRequest(request);
        queueNextRequest();
    }

    private void executeRequest(FossilRequest request) {
        LOG.debug("Executing request: {}", request.getName());
        restartTimeout();
        currentFossilRequest = request;

        shimSupport.createTransactionBuilder(request.getClass().getSimpleName())
                .write(request.getRequestUUID(), request.getRequestData())
                .queue();

        if (request.isFinished()) {
            currentFossilRequest = null;
            stopTimeout();
            queueNextRequest();
        }
    }

    private void queueNextRequest() {
        synchronized (requestQueue) {
            if (requestQueue.isEmpty()) {
                LOG.trace("Request queue empty");
                return;
            }
            Request next = requestQueue.remove(0);
            if (next instanceof SetDeviceStateRequest) {
                device.setState(((SetDeviceStateRequest) next).getDeviceState());
                queueNextRequest();
            } else if (next instanceof FossilRequest) {
                executeRequest((FossilRequest) next);
            } else {
                sendMisfitRequest(next);
                queueNextRequest();
            }
        }
    }

    // ========== BLE callbacks ==========

    /**
     * Called by BluezTransport when a BLE notification arrives.
     */
    private void onCharacteristicChanged(UUID uuid, byte[] value) {
        // Ignore notifications when not connected (e.g. during reconnect window)
        if (!transport.isConnected()) {
            LOG.debug("Ignoring notification on {} — not connected", uuid);
            return;
        }

        String uuidStr = uuid.toString();

        switch (uuidStr) {
            case "3dda0006-957f-7d4a-34a6-74696673696d":
                handleButtonEvent(value);
                return;

            case "3dda0002-957f-7d4a-34a6-74696673696d":
                if (!useFossilProtocol) {
                    handleMisfitResponse(uuid, value);
                    return;
                }
                // Fall through for fossil protocol
                break;

            case "3dda0003-957f-7d4a-34a6-74696673696d":
            case "3dda0004-957f-7d4a-34a6-74696673696d":
            case "3dda0005-957f-7d4a-34a6-74696673696d":
                // Auth indication intercept — if we're waiting for an auth response,
                // complete the future and don't pass to the file transfer handler.
                // Critical: we MUST only intercept actual auth responses on 3dda0005
                // that start with 03 (and followed by 07 or 06). Other packets (like
                // 3dda0003, 3dda0004 or non-auth notifications starting with 81) must
                // NOT be intercepted as auth.
                if (uuidStr.equals("3dda0005-957f-7d4a-34a6-74696673696d") &&
                        pendingAuthResponse != null && !pendingAuthResponse.isDone()) {
                    if (value.length >= 2 && value[0] == 0x03 && (value[1] == 0x07 || value[1] == 0x06)) {
                        LOG.info("Auth indication received: {}", bytesToHex(value));
                        pendingAuthResponse.complete(value);
                        return;
                    } else {
                        LOG.debug("Ignoring non-auth notification on 3dda0005 during handshake: {}", bytesToHex(value));
                    }
                }
                // Fossil protocol responses (file transfer)
                break;

            default:
                LOG.trace("Ignoring notification on {}", uuidStr);
                return;
        }

        // Fossil protocol response handling
        if (currentFossilRequest == null) {
            LOG.warn("Received response on {} but no current request", uuidStr);
            return;
        }

        boolean requestFinished;
        // Use the 3-arg constructor (UUID, properties, permissions) so this resolves
        // identically against the JVM stub and the REAL Android BluetoothGattCharacteristic.
        // (Real Android has no 1-arg UUID constructor.) The request handlers only call
        // characteristic.getUuid(), so properties/permissions are irrelevant here.
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(uuid, 0, 0);
        try {
            if (uuidStr.equals("3dda0003-957f-7d4a-34a6-74696673696d")) {
                byte requestType = (byte) (value[0] & 0x0F);
                if (requestType != 0x0A && requestType != currentFossilRequest.getType()) {
                    // Type mismatches on 0x08 (CRC confirmation) and 0x04 (file close)
                    // are expected during file transfer sequences — not an error
                    LOG.trace("Response type 0x{} for request type 0x{}",
                            String.format("%02X", requestType),
                            String.format("%02X", currentFossilRequest.getType()));
                }
            }

            currentFossilRequest.handleResponse(characteristic, value);
            requestFinished = currentFossilRequest.isFinished();
        } catch (RuntimeException e) {
            LOG.error("Error handling response for {}", currentFossilRequest.getName(), e);
            requestFinished = true;
        }

        if (requestFinished) {
            LOG.debug("{} finished", currentFossilRequest.getName());
            currentFossilRequest = null;
            stopTimeout();
        }

        if (requestFinished) {
            queueNextRequest();
        }
    }

    private void handleMisfitResponse(UUID uuid, byte[] value) {
        LOG.debug("Misfit response on {}: {} bytes", uuid, value.length);
        // Misfit protocol responses are simpler — dispatch based on response byte
        // For now, just log
        if (value.length >= 2) {
            LOG.debug("Response type: 0x{}", String.format("%02X", value[1]));
        }
    }

    /**
     * Handle async events on 3dda0006.
     *
     * Binary format: [opCode(1)] [eventType(1)] [sequence(1)] [data...]
     *   opCode: 0x01=REQUEST, 0x02=NOTIFY
     *   eventType values (from official Fossil app AsyncEventType):
     *     0x01 = JSON_FILE_EVENT       (watch sends JSON messages)
     *     0x02 = HEARTBEAT_EVENT       (periodic heartbeat)
     *     0x03 = CONNECTION_PARAM_CHANGE_EVENT
     *     0x04 = APP_NOTIFICATION_EVENT (notification control: dismiss/accept/reply)
     *     0x05 = MUSIC_EVENT           (music control: play/pause/next/prev)
     *     0x06 = BACKGROUND_SYNC_EVENT (background sync frames)
     *     0x07 = SERVICE_CHANGE_EVENT
     *     0x08 = MICRO_APP_EVENT       (micro app events from button actions)
     *     0x09 = AUTHENTICATION_REQUEST_EVENT
     *     0x0B = TIME_SYNC_EVENT
     *     0x0C = BATTERY_EVENT
     *     0x0D = ENCRYPTED_DATA
     *     0x0F = ALARM_SYNC_EVENT
     *     0x10 = WATCH_APP_SYNC_EVENT
     *     0x11 = WATCH_APP_EVENT
     */
    private void handleButtonEvent(byte[] value) {
        if (value.length < 3) return;

        byte opCode = value[0];
        byte eventType = value[1];
        byte sequence = value[2];
        byte[] eventData = (value.length > 3) ? java.util.Arrays.copyOfRange(value, 3, value.length) : new byte[0];

        String opCodeStr = (opCode == 0x01) ? "REQUEST" : (opCode == 0x02) ? "NOTIFY" : String.format("0x%02X", opCode);

        switch (eventType) {
            case 0x01: // JSON_FILE_EVENT
                handleJsonFileEvent(sequence, eventData);
                break;
            case 0x02: // HEARTBEAT_EVENT
                LOG.debug("Watch heartbeat (seq={})", sequence);
                emitEvent("{\"type\":\"heartbeat\",\"sequence\":" + (sequence & 0xFF)
                        + ",\"timestamp\":\"" + nowIso8601() + "\"}");
                break;
            case 0x04: // APP_NOTIFICATION_EVENT
                handleAppNotificationEvent(sequence, eventData);
                break;
            case 0x05: // MUSIC_EVENT
                handleMusicEvent(sequence, eventData);
                break;
            case 0x06: // BACKGROUND_SYNC_EVENT
                LOG.debug("Background sync event (seq={}, {} bytes data)", sequence, eventData.length);
                emitEvent("{\"type\":\"background_sync\",\"sequence\":" + (sequence & 0xFF)
                        + ",\"dataHex\":\"" + bytesToHex(eventData)
                        + "\",\"timestamp\":\"" + nowIso8601() + "\"}");
                break;
            case 0x07: // SERVICE_CHANGE_EVENT
                LOG.info("Service change event (seq={})", sequence);
                emitEvent("{\"type\":\"service_change\",\"sequence\":" + (sequence & 0xFF)
                        + ",\"timestamp\":\"" + nowIso8601() + "\"}");
                break;
            case 0x08: // MICRO_APP_EVENT
                handleMicroAppEvent(sequence, eventData);
                break;
            case 0x09: // AUTHENTICATION_REQUEST_EVENT
                LOG.info("Authentication request event (seq={})", sequence);
                emitEvent("{\"type\":\"auth_request\",\"sequence\":" + (sequence & 0xFF)
                        + ",\"timestamp\":\"" + nowIso8601() + "\"}");
                break;
            case 0x0B: // TIME_SYNC_EVENT
                LOG.debug("Time sync event (seq={})", sequence);
                emitEvent("{\"type\":\"time_sync\",\"sequence\":" + (sequence & 0xFF)
                        + ",\"timestamp\":\"" + nowIso8601() + "\"}");
                break;
            case 0x0C: // BATTERY_EVENT
                handleBatteryEvent(sequence, eventData);
                break;
            case 0x0F: // ALARM_SYNC_EVENT
                LOG.debug("Alarm sync event (seq={}, {} bytes)", sequence, eventData.length);
                emitEvent("{\"type\":\"alarm_sync\",\"sequence\":" + (sequence & 0xFF)
                        + ",\"dataHex\":\"" + bytesToHex(eventData)
                        + "\",\"timestamp\":\"" + nowIso8601() + "\"}");
                break;
            case 0x10: // WATCH_APP_SYNC_EVENT
                LOG.debug("Watch app sync event (seq={}, {} bytes)", sequence, eventData.length);
                emitEvent("{\"type\":\"watch_app_sync\",\"sequence\":" + (sequence & 0xFF)
                        + ",\"dataHex\":\"" + bytesToHex(eventData)
                        + "\",\"timestamp\":\"" + nowIso8601() + "\"}");
                break;
            default:
                LOG.debug("Unknown async event: op={} type=0x{} seq={} data={}",
                        opCodeStr, String.format("%02X", eventType), sequence, bytesToHex(eventData));
                emitEvent("{\"type\":\"unknown\",\"opCode\":" + (opCode & 0xFF)
                        + ",\"eventType\":" + (eventType & 0xFF)
                        + ",\"sequence\":" + (sequence & 0xFF)
                        + ",\"dataHex\":\"" + bytesToHex(eventData)
                        + "\",\"timestamp\":\"" + nowIso8601() + "\"}");
        }
    }

    private void handleJsonFileEvent(byte sequence, byte[] eventData) {
        try {
            String json = new String(eventData, java.nio.charset.StandardCharsets.UTF_8);
            LOG.info("JSON event (seq={}): {}", sequence, json);
            // Escape the JSON for embedding — since it's valid JSON, we can embed it raw
            emitEvent("{\"type\":\"json\",\"sequence\":" + (sequence & 0xFF)
                    + ",\"data\":" + json
                    + ",\"timestamp\":\"" + nowIso8601() + "\"}");
        } catch (Exception e) {
            LOG.warn("Failed to parse JSON event: {}", bytesToHex(eventData));
        }
    }

    private void handleMusicEvent(byte sequence, byte[] eventData) {
        if (eventData.length < 1) {
            LOG.debug("Music event with no data (seq={})", sequence);
            return;
        }
        String action = switch (eventData[0] & 0xFF) {
            case 0 -> "PLAY";
            case 1 -> "PAUSE";
            case 2 -> "TOGGLE_PLAY_PAUSE";
            case 3 -> "NEXT";
            case 4 -> "PREVIOUS";
            case 5 -> "VOLUME_UP";
            case 6 -> "VOLUME_DOWN";
            default -> "UNKNOWN_" + (eventData[0] & 0xFF);
        };
        LOG.info("Music control: {} (seq={})", action, sequence);
        emitEvent("{\"type\":\"music\",\"action\":\"" + action + "\",\"sequence\":" + (sequence & 0xFF)
                + ",\"timestamp\":\"" + nowIso8601() + "\"}");
    }

    private void handleAppNotificationEvent(byte sequence, byte[] eventData) {
        if (eventData.length < 5) {
            LOG.debug("App notification event too short (seq={}, {} bytes)", sequence, eventData.length);
            return;
        }
        int notificationId = java.nio.ByteBuffer.wrap(eventData, 0, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
        int actionByte = eventData[4] & 0xFF;
        String action = switch (actionByte) {
            case 0 -> "ACCEPT_CALL";
            case 1 -> "REJECT_CALL";
            case 2 -> "DISMISS";
            case 3 -> "REPLY";
            default -> "UNKNOWN_" + actionByte;
        };
        LOG.info("App notification control: {} id={} (seq={})", action, notificationId, sequence);
        emitEvent("{\"type\":\"notification_control\",\"action\":\"" + action
                + "\",\"notificationId\":" + notificationId
                + ",\"sequence\":" + (sequence & 0xFF)
                + ",\"timestamp\":\"" + nowIso8601() + "\"}");
    }

    // declarationId → (MicroAppId name, MicroAppVariant name)
    // From official Fossil app MicroAppUtility.UAPP_MAPPING
    private static final java.util.Map<Integer, String[]> MICRO_APP_MAP = java.util.Map.ofEntries(
        java.util.Map.entry(1025,  new String[]{"GOAL_TRACKING", "STANDARD"}),
        java.util.Map.entry(3073,  new String[]{"RING_PHONE", "STANDARD"}),
        java.util.Map.entry(4097,  new String[]{"SELFIE", "STANDARD"}),
        java.util.Map.entry(4609,  new String[]{"MUSIC_CONTROL", "PLAY_PAUSE"}),
        java.util.Map.entry(4610,  new String[]{"MUSIC_CONTROL", "NEXT"}),
        java.util.Map.entry(4611,  new String[]{"MUSIC_CONTROL", "PREVIOUS"}),
        java.util.Map.entry(4612,  new String[]{"MUSIC_CONTROL", "VOLUME_UP"}),
        java.util.Map.entry(4613,  new String[]{"MUSIC_CONTROL", "VOLUME_DOWN"}),
        java.util.Map.entry(4614,  new String[]{"MUSIC_CONTROL", "STANDARD"}),
        java.util.Map.entry(5121,  new String[]{"DATE", "STANDARD"}),
        java.util.Map.entry(5122,  new String[]{"DATE", "SEQUENCED"}),
        java.util.Map.entry(5633,  new String[]{"TIME2", "STANDARD"}),
        java.util.Map.entry(5634,  new String[]{"TIME2", "SEQUENCED"}),
        java.util.Map.entry(6145,  new String[]{"ALERT", "STANDARD"}),
        java.util.Map.entry(6146,  new String[]{"ALERT", "SEQUENCED"}),
        java.util.Map.entry(6657,  new String[]{"ALARM", "STANDARD"}),
        java.util.Map.entry(6658,  new String[]{"ALARM", "SEQUENCED"}),
        java.util.Map.entry(7169,  new String[]{"PROGRESS", "STANDARD"}),
        java.util.Map.entry(7170,  new String[]{"PROGRESS", "SWEEP"}),
        java.util.Map.entry(7681,  new String[]{"TWENTY_FOUR_HOUR", "STANDARD"}),
        java.util.Map.entry(7682,  new String[]{"TWENTY_FOUR_HOUR", "SEQUENCED"}),
        java.util.Map.entry(8193,  new String[]{"STOPWATCH", "STANDARD"}),
        java.util.Map.entry(8705,  new String[]{"WEATHER", "STANDARD"}),
        java.util.Map.entry(9217,  new String[]{"COMMUTE_TIME", "TRAVEL"}),
        java.util.Map.entry(9218,  new String[]{"COMMUTE_TIME", "ETA"})
    );

    private static String buttonName(int eventId) {
        int buttonIdx = (eventId >> 4) & 0x0F;
        return switch (buttonIdx) {
            case 1 -> "TOP";
            case 2 -> "MIDDLE";
            case 3 -> "BOTTOM";
            default -> "BUTTON_" + buttonIdx;
        };
    }

    /**
     * Parse micro app events (eventType=0x08).
     * Data format (from official app MicroAppEvent.java):
     *   [version(1)] [declarationId(2 LE)] [variationNumber(1)] [contextNumber(1)]
     *   [activityId(1)] [eventId(1)] [requestId(1)] [microAppEvent(1)]
     *
     * eventId high nibble = button index: 0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM
     * declarationId maps to MicroAppId (RING_PHONE, STOPWATCH, MUSIC_CONTROL, etc.)
     */
    private void handleMicroAppEvent(byte sequence, byte[] eventData) {
        if (eventData.length < 9) {
            LOG.debug("Micro app event too short (seq={}, {} bytes)", sequence, eventData.length);
            emitEvent("{\"type\":\"micro_app\",\"sequence\":" + (sequence & 0xFF)
                    + ",\"dataHex\":\"" + bytesToHex(eventData)
                    + "\",\"timestamp\":\"" + nowIso8601() + "\"}");
            return;
        }
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(eventData).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int version = buf.get(0) & 0xFF;
        int declarationId = buf.getShort(1) & 0xFFFF;
        int variationNumber = buf.get(3) & 0xFF;
        int contextNumber = buf.get(4) & 0xFF;
        int activityId = buf.get(5) & 0xFF;
        int eventId = buf.get(6) & 0xFF;
        int requestId = buf.get(7) & 0xFF;
        int microAppEvent = buf.get(8) & 0xFF;

        String button = buttonName(eventId);
        String[] appInfo = MICRO_APP_MAP.get(declarationId);
        String appName = (appInfo != null) ? appInfo[0] : "UNKNOWN_" + declarationId;
        String appVariant = (appInfo != null) ? appInfo[1] : "UNKNOWN";

        LOG.info("Button press: {} → {} ({}) seq={}", button, appName, appVariant, sequence);

        // RING_PHONE (declarationId 3073) uses software gesture detection;
        // all other micro apps emit immediately without gesture classification
        if (declarationId == 3073) {
            String jsonTemplate = "{\"type\":\"button\",\"button\":\"" + button
                    + "\",\"app\":\"" + appName
                    + "\",\"variant\":\"" + appVariant
                    + "\",\"declarationId\":" + declarationId
                    + ",\"eventId\":" + eventId
                    + ",\"sequence\":" + (sequence & 0xFF);
            gestureDetector.onPress(button, jsonTemplate);
        } else {
            emitEvent("{\"type\":\"button\",\"button\":\"" + button
                    + "\",\"app\":\"" + appName
                    + "\",\"variant\":\"" + appVariant
                    + "\",\"declarationId\":" + declarationId
                    + ",\"eventId\":" + eventId
                    + ",\"sequence\":" + (sequence & 0xFF)
                    + ",\"timestamp\":\"" + nowIso8601() + "\"}");
        }
    }

    private void handleBatteryEvent(byte sequence, byte[] eventData) {
        if (eventData.length < 2) {
            LOG.debug("Battery event too short (seq={}, {} bytes)", sequence, eventData.length);
            return;
        }
        int stateId = eventData[0] & 0xFF;
        int level = eventData[1] & 0xFF;
        String state = switch (stateId) {
            case 0 -> "DISCHARGING";
            case 1 -> "CHARGING";
            case 2 -> "FULL";
            default -> "UNKNOWN_" + stateId;
        };
        LOG.info("Battery event: state={} level={}% (seq={})", state, level, sequence);
        emitEvent("{\"type\":\"battery\",\"state\":\"" + state + "\",\"level\":" + level
                + ",\"sequence\":" + (sequence & 0xFF)
                + ",\"timestamp\":\"" + nowIso8601() + "\"}");
    }

    private void emitEvent(String json) {
        if (onEventJson != null) {
            onEventJson.accept(json);
        }
    }

    private static String nowIso8601() {
        return java.time.Instant.now().toString();
    }

    private void onMtuChanged(int newMtu) {
        LOG.info("MTU changed: {}", newMtu);
        this.mtu = newMtu;
        shimAdapter.setMTU(newMtu);

        // If we're waiting for an MTU request to complete, mark it finished
        if (currentFossilRequest instanceof RequestMtuRequest) {
            ((RequestMtuRequest) currentFossilRequest).setFinished(true);
            currentFossilRequest = null;
            stopTimeout();
            queueNextRequest();
        }
    }

    // ========== Timeout handling ==========

    private void restartTimeout() {
        stopTimeout();
        timeoutFuture = timeoutExecutor.schedule(() -> {
            String name = currentFossilRequest != null ? currentFossilRequest.getName() : "unknown";
            LOG.warn("Request {} timed out, queuing next", name);
            currentFossilRequest = null;
            queueNextRequest();
        }, REQUEST_TIMEOUT_SECS, TimeUnit.SECONDS);
    }

    private void stopTimeout() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    /**
     * Set the double-press detection window in milliseconds.
     * If a second press of the same button arrives within this window, it's classified as DOUBLE.
     * Otherwise, after the window expires, a SINGLE gesture is emitted.
     * Only affects RING_PHONE (FORWARD_TO_PHONE) buttons.
     * Default: 400ms.
     */
    public void setGestureWindowMs(long ms) {
        gestureDetector.setDoubleWindowMs(ms);
    }

    // ========== Button Gesture Detection ==========

    /**
     * Software-level multi-press detector for FORWARD_TO_PHONE buttons.
     *
     * The watch firmware only sends identical micro_app events for FORWARD_TO_PHONE buttons
     * (no firmware-level gesture detection, unlike MUSIC_CONTROL). This class adds
     * software timing to classify presses as SINGLE or DOUBLE:
     *
     * - First press starts a timer (doubleWindowMs). If no second press arrives
     *   before it fires, emit SINGLE.
     * - Second press within the window cancels the timer and immediately emits DOUBLE.
     *
     * Each physical button (TOP/MIDDLE/BOTTOM) is tracked independently.
     */
    private class ButtonGestureDetector {
        private long doubleWindowMs = 400;

        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gesture-detector");
            t.setDaemon(true);
            return t;
        });

        // Per-button pending state: button name → scheduled single-press emission
        private final Map<String, PendingPress> pending = new ConcurrentHashMap<>();

        private static class PendingPress {
            final String jsonTemplate; // JSON without gesture/timestamp — to be completed on emission
            final ScheduledFuture<?> timer;

            PendingPress(String jsonTemplate, ScheduledFuture<?> timer) {
                this.jsonTemplate = jsonTemplate;
                this.timer = timer;
            }
        }

        void setDoubleWindowMs(long ms) {
            if (ms < 50 || ms > 5000) {
                LOG.warn("Gesture window {}ms out of reasonable range [50-5000], ignoring", ms);
                return;
            }
            this.doubleWindowMs = ms;
            LOG.info("Gesture double-press window set to {}ms", ms);
        }

        /**
         * Called on each RING_PHONE button press. The jsonTemplate contains all fields
         * except "gesture" and "timestamp" — those are added at emission time.
         */
        void onPress(String button, String jsonTemplate) {
            PendingPress prev = pending.remove(button);
            if (prev != null) {
                // Second press within window → DOUBLE
                prev.timer.cancel(false);
                LOG.info("Gesture: {} DOUBLE press", button);
                emitGesture(jsonTemplate, "DOUBLE");
            } else {
                // First press → schedule single-press emission after window
                ScheduledFuture<?> timer = executor.schedule(() -> {
                    pending.remove(button);
                    LOG.info("Gesture: {} SINGLE press", button);
                    emitGesture(jsonTemplate, "SINGLE");
                }, doubleWindowMs, TimeUnit.MILLISECONDS);
                pending.put(button, new PendingPress(jsonTemplate, timer));
            }
        }

        private void emitGesture(String jsonTemplate, String gesture) {
            emitEvent(jsonTemplate
                    + ",\"gesture\":\"" + gesture
                    + "\",\"timestamp\":\"" + nowIso8601() + "\"}");
        }

        void shutdown() {
            executor.shutdownNow();
            // Emit any pending presses immediately as SINGLE before shutdown
            for (Map.Entry<String, PendingPress> entry : pending.entrySet()) {
                entry.getValue().timer.cancel(false);
                emitGesture(entry.getValue().jsonTemplate, "SINGLE");
            }
            pending.clear();
        }
    }

    public void shutdown() {
        gestureDetector.shutdown();
        timeoutExecutor.shutdownNow();
        if (transport.isConnected()) {
            System.err.print("Disconnecting...");
            System.err.flush();
        }
        if (transport instanceof AutoCloseable) {
            try {
                ((AutoCloseable) transport).close();
            } catch (Exception e) {
                LOG.debug("Error closing transport", e);
            }
        }
        System.err.println(" done.");
    }
}
