// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import qhybrid.protocol.BleTransport;
import qhybrid.protocol.FossilQAdapter;
import qhybrid.protocol.model.NotificationFilterEntry;
import qhybrid.protocol.model.SyncSettings;
import qhybrid.protocol.activity.ActivitySummarizer;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/**
 * Platform-agnostic façade over {@link FossilQAdapter} (WP1 deliverable).
 *
 * <p>Both the CLI and Android drive the protocol through this single entry point.
 * Unlike the raw adapter, the controller takes <em>settings passed in</em> (via
 * {@link SyncSettings}) rather than reading {@code ~/.config/fossil-q} from disk —
 * the caller owns persistence.
 *
 * <p>This is a thin wrapper: it adds NO new wire behavior, it only exposes and
 * makes testable what the adapter already does, behind a stable surface.
 */
public class FossilController {

    private final BleTransport transport;
    private final FossilQAdapter adapter;

    public FossilController(BleTransport transport) {
        this.transport = transport;
        this.adapter = new FossilQAdapter(transport);
    }

    /** Direct access to the underlying adapter (for advanced/legacy CLI paths). */
    public FossilQAdapter adapter() {
        return adapter;
    }

    public BleTransport transport() {
        return transport;
    }

    // ===== Connection / init =====

    /** Connect the transport to the given MAC. */
    public boolean connect(String mac) {
        return transport.connect(mac);
    }

    public void disconnect() {
        transport.disconnect();
    }

    public boolean isConnected() {
        return transport.isConnected();
    }

    /**
     * Initialise the watch. With {@code fullInit=true} this runs the animation,
     * config sync, and notification-filter upload using the supplied
     * {@link #setSyncSettings settings}; with {@code false} it does the minimal
     * (auth-only) path.
     */
    public void init(boolean fullInit) {
        adapter.initialize(fullInit);
    }

    /** Block until init completes (or times out). */
    public boolean waitForInit(long timeoutMs) {
        return adapter.waitForInit(timeoutMs);
    }

    /** Supply the settings used during full init (no disk loading in :protocol). */
    public void setSyncSettings(SyncSettings settings) {
        adapter.setSyncSettings(settings);
    }

    // ===== Operations =====

    public void syncTime() {
        adapter.syncTime();
    }

    /** Upload raw alarm file bytes (3 bytes/alarm). */
    public void setAlarms(byte[] rawAlarmData, java.util.concurrent.CompletableFuture<Boolean> result) {
        adapter.setAlarmsRaw(rawAlarmData, result);
    }

    /** Upload a prebuilt notification-filter file (one 32-byte entry per type). */
    public void uploadNotificationFilter(List<NotificationFilterEntry> entries) {
        adapter.uploadNotificationFilterEntries(entries);
    }

    /** Build the notification-filter file bytes without uploading (for callers wanting raw bytes). */
    public static byte[] buildNotificationFilterFile(List<NotificationFilterEntry> entries) {
        return FossilQAdapter.buildNotificationFilterFile(entries);
    }

    /**
     * Build the official-format NOTIFICATION_PLAY file bytes (pure, deterministic).
     * Time and messageId are injected so the bytes are reproducible; the live
     * play path injects {@code System.currentTimeMillis()} internally.
     */
    public static byte[] buildPlayFile(String packageName, String title, String sender,
                                       String message, int nowEpochSeconds, int messageId) {
        return qhybrid.protocol.requests.fossil.notification.NotificationCompiler
                .buildPlayFile(packageName, title, sender, message, nowEpochSeconds, messageId);
    }

    public void playNotification(String packageName) {
        adapter.playNotificationByPackageName(packageName);
    }

    /**
     * Make the watch vibrate NOW with the given vibration pattern (a one-shot "buzz").
     *
     * <p>Thin passthrough to {@link FossilQAdapter#playNotificationWithPattern(byte)} — it uploads
     * a notification filter carrying the requested pattern, then writes a NOTIFICATION_PLAY file,
     * exactly like the CLI {@code notify}/{@code notify-test} commands. The hands stay at the
     * neutral 90deg/90deg position (the 1-arg adapter overload's default).
     *
     * <p>Pattern bytes (hardware-tested — see {@code cli/.../Main.java}):
     * {@code 1 = CALL (triple buzz)}, {@code 5 = ONE_SHORT_VIBE (strong single buzz)}.
     * Invents NO new wire bytes — reuses the golden NOTIFICATION_FILTER + NOTIFICATION_PLAY path.
     *
     * @param vibePattern vibration pattern byte (0-9)
     */
    public void buzz(int vibePattern) {
        adapter.playNotificationWithPattern((byte) vibePattern);
    }

    /**
     * Make the watch vibrate NOW with the given vibration pattern AND move the hands to the given
     * position. Thin passthrough to
     * {@link FossilQAdapter#playNotificationWithPattern(byte, short, short)} (no new wire bytes).
     *
     * @param vibePattern vibration pattern byte (0-9)
     * @param hourDeg     hour hand position in degrees (0-359)
     * @param minDeg      minute hand position in degrees (0-359)
     */
    public void buzz(int vibePattern, int hourDeg, int minDeg) {
        adapter.playNotificationWithPattern((byte) vibePattern, (short) hourDeg, (short) minDeg);
    }

    /**
     * WP-BUZZ-PLAYONLY: make the watch vibrate NOW with the given pattern using a SINGLE play-file
     * put — NO filter upload. This relies on the reserved buzz filter entries
     * ({@link qhybrid.protocol.requests.fossil.notification.BuzzPatterns}) already being on the watch
     * (uploaded once at connect via {@link #uploadReservedBuzzFilter()} or folded into the
     * notification-sync filter). A buzz is then a single {@code NOTIFICATION_PLAY} put for the
     * reserved {@code qhybrid.linux.buzzN} package, which the watch matches by CRC to pick the
     * pattern. Halves the per-buzz BLE work vs {@link #buzz(int)} (which does filter+play).
     *
     * <p>Invents NO new wire bytes — reuses {@link FossilQAdapter#playNotificationByPackageName}.
     *
     * @param vibePattern a reserved pattern (see {@code BuzzPatterns.RESERVED_PATTERNS})
     */
    public void buzzPlayOnly(int vibePattern) {
        adapter.playNotificationByPackageName(
                qhybrid.protocol.requests.fossil.notification.BuzzPatterns
                        .packageNameForPattern(vibePattern));
    }

    /**
     * Upload the reserved buzz filter ({@link qhybrid.protocol.requests.fossil.notification.BuzzPatterns#reservedEntries()})
     * to the watch as a single NOTIFICATION_FILTER put. Call ONCE at connect so {@link #buzzPlayOnly}
     * can match the reserved package CRCs. NOTE: the filter is whole-file — a later notification sync
     * that uploads its own filter will REPLACE this; fold {@code BuzzPatterns.reservedEntries()} into
     * that sync's entries to keep the reserved entries present.
     */
    public void uploadReservedBuzzFilter() {
        adapter.uploadNotificationFilterEntries(
                qhybrid.protocol.requests.fossil.notification.BuzzPatterns.reservedEntries());
    }

    /** Upload a prebuilt button-config file (SETTINGS_BUTTONS, 0x0600). */
    public void setButtons(byte[] buttonConfigFile) {
        adapter.setButtonsRaw(buttonConfigFile);
    }

    /**
     * Upload a prebuilt button-config file (SETTINGS_BUTTONS, 0x0600), completing [result] when the
     * watch acknowledges the file-put. Lets the caller WAIT for the write (holding the BLE link
     * open until it completes) instead of fire-and-forget. Same bytes/path as the no-future form.
     */
    public void setButtons(byte[] buttonConfigFile, java.util.concurrent.CompletableFuture<Boolean> result) {
        adapter.setButtonsRaw(buttonConfigFile, result);
    }

    /**
     * Build a multi-entry button-config file (mode-toggle capable) without uploading
     * (pure, deterministic). Each button takes an array of
     * {@link ButtonConfigBuilder.ButtonEntry}; payloads are NOT deduplicated and a CRC32
     * trailer is appended. Single source of truth = {@code ButtonCompiler} (WP7).
     */
    public static byte[] compileButtons(ButtonConfigBuilder.ButtonEntry[] top,
                                        ButtonConfigBuilder.ButtonEntry[] mid,
                                        ButtonConfigBuilder.ButtonEntry[] bot) {
        return qhybrid.protocol.requests.fossil.button.ButtonCompiler
                .compileMultiEntry(top, mid, bot);
    }

    /**
     * Build a single-entry-per-button config file (vendored format: dedup payloads,
     * customization count 0) without uploading. Single source of truth = {@code ButtonCompiler}.
     */
    public static byte[] compileButtonsSingleEntry(
            qhybrid.protocol.buttonconfig.ConfigPayload[] payloads, boolean appendChecksum) {
        return qhybrid.protocol.requests.fossil.button.ButtonCompiler
                .compileSingleEntryPerButton(payloads, appendChecksum);
    }

    /**
     * Summarize parsed activity data into per-local-day step/calorie totals (WP8).
     * Pure aggregation over {@link ActivityParser} output; single source of truth =
     * {@code ActivitySummarizer}. The day-bucketing zone is injected (no system clock).
     */
    public static List<ActivitySummarizer.DayActivity> summarizeActivityByDay(
            ActivityParser.ActivityData data, ZoneId zone) {
        return ActivitySummarizer.summarizeByDay(data, zone);
    }

    /**
     * Detect sleep sessions from parsed activity data (WP8). Delegates to
     * {@link ActivityParser#detectSleep} via {@code ActivitySummarizer} — no
     * detection math re-implemented.
     */
    public static List<ActivitySummarizer.SleepSession> detectSleepSessions(
            ActivityParser.ActivityData data) {
        return ActivitySummarizer.detectSleepSessions(data);
    }

    public void setInactivityNudge(int fromHour, int fromMinute, int toHour, int toMinute,
                                   int inactiveMinutes, boolean enabled) {
        adapter.setInactivityNudge(fromHour, fromMinute, toHour, toMinute, inactiveMinutes, enabled);
    }

    public void setVibrationStrength(short strength) {
        adapter.setVibrationStrength(strength);
    }

    public void setStepGoal(int steps) {
        adapter.setStepGoal(steps);
    }

    public void setSecondTimezone(short offsetMinutes) {
        adapter.setSecondTimezone(offsetMinutes);
    }

    public void requestActivity() {
        adapter.fetchActivity();
    }

    public void requestActivity(boolean keep) {
        adapter.fetchActivity(keep);
    }

    // ===== Device info =====

    public String getFirmwareVersion() { return adapter.getFirmwareVersion(); }
    public String getModelNumber() { return adapter.getModelNumber(); }
    public int getBatteryLevel() { return adapter.getBatteryLevel(); }
    public boolean isFossilProtocol() { return adapter.isFossilProtocol(); }

    // ===== Callbacks =====

    public void onActivityData(Consumer<byte[]> cb) { adapter.setOnActivityData(cb); }
    public void onEventJson(Consumer<String> cb) { adapter.setOnEventJson(cb); }
    /** Fired only when the watch actively asks for authorization (vibrates). */
    public void onAuthRequired(Runnable cb) { adapter.setOnAuthRequired(cb); }
    /** Fired after a successful full-init config sync. */
    public void onConfigSynced(Runnable cb) { adapter.setOnConfigSynced(cb); }

    public void shutdown() {
        adapter.shutdown();
    }
}
