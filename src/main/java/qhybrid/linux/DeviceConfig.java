package qhybrid.linux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Per-device configuration stored at ~/.config/fossil-q/devices/<MAC>/config.json.
 *
 * Contains watch-specific settings:
 *   - name: user-friendly name (e.g. "My Fenmore")
 *   - stepGoal: daily step goal (default 10000)
 *   - vibrationStrength: 0-100 (default 100)
 *   - secondTimezone: offset in minutes from UTC, or null if disabled
 *
 * Format:
 * {
 *   "name": "My Fenmore",
 *   "stepGoal": 10000,
 *   "vibrationStrength": 100,
 *   "secondTimezone": null
 * }
 */
public class DeviceConfig {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceConfig.class);
    private static final String CONFIG_FILE = "config.json";

    private final String mac;

    private String name;
    private int stepGoal = 10000;
    private int vibrationStrength = 100;
    private Integer secondTimezone; // null = disabled, otherwise offset in minutes

    public DeviceConfig(String mac) {
        this.mac = mac;
    }

    // ========== Getters / Setters ==========

    public String getMac() { return mac; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getStepGoal() { return stepGoal; }
    public void setStepGoal(int stepGoal) { this.stepGoal = stepGoal; }
    public int getVibrationStrength() { return vibrationStrength; }
    public void setVibrationStrength(int vibrationStrength) { this.vibrationStrength = vibrationStrength; }
    public Integer getSecondTimezone() { return secondTimezone; }
    public void setSecondTimezone(Integer secondTimezone) { this.secondTimezone = secondTimezone; }

    // ========== Paths ==========

    public Path deviceDir() {
        return GlobalConfig.deviceDir(mac);
    }

    public Path configPath() {
        return deviceDir().resolve(CONFIG_FILE);
    }

    public Path notificationsPath() {
        return deviceDir().resolve("notifications.json");
    }

    // ========== Load / Save ==========

    /**
     * Load device config for the given MAC. Returns defaults if file doesn't exist.
     */
    public static DeviceConfig load(String mac) {
        DeviceConfig config = new DeviceConfig(mac);
        Path path = config.configPath();

        if (!Files.exists(path)) {
            LOG.debug("No device config at {} — using defaults", path);
            return config;
        }

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            config.parseJson(json);
            LOG.debug("Loaded device config for {} from {}", mac, path);
        } catch (Exception e) {
            LOG.warn("Failed to load device config from {}: {}", path, e.getMessage());
        }
        return config;
    }

    public void save() throws IOException {
        Path path = configPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, toJson(), StandardCharsets.UTF_8);
        LOG.debug("Saved device config to {}", path);
    }

    // ========== JSON ==========

    private void parseJson(String json) {
        String n = extractString(json, "name");
        if (n != null) this.name = n;
        this.stepGoal = extractInt(json, "stepGoal", 10000);
        this.vibrationStrength = extractInt(json, "vibrationStrength", 100);

        // secondTimezone: null means disabled
        String tzStr = extractRawValue(json, "secondTimezone");
        if (tzStr != null && !tzStr.equals("null")) {
            try {
                this.secondTimezone = Integer.parseInt(tzStr.trim());
            } catch (NumberFormatException e) {
                this.secondTimezone = null;
            }
        }
    }

    String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append(String.format("  \"name\": %s,\n", name != null ? "\"" + escapeJson(name) + "\"" : "null"));
        sb.append(String.format("  \"stepGoal\": %d,\n", stepGoal));
        sb.append(String.format("  \"vibrationStrength\": %d,\n", vibrationStrength));
        sb.append(String.format("  \"secondTimezone\": %s\n", secondTimezone != null ? secondTimezone.toString() : "null"));
        sb.append("}\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Device: %s", mac));
        if (name != null) sb.append(String.format(" (%s)", name));
        sb.append(String.format("\n  Step goal:           %d", stepGoal));
        sb.append(String.format("\n  Vibration strength:  %d%%", vibrationStrength));
        if (secondTimezone != null) {
            sb.append(String.format("\n  Second timezone:     UTC%+.1f (%d min)", secondTimezone / 60.0, secondTimezone));
        } else {
            sb.append("\n  Second timezone:     disabled");
        }
        return sb.toString();
    }

    // ========== Minimal JSON helpers ==========

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;
        // Skip whitespace
        int vStart = colonIdx + 1;
        while (vStart < json.length() && Character.isWhitespace(json.charAt(vStart))) vStart++;
        if (vStart >= json.length()) return null;
        if (json.charAt(vStart) == 'n') return null; // null
        if (json.charAt(vStart) != '"') return null;
        int quoteEnd = json.indexOf('"', vStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(vStart + 1, quoteEnd);
    }

    private static int extractInt(String json, String key, int defaultValue) {
        String raw = extractRawValue(json, key);
        if (raw == null || raw.equals("null")) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String extractRawValue(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;
        int vStart = colonIdx + 1;
        while (vStart < json.length() && Character.isWhitespace(json.charAt(vStart))) vStart++;
        if (vStart >= json.length()) return null;
        // Read until comma, newline, or closing brace
        int vEnd = vStart;
        while (vEnd < json.length() && json.charAt(vEnd) != ',' && json.charAt(vEnd) != '\n'
                && json.charAt(vEnd) != '}') vEnd++;
        return json.substring(vStart, vEnd).trim();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
