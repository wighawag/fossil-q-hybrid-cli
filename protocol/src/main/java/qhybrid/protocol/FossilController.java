// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol;

import qhybrid.protocol.BleTransport;
import qhybrid.protocol.FossilQAdapter;
import qhybrid.protocol.model.NotificationFilterEntry;
import qhybrid.protocol.model.SyncSettings;

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

    public void playNotification(String packageName) {
        adapter.playNotificationByPackageName(packageName);
    }

    /** Upload a prebuilt button-config file (SETTINGS_BUTTONS, 0x0600). */
    public void setButtons(byte[] buttonConfigFile) {
        adapter.setButtonsRaw(buttonConfigFile);
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
