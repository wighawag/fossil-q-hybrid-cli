// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Settings the caller supplies for a full init "sync" pass, replacing the
 * adapter's former direct disk-loading of {@code DeviceConfig}/{@code NotificationConfig}.
 *
 * <p>The protocol layer no longer reads {@code ~/.config/fossil-q/...}; the CLI
 * (or Android) loads its own settings and passes the values in here. All fields
 * are optional — a null leaves that aspect to the adapter's existing default.
 */
public final class SyncSettings {
    /** Daily step goal (config 0x03). Null = don't set. */
    public Integer stepGoal;
    /** Vibration strength 0-100 (config 0x0A). Null = don't set. */
    public Integer vibrationStrength;
    /** Second-timezone offset minutes (config 0x11), or null to skip. */
    public Integer secondTimezone;

    /** Notification filter entries to upload (file 0x0C00). Empty = skip filter upload. */
    public final List<NotificationFilterEntry> notificationFilter = new ArrayList<>();

    public SyncSettings stepGoal(Integer v) { this.stepGoal = v; return this; }
    public SyncSettings vibrationStrength(Integer v) { this.vibrationStrength = v; return this; }
    public SyncSettings secondTimezone(Integer v) { this.secondTimezone = v; return this; }

    public SyncSettings addFilterEntry(NotificationFilterEntry e) {
        this.notificationFilter.add(e);
        return this;
    }

    public List<NotificationFilterEntry> notificationFilter() {
        return Collections.unmodifiableList(notificationFilter);
    }
}
