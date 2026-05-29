package qhybrid.linux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages notification type configurations (name → hand position + vibe pattern).
 *
 * Stored as JSON at ~/.config/fossil-q/notifications.json:
 * {
 *   "types": [
 *     {"name": "phone",    "hourDeg": 60,  "minDeg": 60,  "vibe": 1},
 *     {"name": "whatsapp", "hourDeg": 90,  "minDeg": 90,  "vibe": 4},
 *     {"name": "email",    "hourDeg": 120, "minDeg": 120, "vibe": 3},
 *     {"name": "calendar", "hourDeg": 300, "minDeg": 300, "vibe": 4}
 *   ]
 * }
 *
 * Each type gets a unique CRC derived from "qhybrid.linux.<name>" for the filter.
 * All types are uploaded as filter entries during init. The play file selects
 * which entry to trigger by including the matching CRC.
 */
public class NotificationConfig {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationConfig.class);
    private static final String CONFIG_FILE = "notifications.json";

    /**
     * Human-readable names for the 0-9 vibration patterns. Single source of truth
     * shared by the CLI (Main) and NotifType.toString(). Lives here in :protocol
     * so the protocol module has no back-dependency on the :cli module.
     */
    public static final String[] VIBE_PATTERN_NAMES = {
            "AUTO", "CALL", "TEXT", "EMAIL", "DEFAULT_OTHER_APPS",
            "ONE_SHORT_VIBE", "TWO_SHORT_VIBES", "THREE_SHORT_VIBES",
            "ONE_LONG_VIBE", "NO_VIBE"
    };

    private final List<NotifType> types = new ArrayList<>();

    /** A single notification type definition. */
    public static class NotifType {
        public String name;
        public int hourDeg;
        public int minDeg;
        public int vibe; // 0-9 (NotificationVibePattern byte)

        public NotifType() {}

        public NotifType(String name, int hourDeg, int minDeg, int vibe) {
            this.name = name;
            this.hourDeg = hourDeg;
            this.minDeg = minDeg;
            this.vibe = vibe;
        }

        /** Package name used for CRC computation. Each type gets a unique CRC. */
        public String packageName() {
            return "qhybrid.linux." + name;
        }

        @Override
        public String toString() {
            String vibeName = (vibe >= 0 && vibe < VIBE_PATTERN_NAMES.length)
                    ? VIBE_PATTERN_NAMES[vibe] : "UNKNOWN";
            return String.format("%s: hands=%d°/%d°, vibe=%d (%s)",
                    name, hourDeg, minDeg, vibe, vibeName);
        }
    }

    public List<NotifType> getTypes() {
        return Collections.unmodifiableList(types);
    }

    public NotifType getType(String name) {
        return types.stream()
                .filter(t -> t.name.equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    /** Find by index (1-based) or name. Returns null if not found. */
    public NotifType resolve(String nameOrIndex) {
        // Try as 1-based index
        try {
            int idx = Integer.parseInt(nameOrIndex);
            if (idx >= 1 && idx <= types.size()) {
                return types.get(idx - 1);
            }
        } catch (NumberFormatException ignored) {}
        // Try as name
        return getType(nameOrIndex);
    }

    public void addOrUpdate(NotifType type) {
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).name.equalsIgnoreCase(type.name)) {
                types.set(i, type);
                return;
            }
        }
        types.add(type);
    }

    public boolean remove(String name) {
        return types.removeIf(t -> t.name.equalsIgnoreCase(name));
    }

    // ========== Persistence ==========

    /** Legacy global config path (pre-multi-watch). */
    public static Path legacyConfigPath() {
        return GlobalConfig.configDir().resolve(CONFIG_FILE);
    }

    /** Per-device config path. */
    public static Path configPath(String mac) {
        return GlobalConfig.deviceDir(mac).resolve(CONFIG_FILE);
    }

    /**
     * Load config from disk for a specific device.
     * Migration: if the per-device file doesn't exist but the legacy global file does,
     * copy it into the device folder and rename the old file.
     * Falls back to defaults if neither exists.
     */
    public static NotificationConfig load(String mac) {
        Path devicePath = configPath(mac);
        Path legacyPath = legacyConfigPath();
        NotificationConfig config = new NotificationConfig();

        // Try device-specific file first
        if (Files.exists(devicePath)) {
            try {
                String json = Files.readString(devicePath, StandardCharsets.UTF_8);
                config.parseJson(json);
                LOG.debug("Loaded {} notification type(s) from {}", config.types.size(), devicePath);
                return config;
            } catch (Exception e) {
                LOG.warn("Failed to load notification config from {}: {} — trying legacy", devicePath, e.getMessage());
                config.types.clear();
            }
        }

        // Try legacy global file and migrate
        if (Files.exists(legacyPath)) {
            try {
                String json = Files.readString(legacyPath, StandardCharsets.UTF_8);
                config.parseJson(json);
                LOG.info("Migrating legacy notification config to device folder for {}", mac);
                // Save to device folder
                config.save(mac);
                // Rename legacy file so we don't migrate again
                Path backupPath = legacyPath.resolveSibling(CONFIG_FILE + ".migrated");
                Files.move(legacyPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Legacy config migrated to {} (old file renamed to {})",
                        devicePath, backupPath.getFileName());
                return config;
            } catch (Exception e) {
                LOG.warn("Failed to migrate legacy notification config: {}", e.getMessage());
                config.types.clear();
            }
        }

        // No config found — use defaults
        LOG.debug("No notification config found for {} — using defaults", mac);
        config.addDefaults();
        return config;
    }

    /**
     * Load config without a device context (legacy behavior, used for notify-config list).
     * Tries active device from global config, then legacy path, then defaults.
     */
    public static NotificationConfig load() {
        GlobalConfig global = GlobalConfig.load();
        if (global.getActiveDevice() != null) {
            return load(global.getActiveDevice());
        }
        // No active device — try legacy path
        Path legacyPath = legacyConfigPath();
        NotificationConfig config = new NotificationConfig();
        if (Files.exists(legacyPath)) {
            try {
                String json = Files.readString(legacyPath, StandardCharsets.UTF_8);
                config.parseJson(json);
                return config;
            } catch (Exception e) {
                LOG.warn("Failed to load legacy notification config: {}", e.getMessage());
            }
        }
        config.addDefaults();
        return config;
    }

    /**
     * Save config to device-specific path.
     */
    public void save(String mac) throws IOException {
        Path path = configPath(mac);
        Files.createDirectories(path.getParent());
        Files.writeString(path, toJson(), StandardCharsets.UTF_8);
        LOG.debug("Saved {} notification type(s) to {}", types.size(), path);
    }

    /**
     * Save config (legacy — uses active device from global config).
     */
    public void save() throws IOException {
        GlobalConfig global = GlobalConfig.load();
        if (global.getActiveDevice() != null) {
            save(global.getActiveDevice());
        } else {
            // Fallback: save to legacy location
            Path path = legacyConfigPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, toJson(), StandardCharsets.UTF_8);
            LOG.debug("Saved {} notification type(s) to {} (legacy)", types.size(), path);
        }
    }

    private void addDefaults() {
        types.add(new NotifType("default", 90, 90, 4));   // 3:00, DEFAULT
    }

    // ========== Simple JSON parser/writer (no external deps) ==========

    private void parseJson(String json) {
        // Minimal JSON parsing — handles our specific format only.
        // We look for the "types" array and extract objects from it.
        int typesIdx = json.indexOf("\"types\"");
        if (typesIdx < 0) return;

        int arrStart = json.indexOf('[', typesIdx);
        if (arrStart < 0) return;
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return;

        String arrContent = json.substring(arrStart + 1, arrEnd);

        // Find each {...} object
        int pos = 0;
        while (pos < arrContent.length()) {
            int objStart = arrContent.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBracket(arrContent, objStart, '{', '}');
            if (objEnd < 0) break;

            String obj = arrContent.substring(objStart + 1, objEnd);
            NotifType type = parseTypeObject(obj);
            if (type != null && type.name != null && !type.name.isBlank()) {
                types.add(type);
            }
            pos = objEnd + 1;
        }
    }

    private NotifType parseTypeObject(String obj) {
        NotifType t = new NotifType();
        t.name = extractString(obj, "name");
        t.hourDeg = extractInt(obj, "hourDeg", 90);
        t.minDeg = extractInt(obj, "minDeg", 90);
        t.vibe = extractInt(obj, "vibe", 4);
        return t;
    }

    private String extractString(String json, String key) {
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

    private int extractInt(String json, String key, int defaultValue) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return defaultValue;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return defaultValue;
        // Skip whitespace
        int numStart = colonIdx + 1;
        while (numStart < json.length() && Character.isWhitespace(json.charAt(numStart))) numStart++;
        // Read digits (and optional negative sign)
        int numEnd = numStart;
        if (numEnd < json.length() && json.charAt(numEnd) == '-') numEnd++;
        while (numEnd < json.length() && Character.isDigit(json.charAt(numEnd))) numEnd++;
        if (numEnd == numStart) return defaultValue;
        try {
            return Integer.parseInt(json.substring(numStart, numEnd));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int findMatchingBracket(String s, int openIdx, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == open) depth++;
                else if (c == close) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"types\": [\n");
        for (int i = 0; i < types.size(); i++) {
            NotifType t = types.get(i);
            sb.append(String.format("    {\"name\": \"%s\", \"hourDeg\": %d, \"minDeg\": %d, \"vibe\": %d}",
                    escapeJson(t.name), t.hourDeg, t.minDeg, t.vibe));
            if (i < types.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
