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
                Main.SecondTimezoneCmd.class,
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

            FossilQAdapter adapter = connectAndInit(alarmParent.parent.macAddress, alarmParent.parent.useSubprocess);

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

            FossilQAdapter adapter = connectAndInit(alarmParent.parent.macAddress, alarmParent.parent.useSubprocess);

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
            FossilQAdapter adapter = connectAndInit(alarmParent.parent.macAddress, alarmParent.parent.useSubprocess);

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
            FossilQAdapter adapter = connectAndInit(alarmParent.parent.macAddress, alarmParent.parent.useSubprocess);

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

            FossilQAdapter adapter = connectAndInit(alarmParent.parent.macAddress, alarmParent.parent.useSubprocess);

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

            FossilQAdapter adapter = connectAndInit(alarmParent.parent.macAddress, alarmParent.parent.useSubprocess);

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

            FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
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

    @Command(name = "buttons", mixinStandardHelpOptions = true,
             description = "Set button functions. Available: forward_to_phone, stopwatch, date, music, " +
                     "volume_up, volume_down, step_goal, last_notification, second_timezone, ring_phone, mode_toggle")
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
            boolean hasModeToggle = isModeToggle(top) || isModeToggle(middle) || isModeToggle(bottom);

            if (hasModeToggle) {
                // Mode toggle uses multi-entry button config — bypass ConfigFileBuilder.
                // Parse non-toggle buttons normally (they must be single-entry).
                ConfigPayload topPayload = isModeToggle(top) ? null : parsePayload(top, "top");
                ConfigPayload midPayload = isModeToggle(middle) ? null : parsePayload(middle, "middle");
                ConfigPayload botPayload = isModeToggle(bottom) ? null : parsePayload(bottom, "bottom");
                // Check for parse errors on non-toggle buttons
                if (!isModeToggle(top) && topPayload == null) return 1;
                if (!isModeToggle(middle) && midPayload == null) return 1;
                if (!isModeToggle(bottom) && botPayload == null) return 1;

                FossilQAdapter adapter = connectAndInit(parent.macAddress, parent.useSubprocess);
                adapter.overwriteButtonsWithModeToggle(
                        isModeToggle(top), isModeToggle(middle), isModeToggle(bottom),
                        topPayload, midPayload, botPayload);
                System.out.printf("Buttons set: TOP=%s, MIDDLE=%s, BOTTOM=%s%n", top, middle, bottom);
                sleep(2000);
                adapter.shutdown();
                return 0;
            }

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

        private static boolean isModeToggle(String name) {
            String n = name.toLowerCase().replace("-", "_");
            return n.equals("mode_toggle") || n.equals("toggle") || n.equals("toggle_mode");
        }

        static ConfigPayload parsePayload(String name, String position) {
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
                            "volume_down, step_goal, last_notification, second_timezone, ring_phone, mode_toggle");
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
