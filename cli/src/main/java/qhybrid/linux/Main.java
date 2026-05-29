package qhybrid.linux;

import qhybrid.protocol.buttonconfig.ConfigPayload;
import qhybrid.protocol.requests.fossil.alarm.Alarm;
import qhybrid.protocol.requests.misfit.PlayNotificationRequest.VibrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import com.github.hypfvieh.bluetooth.DeviceManager;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter;
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

@Command(name = "fossil-q",
        description = "Fossil Q Hybrid CLI for Linux (coin-cell watches: Q Commuter, Q Activist)",
        mixinStandardHelpOptions = true,
        version = "0.2.0",
        subcommands = {
                Main.InfoCmd.class,
                Main.TimeCmd.class,
                Main.NotifyCmd.class,
                Main.NotifyTestCmd.class,
                Main.PositionTestCmd.class,
                Main.FindCmd.class,
                Main.HandsCmd.class,
                Main.CalibrateCmd.class,
                Main.StepGoalCmd.class,
                Main.StepCountCmd.class,
                Main.VibrationCmd.class,
                Main.TimezoneCmd.class,
                Main.AlarmCmd.class,
                Main.ActivityCmd.class,
                Main.PairCmd.class,
                Main.MonitorCmd.class,
                Main.ButtonsCmd.class,
                Main.SecondTimezoneCmd.class,
                Main.GoalConfigCmd.class,
                Main.NotifyConfigCmd.class,
                Main.ReadConfigCmd.class,
                Main.InactivityNudgeCmd.class,
                Main.HandAnimCmd.class,
                Main.ConfigCmd.class,
                Main.ScanCmd.class,
        },
        footer = {
                "",
                "Commands that connect to the watch: info, time, notify, notify-test, position-test,",
                "  find, hands, calibrate, step-goal, step-count, vibration, timezone, alarm,",
                "  activity, pair, monitor, buttons, second-timezone, goal-config, read-config,",
                "  inactivity-nudge, hand-anim, notify-config --sync, config sync",
                "",
                "Local-only commands: config, notify-config (without --sync)",
        })
public class Main implements Runnable {
    private static final Logger LOG;

    // Set log level BEFORE any LoggerFactory.getLogger() call.
    // slf4j-simple reads the system property once at first logger creation.
    // We must scan for --verbose in the process args before creating any logger.
    static {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            // Check /proc/self/cmdline or ProcessHandle for --verbose
            boolean verbose = false;
            try {
                String cmdline = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/self/cmdline"));
                verbose = cmdline.contains("--verbose");
            } catch (Exception ignored) {
                // Fallback: check sun.java.command property
                String cmd = System.getProperty("sun.java.command", "");
                verbose = cmd.contains("--verbose");
            }
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", verbose ? "info" : "warn");
        }
        LOG = LoggerFactory.getLogger("fossil-q");
    }

    @Option(names = {"-d", "--device"}, description = "Watch MAC address (e.g. AA:BB:CC:DD:EE:FF). " +
            "Defaults to active device from ~/.config/fossil-q/config.json", scope = ScopeType.INHERIT)
    String macAddress;

    @Option(names = {"--subprocess"}, description = "Use subprocess-based BLE transport (bluetoothctl/busctl/gdbus) instead of dbus-java", scope = ScopeType.INHERIT)
    boolean useSubprocess;

    @Option(names = {"--verbose"}, description = "Show verbose log output (connection progress, BLE details)", scope = ScopeType.INHERIT)
    boolean verbose;

    @Option(names = {"--json"}, description = "Machine-readable JSON output", scope = ScopeType.INHERIT)
    boolean jsonOutput;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /**
     * Resolve the MAC address: explicit -d flag > global config activeDevice.
     * Returns null if neither is set.
     */
    String resolvedMac() {
        if (macAddress != null && !macAddress.isBlank()) {
            return macAddress;
        }
        GlobalConfig global = GlobalConfig.load();
        return global.getActiveDevice();
    }

    /**
     * Resolve MAC, printing an error and exiting if not available.
     */
    String requireMac() {
        String mac = resolvedMac();
        if (mac == null || mac.isBlank()) {
            System.err.println("Error: No device specified. Use -d <MAC> or set active device with: fossil-q config set-device <MAC>");
            System.exit(1);
        }
        return mac;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    // --- Shared connection logic ---

    /**
     * Connect with minimal init (auth + file versions, no animation/config sync/filter upload).
     * This is the fast path for most commands.
     */
    static FossilQAdapter connectAndInit(String mac, boolean useSubprocess) {
        return connectAndInit(mac, useSubprocess, false);
    }

    /**
     * Connect and initialize.
     * @param fullInit true = full init (animation + config sync + filter upload);
     *                 false = minimal init (auth + file versions only, ~2-3s faster)
     */
    static FossilQAdapter connectAndInit(String mac, boolean useSubprocess, boolean fullInit) {
        if (mac == null || mac.isBlank()) {
            System.err.println("Error: No device specified. Use -d <MAC> or set active device with: fossil-q config set-device <MAC>");
            System.exit(1);
        }

        // Status message on stderr (visible even in quiet mode)
        System.err.print("Connecting to " + mac + "...");
        System.err.flush();

        BleTransport transport;
        if (useSubprocess) {
            LOG.info("Using subprocess transport (bluetoothctl/busctl/gdbus)");
            transport = new BluezTransport();
        } else {
            LOG.info("Using dbus-java transport (direct D-Bus)");
            transport = new DbusTransport();
        }
        if (!transport.connect(mac)) {
            System.err.println(" failed");
            System.err.println("Error: Failed to connect to " + mac);
            System.exit(1);
        }

        System.err.print(" connected. Initializing...");
        System.err.flush();

        FossilQAdapter adapter = new FossilQAdapter(transport);

        // Auto-sync: if device config is dirty, promote to fullInit
        boolean autoSyncing = false;
        if (!fullInit) {
            DeviceConfig dc = DeviceConfig.load(mac);
            if (dc.isSyncNeeded()) {
                LOG.info("Device config has unsynced changes -- promoting to full init");
                fullInit = true;
                autoSyncing = true;
            }
        }

        adapter.initialize(fullInit);
        adapter.initialize(fullInit);

        // For Fossil protocol, wait for the async init to complete
        if (adapter.isFossilProtocol()) {
            // 60s timeout: allows 30s for auth button press + init overhead
            if (!adapter.waitForInit(60_000)) {
                LOG.warn("Initialization may not have completed fully");
            }
        }

        System.err.println(" ready.");

        // If auto-sync was triggered, wait for the config/filter uploads to complete.
        // waitForInit returns when auth finishes, but the config put and filter
        // upload are still queued. Give them time to complete before the caller
        // starts its own operations.
        if (autoSyncing && adapter.isFossilProtocol()) {
            sleep(3000);
        }

        return adapter;
    }

    /**
     * Shutdown adapter with status message.
     */
    static void shutdownAdapter(FossilQAdapter adapter) {
        adapter.shutdown();
    }

    // ========== Subcommands ==========

    @Command(name = "info", description = "Show device info (model, firmware, battery)")
    static class InfoCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            String mac = parent.requireMac();
            FossilQAdapter adapter = connectAndInit(mac, parent.useSubprocess);

            if (parent.jsonOutput) {
                System.out.printf("{\"model\":\"%s\",\"firmware\":\"%s\",\"battery\":%d,\"protocol\":\"%s\"}%n",
                        adapter.getModelNumber(), adapter.getFirmwareVersion(),
                        adapter.getBatteryLevel(),
                        adapter.isFossilProtocol() ? "fossil" : "misfit");
            } else {
                System.out.println("Model:    " + adapter.getModelNumber());
                System.out.println("Firmware: " + adapter.getFirmwareVersion());
                System.out.println("Battery:  " + adapter.getBatteryLevel() + "%");
                System.out.println("Protocol: " + (adapter.isFossilProtocol() ? "Fossil (2.x)" : "Misfit (0.x/1.x)"));
            }

            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "time", description = "Sync current time to watch")
    static class TimeCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            String mac = parent.requireMac();
            FossilQAdapter adapter = connectAndInit(mac, parent.useSubprocess);
            adapter.syncTime();
            System.out.println("Time synced");
            adapter.shutdown();
            return 0;
        }
    }

    // Vibration pattern names (from official Fossil app NotificationVibePattern.java)
    // Hardware-tested on Q Commuter HW.0.0 (2026-05-22):
    //   0 = AUTO           → no vibration (silent)
    //   1 = CALL           → triple vibration
    //   2 = TEXT           → double vibration
    //   3 = EMAIL          → single vibration
    //   4 = DEFAULT        → single vibration (same as EMAIL/3)
    //   5 = ONE_SHORT_VIBE → strong single vibration
    //   6 = TWO_SHORT_VIBES → strong double vibration
    //   7 = THREE_SHORT_VIBES → strong triple vibration
    //   8 = ONE_LONG_VIBE  → long vibration
    //   9 = NO_VIBE        → no vibration (silent)
    // Single source of truth lives in :protocol (NotificationConfig) so the
    // protocol module has no back-dependency on :cli. Aliased here for the
    // many existing CLI references below.
    static final String[] VIBE_PATTERN_NAMES = NotificationConfig.VIBE_PATTERN_NAMES;

    /**
     * Parse a vibration pattern from name or number (0-9).
     * Returns -1 if invalid.
     */
    static int parseVibePattern(String s) {
        // Try as number first
        try {
            int n = Integer.parseInt(s);
            if (n >= 0 && n <= 9) return n;
            return -1;
        } catch (NumberFormatException ignored) {}

        // Try as name (case-insensitive)
        String upper = s.toUpperCase().replace("-", "_");
        for (int i = 0; i < VIBE_PATTERN_NAMES.length; i++) {
            if (VIBE_PATTERN_NAMES[i].equals(upper)) return i;
        }
        // Short aliases
        return switch (upper) {
            case "DEFAULT" -> 4;
            case "SHORT", "ONE_SHORT", "1SHORT" -> 5;
            case "TWO_SHORT", "2SHORT", "DOUBLE_SHORT" -> 6;
            case "THREE_SHORT", "3SHORT", "TRIPLE_SHORT" -> 7;
            case "LONG", "ONE_LONG", "1LONG" -> 8;
            case "NONE", "SILENT", "OFF" -> 9;
            default -> -1;
        };
    }

    /**
     * Parse a hand position string. Accepts:
     *   - Degrees: "90" or "90/90" (hour/minute)
     *   - Clock positions: "3:00" or "3:15" (maps to degrees)
     *   - Named presets: "phone", "sms", "email", "whatsapp", "calendar"
     * Returns [hourDeg, minDeg] or null if invalid.
     */
    static int[] parseHandPosition(String s) {
        if (s == null || s.isBlank()) return null;
        String norm = s.trim().toLowerCase().replace("-", "_");

        // Named presets (from official Fossil app positions — FINDINGS.md #17 & #21d)
        return switch (norm) {
            case "phone", "call" -> new int[]{60, 60};           // 2:00
            case "sms", "text", "message" -> new int[]{60, 60};  // 2:00
            case "whatsapp", "chat" -> new int[]{90, 90};        // 3:00 (default)
            case "email", "mail" -> new int[]{120, 120};         // 4:00
            case "calendar", "cal" -> new int[]{300, 300};       // 10:00
            case "catchall", "all", "default" -> new int[]{359, 359}; // 11:59
            case "1", "1:00" -> new int[]{30, 30};
            case "2", "2:00" -> new int[]{60, 60};
            case "3", "3:00" -> new int[]{90, 90};
            case "4", "4:00" -> new int[]{120, 120};
            case "5", "5:00" -> new int[]{150, 150};
            case "6", "6:00" -> new int[]{180, 180};
            case "7", "7:00" -> new int[]{210, 210};
            case "8", "8:00" -> new int[]{240, 240};
            case "9", "9:00" -> new int[]{270, 270};
            case "10", "10:00" -> new int[]{300, 300};
            case "11", "11:00" -> new int[]{330, 330};
            case "12", "12:00" -> new int[]{0, 0};
            default -> parseHandPositionRaw(norm);
        };
    }

    /**
     * Parse raw degree specification: "90" (both hands), "90/180" (hour/minute separate),
     * or clock times like "3:15" → 90°/90°.
     */
    private static int[] parseHandPositionRaw(String s) {
        // Try "hourDeg/minDeg" format
        if (s.contains("/")) {
            String[] parts = s.split("/");
            if (parts.length == 2) {
                try {
                    int h = Integer.parseInt(parts[0].trim());
                    int m = Integer.parseInt(parts[1].trim());
                    if (h >= 0 && h <= 360 && m >= 0 && m <= 360) return new int[]{h, m};
                } catch (NumberFormatException ignored) {}
            }
            return null;
        }
        // Try clock format "H:MM" — map to degrees
        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length == 2) {
                try {
                    int hour = Integer.parseInt(parts[0].trim());
                    int minute = Integer.parseInt(parts[1].trim());
                    if (hour >= 0 && hour <= 12 && minute >= 0 && minute <= 59) {
                        // Convert clock time to degrees:
                        // Hour hand: each hour = 30°, each minute = 0.5° additional
                        int hourDeg = (hour % 12) * 30 + minute / 2;
                        // Minute hand: each minute = 6°
                        int minDeg = minute * 6;
                        return new int[]{hourDeg, minDeg};
                    }
                } catch (NumberFormatException ignored) {}
            }
            return null;
        }
        // Try single degree value (applies to both hands)
        try {
            int deg = Integer.parseInt(s.trim());
            if (deg >= 0 && deg <= 360) return new int[]{deg, deg};
        } catch (NumberFormatException ignored) {}
        return null;
    }

    @Command(name = "notify", mixinStandardHelpOptions = true,
             description = "Send a notification. Use a configured type name (from notify-config), " +
                     "or specify --vibe/--position for ad-hoc notifications.")
    static class NotifyCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", arity = "0..1",
                description = "Configured notification type name or index (from notify-config), " +
                        "or a VibrationType for legacy mode (SINGLE_SHORT, DOUBLE_SHORT, etc.)")
        String typeOrVibration;

        @Option(names = {"--direct"}, description = "Use misfit-style direct characteristic write instead of Fossil file protocol")
        boolean direct;

        @Option(names = {"-H", "--hour"}, description = "Hour hand degrees (0-360, only with --direct)", defaultValue = "-1")
        int hourDeg;

        @Option(names = {"-M", "--minute"}, description = "Minute hand degrees (0-360, only with --direct)", defaultValue = "-1")
        int minDeg;

        @Option(names = {"--vibe", "-v"}, description = "Vibration pattern (0-9 or name). " +
                "0=AUTO(silent), 1=CALL(triple), 2=TEXT(double), 3=EMAIL(single), 4=DEFAULT(single), " +
                "5=ONE_SHORT(strong single), 6=TWO_SHORT(strong double), 7=THREE_SHORT(strong triple), " +
                "8=ONE_LONG(long), 9=NO_VIBE(silent)")
        String vibePattern;

        @Option(names = {"-p", "--position"}, description = "Hand position (degrees, clock time, or preset). " +
                "E.g. 90, 3:00, 120/240, phone, email, calendar.")
        String position;

        @Override
        public Integer call() {
            // --- Mode 1: Trigger a configured notification type by name/index ---
            if (typeOrVibration != null && vibePattern == null && position == null && !direct) {
                String mac = parent.resolvedMac();
                NotificationConfig config = (mac != null) ? NotificationConfig.load(mac) : NotificationConfig.load();
                NotificationConfig.NotifType type = config.resolve(typeOrVibration);
                if (type != null) {
                    return triggerConfigured(type, config);
                }
                // Not a configured name — fall through to try as VibrationType
            }

            // --- Mode 2: Ad-hoc with --vibe and/or --position ---
            if ((vibePattern != null || position != null) && !direct) {
                int pattern = 4; // DEFAULT
                if (vibePattern != null) {
                    pattern = parseVibePattern(vibePattern);
                    if (pattern < 0) {
                        System.err.println("Invalid vibration pattern: " + vibePattern);
                        System.err.println("Use 0-9 or name: AUTO, CALL, TEXT, EMAIL, DEFAULT, " +
                                "ONE_SHORT, TWO_SHORT, THREE_SHORT, ONE_LONG, NO_VIBE");
                        return 1;
                    }
                }
                short hDeg = 90, mDeg = 90;
                if (position != null) {
                    int[] pos = parseHandPosition(position);
                    if (pos == null) {
                        System.err.println("Invalid hand position: " + position);
                        System.err.println("Use: degrees (90 or 90/180), clock (3:00), " +
                                "or preset (phone, sms, email, whatsapp, calendar, 1-12)");
                        return 1;
                    }
                    hDeg = (short) pos[0];
                    mDeg = (short) pos[1];
                }
                FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
                sleep(500);
                String patternName = VIBE_PATTERN_NAMES[pattern];
                adapter.playNotificationWithPattern((byte) pattern, hDeg, mDeg);
                System.out.printf("Notification sent: vibe=%d (%s), hands=%d\u00b0/%d\u00b0%n",
                        pattern, patternName, hDeg, mDeg);
                sleep(2000);
                adapter.shutdown();
                return 0;
            }

            // --- Mode 3: Direct misfit-style ---
            if (direct) {
                VibrationType vt = parseVibrationType(typeOrVibration);
                FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
                sleep(500);
                adapter.playMisfitNotification(vt, hourDeg, minDeg);
                System.out.println("Direct notification sent: " + vt);
                sleep(2000);
                adapter.shutdown();
                return 0;
            }

            // --- Mode 4: Legacy (bare VibrationType name, no --vibe/--position) ---
            if (typeOrVibration != null) {
                // Check if it looks like an intended config name that wasn't found
                try {
                    VibrationType.valueOf(typeOrVibration.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Not a valid VibrationType — user probably meant a notification type name
                    System.err.println("Unknown notification type: " + typeOrVibration);
                    System.err.println("Not a configured type (see 'notify-config --list') or a VibrationType.");
                    System.err.println("Available VibrationTypes: SINGLE_SHORT, DOUBLE_SHORT, TRIPLE_SHORT, " +
                            "SINGLE_NORMAL, DOUBLE_NORMAL, TRIPLE_NORMAL, SINGLE_LONG");
                    return 1;
                }
            }
            VibrationType vt = parseVibrationType(typeOrVibration);
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            sleep(500);
            adapter.playNotification(vt, -1, -1);
            System.out.println("Notification sent: " + vt);
            sleep(2000);
            shutdownAdapter(adapter);
            return 0;
        }

        /** Trigger a configured notification type. Uploads all filters, then plays the specific one. */
        private int triggerConfigured(NotificationConfig.NotifType type, NotificationConfig config) {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            sleep(500);

            // Upload all configured filters (so the watch knows about all types)
            adapter.uploadNotificationFilter(config);
            // Play the specific type by its package name
            adapter.playNotificationByPackageName(type.packageName());

            String vibeName = (type.vibe >= 0 && type.vibe < VIBE_PATTERN_NAMES.length)
                    ? VIBE_PATTERN_NAMES[type.vibe] : "?";
            System.out.printf("Notification '%s' sent: hands=%d\u00b0/%d\u00b0, vibe=%d (%s)%n",
                    type.name, type.hourDeg, type.minDeg, type.vibe, vibeName);
            sleep(2000);
            adapter.shutdown();
            return 0;
        }

        /** Parse VibrationType from string, defaulting to SINGLE_SHORT. */
        private static VibrationType parseVibrationType(String s) {
            if (s == null || s.isBlank()) return VibrationType.SINGLE_SHORT;
            try {
                return VibrationType.valueOf(s.toUpperCase());
            } catch (IllegalArgumentException e) {
                return VibrationType.SINGLE_SHORT;
            }
        }
    }

    @Command(name = "notify-test", mixinStandardHelpOptions = true,
             description = "Play all vibration patterns sequentially (2s gap between each)")
    static class NotifyTestCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = {"--from"}, description = "Start pattern number (0-9)", defaultValue = "0")
        int from;

        @Option(names = {"--to"}, description = "End pattern number (0-9, inclusive)", defaultValue = "9")
        int to;

        @Option(names = {"--gap"}, description = "Seconds between patterns (min 12 — hands must return before next notif)", defaultValue = "12")
        int gapSeconds;

        @Override
        public Integer call() {
            if (from < 0 || from > 9 || to < 0 || to > 9 || from > to) {
                System.err.println("Pattern range must be 0-9 with --from <= --to");
                return 1;
            }

            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            // Wait for init to fully complete
            sleep(5000);

            System.out.printf("Testing vibration patterns %d-%d with %ds gap...%n", from, to, gapSeconds);
            System.out.println("Feel the watch for each pattern.\n");

            for (int i = from; i <= to; i++) {
                String name = VIBE_PATTERN_NAMES[i];
                System.out.printf("  [%d] %s ...", i, name);
                System.out.flush();

                // Upload filter with this pattern, then send notification
                adapter.playNotificationWithPattern((byte) i);

                // Wait for the vibration to complete + gap
                sleep(gapSeconds * 1000L);
                System.out.println(" done");
            }

            System.out.println("\nAll patterns tested.");
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "position-test", mixinStandardHelpOptions = true,
             description = "Test notification hand positions by cycling through clock positions (1:00 through 12:00)")
    static class PositionTestCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = {"--from"}, description = "Start clock hour (1-12)", defaultValue = "1")
        int from;

        @Option(names = {"--to"}, description = "End clock hour (1-12, inclusive)", defaultValue = "12")
        int to;

        @Option(names = {"--gap"}, description = "Seconds between positions (min 12 — hands must return)", defaultValue = "12")
        int gapSeconds;

        @Option(names = {"--vibe", "-v"}, description = "Vibration pattern to use for all positions (0-9 or name, default: 4=DEFAULT)", defaultValue = "4")
        String vibePattern;

        @Option(names = {"--positions"}, description = "Custom position list (comma-separated degrees or clock times), e.g. '30,60,90,180,270'")
        String positions;

        @Override
        public Integer call() {
            int pattern = parseVibePattern(vibePattern);
            if (pattern < 0) {
                System.err.println("Invalid vibration pattern: " + vibePattern);
                return 1;
            }

            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            sleep(5000);

            if (positions != null) {
                // Custom position list
                String[] parts = positions.split(",");
                System.out.printf("Testing %d custom positions with %ds gap (vibe=%s)...%n",
                        parts.length, gapSeconds, VIBE_PATTERN_NAMES[pattern]);
                System.out.println("Watch the hands move to each position.\n");

                for (int i = 0; i < parts.length; i++) {
                    int[] pos = parseHandPosition(parts[i].trim());
                    if (pos == null) {
                        System.err.printf("  [%d] Invalid position: %s — skipping%n", i + 1, parts[i].trim());
                        continue;
                    }
                    System.out.printf("  [%d] %d\u00b0/%d\u00b0 ...", i + 1, pos[0], pos[1]);
                    System.out.flush();
                    adapter.playNotificationWithPattern((byte) pattern, (short) pos[0], (short) pos[1]);
                    sleep(gapSeconds * 1000L);
                    System.out.println(" done");
                }
            } else {
                // Clock hour positions (1:00 through 12:00)
                if (from < 1 || from > 12 || to < 1 || to > 12 || from > to) {
                    System.err.println("Hour range must be 1-12 with --from <= --to");
                    adapter.shutdown();
                    return 1;
                }

                System.out.printf("Testing hand positions %d:00-%d:00 with %ds gap (vibe=%s)...%n",
                        from, to, gapSeconds, VIBE_PATTERN_NAMES[pattern]);
                System.out.println("Watch the hands move to each clock position.\n");

                for (int hour = from; hour <= to; hour++) {
                    int deg = (hour % 12) * 30; // 1:00=30°, 2:00=60°, ..., 12:00=0°
                    String clockStr = hour + ":00";
                    System.out.printf("  [%2d:00] %3d\u00b0/%3d\u00b0 ...", hour, deg, deg);
                    System.out.flush();
                    adapter.playNotificationWithPattern((byte) pattern, (short) deg, (short) deg);
                    sleep(gapSeconds * 1000L);
                    System.out.println(" done");
                }
            }

            System.out.println("\nAll positions tested.");
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "find", description = "Make the watch vibrate (find my watch)")
    static class FindCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = {"--stop"}, description = "Stop vibration")
        boolean stop;

        @Option(names = {"-t", "--time"}, description = "Vibration duration in seconds", defaultValue = "5")
        int seconds;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            if (stop) {
                adapter.stopFindDevice();
                System.out.println("Stopped vibration");
            } else {
                adapter.findDevice();
                System.out.println("Vibrating for " + seconds + " seconds...");
                sleep(seconds * 1000);
                adapter.stopFindDevice();
            }
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "hands", description = "Move watch hands to specific positions")
    static class HandsCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Hour hand degrees (0-360)")
        int hourDeg;

        @Parameters(index = "1", description = "Minute hand degrees (0-360)")
        int minDeg;

        @Parameters(index = "2", description = "Sub-eye degrees (0-360)", defaultValue = "0")
        int subDeg;

        @Option(names = {"--release"}, description = "Release hands control after moving")
        boolean release;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            adapter.requestHandsControl();
            sleep(200);
            adapter.setHands(hourDeg, minDeg, subDeg);
            System.out.printf("Hands set to: hour=%d° min=%d° sub=%d°%n", hourDeg, minDeg, subDeg);
            sleep(500);
            if (release) {
                adapter.releaseHandsControl();
                System.out.println("Hands control released");
            }
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "calibrate", description = "Interactive hand calibration — nudge hands to 12:00:00, then save")
    static class CalibrateCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);

            // Track hand positions (degrees, 0-359)
            int hourDeg = 0, minDeg = 0, subDeg = 0;
            int step = 6; // default coarse = 6° (one minute mark)

            // Take hand control and move to 0°/0°/0°
            adapter.requestHandsControl();
            sleep(300);
            adapter.setHands(0, 0, 0);
            sleep(500);

            System.out.println();
            System.out.println("  ╔══════════════════════════════════════════════════════╗");
            System.out.println("  ║          INTERACTIVE HAND CALIBRATION                ║");
            System.out.println("  ╠══════════════════════════════════════════════════════╣");
            System.out.println("  ║  All hands have been moved to 0°.                   ║");
            System.out.println("  ║  Nudge each hand until it points exactly at 12.     ║");
            System.out.println("  ║                                                      ║");
            System.out.println("  ║  h / H  — nudge hour hand  +step / −step            ║");
            System.out.println("  ║  m / M  — nudge minute hand +step / −step           ║");
            System.out.println("  ║  s / S  — nudge sub-eye    +step / −step            ║");
            System.out.println("  ║  f      — fine step   (1°)                          ║");
            System.out.println("  ║  c      — coarse step (6°, default)                 ║");
            System.out.println("  ║  Enter  — save calibration & sync time              ║");
            System.out.println("  ║  q      — quit without saving                       ║");
            System.out.println("  ╚══════════════════════════════════════════════════════╝");
            System.out.println();

            Terminal terminal = null;
            try {
                terminal = TerminalBuilder.builder()
                        .system(true)
                        .jansi(false)
                        .build();
                terminal.enterRawMode();
                var reader = terminal.reader();

                printStatus(hourDeg, minDeg, subDeg, step);

                boolean saved = false;
                loop:
                while (true) {
                    int ch = reader.read();
                    if (ch == -1) break; // EOF

                    switch (ch) {
                        case 'h': hourDeg = wrap(hourDeg + step); break;
                        case 'H': hourDeg = wrap(hourDeg - step); break;
                        case 'm': minDeg  = wrap(minDeg + step);  break;
                        case 'M': minDeg  = wrap(minDeg - step);  break;
                        case 's': subDeg  = wrap(subDeg + step);  break;
                        case 'S': subDeg  = wrap(subDeg - step);  break;
                        case 'f': step = 1; printStatus(hourDeg, minDeg, subDeg, step); continue loop;
                        case 'c': step = 6; printStatus(hourDeg, minDeg, subDeg, step); continue loop;
                        case 'q':
                            System.out.print("\r\033[K"); // clear line
                            System.out.println("Aborted — releasing hands without saving.");
                            adapter.releaseHandsControl();
                            sleep(300);
                            adapter.shutdown();
                            return 0;
                        case '\r': case '\n': // Enter
                            saved = true;
                            break loop;
                        default:
                            continue loop; // ignore unknown keys
                    }

                    // Send the updated hand positions to the watch
                    adapter.setHands(hourDeg, minDeg, subDeg);
                    printStatus(hourDeg, minDeg, subDeg, step);
                    sleep(200); // let watch respond before next keystroke
                }

                if (saved) {
                    System.out.print("\r\033[K");
                    System.out.println("Saving calibration...");
                    adapter.saveCalibration();
                    sleep(300);
                    adapter.releaseHandsControl();
                    sleep(300);
                    adapter.syncTime();
                    sleep(500);
                    System.out.println("Calibration saved. Time synced. Hands should show correct time.");
                }

            } catch (IOException e) {
                System.err.println("Terminal error: " + e.getMessage());
                // Release hands on error
                try { adapter.releaseHandsControl(); } catch (Exception ignored) {}
                adapter.shutdown();
                return 1;
            } finally {
                if (terminal != null) {
                    try { terminal.close(); } catch (IOException ignored) {}
                }
            }

            adapter.shutdown();
            return 0;
        }

        /** Wrap degrees to 0-359 range. */
        private static int wrap(int deg) {
            return ((deg % 360) + 360) % 360;
        }

        /** Format degrees as a clock-face time string. */
        private static String clockStr(int deg, String hand) {
            if (hand.equals("hour")) {
                // 360° = 12 hours, each degree = 12/360 = 1/30 hour = 2 minutes
                int totalMin = (deg * 2) % (12 * 60);
                return String.format("%d:%02d", totalMin / 60, totalMin % 60);
            } else {
                // minute/sub: 360° = 60 minutes, each degree = 1/6 minute = 10 seconds
                int totalSec = (deg * 10) % 3600;
                return String.format("%d:%02d", totalSec / 60, totalSec % 60);
            }
        }

        private static void printStatus(int hourDeg, int minDeg, int subDeg, int step) {
            System.out.printf("\r\033[K  Hour: %3d° (%5s)  Min: %3d° (%5s)  Sub: %3d°  [step=%d°]",
                    hourDeg, clockStr(hourDeg, "hour"),
                    minDeg, clockStr(minDeg, "min"),
                    subDeg, step);
            System.out.flush();
        }
    }

    @Command(name = "step-goal", description = "Set daily step goal")
    static class StepGoalCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Step goal (e.g. 10000)")
        int steps;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            adapter.setStepGoal(steps);
            System.out.println("Step goal set to " + steps);
            sleep(1000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "step-count", description = "Read or set/reset current step count",
             mixinStandardHelpOptions = true,
             subcommands = {
                     Main.StepCountSetCmd.class
             })
    static class StepCountCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);

            var future = new java.util.concurrent.CompletableFuture<Integer>();
            adapter.getStepCount(future);

            Integer steps;
            try {
                steps = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("Timeout reading step count (30s)");
                adapter.shutdown();
                return 1;
            } catch (Exception e) {
                System.err.println("Error reading step count: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            if (steps == null) {
                System.err.println("Failed to read step count from watch.");
                adapter.shutdown();
                return 1;
            }

            System.out.println("Current step count: " + steps);

            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "set", description = "Set/reset the current step count")
    static class StepCountSetCmd implements Callable<Integer> {
        @ParentCommand StepCountCmd stepCountParent;

        @Parameters(index = "0", description = "New step count value (e.g. 500)")
        int steps;

        @Override
        public Integer call() {
            if (steps < 0) {
                System.err.println("Step count must be non-negative");
                return 1;
            }

            FossilQAdapter adapter = connectAndInit(stepCountParent.parent.requireMac(), stepCountParent.parent.useSubprocess);
            adapter.setStepCount(steps);
            System.out.println("Step count set to " + steps);
            sleep(1000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "vibration", description = "Set vibration strength (0-100)")
    static class VibrationCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Vibration strength (0-100)")
        int strength;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            adapter.setVibrationStrength((short) Math.min(100, Math.max(0, strength)));
            System.out.println("Vibration strength set to " + strength);
            sleep(1000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "timezone", description = "Set timezone offset (Fossil protocol only)")
    static class TimezoneCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Timezone offset in minutes from UTC (e.g. 60 for UTC+1)")
        short offsetMinutes;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            adapter.setTimezoneOffset(offsetMinutes);
            System.out.printf("Timezone offset set to %d minutes (%+.1f hours)%n",
                    offsetMinutes, offsetMinutes / 60.0);
            sleep(1000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "alarm", description = "Manage alarms (Fossil protocol only)",
             mixinStandardHelpOptions = true,
             subcommands = {
                     Main.AlarmSetCmd.class,
                     Main.AlarmAtCmd.class,
                     Main.AlarmListCmd.class,
                     Main.AlarmClearCmd.class,
                     Main.AlarmTestCmd.class,
                     Main.AlarmRawCmd.class,
             })
    static class AlarmCmd implements Runnable {
        @ParentCommand Main parent;

        @Override
        public void run() {
            CommandLine.usage(this, System.out);
        }
    }

    @Command(name = "set", description = "Set one or more alarms (replaces all existing alarms)")
    static class AlarmSetCmd implements Callable<Integer> {
        @ParentCommand AlarmCmd alarmParent;

        @Parameters(description = "Alarm times in HH:MM format (one or more)", arity = "1..*")
        String[] times;

        @Option(names = {"--days"}, description = "Repeat days bitmask applied to all alarms (Mon=2, Tue=4, Wed=8, Thu=16, Fri=32, Sat=64, Sun=1). 0 = one-shot. Note: GB has Wed/Thu swapped.", defaultValue = "0")
        int days;

        @Option(names = {"--label"}, description = "Alarm label", defaultValue = "Alarm")
        String label;

        @Override
        public Integer call() {
            Alarm[] alarms = new Alarm[times.length];
            for (int i = 0; i < times.length; i++) {
                String[] parts = times[i].split(":");
                if (parts.length != 2) {
                    System.err.println("Invalid time format '" + times[i] + "'. Use HH:MM");
                    return 1;
                }
                int hour, minute;
                try {
                    hour = Integer.parseInt(parts[0]);
                    minute = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid time '" + times[i] + "': not a number");
                    return 1;
                }
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                    System.err.println("Invalid time '" + times[i] + "': hour must be 0-23, minute 0-59");
                    return 1;
                }
                if (days > 0) {
                    alarms[i] = new Alarm((byte) minute, (byte) hour, (byte) days, label, "");
                } else {
                    alarms[i] = new Alarm((byte) minute, (byte) hour, label, "");
                }
            }

            FossilQAdapter adapter = connectAndInit(alarmParent.parent.requireMac(), alarmParent.parent.useSubprocess);

            var result = new java.util.concurrent.CompletableFuture<Boolean>();
            adapter.setAlarmsWithResult(alarms, result);

            Boolean success;
            try {
                success = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Timeout or error setting alarms: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            if (success) {
                System.out.printf("Set %d alarm(s):%n", alarms.length);
                for (Alarm a : alarms) {
                    System.out.println("  " + a);
                }
            } else {
                System.err.println("Failed to set alarms — watch rejected the upload");
            }

            sleep(1000);
            adapter.shutdown();
            return success ? 0 : 1;
        }
    }

    @Command(name = "at", description = "Set a one-shot alarm for a specific date/time within the next 7 days")
    static class AlarmAtCmd implements Callable<Integer> {
        @ParentCommand AlarmCmd alarmParent;

        @Parameters(description = "Target date-time: YYYY-MM-DDThh:mm, 'tomorrow HH:MM', or weekday like 'friday 14:30'", arity = "1..2")
        String[] dateTimeParts;

        @Override
        public Integer call() {
            // Parse the target datetime
            java.time.LocalDateTime target;
            try {
                target = parseDateTimeArg(dateTimeParts);
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                System.err.println("Usage: alarm at <datetime>");
                System.err.println("  alarm at 2026-05-23T14:30");
                System.err.println("  alarm at tomorrow 07:30");
                System.err.println("  alarm at friday 14:30");
                return 1;
            }

            // Validate: must be in the future
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            if (!target.isAfter(now)) {
                System.err.println("Error: target time " + target + " is in the past (now: " + now.withNano(0) + ")");
                return 1;
            }

            // Validate: must be within 7 days minus 1 second
            long secondsUntil = java.time.Duration.between(now, target).getSeconds();
            long maxSeconds = 7 * 24 * 3600 - 1;
            if (secondsUntil > maxSeconds) {
                System.err.printf("Error: target is %d seconds (%.1f days) in the future, max is %d seconds (< 7 days)%n",
                        secondsUntil, secondsUntil / 86400.0, maxSeconds);
                return 1;
            }

            // Compute target weekday
            java.time.DayOfWeek targetDay = target.getDayOfWeek();
            int hour = target.getHour();
            int minute = target.getMinute();

            // Map Java DayOfWeek to alarm bitmask
            // Hardware-verified mapping (Q Commuter HW.0.0):
            //   bit3=Wed, bit4=Thu (opposite of GB Alarm.java which has them swapped)
            int dayBit = switch (targetDay) {
                case SUNDAY    -> 1;   // bit 0
                case MONDAY    -> 2;   // bit 1
                case TUESDAY   -> 4;   // bit 2
                case WEDNESDAY -> 8;   // bit 3 (GB says Thu, actually Wed)
                case THURSDAY  -> 16;  // bit 4 (GB says Wed, actually Thu)
                case FRIDAY    -> 32;  // bit 5
                case SATURDAY  -> 64;  // bit 6
            };

            System.out.printf("Alarm for %s %s %02d:%02d (in %dh %dm)%n",
                    targetDay, target.toLocalDate(), hour, minute,
                    secondsUntil / 3600, (secondsUntil % 3600) / 60);

            // Build raw alarm bytes using the non-repeating weekday format:
            //   byte0 = 0x80 | day_bits  (weekday mask with marker)
            //   byte1 = minute           (NO 0x80 repeat flag)
            //   byte2 = hour
            // This fires once on the specified weekday, then stops.
            byte[] rawAlarm = new byte[] {
                    (byte) (0x80 | dayBit),  // day bits + marker
                    (byte) minute,            // minute, no repeat flag
                    (byte) hour
            };

            FossilQAdapter adapter = connectAndInit(alarmParent.parent.requireMac(), alarmParent.parent.useSubprocess);

            var result = new java.util.concurrent.CompletableFuture<Boolean>();
            adapter.setAlarmsRaw(rawAlarm, result);

            Boolean success;
            try {
                success = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Error setting alarm: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            if (success) {
                System.out.println("Alarm set.");
            } else {
                System.err.println("Failed to set alarm.");
            }

            sleep(1000);
            adapter.shutdown();
            return success ? 0 : 1;
        }

        /**
         * Parse flexible date-time arguments:
         *   "2026-05-23T14:30"       → ISO local datetime
         *   "2026-05-23" "14:30"     → ISO date + time
         *   "tomorrow" "07:30"       → tomorrow at 07:30
         *   "friday" "14:30"         → next friday at 14:30
         *   "fri" "14:30"            → next friday at 14:30
         */
        private java.time.LocalDateTime parseDateTimeArg(String[] parts) {
            if (parts == null || parts.length == 0) {
                throw new IllegalArgumentException("No date-time specified");
            }

            String joined = String.join(" ", parts).trim();

            // Try ISO format: 2026-05-23T14:30
            try {
                return java.time.LocalDateTime.parse(joined);
            } catch (java.time.format.DateTimeParseException ignored) {}

            // Split into date-part and time-part
            if (parts.length < 2) {
                throw new IllegalArgumentException("Need both date and time, e.g. 'tomorrow 07:30' or '2026-05-23 14:30'");
            }

            String datePart = parts[0].toLowerCase();
            String timePart = parts[1];

            // Parse time
            String[] tp = timePart.split(":");
            if (tp.length != 2) {
                throw new IllegalArgumentException("Invalid time '" + timePart + "'. Use HH:MM");
            }
            int hour, minute;
            try {
                hour = Integer.parseInt(tp[0]);
                minute = Integer.parseInt(tp[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid time '" + timePart + "': not a number");
            }
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                throw new IllegalArgumentException("Invalid time '" + timePart + "': hour 0-23, minute 0-59");
            }
            java.time.LocalTime time = java.time.LocalTime.of(hour, minute);

            // Parse date
            java.time.LocalDate date;
            java.time.LocalDate today = java.time.LocalDate.now();

            if (datePart.equals("today")) {
                date = today;
            } else if (datePart.equals("tomorrow")) {
                date = today.plusDays(1);
            } else {
                // Try as weekday name
                java.time.DayOfWeek targetDow = parseWeekday(datePart);
                if (targetDow != null) {
                    // Find next occurrence of this weekday
                    date = today;
                    java.time.LocalDateTime candidate = java.time.LocalDateTime.of(date, time);
                    if (date.getDayOfWeek() == targetDow && candidate.isAfter(java.time.LocalDateTime.now())) {
                        // Today is the target day and the time is still in the future
                    } else {
                        // Advance to next occurrence
                        int daysAhead = (targetDow.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
                        if (daysAhead == 0) daysAhead = 7; // same day but time has passed
                        date = today.plusDays(daysAhead);
                    }
                } else {
                    // Try as ISO date: 2026-05-23
                    try {
                        date = java.time.LocalDate.parse(datePart);
                    } catch (java.time.format.DateTimeParseException e) {
                        throw new IllegalArgumentException(
                                "Unknown date '" + datePart + "'. Use: tomorrow, today, a weekday name (monday, tue, ...), or YYYY-MM-DD");
                    }
                }
            }

            return java.time.LocalDateTime.of(date, time);
        }

        private java.time.DayOfWeek parseWeekday(String s) {
            return switch (s) {
                case "monday", "mon" -> java.time.DayOfWeek.MONDAY;
                case "tuesday", "tue" -> java.time.DayOfWeek.TUESDAY;
                case "wednesday", "wed" -> java.time.DayOfWeek.WEDNESDAY;
                case "thursday", "thu" -> java.time.DayOfWeek.THURSDAY;
                case "friday", "fri" -> java.time.DayOfWeek.FRIDAY;
                case "saturday", "sat" -> java.time.DayOfWeek.SATURDAY;
                case "sunday", "sun" -> java.time.DayOfWeek.SUNDAY;
                default -> null;
            };
        }
    }

    @Command(name = "list", description = "Read current alarms from watch")
    static class AlarmListCmd implements Callable<Integer> {
        @ParentCommand AlarmCmd alarmParent;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(alarmParent.parent.requireMac(), alarmParent.parent.useSubprocess);

            var future = new java.util.concurrent.CompletableFuture<Alarm[]>();
            adapter.getAlarms(future);

            Alarm[] alarms;
            try {
                alarms = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("Timeout reading alarms (30s)");
                adapter.shutdown();
                return 1;
            } catch (Exception e) {
                System.err.println("Error reading alarms: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            if (alarms.length == 0) {
                System.out.println("No alarms set.");
            } else {
                System.out.printf("%d alarm(s):%n", alarms.length);
                for (int i = 0; i < alarms.length; i++) {
                    System.out.printf("  [%d] %s%n", i + 1, alarms[i]);
                }
            }

            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "clear", description = "Remove all alarms from watch")
    static class AlarmClearCmd implements Callable<Integer> {
        @ParentCommand AlarmCmd alarmParent;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(alarmParent.parent.requireMac(), alarmParent.parent.useSubprocess);

            var result = new java.util.concurrent.CompletableFuture<Boolean>();
            adapter.clearAlarms(result);

            Boolean success;
            try {
                success = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Error clearing alarms: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            if (success) {
                System.out.println("All alarms cleared.");
            } else {
                System.err.println("Failed to clear alarms.");
            }

            sleep(1000);
            adapter.shutdown();
            return success ? 0 : 1;
        }
    }

    @Command(name = "test", description = "Set N test alarms with staggered times (for max alarm count experiment)")
    static class AlarmTestCmd implements Callable<Integer> {
        @ParentCommand AlarmCmd alarmParent;

        @Parameters(index = "0", description = "Number of test alarms to set")
        int count;

        @Option(names = {"--start"}, description = "Start time in HH:MM (alarms stagger from here)", defaultValue = "00:00")
        String startTime;

        @Option(names = {"--repeat"}, description = "Make alarms repeating (all days)", defaultValue = "false")
        boolean repeat;

        @Override
        public Integer call() {
            if (count < 0 || count > 200) {
                System.err.println("Count must be 0-200");
                return 1;
            }

            String[] parts = startTime.split(":");
            int startHour = 0, startMinute = 0;
            if (parts.length == 2) {
                startHour = Integer.parseInt(parts[0]);
                startMinute = Integer.parseInt(parts[1]);
            }

            // Generate N alarms with 1-minute stagger, wrapping at 23:59
            Alarm[] alarms = new Alarm[count];
            for (int i = 0; i < count; i++) {
                int totalMin = (startHour * 60 + startMinute + i) % (24 * 60);
                byte hour = (byte) (totalMin / 60);
                byte minute = (byte) (totalMin % 60);
                String label = "Test" + (i + 1);
                if (repeat) {
                    // All days: Mon=1+Tue=2+Wed=4+Thu=8+Fri=16+Sat=32+Sun=64 = 127
                    alarms[i] = new Alarm(minute, hour, (byte) 127, label, "");
                } else {
                    alarms[i] = new Alarm(minute, hour, label, "");
                }
            }

            System.out.printf("Setting %d test alarm(s) starting at %02d:%02d...%n",
                    count, startHour, startMinute);
            System.out.printf("File size: %d bytes (%d alarms × 3 bytes)%n", count * 3, count);

            FossilQAdapter adapter = connectAndInit(alarmParent.parent.requireMac(), alarmParent.parent.useSubprocess);

            var result = new java.util.concurrent.CompletableFuture<Boolean>();
            adapter.setAlarmsWithResult(alarms, result);

            Boolean success;
            try {
                success = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Timeout or error: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            if (success) {
                System.out.printf("SUCCESS: %d alarms accepted by watch%n", count);
            } else {
                System.err.printf("REJECTED: watch refused %d alarms (check log for error code)%n", count);
            }

            sleep(1000);
            adapter.shutdown();
            return success ? 0 : 1;
        }
    }

    @Command(name = "raw", description = "Send raw alarm bytes (hex, 3 bytes per alarm). For protocol testing.", hidden = true)
    static class AlarmRawCmd implements Callable<Integer> {
        @ParentCommand AlarmCmd alarmParent;

        @Parameters(description = "Hex bytes, e.g. 'A0 1E 07' for a single alarm")
        String[] hexParts;

        @Override
        public Integer call() {
            // Join and parse hex bytes
            String hex = String.join(" ", hexParts).replaceAll("[^0-9a-fA-F]", " ").trim();
            String[] tokens = hex.split("\\s+");
            byte[] data = new byte[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                data[i] = (byte) Integer.parseInt(tokens[i], 16);
            }

            if (data.length % 3 != 0) {
                System.err.println("Warning: data length " + data.length + " is not a multiple of 3 (alarm size)");
            }

            System.out.printf("Sending %d raw bytes (%d potential alarms):%n", data.length, data.length / 3);
            for (int i = 0; i + 2 < data.length; i += 3) {
                byte b0 = data[i], b1 = data[i + 1], b2 = data[i + 2];
                boolean repeat = (b1 & 0x80) != 0;
                int minute = b1 & 0x7F;
                int hour = b2 & 0xFF;
                int days = b0 & 0x7F;
                boolean dayMarker = (b0 & 0x80) != 0;
                System.out.printf("  [%d] %02X %02X %02X → %02d:%02d  days=0x%02X  b0.7=%d  repeat=%b%n",
                        i / 3, b0 & 0xFF, b1 & 0xFF, b2 & 0xFF, hour, minute, days, dayMarker ? 1 : 0, repeat);
            }

            FossilQAdapter adapter = connectAndInit(alarmParent.parent.requireMac(), alarmParent.parent.useSubprocess);

            var result = new java.util.concurrent.CompletableFuture<Boolean>();
            adapter.setAlarmsRaw(data, result);

            Boolean success;
            try {
                success = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            System.out.println(success ? "SUCCESS" : "FAILED");
            sleep(1000);
            adapter.shutdown();
            return success ? 0 : 1;
        }
    }

    @Command(name = "pair", description = "Trigger BLE pairing (creates bond, ties auth to link key)")
    static class PairCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            String mac = parent.requireMac();
            FossilQAdapter adapter = connectAndInit(mac, parent.useSubprocess);
            System.out.println("Initiating BLE pairing...");
            boolean ok = adapter.getTransport().pair();
            if (ok) {
                System.out.println("Pairing successful — auth state is now tied to BLE bond");
                System.out.println("To clear auth: bluetoothctl remove " + mac);
            } else {
                System.out.println("Pairing failed or already paired");
            }
            adapter.shutdown();
            return ok ? 0 : 1;
        }
    }

    @Command(name = "second-timezone",
             description = "Set or disable the second timezone (shown by SECOND_TIMEZONE button function)")
    static class SecondTimezoneCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0",
                description = "Offset in minutes from UTC (e.g. -300 for EST, 330 for IST, 540 for JST). " +
                        "Use 'off' or 'disable' to disable.")
        String offset;

        @Override
        public Integer call() {
            short minutes;
            if (offset.equalsIgnoreCase("off") || offset.equalsIgnoreCase("disable") || offset.equals("1024")) {
                minutes = 1024;
            } else {
                try {
                    minutes = Short.parseShort(offset);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid offset: " + offset);
                    return 1;
                }
                if (minutes < -720 || minutes > 840) {
                    System.err.println("Offset must be between -720 and 840 minutes (or 'off' to disable)");
                    return 1;
                }
            }

            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            adapter.setSecondTimezone(minutes);
            if (minutes == 1024) {
                System.out.println("Second timezone disabled");
            } else {
                System.out.printf("Second timezone set to UTC%+.1f (%d minutes)%n", minutes / 60.0, minutes);
            }
            sleep(1000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "goal-config",
             description = "Set goal tracking target and current value (for GOAL_TRACKING button function)")
    static class GoalConfigCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Goal target (e.g. 8 for '8 glasses of water')")
        int target;

        @Parameters(index = "1", arity = "0..1", description = "Current value (default: 0)", defaultValue = "0")
        int current;

        @Override
        public Integer call() {
            if (target < 1 || target > 99999) {
                System.err.println("Target must be between 1 and 99999");
                return 1;
            }
            if (current < 0) {
                System.err.println("Current value must be >= 0");
                return 1;
            }

            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            adapter.setGoalConfig(target, current);
            System.out.printf("Goal config set: target=%d, current=%d%n", target, current);
            sleep(1000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "read-config", mixinStandardHelpOptions = true,
             description = "Read the watch's current configuration (step count, goals, battery, timezone, etc.)")
    static class ReadConfigCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = {"--raw"}, description = "Show raw hex bytes for each config entry")
        boolean raw;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);

            var future = new java.util.concurrent.CompletableFuture<java.util.List<FossilQAdapter.ConfigEntry>>();
            adapter.readConfig(future);

            java.util.List<FossilQAdapter.ConfigEntry> entries;
            try {
                entries = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("Timeout reading config (30s)");
                adapter.shutdown();
                return 1;
            } catch (Exception e) {
                System.err.println("Error reading config: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            if (entries.isEmpty()) {
                System.out.println("No configuration data returned (or read failed).");
                adapter.shutdown();
                return 1;
            }

            System.out.printf("Watch configuration (%d entries):%n%n", entries.size());
            System.out.printf("  %-8s %-30s %s%n", "ID", "Name", "Value");
            System.out.printf("  %-8s %-30s %s%n", "------", "------------------------------", "-----");
            for (var entry : entries) {
                System.out.printf("  0x%04X  %-30s %s%n", entry.id, entry.name, entry.formattedValue);
                if (raw) {
                    StringBuilder hex = new StringBuilder();
                    for (byte b : entry.rawData) hex.append(String.format("%02X ", b & 0xFF));
                    System.out.printf("  %8s %-30s [%s]%n", "", "", hex.toString().trim());
                }
            }

            System.out.println();
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "inactivity-nudge", mixinStandardHelpOptions = true,
             description = "Configure inactivity warning — watch vibrates if idle for X minutes within a time window")
    static class InactivityNudgeCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0",
                description = "'on' to enable, 'off' to disable, or idle minutes (e.g. 30, 60)")
        String action;

        @Option(names = {"--from"}, description = "Start time HH:MM (default: 08:00)", defaultValue = "08:00")
        String from;

        @Option(names = {"--to"}, description = "End time HH:MM (default: 20:00)", defaultValue = "20:00")
        String to;

        @Option(names = {"--minutes", "-m"}, description = "Minutes of inactivity before nudge (default: 60)", defaultValue = "60")
        int minutes;

        @Override
        public Integer call() {
            boolean enabled;
            int nudgeMinutes = minutes;

            String norm = action.toLowerCase();
            if (norm.equals("off") || norm.equals("disable") || norm.equals("0")) {
                enabled = false;
            } else if (norm.equals("on") || norm.equals("enable")) {
                enabled = true;
            } else {
                // Try parsing as minutes
                try {
                    nudgeMinutes = Integer.parseInt(norm);
                    if (nudgeMinutes < 1 || nudgeMinutes > 255) {
                        System.err.println("Minutes must be 1-255");
                        return 1;
                    }
                    enabled = true;
                } catch (NumberFormatException e) {
                    System.err.println("Invalid action: '" + action + "'. Use 'on', 'off', or a number of minutes.");
                    return 1;
                }
            }

            int fromH = 8, fromM = 0, toH = 20, toM = 0;
            if (enabled) {
                int[] fromParsed = parseTime(from);
                int[] toParsed = parseTime(to);
                if (fromParsed == null) {
                    System.err.println("Invalid --from time: " + from + ". Use HH:MM");
                    return 1;
                }
                if (toParsed == null) {
                    System.err.println("Invalid --to time: " + to + ". Use HH:MM");
                    return 1;
                }
                fromH = fromParsed[0]; fromM = fromParsed[1];
                toH = toParsed[0]; toM = toParsed[1];
            }

            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            adapter.setInactivityNudge(fromH, fromM, toH, toM, nudgeMinutes, enabled);

            if (enabled) {
                System.out.printf("Inactivity nudge ENABLED: vibrate after %d min idle, %02d:%02d-%02d:%02d%n",
                        nudgeMinutes, fromH, fromM, toH, toM);
            } else {
                System.out.println("Inactivity nudge DISABLED");
            }

            sleep(1000);
            adapter.shutdown();
            return 0;
        }

        private static int[] parseTime(String s) {
            if (s == null) return null;
            String[] parts = s.split(":");
            if (parts.length != 2) return null;
            try {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                if (h < 0 || h > 23 || m < 0 || m > 59) return null;
                return new int[]{h, m};
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    @Command(name = "notify-config", mixinStandardHelpOptions = true,
             description = "Configure notification types (name → hand position + vibration pattern). " +
                     "Stored per-device in ~/.config/fossil-q/devices/<MAC>/notifications.json")
    static class NotifyConfigCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = {"--list", "-l"}, description = "List all configured notification types")
        boolean list;

        @Option(names = {"--add", "-a"}, description = "Add/update a notification type: NAME")
        String addName;

        @Option(names = {"--remove", "-r"}, description = "Remove a notification type by name")
        String removeName;

        @Option(names = {"-p", "--position"}, description = "Hand position (degrees, clock time, or preset). Used with --add.")
        String position;

        @Option(names = {"-v", "--vibe"}, description = "Vibration pattern (0-9 or name). Used with --add.", defaultValue = "4")
        String vibe;

        @Option(names = {"--sync", "-s"}, description = "Upload current config to watch (requires -d)")
        boolean sync;

        @Option(names = {"--interactive", "-i"}, description = "Interactive mode: add types one by one")
        boolean interactive;

        @Override
        public Integer call() {
            String mac = parent.resolvedMac();
            NotificationConfig config = (mac != null) ? NotificationConfig.load(mac) : NotificationConfig.load();

            if (removeName != null) {
                if (config.remove(removeName)) {
                    System.out.println("Removed: " + removeName);
                    try {
                        if (mac != null) config.save(mac); else config.save();
                    } catch (Exception e) {
                        System.err.println("Error saving config: " + e.getMessage());
                        return 1;
                    }
                } else {
                    System.err.println("Not found: " + removeName);
                    return 1;
                }
                printTypes(config);
                return 0;
            }

            if (addName != null) {
                int[] pos = null;
                if (position != null) {
                    pos = parseHandPosition(position);
                    if (pos == null) {
                        System.err.println("Invalid position: " + position);
                        return 1;
                    }
                } else {
                    pos = new int[]{90, 90}; // default 3:00
                }
                int vibeNum = parseVibePattern(vibe);
                if (vibeNum < 0) {
                    System.err.println("Invalid vibe pattern: " + vibe);
                    return 1;
                }

                var type = new NotificationConfig.NotifType(addName, pos[0], pos[1], vibeNum);
                config.addOrUpdate(type);
                System.out.println("Added/updated: " + type);
                try {
                    if (mac != null) config.save(mac); else config.save();
                } catch (Exception e) {
                    System.err.println("Error saving config: " + e.getMessage());
                    return 1;
                }
                printTypes(config);
                return 0;
            }

            if (interactive) {
                return runInteractive(config, mac);
            }

            if (sync) {
                if (config.getTypes().isEmpty()) {
                    System.err.println("No notification types configured. Use --add or --interactive first.");
                    return 1;
                }
                FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
                sleep(3000);
                adapter.uploadNotificationFilter(config);
                System.out.printf("Uploaded %d notification type(s) to watch.%n", config.getTypes().size());
                sleep(2000);
                adapter.shutdown();
                return 0;
            }

            // Default: list
            printTypes(config);
            if (mac != null) {
                System.out.println("\nConfig file: " + NotificationConfig.configPath(mac));
            } else {
                System.out.println("\nConfig file: " + NotificationConfig.legacyConfigPath() + " (no active device set)");
            }
            System.out.println("\nUsage:");
            System.out.println("  notify-config --add phone --position 2:00 --vibe call");
            System.out.println("  notify-config --add email --position 4:00 --vibe email");
            System.out.println("  notify-config --remove phone");
            System.out.println("  notify-config --interactive");
            System.out.println("  notify-config --sync -d <MAC>   # upload to watch");
            System.out.println("  notify <name> -d <MAC>          # trigger by name");
            return 0;
        }

        private void printTypes(NotificationConfig config) {
            var types = config.getTypes();
            if (types.isEmpty()) {
                System.out.println("No notification types configured.");
                return;
            }
            System.out.println("Notification types:");
            for (int i = 0; i < types.size(); i++) {
                System.out.printf("  [%d] %s%n", i + 1, types.get(i));
            }
        }

        private int runInteractive(NotificationConfig config, String mac) {
            Terminal terminal = null;
            try {
                terminal = TerminalBuilder.builder()
                        .system(true)
                        .jansi(false)
                        .build();
                var lineReader = org.jline.reader.LineReaderBuilder.builder()
                        .terminal(terminal)
                        .build();

                System.out.println();
                System.out.println("  ╔══════════════════════════════════════════════════════╗");
                System.out.println("  ║     NOTIFICATION TYPE CONFIGURATION          ║");
                System.out.println("  ╠══════════════════════════════════════════════════════╣");
                System.out.println("  ║  Define notification types that map to       ║");
                System.out.println("  ║  specific hand positions + vibe patterns.     ║");
                System.out.println("  ║                                                ║");
                System.out.println("  ║  Enter blank name to finish.                  ║");
                System.out.println("  ╚══════════════════════════════════════════════════════╝");
                System.out.println();

                if (!config.getTypes().isEmpty()) {
                    printTypes(config);
                    System.out.println();
                }

                while (true) {
                    String name;
                    try {
                        name = lineReader.readLine("  Name (blank to finish): ").trim();
                    } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
                        break;
                    }
                    if (name.isEmpty()) break;

                    // Position
                    int[] pos = null;
                    while (pos == null) {
                        String posStr;
                        try {
                            posStr = lineReader.readLine("  Position (degrees, clock time, or preset): ").trim();
                        } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
                            return 0;
                        }
                        pos = parseHandPosition(posStr);
                        if (pos == null) {
                            System.out.println("    Invalid. Try: 90, 3:00, 120/240, phone, email, calendar");
                        }
                    }

                    // Vibe pattern
                    int vibeNum = -1;
                    while (vibeNum < 0) {
                        String vibeStr;
                        try {
                            vibeStr = lineReader.readLine("  Vibe pattern (0-9, name, or Enter for default): ").trim();
                        } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
                            return 0;
                        }
                        if (vibeStr.isEmpty()) {
                            vibeNum = 4; // DEFAULT
                        } else {
                            vibeNum = parseVibePattern(vibeStr);
                            if (vibeNum < 0) {
                                System.out.println("    Invalid. Try: 0-9, call, text, email, default, one_short, long");
                            }
                        }
                    }

                    var type = new NotificationConfig.NotifType(name, pos[0], pos[1], vibeNum);
                    config.addOrUpdate(type);
                    System.out.println("  → Added: " + type);
                    System.out.println();
                }

                try {
                    if (mac != null) config.save(mac); else config.save();
                    System.out.println("Saved to " + (mac != null ? NotificationConfig.configPath(mac) : NotificationConfig.legacyConfigPath()));
                } catch (Exception e) {
                    System.err.println("Error saving: " + e.getMessage());
                    return 1;
                }

                printTypes(config);

            } catch (java.io.IOException e) {
                System.err.println("Terminal error: " + e.getMessage());
                return 1;
            } finally {
                if (terminal != null) {
                    try { terminal.close(); } catch (java.io.IOException ignored) {}
                }
            }
            return 0;
        }
    }

    @Command(name = "hand-anim", mixinStandardHelpOptions = true,
             description = "Play hand animation choreography (MicroAppCommand sequence). " +
                     "WARNING: Writes to HAND_ACTIONS handle (0x0600 = same as SETTINGS_BUTTONS). " +
                     "Will overwrite button config! Use 'buttons' command afterward to restore.")
    static class HandAnimCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", defaultValue = "vibrate",
                description = "Animation preset: vibrate, dance, sweep, spin, all")
        String preset;

        @Override
        public Integer call() {
            java.io.ByteArrayOutputStream cmdBuf = new java.io.ByteArrayOutputStream();

            switch (preset.toLowerCase()) {
                case "vibrate" -> {
                    // Default from PlayCrazyShitRequest: vibrate + 1s delay
                    System.out.println("Playing 'vibrate': StartCritical → Vibrate → Delay(1s) → Close");
                    cmdBuf.write(new byte[]{0x03, 0x00}, 0, 2);  // StartCritical
                    cmdBuf.write(new byte[]{(byte) 0x93, 0x00, 0x04}, 0, 3);  // Vibrate NORMAL
                    cmdBuf.write(new byte[]{0x08, 0x01, 0x0A, 0x00}, 0, 4);  // Delay 1s
                    cmdBuf.write(new byte[]{0x01, 0x00}, 0, 2);  // Close
                }
                case "dance" -> {
                    // Hands dance: animate to several positions with vibrations
                    System.out.println("Playing 'dance': Vibrate → Hands to 3:00 → Delay → Hands to 9:00 → Vibrate → Close");
                    cmdBuf.write(new byte[]{0x03, 0x00}, 0, 2);  // StartCritical
                    cmdBuf.write(new byte[]{(byte) 0x93, 0x00, 0x04}, 0, 3);  // Vibrate
                    // AnimationCommand(90, 90) = hands to 3:00
                    // Format: 09 04 01 03 [hourCtrl hourLo hourHi] [minCtrl minLo minHi]
                    // ctrl = (direction<<6) | (absolute<<5) | speed = (2<<6)|(1<<5)|0 = 0xA0
                    // Wait, from the code: (direction.getValue() << 6) | (absoluteMovementFlag << 5) | speed.getValue()
                    // SHORTEST=2, absolute=1, MAX=0 → (2<<6)|(1<<5)|0 = 0x80|0x20 = 0xA0
                    // Actually wait: (2<<6) = 128 = 0x80. 0x80|0x20 = 0xA0. But code uses:
                    // 0x09, 0x04, 0x01, 0x03, ctrl, hourLE, ctrl, minLE
                    // AnimationCommand uses putShort for hour and minute degrees
                    byte ctrl = (byte) ((2 << 6) | (1 << 5) | 0);  // SHORTEST, absolute, MAX speed = 0xA0
                    // hands to 90° (3:00)
                    cmdBuf.write(new byte[]{0x09, 0x04, 0x01, 0x03,
                            ctrl, 90, 0,    // hour hand: 90°
                            ctrl, 90, 0     // minute hand: 90°
                    }, 0, 10);
                    cmdBuf.write(new byte[]{0x08, 0x01, 0x14, 0x00}, 0, 4);  // Delay 2s
                    cmdBuf.write(new byte[]{(byte) 0x93, 0x00, 0x04}, 0, 3);  // Vibrate
                    // hands to 270° (9:00)
                    cmdBuf.write(new byte[]{0x09, 0x04, 0x01, 0x03,
                            ctrl, (byte) (270 & 0xFF), (byte) (270 >> 8),   // hour: 270°
                            ctrl, (byte) (270 & 0xFF), (byte) (270 >> 8)    // minute: 270°
                    }, 0, 10);
                    cmdBuf.write(new byte[]{0x08, 0x01, 0x14, 0x00}, 0, 4);  // Delay 2s
                    cmdBuf.write(new byte[]{(byte) 0x93, 0x00, 0x04}, 0, 3);  // Vibrate
                    cmdBuf.write(new byte[]{0x01, 0x00}, 0, 2);  // Close
                }
                case "sweep" -> {
                    // Sweep: hour and minute hands do a full rotation
                    System.out.println("Playing 'sweep': Hands sweep 12→3→6→9→12");
                    byte ctrl = (byte) ((0 << 6) | (1 << 5) | 0);  // CLOCKWISE, absolute, MAX speed
                    cmdBuf.write(new byte[]{0x03, 0x00}, 0, 2);  // StartCritical
                    for (int deg : new int[]{90, 180, 270, 0}) {
                        cmdBuf.write(new byte[]{0x09, 0x04, 0x01, 0x03,
                                ctrl, (byte)(deg & 0xFF), (byte)(deg >> 8),
                                ctrl, (byte)(deg & 0xFF), (byte)(deg >> 8)
                        }, 0, 10);
                        cmdBuf.write(new byte[]{0x08, 0x01, 0x0A, 0x00}, 0, 4);  // Delay 1s
                    }
                    cmdBuf.write(new byte[]{0x01, 0x00}, 0, 2);  // Close
                }
                case "spin" -> {
                    // Spin: repeat animation 5 times
                    System.out.println("Playing 'spin': Repeat 5x (hands to 6:00 → 12:00)");
                    byte ctrlCw = (byte) ((0 << 6) | (1 << 5) | 2);  // CLOCKWISE, absolute, QUARTER speed
                    cmdBuf.write(new byte[]{0x03, 0x00}, 0, 2);  // StartCritical
                    cmdBuf.write(new byte[]{(byte) 0x86, 0x00, 0x05}, 0, 3);  // RepeatStart(5)
                    cmdBuf.write(new byte[]{0x09, 0x04, 0x01, 0x03,
                            ctrlCw, (byte) 180, 0,   // hour: 180° (6:00)
                            ctrlCw, (byte) 180, 0    // minute: 180° (6:00)
                    }, 0, 10);
                    cmdBuf.write(new byte[]{0x08, 0x01, 0x05, 0x00}, 0, 4);  // Delay 0.5s
                    cmdBuf.write(new byte[]{0x09, 0x04, 0x01, 0x03,
                            ctrlCw, 0, 0,             // hour: 0° (12:00)
                            ctrlCw, 0, 0              // minute: 0° (12:00)
                    }, 0, 10);
                    cmdBuf.write(new byte[]{0x08, 0x01, 0x05, 0x00}, 0, 4);  // Delay 0.5s
                    cmdBuf.write(new byte[]{0x07, 0x00}, 0, 2);  // RepeatStop
                    cmdBuf.write(new byte[]{0x01, 0x00}, 0, 2);  // Close
                }
                case "all" -> {
                    // Full demo: vibrate + stream + animation + repeat
                    System.out.println("Playing 'all': Full demo — vibrate + animation + repeat");
                    byte ctrl = (byte) ((2 << 6) | (1 << 5) | 1);  // SHORTEST, absolute, HALF speed
                    cmdBuf.write(new byte[]{0x03, 0x00}, 0, 2);  // StartCritical
                    cmdBuf.write(new byte[]{(byte) 0x93, 0x00, 0x04}, 0, 3);  // Vibrate
                    cmdBuf.write(new byte[]{(byte) 0x8B, 0x00, (byte) 0xFF}, 0, 3);  // Stream(0xFF)
                    cmdBuf.write(new byte[]{0x09, 0x04, 0x01, 0x03,
                            ctrl, 90, 0,    // hour: 3:00
                            ctrl, (byte) 270, 0  // minute: 9:00
                    }, 0, 10);
                    cmdBuf.write(new byte[]{0x08, 0x01, 0x14, 0x00}, 0, 4);  // Delay 2s
                    cmdBuf.write(new byte[]{0x01, 0x00}, 0, 2);  // Close
                }
                default -> {
                    System.err.println("Unknown preset: " + preset);
                    System.err.println("Available: vibrate, dance, sweep, spin, all");
                    return 1;
                }
            }

            byte[] commands = cmdBuf.toByteArray();
            System.out.printf("Command payload: %d bytes%n", commands.length);
            System.out.println("WARNING: This overwrites button config! Use 'buttons' command to restore.");

            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);
            adapter.playHandAnimation(commands);
            sleep(2000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "buttons", mixinStandardHelpOptions = true,
             description = "Set button functions. Available: forward_to_phone, stopwatch, date, music, " +
                     "volume_up, volume_down, step_goal, last_notification, second_timezone, ring_phone, " +
                     "goal_tracking, alarm_toggle, 24hr, 24hr_seq, mode_toggle")
    static class ButtonsCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Top button function (use + for multi-entry toggle, e.g. second_timezone+date+last_notification)")
        String top;

        @Parameters(index = "1", description = "Middle button function")
        String middle;

        @Parameters(index = "2", description = "Bottom button function")
        String bottom;

        @Override
        public Integer call() {
            // Parse each button spec — may be single function or multi-entry (with +)
            ButtonConfigBuilder.ButtonEntry[] topEntries = parseButtonSpec(top, "top");
            ButtonConfigBuilder.ButtonEntry[] midEntries = parseButtonSpec(middle, "middle");
            ButtonConfigBuilder.ButtonEntry[] botEntries = parseButtonSpec(bottom, "bottom");
            if (topEntries == null || midEntries == null || botEntries == null) return 1;

            boolean needsMultiEntry = topEntries.length > 1 || midEntries.length > 1 || botEntries.length > 1;

            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);

            if (needsMultiEntry) {
                adapter.overwriteButtonsMultiEntry(topEntries, midEntries, botEntries);
            } else {
                // All single-entry — can use vendored ConfigFileBuilder via overwriteButtons,
                // but only if none are STEP_GOAL_PROGRESS (not in ConfigPayload enum).
                // Simplest: always use our builder when it's available.
                adapter.overwriteButtonsMultiEntry(topEntries, midEntries, botEntries);
            }
            System.out.printf("Buttons set: TOP=%s, MIDDLE=%s, BOTTOM=%s%n", top, middle, bottom);
            sleep(2000);
            adapter.shutdown();
            return 0;
        }

        /**
         * Parse a button spec into ButtonEntry[]. Can be:
         *   - "mode_toggle" → expands to [second_timezone, date, step_goal_progress]
         *   - "second_timezone+date+last_notification" → multi-entry toggle
         *   - "stopwatch" → single entry
         */
        private static ButtonConfigBuilder.ButtonEntry[] parseButtonSpec(String spec, String position) {
            String normalized = spec.toLowerCase().replace("-", "_");

            // mode_toggle shorthand
            if (normalized.equals("mode_toggle") || normalized.equals("toggle") || normalized.equals("toggle_mode")) {
                return ButtonConfigBuilder.MODE_TOGGLE_ENTRIES;
            }

            // Multi-entry with + separator
            if (spec.contains("+")) {
                String[] parts = spec.split("\\+");
                ButtonConfigBuilder.ButtonEntry[] entries = new ButtonConfigBuilder.ButtonEntry[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    entries[i] = parseSingleEntry(parts[i].trim(), position + "[" + i + "]");
                    if (entries[i] == null) return null;
                }
                return entries;
            }

            // Single entry
            ButtonConfigBuilder.ButtonEntry e = parseSingleEntry(spec, position);
            if (e == null) return null;
            return new ButtonConfigBuilder.ButtonEntry[]{e};
        }

        static ButtonConfigBuilder.ButtonEntry parseSingleEntry(String name, String position) {
            ConfigPayload p = switch (name.toLowerCase().replace("-", "_")) {
                case "forward", "forward_to_phone", "phone" -> ConfigPayload.FORWARD_TO_PHONE;
                case "stopwatch", "stop_watch" -> ConfigPayload.STOPWATCH;
                case "date", "show_date" -> ConfigPayload.DATE;
                case "music", "music_control" -> ConfigPayload.MUSIC_CONTROL;
                case "volume_up", "vol_up" -> ConfigPayload.VOLUME_UP;
                case "volume_down", "vol_down" -> ConfigPayload.VOLUME_DOWN;
                case "step_goal", "steps", "goal", "step_goal_completion" -> ConfigPayload.STEP_GOAL_COMPLETION;
                case "last_notification", "notification", "notif" -> ConfigPayload.LAST_NOTIFICATION;
                case "second_timezone", "timezone2", "tz2" -> ConfigPayload.SECOND_TIMEZONE;
                case "ring_phone", "ring" -> ConfigPayload.RING_PHONE;
                default -> null;
            };
            if (p != null) return ButtonConfigBuilder.entryFrom(p);

            // Non-enum entries (not in vendored ConfigPayload)
            String norm = name.toLowerCase().replace("-", "_");
            if (norm.equals("alarm_toggle") || norm.equals("alarm_sequenced") || norm.equals("alarm_seq")) {
                return ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY;
            }
            // Legacy alias — "step_goal_progress" was a misidentification of ALARM_SEQUENCED
            if (norm.equals("step_goal_progress") || norm.equals("step_progress")) {
                return ButtonConfigBuilder.ALARM_SEQUENCED_ENTRY;
            }
            if (norm.equals("goal_tracking") || norm.equals("goal_track") || norm.equals("task_tracking") || norm.equals("custom_goal")) {
                return ButtonConfigBuilder.GOAL_TRACKING_ENTRY;
            }
            if (norm.equals("twenty_four_hour") || norm.equals("24hr") || norm.equals("24h") || norm.equals("24_hour")) {
                return ButtonConfigBuilder.TWENTY_FOUR_HOUR_ENTRY;
            }
            if (norm.equals("twenty_four_hour_seq") || norm.equals("24hr_seq") || norm.equals("24h_seq") || norm.equals("24_hour_seq")) {
                return ButtonConfigBuilder.TWENTY_FOUR_HOUR_SEQ_ENTRY;
            }

            System.err.printf("Unknown button function '%s' for %s button.%n", name, position);
            System.err.println("Available: forward_to_phone, stopwatch, date, music, volume_up, " +
                    "volume_down, step_goal, goal_tracking, alarm_toggle, last_notification, " +
                    "second_timezone, ring_phone, 24hr, 24hr_seq, mode_toggle, or combine with + (e.g. second_timezone+date+alarm_toggle)");
            return null;
        }
    }

    @Command(name = "monitor", mixinStandardHelpOptions = true,
             description = "Listen for watch events (button presses, heartbeats, JSON messages). Ctrl+C to stop.")
    static class MonitorCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = "--gesture-window", paramLabel = "<ms>",
                description = "Double-press detection window in ms for FORWARD_TO_PHONE buttons (default: 400)")
        Integer gestureWindowMs;

        @Option(names = "--no-reconnect",
                description = "Exit on disconnect instead of auto-reconnecting")
        boolean noReconnect;

        @Option(names = "--max-reconnect-delay", paramLabel = "<seconds>",
                description = "Maximum reconnect backoff delay in seconds (default: 60)",
                defaultValue = "60")
        int maxReconnectDelay;

        private volatile boolean shuttingDown = false;

        @Override
        public Integer call() {
            String mac = parent.requireMac();

            // Install shutdown hook for graceful cleanup
            Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println("\nShutting down...");
                shuttingDown = true;
                mainThread.interrupt();
            }, "monitor-shutdown"));

            int reconnectAttempts = 0;

            while (!shuttingDown) {
                FossilQAdapter adapter = null;
                try {
                    if (reconnectAttempts > 0) {
                        int delaySec = Math.min((int) Math.pow(2, Math.min(reconnectAttempts, 6)), maxReconnectDelay);
                        System.err.printf("Reconnecting in %ds (attempt %d)...%n", delaySec, reconnectAttempts);
                        Thread.sleep(delaySec * 1000L);
                        if (shuttingDown) break;
                    }

                    adapter = connectAndInit(mac, parent.useSubprocess);
                    reconnectAttempts = 0; // reset on successful connect

                    // Configure gesture detection window if specified
                    if (gestureWindowMs != null) {
                        adapter.setGestureWindowMs(gestureWindowMs);
                    }

                    // Set up NDJSON event output to stdout
                    adapter.setOnEventJson(json -> System.out.println(json));

                    if (reconnectAttempts == 0) {
                        System.err.println("Monitoring watch events... (Ctrl+C to stop)");
                        System.err.println("Events will be printed as JSON lines to stdout.");
                    } else {
                        System.err.println("Reconnected. Resuming event stream.");
                    }

                    // Block until disconnected or interrupted
                    while (adapter.getTransport().isConnected() && !shuttingDown) {
                        Thread.sleep(1000);
                    }

                    if (shuttingDown) {
                        adapter.shutdown();
                        break;
                    }

                    System.err.println("Watch disconnected.");
                    adapter.shutdown();

                    if (noReconnect) {
                        return 0;
                    }

                    reconnectAttempts++;

                } catch (InterruptedException e) {
                    // Expected from shutdown hook
                    Thread.currentThread().interrupt();
                    if (adapter != null) adapter.shutdown();
                    break;
                } catch (Exception e) {
                    System.err.println("Connection error: " + e.getMessage());
                    if (adapter != null) {
                        try { adapter.shutdown(); } catch (Exception ignored) {}
                    }
                    if (noReconnect || shuttingDown) {
                        return 1;
                    }
                    reconnectAttempts++;
                }
            }

            return 0;
        }
    }

    @Command(name = "activity", description = "Fetch and display activity/step data from watch")
    static class ActivityCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = {"-o", "--output"}, description = "Save raw binary to file (e.g. activity.bin)")
        Path outputFile;

        @Option(names = {"--raw"}, description = "Print raw NDJSON (one JSON line per minute-record)")
        boolean raw;

        @Option(names = {"--all"}, description = "Include zero-step records in NDJSON output")
        boolean all;

        @Option(names = {"--keep"}, description = "Don't delete activity data from watch after fetching")
        boolean keep;

        @Option(names = {"--sleep"}, description = "Show only sleep analysis (no step/activity data)")
        boolean sleepOnly;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.requireMac(), parent.useSubprocess);

            var future = new java.util.concurrent.CompletableFuture<byte[]>();
            adapter.setOnActivityData(future::complete);

            adapter.fetchActivity(keep);

            byte[] data;
            try {
                data = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                System.err.println("Timeout waiting for activity data (30s)");
                adapter.shutdown();
                return 1;
            } catch (Exception e) {
                System.err.println("Error fetching activity data: " + e.getMessage());
                adapter.shutdown();
                return 1;
            }

            if (data.length == 0) {
                System.out.println("No activity data on watch.");
                adapter.shutdown();
                return 0;
            }

            // Save raw binary if requested
            if (outputFile != null) {
                try {
                    Files.write(outputFile, data);
                    System.err.println("Raw data saved to " + outputFile + " (" + data.length + " bytes)");
                } catch (IOException e) {
                    System.err.println("Error writing file: " + e.getMessage());
                }
            }

            // Parse and display
            try {
                var activity = ActivityParser.parse(data);
                if (sleepOnly) {
                    // Sleep-only mode
                    var sleepPeriods = ActivityParser.detectSleep(activity);
                    if (raw) {
                        System.out.print(ActivityParser.formatSleepNdjson(sleepPeriods));
                    } else {
                        System.out.println("Sleep analysis (" + activity.records.size() + " activity records):");
                        System.out.print(ActivityParser.formatSleepSummary(sleepPeriods));
                    }
                } else if (raw) {
                    System.out.print(all ? ActivityParser.formatNdjson(activity)
                                         : ActivityParser.formatNdjsonStepsOnly(activity));
                    // Append sleep data as NDJSON too
                    var sleepPeriods = ActivityParser.detectSleep(activity);
                    System.out.print(ActivityParser.formatSleepNdjson(sleepPeriods));
                } else {
                    System.out.print(ActivityParser.formatSummary(activity));
                }
            } catch (Exception e) {
                System.err.println("Parse error: " + e.getMessage());
                // Still save raw data so user can investigate
                if (outputFile == null) {
                    try {
                        Path fallback = Path.of("activity.bin");
                        Files.write(fallback, data);
                        System.err.println("Raw data saved to " + fallback + " for inspection");
                    } catch (IOException ex) { /* ignore */ }
                }
            }

            sleep(1000); // let delete complete if queued
            adapter.shutdown();
            return 0;
        }
    }

    // ========== Config command ==========

    @Command(name = "config", mixinStandardHelpOptions = true,
             description = "Manage CLI configuration (active device, per-watch settings)",
             subcommands = {
                     Main.ConfigShowCmd.class,
                     Main.ConfigSetCmd.class,
                     Main.ConfigSetDeviceCmd.class,
                     Main.ConfigListDevicesCmd.class,
                     Main.ConfigSyncCmd.class,
             })
    static class ConfigCmd implements Runnable {
        @ParentCommand Main parent;

        @Override
        public void run() {
            // Default: show
            new ConfigShowCmd().parent = this;
            try {
                new ConfigShowCmd() {{ parent = ConfigCmd.this; }}.call();
            } catch (Exception e) {
                CommandLine.usage(this, System.out);
            }
        }
    }

    @Command(name = "show", description = "Show current configuration")
    static class ConfigShowCmd implements Callable<Integer> {
        @ParentCommand ConfigCmd parent;

        @Override
        public Integer call() {
            GlobalConfig global = GlobalConfig.load();
            String activeMac = global.getActiveDevice();

            // If --json flag is set on parent
            Main main = parent.parent;
            if (main != null && main.jsonOutput) {
                StringBuilder json = new StringBuilder();
                json.append("{\"activeDevice\":");
                json.append(activeMac != null ? "\"" + activeMac + "\"" : "null");
                if (activeMac != null) {
                    DeviceConfig dc = DeviceConfig.load(activeMac);
                    json.append(",\"device\":{");
                    json.append("\"mac\":\"").append(activeMac).append("\"");
                    if (dc.getName() != null) json.append(",\"name\":\"").append(dc.getName()).append("\"");
                    json.append(",\"stepGoal\":").append(dc.getStepGoal());
                    json.append(",\"vibrationStrength\":").append(dc.getVibrationStrength());
                    json.append(",\"secondTimezone\":").append(dc.getSecondTimezone() != null ? dc.getSecondTimezone() : "null");
                    json.append("}");
                }
                json.append("}");
                System.out.println(json);
                return 0;
            }

            System.out.println("Global config: " + GlobalConfig.configPath());
            System.out.println("Active device: " + (activeMac != null ? activeMac : "(not set)"));

            if (activeMac != null) {
                DeviceConfig dc = DeviceConfig.load(activeMac);
                System.out.println();
                System.out.println(dc);
                System.out.println("  Config dir:          " + dc.deviceDir());

                NotificationConfig nc = NotificationConfig.load(activeMac);
                var types = nc.getTypes();
                System.out.println("  Notification types:  " + types.size());
                for (int i = 0; i < types.size(); i++) {
                    System.out.printf("    [%d] %s%n", i + 1, types.get(i));
                }
            }

            return 0;
        }
    }

    @Command(name = "set", description = "Set a configuration value for the active device")
    static class ConfigSetCmd implements Callable<Integer> {
        @ParentCommand ConfigCmd parent;

        @Parameters(index = "0", description = "Setting name: name, step-goal, vibration-strength, second-timezone")
        String key;

        @Parameters(index = "1", description = "Setting value")
        String value;

        @Override
        public Integer call() {
            String mac = parent.parent.resolvedMac();
            if (mac == null || mac.isBlank()) {
                System.err.println("Error: No active device. Use -d <MAC> or: fossil-q config set-device <MAC>");
                return 1;
            }

            DeviceConfig dc = DeviceConfig.load(mac);
            String normalized = key.toLowerCase().replace("-", "_").replace(" ", "_");
            boolean needsSync = false; // watch-relevant settings need syncing

            try {
                switch (normalized) {
                    case "name" -> {
                        dc.setName(value);
                        System.out.println("Name set to: " + value);
                        // name is local-only, no sync needed
                    }
                    case "step_goal", "stepgoal", "steps" -> {
                        int goal = Integer.parseInt(value);
                        if (goal < 1 || goal > 999999) {
                            System.err.println("Step goal must be between 1 and 999999");
                            return 1;
                        }
                        dc.setStepGoal(goal);
                        needsSync = true;
                        System.out.println("Step goal set to: " + goal);
                    }
                    case "vibration_strength", "vibration", "vibe" -> {
                        int strength = Integer.parseInt(value);
                        if (strength < 0 || strength > 100) {
                            System.err.println("Vibration strength must be 0-100");
                            return 1;
                        }
                        dc.setVibrationStrength(strength);
                        needsSync = true;
                        System.out.println("Vibration strength set to: " + strength + "%");
                    }
                    case "second_timezone", "timezone2", "tz2" -> {
                        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("disable") || value.equalsIgnoreCase("null")) {
                            dc.setSecondTimezone(null);
                            System.out.println("Second timezone disabled");
                        } else {
                            int offset = Integer.parseInt(value);
                            if (offset < -720 || offset > 840) {
                                System.err.println("Offset must be -720 to 840 minutes (or 'off')");
                                return 1;
                            }
                            dc.setSecondTimezone(offset);
                            System.out.printf("Second timezone set to UTC%+.1f (%d min)%n", offset / 60.0, offset);
                        }
                        needsSync = true;
                    }
                    default -> {
                        System.err.println("Unknown setting: " + key);
                        System.err.println("Available: name, step-goal, vibration-strength, second-timezone");
                        return 1;
                    }
                }

                if (needsSync) {
                    dc.setSyncNeeded(true);
                }
                dc.save();
                if (needsSync) {
                    System.out.println("Run 'fossil-q config sync' to push to watch, or it will sync on next connect.");
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid value: " + value);
                return 1;
            } catch (java.io.IOException e) {
                System.err.println("Error saving config: " + e.getMessage());
                return 1;
            }

            return 0;
        }
    }

    @Command(name = "set-device", description = "Set the active (default) watch MAC address")
    static class ConfigSetDeviceCmd implements Callable<Integer> {
        @ParentCommand ConfigCmd parent;

        @Parameters(index = "0", description = "Watch MAC address (e.g. D9:20:71:11:74:2A)")
        String mac;

        @Override
        public Integer call() {
            // Basic MAC validation
            if (!mac.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) {
                System.err.println("Invalid MAC address: " + mac);
                System.err.println("Expected format: AA:BB:CC:DD:EE:FF");
                return 1;
            }

            String normalized = mac.toUpperCase();
            GlobalConfig global = GlobalConfig.load();
            global.setActiveDevice(normalized);
            try {
                global.save();
            } catch (java.io.IOException e) {
                System.err.println("Error saving config: " + e.getMessage());
                return 1;
            }

            // Ensure device directory exists
            try {
                java.nio.file.Files.createDirectories(GlobalConfig.deviceDir(normalized));
            } catch (java.io.IOException e) {
                System.err.println("Warning: could not create device directory: " + e.getMessage());
            }

            System.out.println("Active device set to: " + normalized);
            System.out.println("Config dir: " + GlobalConfig.deviceDir(normalized));
            System.out.println("\nThe -d flag is now optional for this watch.");
            return 0;
        }
    }

    @Command(name = "list-devices", description = "List all known device configurations")
    static class ConfigListDevicesCmd implements Callable<Integer> {
        @ParentCommand ConfigCmd parent;

        @Override
        public Integer call() {
            GlobalConfig global = GlobalConfig.load();
            String activeMac = global.getActiveDevice();

            java.nio.file.Path devicesDir = GlobalConfig.configDir().resolve("devices");
            if (!java.nio.file.Files.exists(devicesDir)) {
                System.out.println("No devices configured.");
                return 0;
            }

            try (var dirs = java.nio.file.Files.list(devicesDir)) {
                var deviceDirs = dirs.filter(java.nio.file.Files::isDirectory).sorted().toList();
                if (deviceDirs.isEmpty()) {
                    System.out.println("No devices configured.");
                    return 0;
                }

                System.out.println("Known devices:");
                for (var dir : deviceDirs) {
                    String folderName = dir.getFileName().toString();
                    String mac = folderName.replace("_", ":");
                    boolean isActive = mac.equalsIgnoreCase(activeMac);
                    DeviceConfig dc = DeviceConfig.load(mac);
                    String label = dc.getName() != null ? dc.getName() : "";
                    System.out.printf("  %s %s%s%n",
                            isActive ? "*" : " ",
                            mac,
                            label.isEmpty() ? "" : " (" + label + ")");
                }
            } catch (java.io.IOException e) {
                System.err.println("Error listing devices: " + e.getMessage());
                return 1;
            }

            return 0;
        }
    }

    @Command(name = "sync", description = "Push local config to the watch (connects to device)")
    static class ConfigSyncCmd implements Callable<Integer> {
        @ParentCommand ConfigCmd parent;

        @Override
        public Integer call() {
            String mac = parent.parent.requireMac();

            // Force full init which syncs config + notification filter
            FossilQAdapter adapter = connectAndInit(mac, parent.parent.useSubprocess, true);

            // Wait for config upload to complete (queued during init)
            sleep(3000);

            // syncNeeded flag is cleared by onFilePut callback in adapter
            System.out.println("Config synced to watch.");
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "scan", description = "Scan for nearby Fossil Q Hybrid watches and add them")
    static class ScanCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = {"--all"}, description = "Show all BLE devices, not just Fossil watches")
        boolean showAll;

        @Option(names = {"-t", "--timeout"}, description = "Scan timeout in seconds", defaultValue = "15")
        int timeout;

        @Override
        public Integer call() {
            System.out.println("Initializing Bluetooth discovery...");
            
            DeviceManager deviceManager;
            BluetoothAdapter adapter;
            try {
                deviceManager = DeviceManager.createInstance(false);
                List<BluetoothAdapter> adapters = deviceManager.scanForBluetoothAdapters();
                if (adapters.isEmpty()) {
                    System.err.println("Error: No Bluetooth adapters found on this system.");
                    return 1;
                }
                adapter = adapters.get(0);
            } catch (Exception e) {
                System.err.println("Error initializing Bluetooth/D-Bus: " + e.getMessage());
                System.err.println("Make sure dbus is running and your user is in the bluetooth group.");
                return 1;
            }

            System.out.println("Scanning for BLE devices... Press a button on your watch to make it advertise.");
            try {
                adapter.startDiscovery();
            } catch (Exception e) {
                System.err.println("Error starting discovery: " + e.getMessage());
                return 1;
            }

            List<BluetoothDevice> foundWatches = new ArrayList<>();
            Set<String> seenMacs = new HashSet<>();
            
            long deadline = System.currentTimeMillis() + (timeout * 1000L);
            long lastPrint = 0;
            
            try {
                while (System.currentTimeMillis() < deadline) {
                    deviceManager.findBtDevicesByIntrospection(adapter);
                    List<BluetoothDevice> devices = deviceManager.getDevices(true);
                    for (BluetoothDevice d : devices) {
                        String mac = d.getAddress();
                        if (mac != null && !seenMacs.contains(mac)) {
                            String name = d.getName();
                            boolean isFossil = name != null && (
                                    name.toLowerCase().contains("fossil") || 
                                    name.toLowerCase().contains("q commuter") || 
                                    name.toLowerCase().contains("q activist") || 
                                    name.toLowerCase().contains("hybrid") || 
                                    name.toLowerCase().contains("commuter") || 
                                    name.toLowerCase().contains("activist")
                            );
                            
                            if (showAll || isFossil) {
                                seenMacs.add(mac);
                                foundWatches.add(d);
                                System.out.printf("  [%d] %s (%s)%n", foundWatches.size(), (name != null ? name : "Unknown"), mac);
                            }
                        }
                    }
                    
                    long remaining = (deadline - System.currentTimeMillis()) / 1000;
                    if (remaining > 0 && remaining != lastPrint && remaining % 3 == 0) {
                        lastPrint = remaining;
                        System.out.printf("Scanning... (%ds remaining)%n", remaining);
                    }
                    
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                try {
                    adapter.stopDiscovery();
                } catch (Exception ignored) {}
            }

            if (foundWatches.isEmpty()) {
                System.out.println("No watches found. Make sure the watch is awake and not paired to another device.");
                return 0;
            }

            System.out.println("\nScan complete.");
            System.out.println("Select a watch to add (or press Enter to cancel):");
            
            Scanner scanner = new Scanner(System.in);
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                System.out.println("Cancelled.");
                return 0;
            }

            int index;
            try {
                index = Integer.parseInt(input);
            } catch (NumberFormatException e) {
                System.err.println("Invalid selection.");
                return 1;
            }

            if (index < 1 || index > foundWatches.size()) {
                System.err.println("Selection out of range.");
                return 1;
            }

            BluetoothDevice selected = foundWatches.get(index - 1);
            String mac = selected.getAddress().toUpperCase();
            String name = selected.getName();
            if (name == null) name = "Fossil Q Hybrid";

            System.out.printf("Adding watch: %s (%s)%n", name, mac);
            
            // Register active device
            GlobalConfig global = GlobalConfig.load();
            global.setActiveDevice(mac);
            try {
                global.save();
                Files.createDirectories(GlobalConfig.deviceDir(mac));
                
                // Save a default friendly name to the device config
                DeviceConfig dc = DeviceConfig.load(mac);
                dc.setName(name);
                dc.save();
            } catch (IOException e) {
                System.err.println("Error saving configuration: " + e.getMessage());
                return 1;
            }

            System.out.println("\nSuccessfully added and set as active device!");
            System.out.println("Config dir: " + GlobalConfig.deviceDir(mac));
            System.out.println("You can now test connection with: fossil-q info");

            return 0;
        }
    }

    // --- Utility ---

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
