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
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.alarm.AlarmsGetRequest;
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

    // Authentication handshake
    private CompletableFuture<byte[]> pendingAuthResponse;

    // Callbacks for CLI
    private Runnable onInitialized;
    private java.util.function.Consumer<byte[]> onActivityData;
    private java.util.function.Consumer<String> onEventJson;

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

    /**
     * Build notification file data matching the official Fossil app format.
     * Key difference from GB: lengthBufferLength=12 (not 10), with 2 extra fields
     * (0xFFFFFFFF sentinel + Unix timestamp). The HW.0.0 firmware requires this
     * format for vibration to work.
     */
    private byte[] buildOfficialNotificationFile(String title, String sender, String message) {
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
        int packageCrc = computeNullTerminatedCrc("qhybrid.linux");
        LOG.info("Notification CRC: 0x{}", String.format("%08X", packageCrc));

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
     * Read current alarms from the watch via FileGetRequest on handle 0x0A00.
     * Note: returns empty array if the watch rejects the read (e.g. no alarms file exists).
     */
    public void getAlarms(CompletableFuture<Alarm[]> result) {
        if (!useFossilProtocol) {
            LOG.warn("Alarms not supported on Misfit protocol firmware");
            result.complete(new Alarm[0]);
            return;
        }
        queueWrite(new AlarmsGetRequest(shimAdapter) {
            private boolean errorOccurred = false;

            @Override
            public void handleAlarms(Alarm[] alarms) {
                LOG.info("Read {} alarm(s) from watch", alarms.length);
                result.complete(alarms);
            }

            @Override
            public void handleFileData(byte[] fileData) {
                LOG.info("Alarm file: {} bytes", fileData.length);
                super.handleFileData(fileData);
            }

            @Override
            public void handleResponse(BluetoothGattCharacteristic characteristic, byte[] value) {
                try {
                    super.handleResponse(characteristic, value);
                } catch (RuntimeException e) {
                    LOG.warn("Alarm read failed: {}", e.getMessage());
                    errorOccurred = true;
                    result.complete(new Alarm[0]);
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

    /**
     * Set callback for async events from the watch (button presses, heartbeats, JSON messages, etc.).
     * The callback receives one JSON string per event, suitable for outputting as NDJSON.
     */
    public void setOnEventJson(java.util.function.Consumer<String> callback) {
        this.onEventJson = callback;
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

                // 4. Authenticate + continue init on a separate thread.
                // Auth blocks waiting for indications on 3dda0005 — can't block
                // the bluez-monitor thread (which delivers those indications).
                new Thread(() -> {
                    try {
                        performAuthentication();

                        // 5. Sync configuration
                        syncConfiguration();

                        // 6. Sync notification filter
                        syncNotificationSettings();

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
            LOG.warn("Unexpected auth status response: {} — skipping auth", bytesToHex(statusResponse));
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
    /**
     * Build a notification filter entry matching the official Fossil app format.
     * Key fields that differ from GadgetBridge's format:
     * - GROUP_ID = 0 (not 2)
     * - PRIORITY = 0 (not 0xFF)
     * - HAND_MOVEMENT = 10 bytes (includes subeye2Degree)
     * - DISPLAY_CONFIG (0xC4) = present (value 0)
     * - Uses null-terminated CRC
     */
    private byte[] buildNotificationFilterData(String packageName, byte vibePattern,
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
        // Build filter data manually with null-terminated CRC (matching official Fossil app).
        // Official app requires at least 2 filter entries (GB duplicates if only 1).
        String packageName = "qhybrid.linux";
        byte vibePattern = 4; // DEFAULT_OTHER_APPS (official Fossil app default)
        short hourDeg = 90;   // 3 o'clock position for generic notifications
        short minDeg = 90;
        byte[] filter = buildNotificationFilterData(packageName, vibePattern, hourDeg, minDeg);

        queueWrite(new FilePutRequest(FileHandle.NOTIFICATION_FILTER, filter, shimAdapter) {
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
                // Auth indication intercept — if we're waiting for an auth response,
                // complete the future and don't pass to the file transfer handler
                if (pendingAuthResponse != null && !pendingAuthResponse.isDone()) {
                    LOG.info("Auth indication received: {}", bytesToHex(value));
                    pendingAuthResponse.complete(value);
                    return;
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

        emitEvent("{\"type\":\"button\",\"button\":\"" + button
                + "\",\"app\":\"" + appName
                + "\",\"variant\":\"" + appVariant
                + "\",\"declarationId\":" + declarationId
                + ",\"eventId\":" + eventId
                + ",\"sequence\":" + (sequence & 0xFF)
                + ",\"timestamp\":\"" + nowIso8601() + "\"}");
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

    public void shutdown() {
        timeoutExecutor.shutdownNow();
        if (transport instanceof AutoCloseable) {
            try {
                ((AutoCloseable) transport).close();
            } catch (Exception e) {
                LOG.debug("Error closing transport", e);
            }
        }
    }
}
