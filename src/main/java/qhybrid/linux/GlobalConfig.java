package qhybrid.linux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Global configuration stored at ~/.config/fossil-q/config.json.
 *
 * Contains:
 *   - activeDevice: MAC address of the default watch (so -d becomes optional)
 *   - Any future general settings
 *
 * Format:
 * {
 *   "activeDevice": "D9:20:71:11:74:2A"
 * }
 */
public class GlobalConfig {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalConfig.class);
    static final String CONFIG_DIR = ".config/fossil-q";
    private static final String CONFIG_FILE = "config.json";

    private String activeDevice; // MAC address, e.g. "D9:20:71:11:74:2A"

    public String getActiveDevice() { return activeDevice; }
    public void setActiveDevice(String mac) { this.activeDevice = mac; }

    // ========== Paths ==========

    public static Path configDir() {
        return Path.of(System.getProperty("user.home"), CONFIG_DIR);
    }

    public static Path configPath() {
        return configDir().resolve(CONFIG_FILE);
    }

    /**
     * Get the device-specific config directory for a given MAC address.
     * MAC colons are replaced with underscores.
     * e.g. ~/.config/fossil-q/devices/D9_20_71_11_74_2A/
     */
    public static Path deviceDir(String mac) {
        String folderName = mac.replace(":", "_").toUpperCase();
        return configDir().resolve("devices").resolve(folderName);
    }

    // ========== Load / Save ==========

    public static GlobalConfig load() {
        Path path = configPath();
        GlobalConfig config = new GlobalConfig();

        if (!Files.exists(path)) {
            LOG.debug("No global config at {} — using defaults", path);
            return config;
        }

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            config.parseJson(json);
            LOG.debug("Loaded global config from {}", path);
        } catch (Exception e) {
            LOG.warn("Failed to load global config from {}: {}", path, e.getMessage());
        }
        return config;
    }

    public void save() throws IOException {
        Path path = configPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, toJson(), StandardCharsets.UTF_8);
        LOG.debug("Saved global config to {}", path);
    }

    // ========== JSON ==========

    private void parseJson(String json) {
        activeDevice = extractString(json, "activeDevice");
    }

    String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        if (activeDevice != null) {
            sb.append(String.format("  \"activeDevice\": \"%s\"\n", escapeJson(activeDevice)));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
