# Fossil Q Hybrid CLI — TODO

## Status: Working on Real Hardware ✅

Watch: Fossil Q Commuter (HW.0.0), Firmware HW0.0.2.9r.v3, Fossil protocol (2.x)

---

## Confirmed Working

- [x] Connect / disconnect (BlueZ D-Bus + persistent bluetoothctl)
- [x] Read device info (model, firmware, battery)
- [x] Protocol detection (Fossil 2.x)
- [x] Full Fossil init sequence (device info, config, button settings)
- [x] Time sync (UTC epoch + TimezoneOffsetConfigItem)
- [x] Hand positioning (absolute degrees)
- [x] Hand calibration (save reference point)
- [x] Find device (vibrate via call characteristic)
- [x] Notification monitoring (gdbus PropertiesChanged)
- [x] File upload protocol (ConfigurationPutRequest, FilePutRequest)
- [x] File download protocol (GetDeviceInfoRequest)

---

## Test Existing Commands

- [ ] `notify` — send notification with vibration + hand movement
- [ ] `step-goal` — set daily step goal (ConfigurationPutRequest item 0x0003)
- [ ] `vibration` — set vibration strength (ConfigurationPutRequest item 0x000A)
- [ ] `timezone` — set timezone offset standalone
- [ ] `alarm` — set alarm (file handle 0x0A00, **must use version 2** per Python findings)
- [ ] `activity` — fetch activity data (file handle 0x0100)

---

## Features to Explore & Implement

### Notifications
- [ ] Test `PlayTextNotificationRequest` — does it vibrate + move hands?
- [ ] Test different `VibrationType` values (SINGLE_SHORT, DOUBLE_NORMAL, etc.)
- [ ] Notification with custom hand animation (hour/minute degree params)
- [ ] Notification filter configuration (which "apps" trigger vibrations)
- [ ] `NotificationFilterPutRequest` — upload notification settings file

### Activity & Step Tracking
- [ ] Fetch activity data file (FileLookupAndGetRequest on 0x0100)
- [ ] Parse activity data format (GB's `ActivityFileParser` not vendored — write our own)
- [ ] Display step count, activity points
- [ ] Delete activity data after fetch (FileDeleteRequest)
- [ ] Read current step count (ConfigurationGetRequest item 0x0002)
- [ ] Set current step count (for activity hand position)
- [ ] Activity hand as notification counter mode

### Alarms
- [ ] Test alarm set (file handle 0x0A00, version 2)
- [ ] Multiple alarms in single upload
- [ ] Repeating alarms (day bitmask — note: Thu/Wed swapped per Python findings)
- [ ] Alarm list/get (AlarmsGetRequest — if watch supports reading back)
- [ ] Alarm clear (upload empty alarm file or FileDeleteRequest)

### Button Configuration
- [ ] Read current button config
- [ ] Set button actions (FORWARD_TO_PHONE, RING_PHONE, MUSIC_CONTROL, etc.)
- [ ] Listen for button press events on 3dda0006
- [ ] Map button presses to custom actions (run command, send notification, etc.)
- [ ] Multi-button press detection (SINGLE, DOUBLE, LONG)

### Watch Events & Monitoring
- [ ] `monitor` command — long-running listener for button presses, heartbeats
- [ ] Parse JSON messages on 3dda0006 (buddyChallengeApp sync, etc.)
- [ ] Heartbeat events (type 0x02 on 3dda0006)
- [ ] Button press events (type 0x08 on 3dda0006)
- [ ] Multi-button actions (type 0x05 on 3dda0006)
- [ ] Connection state monitoring (auto-reconnect)

### Vibration Patterns
- [ ] Test all vibration types via misfit PlayNotificationRequest
- [ ] Extended vibration support (model HW.0.0 supports it per GB code)
- [ ] Custom vibration patterns via call characteristic (3dda0005)
- [ ] VibrateRequest (misfit command — different from PlayNotificationRequest)

### Hand Animations
- [ ] AnimationRequest variations (pairing animation)
- [ ] Hand action files (FileHandle.HAND_ACTIONS) — complex hand choreography
- [ ] RLEEncoder usage for animation data
- [ ] Activity hand (sub-eye) as progress indicator

### Configuration
- [ ] Read all configuration (ConfigurationGetRequest)
- [ ] Inactivity warning (config item 0x0009)
- [ ] Units config — metric/imperial (config item 0x0010)
- [ ] Fitness/workout recognition settings (config item 0x0014)
- [ ] Battery read via config (config item 0x000D — voltage + percent)

### Interactive Calibration
- [ ] Implement interactive calibration flow (see CALIBRATION-PLAN.md)
- [ ] Add JLine dependency for single-keystroke input
- [ ] Per-hand nudge with +/- keys
- [ ] Save calibration + auto time sync

---

## Infrastructure Improvements

### Connection Management
- [ ] Auto-reconnect on disconnect
- [ ] Connection persistence (don't re-init on every command)
- [ ] Daemon mode — stay connected, accept commands via IPC/socket
- [ ] Handle device not found / not paired gracefully
- [ ] Support `bluetoothctl pair` from within the CLI (first-time setup wizard)

### Configuration Persistence
- [ ] JSON config file (~/.config/fossil-q/config.json)
- [ ] Store MAC address (no need to pass -d every time)
- [ ] Store step goal, vibration strength, timezone preferences
- [ ] Store button configuration
- [ ] Store alarm definitions

### CLI Polish
- [ ] Shell completion (picocli generates bash/zsh/fish completions)
- [ ] Reduce init time (skip full config sync for read-only commands like `info`)
- [ ] Quiet mode (suppress log lines, only show results)
- [ ] JSON output mode (--json flag for scripting)
- [ ] Colored output

### Robustness
- [ ] Timeout on all BLE operations (handle watch going out of range)
- [ ] Graceful shutdown (Ctrl+C handler, release hands, stop notify)
- [ ] Retry logic for transient BLE errors
- [ ] Validate alarm data before sending (hour 0-23, minute 0-59, etc.)
- [ ] File transfer error handling (CRC mismatch, missed packets)

### Packaging
- [ ] Native binary via GraalVM native-image (no JVM startup cost)
- [ ] AUR package
- [ ] Flatpak / AppImage
- [ ] Man page
- [ ] Systemd service file (for daemon mode)

---

## Research / Exploration

- [ ] What do the JSON messages on 3dda0006 mean? (buddyChallengeApp, etc.)
- [ ] Can we read the current hand positions from the watch?
- [ ] What are the 3 vendor-specific UUIDs GB enables notifications on?
  - `010541ae-efe8-11c0-91c0-105d1a1155f0`
  - `fef9589f-9c21-4d19-9fc0-105d1a1155f0`
  - `842d2791-0d20-4ce4-1ada-105d1a1155f0`
- [ ] HID service (0x1812) — what reports does the watch send?
- [ ] Does the watch support ConfigurationGetRequest? (read back config)
- [ ] MicroApp commands (PlayCrazyShitRequest — what does it do?)
- [ ] Connection parameters tuning (SetConnectionParametersRequest)
- [ ] What happens if we send Fossil HR-style commands to this watch?
- [ ] Can we use the file system for custom data storage?
- [ ] Watch behavior with multiple alarms (max alarm count?)
- [ ] Does the watch respond differently after factory reset vs re-pair?

---

## File Handles Reference (from vendored FileHandle.java)

| Handle | Name | Purpose |
|--------|------|---------|
| 0x0100 | ACTIVITY_FILE | Activity/step data |
| 0x0500 | NOTIFICATION_PLAY | Play notification |
| 0x0600 | NOTIFICATION_DISMISS | Dismiss notification |
| 0x0700 | NOTIFICATION_FILTER | Notification filter settings |
| 0x0800 | CONFIGURATION | Config items (time, steps, vibration, etc.) |
| 0x0900 | HAND_ACTIONS | Hand movement choreography |
| 0x0A00 | ALARMS | Alarm definitions |
| 0x0B00 | DEVICE_INFO | Device info (file versions, security) |
| 0x0C00 | SETTINGS_BUTTONS | Button configuration |
| 0x1000 | LOOK_UP | File lookup |

---

## Priority Order (suggested)

1. **Test remaining commands** — notify, alarm, step-goal, activity
2. **Monitor mode** — button press listener (useful for integrations)
3. **Interactive calibration** — better UX for hand alignment
4. **Config persistence** — save MAC, preferences to file
5. **Activity data parsing** — make fetched data human-readable
6. **Connection improvements** — daemon mode, auto-reconnect
7. **Packaging** — AUR, native binary
