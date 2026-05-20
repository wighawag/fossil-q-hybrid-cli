package qhybrid.linux;

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

    @Command(name = "activity", description = "Fetch activity data from watch (Fossil protocol only)")
    static class ActivityCmd implements Callable<Integer> {
        @ParentCommand Main parent;

        @Option(names = {"-o", "--output"}, description = "Output file for raw activity data", defaultValue = "activity.bin")
        Path outputFile;

        @Override
        public Integer call() {
            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);

            adapter.setOnActivityData(data -> {
                try {
                    Files.write(outputFile, data);
                    System.out.println("Activity data saved to " + outputFile + " (" + data.length + " bytes)");
                } catch (IOException e) {
                    System.err.println("Error writing activity data: " + e.getMessage());
                }
            });

            adapter.fetchActivity();
            System.out.println("Fetching activity data...");
            sleep(10_000); // Wait for file transfer to complete
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
