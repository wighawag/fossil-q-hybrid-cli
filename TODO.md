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

- [x] `notify` — confirmed: vibration + hand animation via file-based notifications (lbl=12 format). Auth handshake enables notification filters. findDevice() workaround removed.
- [x] `step-goal` — confirmed: goal 50 → sub-eye moved to ~19, goal 99999 → sub-eye at zero
- [x] `vibration` — confirmed: setting to 10 triggered weak buzz, setting 100→100 (no change) no buzz. Watch gives feedback on actual change.
- [x] `timezone` — confirmed: offset 0 shifted hour hand back 1h (BST→UTC), offset 60 restored correct time. Fixed: now sends time+offset together.
- [x] `alarm` — confirmed: 07:30 vibration fired on watch
- [x] `find` — confirmed: 2 vibration bursts + hand rotation over 3 seconds
- [x] `activity` — confirmed: downloads + parses activity data (steps, calories, variability per minute). Multi-segment support, hourly summary, NDJSON output.

---

## Features to Explore & Implement

### Notifications
- [ ] Test `PlayTextNotificationRequest` — does it vibrate + move hands?
- [ ] Test different `VibrationType` values (SINGLE_SHORT, DOUBLE_NORMAL, etc.)
- [ ] Notification with custom hand animation (hour/minute degree params)
- [ ] Notification filter configuration (which "apps" trigger vibrations)
- [ ] `NotificationFilterPutRequest` — upload notification settings file

### Activity & Step Tracking
- [x] Fetch activity data file (FileLookupAndGetRequest on 0x0100)
- [x] Parse activity data format (ActivityParser.java — multi-segment, no-HR variant, file v22)
- [x] Display step count, hourly breakdown, summary
- [x] Delete activity data after fetch (FileDeleteRequest, default; `--keep` to preserve)
- [x] NDJSON output (`--raw` for records with steps, `--raw --all` for everything)
- [x] Save raw binary (`-o activity.bin`)
- [ ] Read current step count (ConfigurationGetRequest item 0x0002)
- [ ] Set current step count (for activity hand position)
- [ ] Activity hand as notification counter mode

### Alarms
- [ ] Test alarm set (file handle 0x0A00, version 2)
- [ ] Multiple alarms in single upload
- [ ] Repeating alarms (day bitmask — note: Thu/Wed swapped per Python findings)
- [ ] Alarm list/get (AlarmsGetRequest — if watch supports reading back)
- [ ] Alarm clear (upload empty alarm file or FileDeleteRequest)
- [ ] Find max alarm count — official Fossil app allows 12+, GB limits to 5 artificially
  - Test: 1, 5, 10, 12, 15, 20... until SIZE_OVER_LIMIT error
  - Safe: watch rejects bad uploads, existing alarms unchanged

### Button Configuration
- [ ] Read current button config (ButtonConfigurationGetRequest)
- [x] Set button actions — `buttons` command with 10 functions: stopwatch, date, music, etc.
- [x] Listen for button press events on 3dda0006 (micro_app events decoded)
- [ ] Map button presses to custom actions (run command, send notification, etc.)
- [ ] Multi-button press detection (SINGLE, DOUBLE, LONG) — firmware handles for MUSIC_CONTROL; software timing needed for FORWARD_TO_PHONE
- [ ] Mode toggle support — needs binary payload capture from official app
- [ ] Take a photo support — needs phone-side camera trigger implementation

### Watch Events & Monitoring
- [x] `monitor` command — long-running NDJSON event stream to stdout, Ctrl+C to stop
- [x] Parse JSON messages on 3dda0006 (eventType 0x01: buddyChallengeApp sync, etc.)
- [x] Heartbeat events (eventType 0x02 on 3dda0006)
- [x] Button press events (eventType 0x08 = MICRO_APP_EVENT, decoded with button name + app name)
- [x] Music events (eventType 0x05: play/pause/next/prev/volume)
- [x] Full async event protocol decoded (15 event types from official app)
- [x] Fixed: init no longer overwrites button config on connect
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
- [x] Connection speed optimization (63s → 8-13s) — faster polling, GATT fail-fast, stale connection handling
- [x] **DbusTransport (dbus-java)** — direct D-Bus calls via bluez-dbus 0.3.2. Default transport.
      BluezTransport kept as `--subprocess` fallback. ~5-7s reconnect (vs ~8s subprocess).
      Eliminates: subprocess fork/exec, stdout parsing, persistent bluetoothctl, gdbus monitor.
- [x] **BLE pairing after auth** — `pair()` called after Fossil auth accepted + on reconnect if
      not yet bonded. Raw `Agent1` impl registered for Just Works auto-confirm. Tested end-to-end:
      fresh auth → button press → pair → bond created. Also works on reconnect (already auth'd).
      Bond provides: encrypted link, WakeAllowed, faster reconnect (~5.9s vs ~9s).
      Auth clears when bonded partner deletes key + watch auto-reconnect is rejected (SMP layer).
- [x] **`pair` CLI command** — `fossil-q pair` connects, inits, and triggers BLE pairing.
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

### Notification Vibration on HW.0.0 (Investigation Needed)

The Fossil notification filter system (`NotificationFilterPutRequest` + `PlayTextNotificationRequest`) does
not produce vibration on HW.0.0 firmware, despite the filter upload succeeding and the notification
triggering hand animation. Currently we workaround by using the call characteristic (`3dda0005`) for
vibration, but this only gives one vibration pattern (call buzz) with duration control.

**Goal:** Find the proper way to trigger different vibration patterns from notifications.

**Investigation approaches:**
- [ ] **Reverse-engineer the official Fossil app APK** — decompile with jadx/apktool and trace how it
  sends notifications. The official app definitely makes the watch vibrate on notifications.
  - Look for writes to `3dda0005` (call char) vs `3dda0003` (indicate char)
  - Look for different byte sequences that produce different vibration patterns
  - Check if there's a different notification flow than what GB uses
- [ ] **Capture BLE traffic** between official Fossil app and watch — use Android BLE logging
  (`btsnoop_hci.log`) or nRF Connect to capture the exact byte sequence when a notification vibrates
- [ ] **Test different vibration bytes on 3dda0005** — the current call vibration sends
  `01 04 30 75 00 00`. Try varying the bytes to discover different patterns:
  - Byte 3 (`0x30`): duration? pattern?
  - Byte 4 (`0x75`): intensity?
  - Other command prefixes: `01 04` is "start call" — are there `01 05`, `01 06`, etc.?
- [ ] **Test `PlayCallNotificationRequest`** — uses `NotificationType.INCOMING_CALL` with a hardcoded
  CRC (`0xB7590080`). Maybe INCOMING_CALL type triggers vibration where NOTIFICATION type doesn't.
- [ ] **Check if notification filter vibration byte values matter** — the filter uses misfit
  `VibrationType` enum values (3,5,6,7,8,9) but maybe the watch expects different values.
  Try raw byte values 1-10 in the filter's VIBRATION field.
- [ ] **Check the `VibrateRequest`** (misfit command) — different from `PlayNotificationRequest`,
  may write different bytes to a different characteristic.

**Findings from official Fossil app (jadx decompile in tmp/FossilOfficialApp-deobf):**
- `3dda0005` is `AUTHENTICATION` characteristic (not "call") — used for key exchange
- Notifications use `putFile` with file handle (same file-based protocol as GB)
- `NotifyAppNotificationEventRequest` on `3dda0006` is for notification *control*
  (dismiss, accept call, reply) — NOT for playing notifications

**🔑 Likely root cause: CRC mismatch in notification filter!**
- Official app (`NotificationFilter.java`): computes CRC32 on `packageName bytes + 0x00` (null terminated)
- GadgetBridge (`NotificationFilterPutRequest.java`): computes CRC32 on `packageName.getBytes()` (no null terminator)
- `PlayNotificationRequest` (Fossil file base): computes CRC32 on `packageName.getBytes()` (no null terminator)
- If filter CRC ≠ notification CRC, watch can't match them → no vibration
- **Fix to try:** Either add null terminator to both CRC computations, or ensure they match
- Note: GB's `PlayNotificationRequest.createFile()` and `NotificationFilterPutRequest.createFile()`
  both use `java.util.zip.CRC32` on bare bytes, so they should match each other. But the watch
  firmware may expect the null-terminated CRC format from the official app.

**Official app vibration patterns (`NotificationVibePattern`):**
| Pattern | Byte |
|---------|------|
| AUTO | 0 |
| CALL | 1 |
| TEXT | 2 |
| EMAIL | 3 |
| DEFAULT_OTHER_APPS | 4 |
| ONE_SHORT_VIBE | 5 |
| TWO_SHORT_VIBES | 6 |
| THREE_SHORT_VIBES | 7 |
| ONE_LONG_VIBE | 8 |
| NO_VIBE | 9 |

**Official app notification filter fields:**
- Entry `0x04`: Package CRC (4 bytes) — CRC32 of package name + null byte
- Entry `0x80`: Group ID (1 byte)
- Entry `0xC1`: Priority (1 byte, default 0xFF)
- Entry `0xC2`: Hand movement (8-10 bytes: hour°, min°, subeye°, duration_ms + optional subeye2°)
- Entry `0xC3`: Vibration pattern (1 byte, see table above)
- Entry `0xC4`: Display config (1 byte) — not in GB
- Entry `0x82`: Icon config — not in GB
- Entry `0x02`: Sender name (null-terminated string) — not in GB

**Other differences:**
- `NotificationHandMovingConfig` has duration field (1000-60000ms) — GB uses hardcoded 5000
- Special value -2 means "device default position", -1 means "no move"
- `AllNotificationFilter` uses package name `"bundleId.all"` as a catch-all with priority 255

**Root cause found and fixed!** The official Fossil app uses `lengthBufferLength=12` in the
NOTIFICATION_PLAY file, with 2 extra fields (0xFFFFFFFF sentinel + Unix timestamp).
GadgetBridge uses `lengthBufferLength=10` with only 3 string fields. The HW.0.0 firmware
requires the lbl=12 format for vibration to trigger.

**BLE capture analysis (btsnoop_hci.log from official Fossil app on Pixel 8a):**
- Official app NEVER writes NOTIFICATION_FILTER (0x0C00) on reconnect — filter is persistent
- Official app writes CONFIGURATION (0x0800) during init
- Notifications use NOTIFICATION_PLAY (0x09xx) with lbl=12 format — vibration works
- Official app does NOT use 3dda0005 (authentication char) for vibration

**Tested approaches during investigation:**
- [x] CRC with null terminator — not the issue (both CRC styles work once format is correct)
- [x] `PlayCallNotificationRequest` (INCOMING_CALL type) — no vibration (still lbl=10)
- [x] `PlayTextNotificationRequest` (NOTIFICATION type) — no vibration (lbl=10)
- [x] Official format with lbl=12 + extra fields — **VIBRATION WORKS!** ✅

**Notification system working (2026-05-20):**
- Auth handshake implemented (`01 07` status check + `02 06` user confirmation)
- File-based notifications produce vibration + hand animation
- findDevice() workaround removed — notifications are fully file-based
- Auth persists across reconnects (no button press on re-auth)

**BLE capture findings (full pairing flow):**
- Official app's notification filter format is now fully decoded (7 entries, 32 bytes each)
- Our filter format is byte-identical to the official app's (verified)
- Auth protocol fully decoded and implemented (PROCESS_USER_AUTHORIZATION_V2)

**Future improvements:**
- [x] Implement Fossil authentication handshake ✔️
- [ ] Support hand position in notifications (filter format is now known)
- [ ] Support different vibration patterns (CALL=1, TEXT=2, EMAIL=3, DEFAULT=4, etc.)
- [ ] Support per-app notification mapping (CRC → hand position + vibe pattern)

---

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
