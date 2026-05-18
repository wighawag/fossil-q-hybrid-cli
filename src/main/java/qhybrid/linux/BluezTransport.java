package qhybrid.linux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BLE transport implementation using BlueZ D-Bus (via busctl/gdbus).
 * No JNI needed — everything goes through subprocess calls.
 *
 * Notification strategy: use `gdbus monitor` to receive Value property changes.
 * StartNotify is called best-effort — on some BlueZ/firmware combos the CCCD write
 * fails silently but the watch sends notifications regardless once connected+bonded.
 */
public class BluezTransport implements BleTransport, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BluezTransport.class);

    private String macAddress;
    private String devicePath;          // e.g. /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF
    private String adapterPath = "/org/bluez/hci0";

    private final Map<UUID, String> charPaths = new ConcurrentHashMap<>();   // UUID → D-Bus path
    private final Map<String, UUID> pathToUuid = new ConcurrentHashMap<>();  // D-Bus path → UUID
    private final Map<UUID, List<String>> charFlags = new ConcurrentHashMap<>(); // UUID → BLE flags
    private volatile boolean connected = false;
    private volatile int mtu = 23;

    private Process monitorProcess;
    private Thread monitorThread;
    private Process btctlProcess;     // persistent bluetoothctl for StartNotify
    private OutputStream btctlStdin;  // pipe to send commands to bluetoothctl

    private BiConsumer<UUID, byte[]> notificationCallback;
    private Consumer<Boolean> connectionCallback;
    private Consumer<Integer> mtuCallback;

    // Timeout for subprocess calls (seconds)
    private static final int CMD_TIMEOUT = 15;

    @Override
    public boolean connect(String macAddress) {
        this.macAddress = macAddress;
        this.devicePath = adapterPath + "/dev_" + macAddress.replace(":", "_");

        LOG.info("Connecting to {} ...", macAddress);

        // Check if already connected via D-Bus property (more reliable than bluetoothctl)
        String connResult = runCmd("busctl", "get-property", "--system", "org.bluez",
                devicePath, "org.bluez.Device1", "Connected");
        if (connResult != null && connResult.contains("true")) {
            LOG.info("Already connected to {}", macAddress);
            connected = true;
        } else {
            // Try connect via D-Bus
            String result = runCmd("busctl", "call", "--system", "org.bluez",
                    devicePath, "org.bluez.Device1", "Connect");
            if (result == null) {
                // Fallback: try bluetoothctl (more forgiving with address formats)
                LOG.debug("D-Bus connect failed, trying bluetoothctl...");
                result = runBluetoothctl("connect", macAddress);
                if (result == null || (!result.contains("Connection successful")
                        && !result.contains("Connected: yes"))) {
                    LOG.error("Connection failed. Is the device paired and in range?");
                    LOG.error("Try: bluetoothctl pair {} && bluetoothctl connect {}",
                            macAddress, macAddress);
                    return false;
                }
            }
            // Verify connection
            connResult = runCmd("busctl", "get-property", "--system", "org.bluez",
                    devicePath, "org.bluez.Device1", "Connected");
            if (connResult == null || !connResult.contains("true")) {
                LOG.error("Connection attempt did not result in connected state");
                return false;
            }
            connected = true;
            LOG.info("Connected to {}", macAddress);
        }

        // Wait for GATT services to be resolved
        if (!waitForServicesResolved(10_000)) {
            LOG.error("GATT services not resolved within timeout");
            return false;
        }

        // Enumerate characteristics
        discoverCharacteristics();

        // Read MTU from characteristic properties
        negotiateMtu();

        // Start notification monitor BEFORE enabling notifications
        startNotificationMonitor();

        // Start persistent bluetoothctl for notification management
        startBluetoothctl();

        // Enable notifications via persistent bluetoothctl connection
        // (busctl StartNotify fails because each call creates/destroys a D-Bus connection)
        enableAllNotifications();

        if (connectionCallback != null) {
            connectionCallback.accept(true);
        }
        return true;
    }

    @Override
    public void disconnect() {
        LOG.info("Disconnecting from {}", macAddress);
        stopNotificationMonitor();
        runBluetoothctl("disconnect", macAddress);
        connected = false;
        if (connectionCallback != null) {
            connectionCallback.accept(false);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void writeCharacteristic(UUID uuid, byte[] data) {
        String path = charPaths.get(uuid);
        if (path == null) {
            LOG.error("No characteristic path for UUID {}", uuid);
            return;
        }

        // Determine write type from characteristic flags
        String writeType = getWriteType(uuid);

        // Build busctl call: WriteValue takes "aya{sv}" — array of bytes + options dict
        // With type option: aya{sv} <count> <bytes...> 1 type s <command|request>
        List<String> args = new ArrayList<>();
        args.addAll(List.of("busctl", "call", "--system", "org.bluez", path,
                "org.bluez.GattCharacteristic1", "WriteValue", "aya{sv}",
                String.valueOf(data.length)));
        for (byte b : data) {
            args.add(String.valueOf(b & 0xFF));
        }
        // Append options dict with type
        args.addAll(List.of("1", "type", "s", writeType));

        String result = runCmd(args.toArray(new String[0]));
        if (result == null) {
            // If "request" failed, retry as "command"
            if (writeType.equals("request")) {
                LOG.debug("Write-with-response failed for {}, retrying as command", uuid);
                args.set(args.size() - 1, "command");
                result = runCmd(args.toArray(new String[0]));
            }
            if (result == null) {
                LOG.error("Write failed for UUID {}", uuid);
            } else {
                LOG.trace("Write {} bytes to {} (fallback command)", data.length, uuid);
            }
        } else {
            LOG.trace("Write {} bytes to {} ({})", data.length, uuid, writeType);
        }
    }

    /**
     * Determine the write type based on characteristic flags.
     * - "write-without-response" → "command" (ATT Write Command, no response)
     * - "write" + "notify" → "command" (Fossil watch quirk: rejects write-with-response on notify chars)
     * - "write" + "indicate" → "request" (ATT Write Request, with response)
     */
    private String getWriteType(UUID uuid) {
        List<String> flags = charFlags.get(uuid);
        if (flags == null) return "command"; // safe default for Fossil watches
        if (flags.contains("write-without-response")) return "command";
        if (flags.contains("indicate")) return "request";
        // "write" + "notify" → watch rejects write-with-response
        return "command";
    }

    @Override
    public byte[] readCharacteristic(UUID uuid) {
        String path = charPaths.get(uuid);
        if (path == null) {
            LOG.error("No characteristic path for UUID {}", uuid);
            return null;
        }

        String result = runCmd("busctl", "call", "--system", "org.bluez", path,
                "org.bluez.GattCharacteristic1", "ReadValue", "a{sv}", "0");
        if (result == null) return null;

        return parseBusctlByteArray(result);
    }

    @Override
    public void enableNotifications(UUID uuid) {
        // Use persistent bluetoothctl to enable notifications.
        // A persistent D-Bus connection is REQUIRED — if the connection closes,
        // BlueZ immediately un-registers the notification and Notifying goes false.
        // This is why busctl (one-shot) doesn't work for StartNotify.
        sendBtctlCommand("select-attribute " + uuid);
        sleep(200);
        sendBtctlCommand("notify on");
        sleep(500);
    }

    /**
     * Enable notifications on all Fossil characteristic UUIDs.
     */
    private void enableAllNotifications() {
        // Enter GATT menu first
        sendBtctlCommand("menu gatt");
        sleep(300);

        UUID[] fossilUuids = {
                UUID.fromString("3dda0002-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0003-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0004-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0005-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0006-957f-7d4a-34a6-74696673696d"),
                UUID.fromString("3dda0007-957f-7d4a-34a6-74696673696d"),
        };
        for (UUID uuid : fossilUuids) {
            enableNotifications(uuid);
        }
        LOG.info("Enabled notifications on {} characteristics", fossilUuids.length);
    }

    @Override
    public void requestMtu(int requestedMtu) {
        // BlueZ auto-negotiates MTU on connect (5.50+).
        // We just report the current MTU.
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

    // --- Discovery ---

    private boolean waitForServicesResolved(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String result = runCmd("busctl", "get-property", "--system", "org.bluez",
                    devicePath, "org.bluez.Device1", "ServicesResolved");
            if (result != null && result.contains("true")) {
                LOG.debug("GATT services resolved");
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void discoverCharacteristics() {
        charPaths.clear();
        pathToUuid.clear();

        // List all objects under the device path
        String result = runCmd("busctl", "tree", "--system", "org.bluez", "--list");
        if (result == null) {
            LOG.error("Failed to enumerate D-Bus tree");
            return;
        }

        // Find all characteristic paths under our device
        for (String line : result.split("\n")) {
            line = line.trim();
            if (!line.startsWith(devicePath) || !line.contains("/char")) continue;
            // Skip descriptor paths (contain /desc)
            if (line.contains("/desc")) continue;

            // Read the UUID property
            String uuidResult = runCmd("busctl", "get-property", "--system", "org.bluez",
                    line, "org.bluez.GattCharacteristic1", "UUID");
            if (uuidResult == null) continue;

            // Parse: s "3dda0002-957f-7d4a-34a6-74696673696d"
            String uuidStr = uuidResult.replaceAll(".*\"(.+?)\".*", "$1").trim();
            try {
                UUID uuid = UUID.fromString(uuidStr);
                charPaths.put(uuid, line);
                pathToUuid.put(line, uuid);

                // Read Flags property to determine write type later
                String flagsResult = runCmd("busctl", "get-property", "--system", "org.bluez",
                        line, "org.bluez.GattCharacteristic1", "Flags");
                if (flagsResult != null) {
                    List<String> flags = parseBusctlStringArray(flagsResult);
                    charFlags.put(uuid, flags);
                    LOG.debug("Characteristic: {} → {} flags={}", uuidStr, line, flags);
                } else {
                    LOG.debug("Characteristic: {} → {} (no flags)", uuidStr, line);
                }
            } catch (IllegalArgumentException e) {
                LOG.warn("Invalid UUID: {}", uuidStr);
            }
        }

        LOG.info("Discovered {} characteristics", charPaths.size());
    }

    private void negotiateMtu() {
        // Try to get MTU from device property first
        String result = runCmd("busctl", "get-property", "--system", "org.bluez",
                devicePath, "org.bluez.Device1", "MTU");
        if (result != null && result.startsWith("q ")) {
            try {
                mtu = Integer.parseInt(result.substring(2).trim());
                LOG.info("Negotiated MTU: {}", mtu);
                return;
            } catch (NumberFormatException e) {
                // fall through
            }
        }

        // Fallback: try to get MTU from characteristic properties
        for (Map.Entry<UUID, String> entry : charPaths.entrySet()) {
            result = runCmd("busctl", "get-property", "--system", "org.bluez",
                    entry.getValue(), "org.bluez.GattCharacteristic1", "MTU");
            if (result != null && result.startsWith("q ")) {
                try {
                    mtu = Integer.parseInt(result.substring(2).trim());
                    LOG.info("Negotiated MTU from characteristic: {}", mtu);
                    if (mtuCallback != null) mtuCallback.accept(mtu);
                    return;
                } catch (NumberFormatException e) {
                    // try next
                }
            }
        }

        LOG.info("Could not determine MTU, using default: {}", mtu);
    }

    // --- Notification monitor using gdbus ---

    private void startNotificationMonitor() {
        try {
            // gdbus monitor catches ALL PropertiesChanged signals from org.bluez
            // including Value changes on characteristics — works even when
            // Notifying flag stays false (Fossil watch quirk)
            ProcessBuilder pb = new ProcessBuilder(
                    "gdbus", "monitor", "--system", "--dest", "org.bluez"
            );
            pb.redirectErrorStream(true);
            monitorProcess = pb.start();

            monitorThread = new Thread(this::processGdbusOutput, "bluez-monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();

            LOG.debug("Started gdbus notification monitor");
        } catch (IOException e) {
            LOG.error("Failed to start gdbus monitor", e);
        }
    }

    private void stopNotificationMonitor() {
        if (monitorProcess != null) {
            monitorProcess.destroyForcibly();
            monitorProcess = null;
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }

    /**
     * Parse gdbus monitor output for Value property changes.
     *
     * gdbus monitor format:
     * /org/bluez/.../charNNNN: org.freedesktop.DBus.Properties.PropertiesChanged
     *   ('org.bluez.GattCharacteristic1', {'Value': <[byte 0x01, 0x02, ...]>}, @as [])
     *
     * Also watches for Connected property changes on the device path.
     */
    private void processGdbusOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(monitorProcess.getInputStream(), StandardCharsets.UTF_8))) {

            // Pattern to match characteristic path in signal lines
            Pattern pathPattern = Pattern.compile("^(/org/bluez/[^:]+): ");
            // Pattern to match Value byte array: 'Value': <[byte 0x01, 0x02, ...]>
            Pattern valuePattern = Pattern.compile("'Value': <\\[byte (.+?)\\]>");
            // Pattern for single byte value (no comma): 'Value': <[byte 0x26]>
            Pattern valueSinglePattern = Pattern.compile("'Value': <\\[byte (0x[0-9a-fA-F]+)\\]>");
            // Pattern for Connected changes
            Pattern connectedPattern = Pattern.compile("'Connected': <(true|false)>");

            String currentPath = null;

            String line;
            while ((line = reader.readLine()) != null) {
                // Check for path header line
                Matcher pathMatcher = pathPattern.matcher(line);
                if (pathMatcher.find()) {
                    currentPath = pathMatcher.group(1);
                }

                // Check for Connected state change
                Matcher connMatcher = connectedPattern.matcher(line);
                if (connMatcher.find()) {
                    boolean isConnected = connMatcher.group(1).equals("true");
                    if (isConnected != connected) {
                        connected = isConnected;
                        LOG.info("Connection state changed: {}", isConnected);
                        if (connectionCallback != null) {
                            connectionCallback.accept(isConnected);
                        }
                    }
                }

                // Check for Value property change
                Matcher valueMatcher = valuePattern.matcher(line);
                if (valueMatcher.find() && currentPath != null) {
                    String bytesStr = valueMatcher.group(1);
                    byte[] data = parseGdbusBytes(bytesStr);
                    deliverNotification(currentPath, data);
                }
            }
        } catch (IOException e) {
            if (connected) {
                LOG.error("Monitor stream error", e);
            }
        }
    }

    /**
     * Parse gdbus byte array string like "0x01, 0x02, 0x03" into byte array.
     */
    private byte[] parseGdbusBytes(String bytesStr) {
        String[] parts = bytesStr.split(",");
        byte[] data = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String hex = parts[i].trim().replace("0x", "");
            try {
                data[i] = (byte) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                LOG.warn("Cannot parse byte: '{}'", parts[i].trim());
            }
        }
        return data;
    }

    private void deliverNotification(String charPath, byte[] data) {
        if (notificationCallback == null || data.length == 0) return;

        UUID uuid = pathToUuid.get(charPath);
        if (uuid == null) {
            LOG.trace("Notification from unknown path: {}", charPath);
            return;
        }

        LOG.debug("Notification on {}: {} bytes [{}]",
                uuid.toString().substring(4, 8),
                data.length,
                data.length <= 16 ? bytesToHex(data) : bytesToHex(data, 16) + "...");
        try {
            notificationCallback.accept(uuid, data);
        } catch (Exception e) {
            LOG.error("Error in notification callback", e);
        }
    }

    // --- Helper methods ---

    private String runBluetoothctl(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "bluetoothctl";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return runCmd(cmd);
    }

    private String runCmd(String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
            }

            boolean finished = p.waitFor(CMD_TIMEOUT, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                LOG.warn("Command timed out: {}", String.join(" ", args));
                return null;
            }

            int exitCode = p.exitValue();
            if (exitCode != 0) {
                LOG.debug("Command exit {}: {} → {}", exitCode, String.join(" ", args),
                        output.length() > 200 ? output.substring(0, 200) : output);
                return null;
            }

            return output;
        } catch (IOException | InterruptedException e) {
            LOG.error("Command failed: {}", String.join(" ", args), e);
            return null;
        }
    }

    /**
     * Parse busctl byte array output. Format: "ay <count> <decimal bytes...>"
     * Example: "ay 13 72 87 48 46 48 46 50 46 57 114 46 118 51"
     * Values are DECIMAL (not hex), despite what you might expect.
     */
    private byte[] parseBusctlByteArray(String output) {
        output = output.trim();

        // Format: "ay <count> <decimal values...>"
        if (output.startsWith("ay ")) {
            String[] parts = output.split("\\s+");
            if (parts.length < 2) return new byte[0];
            int count;
            try {
                count = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                LOG.warn("Cannot parse byte array count: {}", output);
                return new byte[0];
            }
            byte[] data = new byte[count];
            for (int i = 0; i < count && i + 2 < parts.length; i++) {
                try {
                    data[i] = (byte) Integer.parseInt(parts[i + 2]);
                } catch (NumberFormatException e) {
                    LOG.warn("Cannot parse byte at index {}: {}", i, parts[i + 2]);
                }
            }
            return data;
        }

        // Fallback: try parsing as space-separated decimals without "ay" prefix
        String[] parts = output.split("\\s+");
        if (parts.length < 1) return new byte[0];
        try {
            int count = Integer.parseInt(parts[0]);
            byte[] data = new byte[count];
            for (int i = 0; i < count && i + 1 < parts.length; i++) {
                data[i] = (byte) Integer.parseInt(parts[i + 1]);
            }
            return data;
        } catch (NumberFormatException e) {
            LOG.warn("Cannot parse byte array: {}", output);
            return new byte[0];
        }
    }

    /**
     * Parse busctl string array output. Format: 'as <count> "value1" "value2" ...'
     */
    private List<String> parseBusctlStringArray(String output) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(output);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
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

    // --- Persistent bluetoothctl for notification management ---

    private void startBluetoothctl() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bluetoothctl");
            pb.redirectErrorStream(true);
            btctlProcess = pb.start();
            btctlStdin = btctlProcess.getOutputStream();

            // Drain stdout in background to prevent pipe buffer from filling
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(btctlProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                        // discard output
                    }
                } catch (IOException e) {
                    // expected on close
                }
            }, "btctl-drain");
            drain.setDaemon(true);
            drain.start();

            sleep(500);

            // Register agent for pairing/encryption support
            sendBtctlCommand("agent on");
            sleep(200);
            sendBtctlCommand("default-agent");
            sleep(500);

            LOG.debug("Started persistent bluetoothctl");
        } catch (IOException e) {
            LOG.error("Failed to start bluetoothctl", e);
        }
    }

    private void stopBluetoothctl() {
        if (btctlProcess != null) {
            sendBtctlCommand("quit");
            btctlProcess.destroyForcibly();
            btctlProcess = null;
            btctlStdin = null;
        }
    }

    private void sendBtctlCommand(String command) {
        if (btctlStdin == null) return;
        try {
            btctlStdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            btctlStdin.flush();
        } catch (IOException e) {
            LOG.error("Failed to send bluetoothctl command: {}", command, e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stopNotificationMonitor();
        stopBluetoothctl();
        if (connected) {
            disconnect();
        }
    }
}
