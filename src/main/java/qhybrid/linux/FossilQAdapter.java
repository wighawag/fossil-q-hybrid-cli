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
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.notification.NotificationFilterPutRequest;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.notification.PlayTextNotificationRequest;
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

    // Callbacks for CLI
    private Runnable onInitialized;
    private java.util.function.Consumer<byte[]> onActivityData;

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
     * Initialize the watch. Reads device info, detects protocol, runs init sequence.
     */
    public void initialize() {
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

    public void playNotification(PlayNotificationRequest.VibrationType vibration, int hourDeg, int minDeg) {
        if (useFossilProtocol) {
            // File-based notification (hand animation only on HW.0.0 — vibration
            // doesn't work via file protocol despite filter upload succeeding).
            // We still send it for the hand animation effect.
            queueWrite(new PlayTextNotificationRequest("qhybrid.linux", shimAdapter), false);

            // Vibrate via authentication characteristic (3dda0005).
            // This is the only reliable way to produce vibration on HW.0.0 firmware.
            // The official Fossil app uses this char for key exchange, but writing
            // to it triggers the vibration motor as a side effect.
            if (vibration != PlayNotificationRequest.VibrationType.NO_VIBE) {
                findDevice();
                new Thread(() -> {
                    try {
                        Thread.sleep(getVibrationDuration(vibration));
                        stopFindDevice();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        } else {
            sendMisfitRequest(new PlayNotificationRequest(vibration, hourDeg, minDeg));
        }
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

    public void fetchActivity() {
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
                // Delete the file after reading
                queueWrite(new FileDeleteRequest(getHandle()), false);
            }

            @Override
            public void handleFileLookupError(FILE_LOOKUP_ERROR error) {
                if (error == FILE_LOOKUP_ERROR.FILE_EMPTY) {
                    LOG.info("No activity data on watch");
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
        // Send both timezone offset AND time together — the watch uses the
        // TimezoneOffsetConfigItem to shift the UTC epoch for display.
        // TimeConfigItem carries the UTC epoch; the offset in TimeConfigItem
        // is metadata for activity timestamps.
        long millis = System.currentTimeMillis();
        queueWrite(new ConfigurationPutRequest(new ConfigurationPutRequest.ConfigItem[]{
                new ConfigurationPutRequest.TimezoneOffsetConfigItem(minutes),
                new ConfigurationPutRequest.TimeConfigItem(
                        (int) (millis / 1000),
                        (short) (millis % 1000),
                        minutes)
        }, shimAdapter), false);
    }

    public void overwriteButtons(ConfigPayload[] payloads) {
        if (!useFossilProtocol) {
            LOG.warn("Button config not supported on Misfit protocol firmware");
            return;
        }
        ConfigFileBuilder builder = new ConfigFileBuilder(payloads);
        queueWrite(new FilePutRequest(FileHandle.SETTINGS_BUTTONS, builder.build(true), shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Button config: {}", success ? "success" : "FAILED");
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

    public void setOnActivityData(java.util.function.Consumer<byte[]> callback) {
        this.onActivityData = callback;
    }

    // ========== Device info ==========

    public String getFirmwareVersion() { return firmwareVersion; }
    public String getModelNumber() { return modelNumber; }
    public int getBatteryLevel() { return batteryLevel; }
    public boolean isFossilProtocol() { return useFossilProtocol; }

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
        LOG.info("Initializing Fossil protocol...");
        device.setState(GBDevice.State.INITIALIZING);

        // 1. Pairing animation
        queueWrite(new AnimationRequest(), false);

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

                // 4. Sync configuration
                syncConfiguration();

                // 5. Sync notification filter
                syncNotificationSettings();

                // 6. Set initialized
                device.setState(GBDevice.State.INITIALIZED);
                LOG.info("Watch initialized (Fossil protocol)");
                if (onInitialized != null) onInitialized.run();
            }
        }, false);
    }

    private void syncConfiguration() {
        int stepGoal = 10000; // default
        byte vibrationStrength = 100;
        // TimezoneOffsetConfigItem (0x0011): the watch uses this to shift
        // the UTC epoch for display. Must include DST.
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        short timezoneOffset = (short) (zone.getOffset(now) / 60000);

        device.addDeviceInfo(new GenericItem(QHybridSupport.ITEM_TIMEZONE_OFFSET,
                String.valueOf(timezoneOffset)));

        queueWrite(new ConfigurationPutRequest(new ConfigurationPutRequest.ConfigItem[]{
                new ConfigurationPutRequest.DailyStepGoalConfigItem(stepGoal),
                new ConfigurationPutRequest.VibrationStrengthConfigItem(vibrationStrength),
                new ConfigurationPutRequest.TimezoneOffsetConfigItem(timezoneOffset),
                generateTimeConfigItem()
        }, shimAdapter), false);

        // Sync button settings (default: all forward to phone)
        ConfigPayload[] payloads = new ConfigPayload[]{
                ConfigPayload.FORWARD_TO_PHONE,
                ConfigPayload.FORWARD_TO_PHONE,
                ConfigPayload.FORWARD_TO_PHONE
        };
        ConfigFileBuilder builder = new ConfigFileBuilder(payloads);
        queueWrite(new FilePutRequest(FileHandle.SETTINGS_BUTTONS, builder.build(true), shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.debug("Button config init: {}", success ? "success" : "FAILED");
            }
        }, false);
    }

    /**
     * Compute CRC32 of packageName + null byte (0x00), matching the official Fossil app.
     * The vendored GB code computes CRC without null terminator, which may cause
     * the watch firmware to not match the filter entry.
     */
    private int computeNullTerminatedCrc(String packageName) {
        byte[] nameBytes = packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] withNull = new byte[nameBytes.length + 1];
        System.arraycopy(nameBytes, 0, withNull, 0, nameBytes.length);
        withNull[nameBytes.length] = 0;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(withNull);
        return (int) crc.getValue();
    }

    /**
     * Build notification filter raw bytes manually, using null-terminated CRC
     * to match the official Fossil app's format.
     */
    private byte[] buildNotificationFilterData(String packageName, byte vibePattern) {
        int crc = computeNullTerminatedCrc(packageName);

        // Each filter entry: [packetLength(2)] [entries...]
        // Entry format: [entryId(1)] [dataLength(1)] [data...]
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(27);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);

        buf.putShort((short) 25); // packet length (excluding this field)

        buf.put((byte) 0x04);     // PACKAGE_NAME_CRC
        buf.put((byte) 4);
        buf.putInt(crc);

        buf.put((byte) 0x80);     // GROUP_ID
        buf.put((byte) 1);
        buf.put((byte) 2);

        buf.put((byte) 0xC1);     // PRIORITY
        buf.put((byte) 1);
        buf.put((byte) 0xFF);

        buf.put((byte) 0xC2);     // MOVEMENT (hour, min, subeye, duration_ms)
        buf.put((byte) 8);
        buf.putShort((short) -1); // hour: no move
        buf.putShort((short) -1); // min: no move
        buf.putShort((short) -1); // subeye: no move
        buf.putShort((short) 5000); // duration

        buf.put((byte) 0xC3);     // VIBRATION
        buf.put((byte) 1);
        buf.put(vibePattern);

        return buf.array();
    }

    private void syncNotificationSettings() {
        LOG.info("Syncing notification filter...");
        // Build filter data manually with null-terminated CRC (matching official Fossil app).
        // Official app requires at least 2 filter entries (GB duplicates if only 1).
        String packageName = "qhybrid.linux";
        byte vibePattern = 5; // ONE_SHORT_VIBE (official Fossil app value)
        byte[] filter1 = buildNotificationFilterData(packageName, vibePattern);
        byte[] filter2 = buildNotificationFilterData(packageName, vibePattern);
        byte[] combined = new byte[filter1.length + filter2.length];
        System.arraycopy(filter1, 0, combined, 0, filter1.length);
        System.arraycopy(filter2, 0, combined, filter1.length, filter2.length);

        queueWrite(new FilePutRequest(FileHandle.NOTIFICATION_FILTER, combined, shimAdapter) {
            @Override
            public void onFilePut(boolean success) {
                LOG.info("Notification filter sync: {}", success ? "success" : "FAILED");
            }
        }, false);
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
     * Send UTC epoch. The watch uses TimezoneOffsetConfigItem (0x0011)
     * to shift the displayed time. The offset field inside TimeConfigItem
     * is metadata (activity timestamps etc.).
     *
     * See FINDINGS.md #4 for full analysis.
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
                // Fossil protocol responses
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
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(uuid);
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

    private void handleButtonEvent(byte[] value) {
        if (value.length < 3) return;
        byte eventType = value[1];

        switch (eventType) {
            case 0x02: // Heartbeat
                LOG.debug("Watch heartbeat");
                break;
            case 0x08: // Button press (Fossil protocol)
                if (value.length >= 10) {
                    int button = (value[9] >> 4) & 0xFF;
                    LOG.info("Button press: button {}", button);
                }
                break;
            case 0x05: // Multi-button press
                if (value.length >= 4) {
                    int action = value[3];
                    String actionStr = switch (action) {
                        case 1 -> "SINGLE";
                        case 3 -> "DOUBLE";
                        case 4 -> "LONG";
                        default -> "UNKNOWN(" + action + ")";
                    };
                    LOG.info("Multi-button action: {}", actionStr);
                }
                break;
            default:
                LOG.debug("Unknown button event type: 0x{}", String.format("%02X", eventType));
        }
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

    public void shutdown() {
        timeoutExecutor.shutdownNow();
    }
}
