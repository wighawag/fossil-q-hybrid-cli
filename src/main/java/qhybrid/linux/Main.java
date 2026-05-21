package qhybrid.linux;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.buttonconfig.ConfigPayload;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.alarm.Alarm;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit.PlayNotificationRequest.VibrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "fossil-q",
        description = "Fossil Q Hybrid CLI for Linux (coin-cell watches: Q Commuter, Q Activist)",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        subcommands = {
                Main.InfoCmd.class,
                Main.TimeCmd.class,
                Main.NotifyCmd.class,
                Main.FindCmd.class,
                Main.HandsCmd.class,
                Main.CalibrateCmd.class,
                Main.StepGoalCmd.class,
                Main.VibrationCmd.class,
                Main.TimezoneCmd.class,
                Main.AlarmCmd.class,
                Main.ActivityCmd.class,
                Main.PairCmd.class,
                Main.MonitorCmd.class,
                Main.ButtonsCmd.class,
        })
public class Main implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger("fossil-q");

    @Option(names = {"-d", "--device"}, description = "Watch MAC address (e.g. AA:BB:CC:DD:EE:FF)", scope = ScopeType.INHERIT)
    String macAddress;

    @Option(names = {"--subprocess"}, description = "Use subprocess-based BLE transport (bluetoothctl/busctl/gdbus) instead of dbus-java", scope = ScopeType.INHERIT)
    boolean useSubprocess;

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    // --- Shared connection logic ---

    static FossilQAdapter connectAndInit(String mac, boolean useSubprocess) {
        if (mac == null || mac.isBlank()) {
            System.err.println("Error: --device <MAC> is required");
            System.exit(1);
        }

        BleTransport transport;
        if (useSubprocess) {
            LOG.info("Using subprocess transport (bluetoothctl/busctl/gdbus)");
            transport = new BluezTransport();
        } else {
            LOG.info("Using dbus-java transport (direct D-Bus)");
            transport = new DbusTransport();
        }
        if (!transport.connect(mac)) {
            System.err.println("Error: Failed to connect to " + mac);
            System.exit(1);
        }

        FossilQAdapter adapter = new FossilQAdapter(transport);
        adapter.initialize();

        // For Fossil protocol, wait for the async init to complete
        if (adapter.isFossilProtocol()) {
            // 60s timeout: allows 30s for auth button press + init overhead
            if (!adapter.waitForInit(60_000)) {
                LOG.warn("Initialization may not have completed fully");
            }
        }

        return adapter;
    }

    // ========== Subcommands ==========

    @Command(name = "info", description = "Show device info (model, firmware, battery)")
    static class InfoCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            if (parent.macAddress == null || parent.macAddress.isBlank()) {
                System.err.println("Error: --device <MAC> is required");
                return 1;
            }

            BleTransport transport;
            if (parent.useSubprocess) {
                transport = new BluezTransport();
            } else {
                transport = new DbusTransport();
            }
            if (!transport.connect(parent.macAddress)) {
                System.err.println("Failed to connect");
                return 1;
            }

            FossilQAdapter adapter = new FossilQAdapter(transport);
            adapter.initialize();

            // Wait for the async init to process responses
            if (adapter.isFossilProtocol()) {
                adapter.waitForInit(30_000);
            }
            // Extra delay for any in-flight file operations to complete
            sleep(3000);

            System.out.println("Model:    " + adapter.getModelNumber());
            System.out.println("Firmware: " + adapter.getFirmwareVersion());
            System.out.println("Battery:  " + adapter.getBatteryLevel() + "%");
            System.out.println("Protocol: " + (adapter.isFossilProtocol() ? "Fossil (2.x)" : "Misfit (0.x/1.x)"));

            adapter.shutdown();
            try { ((AutoCloseable) transport).close(); } catch (Exception e) { /* ignore */ }
            return 0;
        }
    }

    @Command(name = "time", description = "Sync current time to watch")
    static class TimeCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
            adapter.syncTime();
            System.out.println("Time synced");
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "notify", description = "Send a notification (vibration + hand movement)")
    static class NotifyCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Vibration type: ${COMPLETION-CANDIDATES}",
                defaultValue = "SINGLE_SHORT")
        VibrationType vibration;

        @Option(names = {"--direct"}, description = "Use misfit-style direct characteristic write instead of Fossil file protocol")
        boolean direct;

        @Option(names = {"-H", "--hour"}, description = "Hour hand degrees (0-360, only with --direct)", defaultValue = "-1")
        int hourDeg;

        @Option(names = {"-M", "--minute"}, description = "Minute hand degrees (0-360, only with --direct)", defaultValue = "-1")
        int minDeg;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
            // Wait for init animation and notification filter upload to complete
            sleep(5000);
            if (direct) {
                adapter.playMisfitNotification(vibration, hourDeg, minDeg);
                System.out.println("Direct notification sent: " + vibration);
            } else {
                adapter.playNotification(vibration, hourDeg, minDeg);
                System.out.println("Notification sent: " + vibration);
            }
            // Give time for the vibration to happen
            sleep(2000);
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
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
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
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
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

    @Command(name = "calibrate", description = "Save current hand positions as calibration reference")
    static class CalibrateCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
            adapter.saveCalibration();
            System.out.println("Calibration saved");
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "step-goal", description = "Set daily step goal")
    static class StepGoalCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Step goal (e.g. 10000)")
        int steps;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
            adapter.setStepGoal(steps);
            System.out.println("Step goal set to " + steps);
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
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
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
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
            adapter.setTimezoneOffset(offsetMinutes);
            System.out.printf("Timezone offset set to %d minutes (%+.1f hours)%n",
                    offsetMinutes, offsetMinutes / 60.0);
            sleep(1000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "alarm", description = "Set alarm (Fossil protocol only)")
    static class AlarmCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Time in HH:MM format")
        String time;

        @Option(names = {"--days"}, description = "Repeat days bitmask (Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64). 0 = one-shot.", defaultValue = "0")
        int days;

        @Option(names = {"--label"}, description = "Alarm label", defaultValue = "Alarm")
        String label;

        @Override
        public Integer call() {
            String[] parts = time.split(":");
            if (parts.length != 2) {
                System.err.println("Invalid time format. Use HH:MM");
                return 1;
            }
            byte hour = Byte.parseByte(parts[0]);
            byte minute = Byte.parseByte(parts[1]);

            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);

            Alarm alarm;
            if (days > 0) {
                alarm = new Alarm(minute, hour, (byte) days, label, "");
            } else {
                alarm = new Alarm(minute, hour, label, "");
            }
            adapter.setAlarms(new Alarm[]{alarm});
            System.out.printf("Alarm set for %02d:%02d%s%n", hour, minute,
                    days > 0 ? " (repeating, days=" + days + ")" : " (one-shot)");
            sleep(2000);
            adapter.shutdown();
            return 0;
        }
    }

    @Command(name = "pair", description = "Trigger BLE pairing (creates bond, ties auth to link key)")
    static class PairCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
            System.out.println("Initiating BLE pairing...");
            boolean ok = adapter.getTransport().pair();
            if (ok) {
                System.out.println("Pairing successful — auth state is now tied to BLE bond");
                System.out.println("To clear auth: bluetoothctl remove " + parent.macAddress);
            } else {
                System.out.println("Pairing failed or already paired");
            }
            adapter.shutdown();
            return ok ? 0 : 1;
        }
    }

    @Command(name = "buttons", mixinStandardHelpOptions = true,
             description = "Set button functions. Available: forward_to_phone, stopwatch, date, music, " +
                     "volume_up, volume_down, step_goal, last_notification, second_timezone, ring_phone")
    static class ButtonsCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Parameters(index = "0", description = "Top button function")
        String top;

        @Parameters(index = "1", description = "Middle button function")
        String middle;

        @Parameters(index = "2", description = "Bottom button function")
        String bottom;

        @Override
        public Integer call() {
            ConfigPayload topPayload = parsePayload(top, "top");
            ConfigPayload middlePayload = parsePayload(middle, "middle");
            ConfigPayload bottomPayload = parsePayload(bottom, "bottom");
            if (topPayload == null || middlePayload == null || bottomPayload == null) return 1;

            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
            adapter.overwriteButtons(new ConfigPayload[]{topPayload, middlePayload, bottomPayload});
            System.out.printf("Buttons set: TOP=%s, MIDDLE=%s, BOTTOM=%s%n", top, middle, bottom);
            sleep(2000);
            adapter.shutdown();
            return 0;
        }

        private ConfigPayload parsePayload(String name, String position) {
            return switch (name.toLowerCase().replace("-", "_")) {
                case "forward", "forward_to_phone", "phone" -> ConfigPayload.FORWARD_TO_PHONE;
                case "stopwatch", "stop_watch" -> ConfigPayload.STOPWATCH;
                case "date", "show_date" -> ConfigPayload.DATE;
                case "music", "music_control" -> ConfigPayload.MUSIC_CONTROL;
                case "volume_up", "vol_up" -> ConfigPayload.VOLUME_UP;
                case "volume_down", "vol_down" -> ConfigPayload.VOLUME_DOWN;
                case "step_goal", "steps", "goal" -> ConfigPayload.STEP_GOAL_COMPLETION;
                case "last_notification", "notification", "notif" -> ConfigPayload.LAST_NOTIFICATION;
                case "second_timezone", "timezone2", "tz2" -> ConfigPayload.SECOND_TIMEZONE;
                case "ring_phone", "ring" -> ConfigPayload.RING_PHONE;
                default -> {
                    System.err.printf("Unknown button function '%s' for %s button.%n", name, position);
                    System.err.println("Available: forward_to_phone, stopwatch, date, music, volume_up, " +
                            "volume_down, step_goal, last_notification, second_timezone, ring_phone");
                    yield null;
                }
            };
        }
    }

    @Command(name = "monitor", mixinStandardHelpOptions = true,
             description = "Listen for watch events (button presses, heartbeats, JSON messages). Ctrl+C to stop.")
    static class MonitorCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);

            // Set up NDJSON event output to stdout
            adapter.setOnEventJson(json -> System.out.println(json));

            System.err.println("Monitoring watch events... (Ctrl+C to stop)");
            System.err.println("Events will be printed as JSON lines to stdout.");

            // Install shutdown hook for graceful cleanup
            Thread mainThread = Thread.currentThread();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.err.println("\nShutting down...");
                adapter.shutdown();
                mainThread.interrupt();
            }, "monitor-shutdown"));

            // Block until interrupted (Ctrl+C triggers shutdown hook)
            try {
                while (adapter.getTransport().isConnected()) {
                    Thread.sleep(1000);
                }
                System.err.println("Watch disconnected.");
            } catch (InterruptedException e) {
                // Expected from shutdown hook
                Thread.currentThread().interrupt();
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

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);

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
                if (raw) {
                    System.out.print(all ? ActivityParser.formatNdjson(activity)
                                         : ActivityParser.formatNdjsonStepsOnly(activity));
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

    // --- Utility ---

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
