package qhybrid.linux;

import com.github.hypfvieh.bluetooth.DeviceManager;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattCharacteristic;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattService;
import org.bluez.exceptions.*;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.types.UInt16;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * BLE transport using dbus-java + bluez-dbus for direct D-Bus access to BlueZ.
 *
 * Advantages over BluezTransport (subprocess-based):
 * - No subprocess fork/exec per BLE operation (~1ms vs ~50-100ms)
 * - Single GetManagedObjects call for discovery (vs ~44 busctl calls)
 * - Native signal handlers for notifications (vs gdbus monitor + regex parsing)
 * - Persistent D-Bus connection keeps StartNotify alive (vs bluetoothctl hack)
 * - No fragile stdout parsing (gdbus b'...' vs [byte ...] format)
 *
 * Expected: connect+init from ~8s to ~3-5s.
 */
public class DbusTransport implements BleTransport, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DbusTransport.class);

    private DeviceManager deviceManager;
    private BluetoothAdapter adapter;
    private BluetoothDevice device;
    private String macAddress;
    private String devicePath; // e.g. /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF

    private final Map<UUID, BluetoothGattCharacteristic> characteristics = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> charFlags = new ConcurrentHashMap<>();
    private final Map<String, UUID> pathToUuid = new ConcurrentHashMap<>();

    private volatile boolean connected = false;
    private volatile int mtu = 23;

    private BiConsumer<UUID, byte[]> notificationCallback;
    private Consumer<Boolean> connectionCallback;
    private Consumer<Integer> mtuCallback;

    // Signal handler registration for cleanup
    private AbstractPropertiesChangedHandler propertiesHandler;

    @Override
    public boolean connect(String macAddress) {
        this.macAddress = macAddress;
        this.devicePath = "/org/bluez/hci0/dev_" + macAddress.replace(":", "_");

        LOG.info("Connecting to {} (dbus-java transport)...", macAddress);

        try {
            // Create DeviceManager — opens a persistent D-Bus system connection
            deviceManager = DeviceManager.createInstance(false);

            // Find the adapter
            List<BluetoothAdapter> adapters = deviceManager.scanForBluetoothAdapters();
            if (adapters.isEmpty()) {
                LOG.error("No Bluetooth adapters found");
                return false;
            }
            adapter = adapters.get(0);
            LOG.debug("Using adapter: {} ({})", adapter.getDeviceName(), adapter.getAddress());

            // Look for device — try without scanning first (fast path for known devices)
            device = findDeviceByMac(macAddress, false);

            if (device != null && Boolean.TRUE.equals(device.isConnected())) {
                LOG.info("Already connected to {}", macAddress);
                connected = true;
            } else {
                // Clear any stale connection
                if (device != null) {
                    try {
                        device.disconnect();
                    } catch (Exception e) {
                        LOG.debug("Stale disconnect: {}", e.getMessage());
                    }
                    sleep(300);
                }

                if (device == null) {
                    // Device not known to BlueZ — scan and connect in one flow.
                    // Must keep discovery active while connecting (watch only
                    // advertises briefly after button press).
                    if (!scanAndConnect(macAddress, 30_000)) {
                        LOG.error("Could not find or connect to {}.", macAddress);
                        LOG.error("Make sure the watch is awake (press the button) and not paired to another device.");
                        return false;
                    }
                } else {
                    // Device known to BlueZ — trust and connect directly
                    device.setTrusted(true);
                    if (!connectDevice()) {
                        LOG.error("Failed to connect to {}", macAddress);
                        return false;
                    }
                }
            }

            // Wait for GATT services to resolve, with retry
            if (!waitForServicesAndRetry()) {
                LOG.error("GATT services not resolved");
                return false;
            }

            // Discover characteristics
            discoverCharacteristics();

            // Read MTU
            negotiateMtu();

            // Register PropertiesChanged handler for notifications + connection state
            registerSignalHandlers();

            // Enable notifications on all Fossil characteristics
            enableAllNotifications();

            connected = true;
            if (connectionCallback != null) {
                connectionCallback.accept(true);
            }

            return true;

        } catch (DBusException e) {
            LOG.error("D-Bus connection failed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.error("Connection failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Find a BluetoothDevice by MAC address.
     * If scan=true, runs BLE discovery first.
     */
    private BluetoothDevice findDeviceByMac(String mac, boolean scan) {
        if (!scan) {
            // Check known devices without scanning
            deviceManager.findBtDevicesByIntrospection(adapter);
            List<BluetoothDevice> devices = deviceManager.getDevices(true);
            for (BluetoothDevice d : devices) {
                if (mac.equalsIgnoreCase(d.getAddress())) {
                    return d;
                }
            }
            return null;
        }

        // Scan with timeout
        List<BluetoothDevice> devices = deviceManager.scanForBluetoothDevices(5000);
        for (BluetoothDevice d : devices) {
            if (mac.equalsIgnoreCase(d.getAddress())) {
                LOG.info("Found device {} during scan", mac);
                return d;
            }
        }
        LOG.info("Device not found in 5s scan, trying extended 20s scan...");
        devices = deviceManager.scanForBluetoothDevices(20000);
        for (BluetoothDevice d : devices) {
            if (mac.equalsIgnoreCase(d.getAddress())) {
                LOG.info("Found device {} during extended scan", mac);
                return d;
            }
        }
        return null;
    }

    /**
     * Scan and connect in one flow — keeps discovery active while connecting.
     * Critical: the watch only advertises briefly (~30s after button press).
     * If we stop scanning before connecting, the connect may fail.
     */
    private boolean scanAndConnect(String mac, long timeoutMs) {
        LOG.info("Scanning for device... Press the watch button to wake it up.");

        try {
            // Start discovery
            adapter.startDiscovery();

            long deadline = System.currentTimeMillis() + timeoutMs;
            long lastLog = 0;

            while (System.currentTimeMillis() < deadline) {
                // Check if BlueZ now knows the device
                deviceManager.findBtDevicesByIntrospection(adapter);
                List<BluetoothDevice> devices = deviceManager.getDevices(true);
                for (BluetoothDevice d : devices) {
                    if (mac.equalsIgnoreCase(d.getAddress())) {
                        // Found it! Trust and connect while scan is still active
                        device = d;
                        LOG.info("Found device {} during scan", mac);
                        device.setTrusted(true);

                        // Connect (scan still running — device is still advertising)
                        try {
                            boolean ok = device.connect();
                            LOG.info("connect() returned {}, isConnected={}", ok, device.isConnected());
                            if (ok || Boolean.TRUE.equals(device.isConnected())) {
                                connected = true;
                                LOG.info("Connected to {}", mac);
                            }
                        } catch (Exception e) {
                            LOG.warn("Connect during scan: {} ({})", e.getClass().getSimpleName(), e.getMessage());
                        }

                        if (!connected) {
                            // Poll for async connection (connect can complete after initial call)
                            LOG.info("Polling for connection...");
                            long connectDeadline = System.currentTimeMillis() + 15_000;
                            while (System.currentTimeMillis() < connectDeadline) {
                                sleep(250);
                                try {
                                    if (Boolean.TRUE.equals(device.isConnected())) {
                                        connected = true;
                                        LOG.info("Connected to {} (async)", mac);
                                        break;
                                    }
                                } catch (Exception e2) { break; }
                            }
                        }

                        // Stop discovery and return
                        try { adapter.stopDiscovery(); } catch (Exception e) { /* ignore */ }
                        return connected;
                    }
                }

                sleep(500);
                long remaining = (deadline - System.currentTimeMillis()) / 1000;
                if (remaining > 0 && remaining != lastLog && remaining % 5 == 0) {
                    lastLog = remaining;
                    LOG.info("Still scanning... ({}s remaining). Press the watch button if not done already.", remaining);
                }
            }

            // Timeout — stop discovery
            try { adapter.stopDiscovery(); } catch (Exception e) { /* ignore */ }
            LOG.error("Could not find device {} within {}s", mac, timeoutMs / 1000);
            return false;

        } catch (Exception e) {
            LOG.error("Scan/connect failed: {}", e.getMessage());
            try { adapter.stopDiscovery(); } catch (Exception e2) { /* ignore */ }
            return false;
        }
    }

    /**
     * Connect to the device with retries.
     * BluetoothDevice.connect() can throw NoReply (D-Bus timeout) if the watch
     * is dormant — catch all exceptions and retry.
     */
    /**
     * Connect to the device with retries.
     * Handles NoReply (D-Bus timeout when device is dormant) and "In Progress"
     * (stale connection from a previous attempt) by disconnecting before retry.
     */
    private boolean connectDevice() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            LOG.info("Connecting to {}... (attempt {})", macAddress, attempt);

            // Clear any stale "In Progress" from a previous attempt
            if (attempt > 1) {
                try { device.disconnect(); } catch (Exception e) { /* ignore */ }
                sleep(1000);
            }

            try {
                boolean ok = device.connect();
                if (ok || Boolean.TRUE.equals(device.isConnected())) {
                    connected = true;
                    LOG.info("Connected to {}", macAddress);
                    return true;
                }
            } catch (Exception e) {
                LOG.warn("Connect attempt {} failed: {} ({})",
                        attempt, e.getClass().getSimpleName(), e.getMessage());
            }

            // Poll Connected — connection may complete asynchronously
            for (int poll = 0; poll < 10; poll++) {
                sleep(500);
                try {
                    if (Boolean.TRUE.equals(device.isConnected())) {
                        connected = true;
                        LOG.info("Connected to {} (async)", macAddress);
                        return true;
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }
        LOG.error("Failed to connect after 3 attempts. Is the watch awake? (press button)");
        return false;
    }

    /**
     * Wait for ServicesResolved=true, with GATT retry logic.
     * On first connect after removing a device, BlueZ sometimes fails GATT discovery.
     * Retry up to 3 times — each attempt builds on BlueZ's cached service layout.
     */
    private boolean waitForServicesAndRetry() {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (waitForServicesResolved(5000)) {
                return true;
            }
            if (attempt < 2) {
                LOG.info("GATT not resolved, reconnecting (attempt {})...", attempt + 2);
                device.disconnect();
                connected = false;
                sleep(1000);
                if (!device.connect()) {
                    // Retry connect itself
                    sleep(500);
                    if (!device.connect()) {
                        LOG.error("Reconnect failed");
                        return false;
                    }
                }
                connected = true;
                LOG.info("Reconnected to {}", macAddress);
            }
        }
        LOG.error("GATT services not resolved after 3 attempts");
        return false;
    }

    /**
     * Poll ServicesResolved property until true or timeout.
     */
    private boolean waitForServicesResolved(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Boolean resolved = device.isServicesResolved();
            if (Boolean.TRUE.equals(resolved)) {
                LOG.debug("GATT services resolved");
                return true;
            }
            sleep(250);
        }
        return false;
    }

    /**
     * Discover GATT characteristics via bluez-dbus wrapper objects.
     * One call to getGattServices() replaces ~44 subprocess busctl calls.
     */
    private void discoverCharacteristics() {
        characteristics.clear();
        pathToUuid.clear();
        charFlags.clear();

        List<BluetoothGattService> services = device.getGattServices();
        for (BluetoothGattService service : services) {
            for (BluetoothGattCharacteristic ch : service.getGattCharacteristics()) {
                String uuidStr = ch.getUuid();
                if (uuidStr == null) continue;
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    characteristics.put(uuid, ch);
                    pathToUuid.put(ch.getDbusPath(), uuid);

                    List<String> flags = ch.getFlags();
                    if (flags != null) {
                        charFlags.put(uuid, flags);
                    }

                    LOG.debug("Characteristic: {} → {} flags={}", uuidStr, ch.getDbusPath(), flags);
                } catch (IllegalArgumentException e) {
                    LOG.warn("Invalid UUID: {}", uuidStr);
                }
            }
        }
        LOG.info("Discovered {} characteristics", characteristics.size());
    }

    /**
     * Read MTU from a characteristic property.
     */
    private void negotiateMtu() {
        // Read MTU from any GATT characteristic that exposes it.
        // Not all characteristics have the MTU property — iterate through all services.
        // Note: Fossil chars (service0028+) often don't expose MTU, but standard
        // GATT chars (service0001) do.
        DBusConnection dbus = deviceManager.getDbusConnection();
        List<BluetoothGattService> allServices = device.getGattServices();
        LOG.debug("Looking for MTU across {} services", allServices.size());
        for (BluetoothGattService service : allServices) {
            for (BluetoothGattCharacteristic ch : service.getGattCharacteristics()) {
                try {
                    Properties props = dbus.getRemoteObject("org.bluez",
                            ch.getDbusPath(), Properties.class);
                    Object mtuObj = props.Get("org.bluez.GattCharacteristic1", "MTU");
                    if (mtuObj != null) {
                        // dbus-java may return UInt16, Variant<UInt16>, or other Number types
                        if (mtuObj instanceof Variant<?>) {
                            mtuObj = ((Variant<?>) mtuObj).getValue();
                        }
                        if (mtuObj instanceof UInt16) {
                            mtu = ((UInt16) mtuObj).intValue();
                        } else if (mtuObj instanceof Number) {
                            mtu = ((Number) mtuObj).intValue();
                        }
                        if (mtu > 23) {
                            LOG.info("Negotiated MTU: {}", mtu);
                            if (mtuCallback != null) mtuCallback.accept(mtu);
                            return;
                        }
                    }
                } catch (Exception e) {
                    LOG.trace("MTU not available on {}: {}", ch.getDbusPath(), e.getMessage());
                }
            }
        }

        LOG.info("Could not determine MTU, using default: {}", mtu);
    }

    /**
     * Register PropertiesChanged signal handler for notifications and connection state.
     * This replaces the gdbus monitor subprocess entirely.
     */
    private void registerSignalHandlers() throws DBusException {
        propertiesHandler = new AbstractPropertiesChangedHandler() {
            @Override
            public void handle(Properties.PropertiesChanged signal) {
                String path = signal.getPath();
                Map<String, Variant<?>> changed = signal.getPropertiesChanged();

                // Handle characteristic Value changes (BLE notifications)
                if (changed.containsKey("Value")) {
                    UUID uuid = pathToUuid.get(path);
                    if (uuid != null && notificationCallback != null) {
                        try {
                            Object rawValue = changed.get("Value").getValue();
                            byte[] data = extractBytes(rawValue);
                            if (data != null && data.length > 0) {
                                LOG.debug("Notification on {}: {} bytes [{}]",
                                        uuid.toString().substring(4, 8),
                                        data.length,
                                        data.length <= 16 ? bytesToHex(data) : bytesToHex(data, 16) + "...");
                                notificationCallback.accept(uuid, data);
                            }
                        } catch (Exception e) {
                            LOG.error("Error processing notification on {}", path, e);
                        }
                    }
                }

                // Handle connection state changes
                if (changed.containsKey("Connected") && path.equals(devicePath)) {
                    boolean isConnected = Boolean.TRUE.equals(changed.get("Connected").getValue());
                    if (isConnected != connected) {
                        connected = isConnected;
                        LOG.info("Connection state changed: {}", isConnected);
                        if (connectionCallback != null) {
                            connectionCallback.accept(isConnected);
                        }
                    }
                }

                // Handle ServicesResolved (for logging)
                if (changed.containsKey("ServicesResolved")) {
                    LOG.debug("ServicesResolved changed: {}", changed.get("ServicesResolved").getValue());
                }
            }
        };

        deviceManager.registerPropertyHandler(propertiesHandler);
        LOG.debug("Registered PropertiesChanged signal handler");
    }

    /**
     * Extract byte[] from a D-Bus Variant Value.
     * The value might be byte[], List<Byte>, or other collection types.
     */
    private byte[] extractBytes(Object rawValue) {
        if (rawValue instanceof byte[]) {
            return (byte[]) rawValue;
        }
        if (rawValue instanceof List<?>) {
            List<?> list = (List<?>) rawValue;
            byte[] data = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Byte) {
                    data[i] = (Byte) item;
                } else if (item instanceof Number) {
                    data[i] = ((Number) item).byteValue();
                }
            }
            return data;
        }
        LOG.warn("Unexpected Value type: {} — {}", rawValue.getClass().getName(), rawValue);
        return null;
    }

    /**
     * Enable notifications on all Fossil characteristic UUIDs.
     * Uses direct D-Bus StartNotify — no persistent bluetoothctl needed!
     * Our DBusConnection IS the persistent connection.
     */
    private void enableAllNotifications() {
        UUID[] fossilUuids = {
                UUID.fromString("3dda0002-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0005-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0006-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0007-957f-7d4a-34a6-74696673696d"),
        };
        int enabled = 0;
        for (UUID uuid : fossilUuids) {
            BluetoothGattCharacteristic ch = characteristics.get(uuid);
            if (ch == null) {
                LOG.debug("Characteristic {} not found, skipping notification enable", uuid);
                continue;
            }
            try {
                ch.startNotify();
                enabled++;
                LOG.debug("StartNotify on {}", uuid.toString().substring(4, 8));
            } catch (BluezNotPermittedException e) {
                // Expected on Fossil watches — CCCD write fails without BLE bonding,
                // but the watch sends notifications anyway once connected+trusted
                LOG.debug("StartNotify not permitted on {} (expected — watch sends notifications anyway)",
                        uuid.toString().substring(4, 8));
                enabled++; // Count as success — notifications still work via signal handler
            } catch (BluezNotConnectedException e) {
                LOG.warn("StartNotify failed — not connected: {}", uuid);
            } catch (Exception e) {
                LOG.debug("StartNotify on {}: {} (notifications may still work via signal handler)",
                        uuid.toString().substring(4, 8), e.getMessage());
                enabled++; // Optimistic — signal handler may still catch notifications
            }
        }
        LOG.info("Enabled notifications on {} characteristics", enabled);
    }

    @Override
    public boolean pair() {
        if (device == null) return false;

        // Check if already paired/bonded
        Boolean paired = device.isPaired();
        if (Boolean.TRUE.equals(paired)) {
            LOG.info("Already paired");
            return true;
        }

        LOG.info("Initiating BLE pairing (creates bond for auth persistence)...");

        // Register a no-op agent for "Just Works" pairing.
        // BlueZ requires an agent to auto-confirm the pairing request.
        try {
            registerAgent();
        } catch (Exception e) {
            LOG.debug("Agent registration: {}", e.getMessage());
        }

        try {
            boolean ok = device.pair();
            if (ok || Boolean.TRUE.equals(device.isPaired())) {
                LOG.info("BLE pairing successful — auth state is now tied to bond");
                return true;
            }
        } catch (Exception e) {
            LOG.warn("BLE pairing failed: {} — auth works for this session but won't survive bluetoothctl remove",
                    e.getMessage());
        }
        return false;
    }

    /**
     * Register a BlueZ agent for "Just Works" pairing.
     * The agent auto-confirms pairing requests (no PIN/passkey needed).
     * Implements org.bluez.Agent1 directly with correct method signatures.
     */
    private void registerAgent() throws DBusException {
        DBusConnection dbus = deviceManager.getDbusConnection();
        String agentPath = "/qhybrid/agent";

        // Export a raw Agent1 implementation with correct signatures
        org.bluez.Agent1 agent = new org.bluez.Agent1() {
            @Override public void Release() {
                LOG.debug("Agent: Release");
            }
            @Override public String RequestPinCode(DBusPath device) {
                LOG.debug("Agent: RequestPinCode for {}", device);
                return "";
            }
            @Override public void DisplayPinCode(DBusPath device, String pincode) {
                LOG.debug("Agent: DisplayPinCode {} for {}", pincode, device);
            }
            @Override public UInt32 RequestPasskey(DBusPath device) {
                LOG.debug("Agent: RequestPasskey for {}", device);
                return new UInt32(0);
            }
            @Override public void DisplayPasskey(DBusPath device, UInt32 passkey, UInt16 entered) {
                LOG.debug("Agent: DisplayPasskey {} for {}", passkey, device);
            }
            @Override public void RequestConfirmation(DBusPath device, UInt32 passkey) {
                LOG.debug("Agent: RequestConfirmation passkey={} for {} — auto-confirming", passkey, device);
                // Don't throw = auto-accept
            }
            @Override public void RequestAuthorization(DBusPath device) {
                LOG.debug("Agent: RequestAuthorization for {} — auto-authorizing", device);
                // Don't throw = auto-accept
            }
            @Override public void AuthorizeService(DBusPath device, String uuid) {
                LOG.debug("Agent: AuthorizeService {} for {}", uuid, device);
            }
            @Override public void Cancel() {
                LOG.debug("Agent: Cancel");
            }
            @Override public boolean isRemote() { return false; }
            @Override public String getObjectPath() { return agentPath; }
        };

        dbus.exportObject(agentPath, agent);

        // Register with AgentManager via raw D-Bus call
        org.bluez.AgentManager1 agentMgr = dbus.getRemoteObject(
                "org.bluez", "/org/bluez", org.bluez.AgentManager1.class);
        agentMgr.RegisterAgent(new DBusPath(agentPath), "NoInputNoOutput");
        agentMgr.RequestDefaultAgent(new DBusPath(agentPath));
        LOG.info("Registered BlueZ agent at {} for Just Works pairing", agentPath);
    }

    @Override
    public void enableNotifications(UUID uuid) {
        BluetoothGattCharacteristic ch = characteristics.get(uuid);
        if (ch == null) {
            LOG.error("No characteristic for UUID {}", uuid);
            return;
        }
        try {
            ch.startNotify();
        } catch (Exception e) {
            LOG.debug("StartNotify on {}: {}", uuid, e.getMessage());
        }
    }

    @Override
    public void disconnect() {
        LOG.info("Disconnecting from {}", macAddress);
        connected = false;

        // Unregister signal handler
        if (propertiesHandler != null && deviceManager != null) {
            try {
                deviceManager.unRegisterPropertyHandler(propertiesHandler);
            } catch (Exception e) {
                LOG.debug("Failed to unregister property handler: {}", e.getMessage());
            }
            propertiesHandler = null;
        }

        if (device != null) {
            device.disconnect();
        }

        if (connectionCallback != null) {
            connectionCallback.accept(false);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getConnectedMac() {
        return macAddress;
    }

    @Override
    public void writeCharacteristic(UUID uuid, byte[] data) {
        BluetoothGattCharacteristic ch = characteristics.get(uuid);
        if (ch == null) {
            LOG.error("No characteristic path for UUID {}", uuid);
            return;
        }

        String writeType = getWriteType(uuid);
        Map<String, Object> options = new HashMap<>();
        options.put("type", writeType);

        try {
            ch.writeValue(data, options);
            LOG.trace("Write {} bytes to {} ({})", data.length, uuid.toString().substring(4, 8), writeType);
        } catch (BluezNotPermittedException e) {
            // If "request" failed, retry as "command"
            if ("request".equals(writeType)) {
                LOG.debug("Write-with-response failed for {}, retrying as command", uuid);
                options.put("type", "command");
                try {
                    ch.writeValue(data, options);
                    LOG.trace("Write {} bytes to {} (fallback command)", data.length, uuid.toString().substring(4, 8));
                } catch (Exception e2) {
                    LOG.error("Write failed (both types) for UUID {}: {}", uuid, e2.getMessage());
                }
            } else {
                LOG.warn("Write failed for UUID {}: {}", uuid, e.getMessage());
            }
        } catch (Exception e) {
            // "Operation is not supported" on indicate chars is common on Fossil watches
            // (firmware rejects write-with-response on some chars). Often harmless.
            if (e.getMessage() != null && e.getMessage().contains("not supported") && "request".equals(writeType)) {
                LOG.debug("Write-not-supported for {}, retrying as command", uuid);
                options.put("type", "command");
                try {
                    ch.writeValue(data, options);
                    LOG.trace("Write {} bytes to {} (fallback command)", data.length, uuid.toString().substring(4, 8));
                    return;
                } catch (Exception e2) {
                    LOG.warn("Write failed (both types) for UUID {}: {}", uuid, e2.getMessage());
                    return;
                }
            }
            LOG.warn("Write failed for UUID {}: {}", uuid, e.getMessage());
        }
    }

    /**
     * Determine write type from characteristic flags.
     * Same logic as BluezTransport — Fossil watch quirk: rejects write-with-response on notify chars.
     */
    private String getWriteType(UUID uuid) {
        List<String> flags = charFlags.get(uuid);
        if (flags == null) return "command";
        if (flags.contains("write-without-response")) return "command";
        if (flags.contains("indicate")) return "request";
        return "command";
    }

    @Override
    public byte[] readCharacteristic(UUID uuid) {
        BluetoothGattCharacteristic ch = characteristics.get(uuid);
        if (ch == null) {
            LOG.error("No characteristic for UUID {}", uuid);
            return null;
        }

        try {
            return ch.readValue(Collections.emptyMap());
        } catch (Exception e) {
            LOG.error("Read failed for UUID {}: {}", uuid, e.getMessage());
            return null;
        }
    }

    @Override
    public void requestMtu(int requestedMtu) {
        // BlueZ auto-negotiates MTU on connect (5.50+)
        LOG.debug("MTU request for {} (current: {})", requestedMtu, mtu);
    }

    @Override
    public int getMtu() {
        return mtu;
    }

    @Override
    public void setNotificationCallback(BiConsumer<UUID, byte[]> callback) {
        this.notificationCallback = callback;
    }

    @Override
    public void setConnectionCallback(Consumer<Boolean> callback) {
        this.connectionCallback = callback;
    }

    @Override
    public void setMtuCallback(Consumer<Integer> callback) {
        this.mtuCallback = callback;
    }

    @Override
    public void close() {
        if (connected) {
            disconnect();
        }
        if (deviceManager != null) {
            deviceManager.closeConnection();
            deviceManager = null;
        }
    }

    // --- Helpers ---

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, bytes.length);
    }

    private static String bytesToHex(byte[] bytes, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, maxLen); i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}
