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
- [x] `alarm` — confirmed: set/clear/test subcommands. Multi-alarm support (up to 32). Max count experiment: exactly 32 slots (firmware limit). Read-back not supported (INVALID_OPERATION_DATA).
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
- [x] Read current step count (ConfigurationGetRequest item 0x0002)
- [x] Set current step count (for activity hand position)
- [ ] Activity hand as notification counter mode

### Alarms
- [x] Test alarm set (file handle 0x0A00, version 2) — working
- [x] Multiple alarms in single upload — `alarm set 07:30 08:00 12:00`
- [x] Repeating alarms (day bitmask — note: Thu/Wed swapped per Python findings)
- [x] Alarm list/get (AlarmsGetRequest) — **not supported**: watch returns INVALID_OPERATION_DATA (1). Alarms are write-only on this firmware.
- [x] Alarm clear (upload empty alarm file) — `alarm clear`
- [x] Find max alarm count — **exactly 32** (firmware limit, power-of-two). Official Fossil app limits to 12, GadgetBridge to 5 — both artificial.
  - Tested: 1, 5, 10, 12, 15, 20, 30, 32 ✅ | 33, 35, 40, 50 ❌ (silent timeout)
  - Safe: failed uploads don't affect existing alarms
  - Storage per file handle is independent (activity data doesn't reduce alarm capacity)
- [x] Specific-date alarms — **working!** Undocumented non-repeating weekday format: `[0x80|day_bits] [minute] [hour]` (no repeat flag in byte1). `alarm at tomorrow 07:30` / `alarm at friday 14:30` / `alarm at 2026-05-23T09:00`. Validated within 7 days.
- [x] Weekday bitmask corrected — hardware-verified: bit3=Wed, bit4=Thu (opposite of GB docs).
- [x] Alarms use local time (not UTC) — confirmed on hardware

### Button Configuration
- [ ] Read current button config (ButtonConfigurationGetRequest)
- [x] Set button actions — `buttons` command with 10 functions: stopwatch, date, music, etc.
- [x] Listen for button press events on 3dda0006 (micro_app events decoded)
- [ ] Map button presses to custom actions (run command, send notification, etc.)
- [x] Multi-button press detection (SINGLE, DOUBLE) — firmware handles for MUSIC_CONTROL; software timing added for FORWARD_TO_PHONE (ButtonGestureDetector in FossilQAdapter). Uses 400ms double-press window (configurable via `--gesture-window`). RING_PHONE events delayed until gesture resolved; all other events unchanged.
- [x] Mode toggle support — multi-entry button config via ButtonConfigBuilder. `mode_toggle` keyword + `+` syntax for custom combos. Up to 5+ entries confirmed working (TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIFICATION). Entries without data (no alarm, no notification, 0% steps) are silently skipped. GOAL_TRACKING incompatible with toggle (error vibration). See FINDINGS.md #22.
- [ ] Take a photo support — needs phone-side camera trigger implementation
- [x] **FIXED: one physical button press fired MULTIPLE events (multi-buzz in succession).**
  ROOT CAUSE (confirmed by logcat 2026-06-14): the WATCH itself re-sends the SAME async-event frame
  on `3dda0006` ~10x within a few ms for ONE press (transport log showed `NOTIFY 3dda0006 <- 01 05
  14 02` x10 — identical opcode+eventType+sequence+data — before any app logic). Each copy drove a
  full effect (arm timer + ALARMS sync), so one press became a buzz/sync storm (an alarm upload even
  timed out). Not duplicate controllers, not the battery; consistent with the watch-state drift that
  a re-provision clears. FIX: de-duplicate identical action-event frames within a 750ms window at the
  adapter chokepoint (`FossilQAdapter.handleButtonEvent`), scoped to the discrete-action event types
  (music 0x05 / micro_app 0x08 / app-notification 0x04). Covered by `AdapterAsyncEventDedupTest`.
  (Below: the original investigation notes, kept for context.)
- [ ] **ROOT-CAUSE the watch's async-event re-send (so we can stop it at the source, not just
  absorb it).** Strong hypothesis from code review (b): the watch sends button/event frames on
  `3dda0006` with `opCode = 0x01 = REQUEST` (the captured storm was `01 05 14 02` — opcode 01), i.e.
  it EXPECTS an app acknowledgement/response. Our adapter only READS the event (`handleButtonEvent`
  → `emitEvent` to the phone callback) and NEVER writes anything back to `3dda0006`, and the
  `opCode` byte is read+logged but otherwise ignored. With no ack, the firmware re-requests the same
  frame (~10x, same sequence) until it times out → the buzz/sync storm. The de-dup patch absorbs
  this; the REAL fix is to send whatever ack the OFFICIAL app sends after a button event.
  CAPTURE (bundle with the 24h capture below — same btsnoop method): with the OFFICIAL Fossil app,
  enable Bluetooth HCI snoop log, press a watch button that emits an event (mode switch / music /
  micro-app), pull the btsnoop (`adb bugreport`), and in Wireshark look at what the phone WRITES to
  the watch in the ~tens of ms AFTER the `3dda0006` notification — specifically any write back to
  `3dda0006` (or a related char) that echoes the event's `eventType`+`sequence` (an ACK), AND check
  whether the official app's event frames carry `opCode 0x02` (NOTIFY, fire-and-forget) vs our
  observed `0x01` (REQUEST). If an ack write exists, replicate it in `FossilQAdapter.handleButtonEvent`
  (write the ack for REQUEST-opcode events) — that should stop the re-send entirely and remove the
  reliance on de-dup. Update FINDINGS with the ack frame format + provenance.
- [x] **FIXED: TIMER-mode press kicked a FULL alarm-file upload PER press (latency / timeouts).**
  Confirmed on the clean reinstall (logcat 20:06): the de-dup now works (one press → one gesture),
  but each press still fired a full `SyncSection.ALARMS` 32-slot upload (~1.5s), so two presses
  serialized and hit the 12s `UPLOAD_TIMEOUT_MS` twice before draining (~26s). FIX: debounce the
  timer alarm PUSH in `ServiceTrackerDispatch` (~1.2s trailing window) so a burst coalesces into
  ONE upload of the final armed time; the Room write stays per-press so the armed time is always
  live. Covered by `TimerAlarmPushDebounceTest`. (Original investigation notes below.)
- [ ] ~~BUG: TIMER-mode button press can take ~20s before the alarm reaches the watch (sync
  latency/queuing).~~ After the multi-buzz fix (commit 6d8432f), one press now correctly arms ONE
  timer, BUT a 2nd press a few seconds later showed a ~20s gap between `armed timer alarm slot 15`
  (instant, on the IO scope) and the alarm file PUT actually starting on BLE (`WRITE 3dda0003`).
  First press was instant; the second stalled. Every TIMER press fires a FULL `SyncSection.ALARMS`
  file sync (a ~1.5s, 3-write BLE transaction) on the single `ble-worker`, so back-to-back presses
  serialize and can stack; the watch's async re-send (still happening intermittently) and the 12s
  `UPLOAD_TIMEOUT_MS` / 30s adapter `REQUEST_TIMEOUT_SECS` may compound it. The 20s doesn't match a
  single constant exactly → looks like queuing/contention, not one timeout.
  CAPTURE NEEDED (service tag included): `adb logcat -c && adb logcat -v time -s FossilQ-Tracker
  FossilQ-Svc AndroidBleTransport`, press twice a few seconds apart, and inspect around the gap:
  when `syncNow:` fires vs when the `WRITE` starts, any `sync done` / `alarm upload timed out` /
  `ERROR`, and whether a prior sync is still running when the next press lands.
  LIKELY FIX DIRECTION: the timer only needs slot 15 — avoid a full ALARMS re-sync per press
  (debounce/coalesce rapid timer presses into ONE alarm push, or write just the timer slot), and/or
  make sure a press doesn't queue behind a stale/abandoned sync. Investigate after the root-cause
  ack work above (the re-send amplifies any per-press cost).
  CAPTURE 2026-06-14 20:02 (with FossilQ-Svc): ONE press emitted 3 `timer gesture` + 3 `SYNC_NOW`,
  and the alarm FILE was uploaded ~3-4 times (each = `03 00 09` open / `00 00 09 02...` data /
  `04 00 09` verify). A 2nd press then hit the 12s `alarm upload timed out` (UPLOAD_TIMEOUT_MS) on a
  BLE link saturated by the backlog. TWO compounding bugs confirmed:
    (A) the de-dup (commit 6d8432f) did NOT fire — 3 identical `01 05 1d 02` frames (same seq 0x1d)
        all produced a gesture. The dedup code is correct + present in the 19:47 APK, so the most
        likely cause is that the build on the phone during this capture was STALE (pre-fix). MUST
        re-verify with a guaranteed-fresh install (uninstall qhybrid.android, install the 19:47+
        APK) — expect the `Dropping duplicate async event` debug line + ONE gesture per press.
    (B) EVEN WITH dedup, each timer press triggers a FULL `SyncSection.ALARMS` 32-slot file upload
        (`armTimerAlarmAsync` → `ServiceSaveToWatch.trigger(ALARMS_ONLY)`). The timer only changes
        the reserved slot 15, so this is wasteful and serializes on the ble-worker. FIX: coalesce /
        debounce the timer alarm push, and/or only re-push when the alarm rows actually changed.
  Re-capture after the clean reinstall to separate (A) from (B).
- [ ] ~~BUG: one physical button press sometimes fires MULTIPLE events (multi-buzz in succession).~~
  Repro (Android, hardware): press the mode-SWITCH button once and instead of one buzz for the
  next mode, the watch buzzes several patterns in succession (e.g. from Lyrion it should buzz a
  single strong-single for Tracker, but it buzzed ~5-6 buzzes — looks like the rotation advanced
  several steps, each buzzing its own mode's pattern). Also observed on the TOP button (in TIMER
  mode, which buzzes to confirm the armed timer), so it is NOT specific to the switch action — it
  is a single press producing multiple `micro_app` (0x08) button events. Comes and goes (worse
  after the watch has been used a while); a clean re-provision has cleared related button issues
  before (see FINDINGS "Buttons go dead").
  The switch handler itself is correct (one press → one `advanceRotation()` → one `buzz()` in
  `ServiceTrackerDispatch.onButtonEventJson`), so the multiplication is UPSTREAM — duplicate event
  delivery. Two prime suspects:
    1. **Two live controllers.** `onEventJson` is wired for BOTH the foreground and the
       auto-reconnect controller (`WatchConnectionService.wireConnectionCallbacks`). If both links
       are momentarily up (reconnect churn), the same watch event is routed twice+.
    2. **Firmware/adapter re-delivery or the gesture detector.** `FossilQAdapter.handleMicroAppEvent`
       routes RING_PHONE (0x08) presses through `ButtonGestureDetector` (400ms window); the raw
       micro_app event carries a `sequence` byte that is NOT used for de-duplication anywhere.
  CAPTURE 2026-06-14 (TIMER mode, ONE short press of the TOP button): the handler fired SEVEN
  times in a ~4ms cluster — `FossilQ-Tracker: timer gesture: SHORT -> ring at 17:57 (+3 min)` x7,
  then `armed timer alarm slot 15` x7, `buzzNow ... pattern=5` x6, and `onStartCommand BUZZ` x7.
  So it is the **0x05 MUSIC-stream path** (`onTimerEventJson`/`onMusicEventJson`), NOT the 0x08
  button path, and the multiplication is at/above event delivery (every downstream effect is x6-7).
  The dispatch + switch handlers are each correct (one call → one effect); something delivers the
  SAME watch event ~7 times.
  STILL UNKNOWN (the adapter SLF4J tag `FossilQAdapter` did not surface in that capture, so we
  could not see the per-event `seq`). The decisive question: did the ADAPTER emit 7 events, or did
  ONE adapter event fan out to 7 pipelines?
  NEXT STEP — capture with the adapter + transport tags included:
    adb logcat -c && adb logcat -v time -s FossilQ-Tracker FossilQAdapter AndroidBleTransport FossilQ-Svc
  press once, then read:
    - adapter: `Music control: <ACTION> (seq=<N>)` — how many per press?
        * 7 lines, SAME seq  ⇒ ONE watch notification fanned out to ~7 live controllers/transports
          (duplicate-pipeline leak across reconnects) → FIX: route events from ONLY the foreground
          `controllerRef`, and/or ensure old controllers/transports fully tear down their GATT
          notification subscription on disconnect.
        * 7 lines, DIFFERENT seq ⇒ the WATCH actually sent ~7 notifications (firmware/watch-state
          issue, consistent with the "corrupted button state, fixed by re-provision" pattern)
          → FIX: de-duplicate at the adapter (drop a repeat within a short window) and/or guard by
          the press's own identity.
        * only 1 adapter line but 7 downstream ⇒ the fan-out is in the Android routing layer.
    - also note how many distinct `AndroidBleTransport`/controller instances appear.
  Do NOT re-add the old switch-buzz debounce — it only masked this; fix the duplicate delivery.
- [ ] **Capture the real TWENTY_FOUR_HOUR (24h) dial-mode bytes from the official Fossil app.**
  The 24h sub-eye position does NOT trigger in a mode-toggle cycle on a 5-position dial: on a
  cycle like `time2, alert, alarm, 24h, date`, the 24h press makes the dial return but the
  hour/minute hands do NOT move (the entry is silently skipped), then the next press shows date.
  ROOT CAUSE: the 24h entries in `protocol/.../ButtonConfigBuilder.java`
  (`TWENTY_FOUR_HOUR_DATA` / `TWENTY_FOUR_HOUR_SEQ_DATA`, headers `01 01 1E 00` / `01 02 1E 00`)
  were **"constructed by analogy" with ALARM/SECOND_TIMEZONE, never captured from the official
  app** — FINDINGS.md says *"TWENTY_FOUR_HOUR (appId 0x1E) is untested."* Every dial entry that
  works (Time2, Date, Alarm, Alert) is marked *"Captured from official Fossil app BLE trace."*
  HOW TO CAPTURE (mirrors the existing FINDINGS captures, e.g. bugreport5 DATE/ALARM):
    1. On a 5-position-dial watch (Q Activist or similar that HAS a labelled 24HR position),
       use the **official Fossil app** to assign a button a mode-toggle cycle that INCLUDES the
       24-hour position (ideally `Time 2 → Alert → Alarm → 24HR → Date` to match the repro).
    2. Enable Android Bluetooth HCI snoop log (Developer options → "Enable Bluetooth HCI snoop
       log"), reproduce the app pushing the button config, then pull the btsnoop log
       (`adb bugreport` or `/sdcard/.../btsnoop_hci.log`).
    3. In Wireshark, find the `SETTINGS_BUTTONS` (file handle 0x0600 / 0x0C00) file-put to the
       data characteristic; isolate the per-entry payload whose header is `01 02 1E 00`
       (SEQUENCED, used inside a toggle) — note its exact byte length + bytes incl. the trailing
       CRC. Also grab `01 01 1E 00` (STANDARD) if a standalone 24h button was captured.
    4. Replace `TWENTY_FOUR_HOUR_SEQ_DATA` (and `_DATA`) in `ButtonConfigBuilder.java` with the
       captured bytes; update `protocol/.../golden/Wp7ButtonCompilerTest.java` lengths
       (currently asserts 47 STANDARD / 54 SEQUENCED — verify against the capture) and add a
       golden assertion of the captured bytes. Update FINDINGS.md (#1205-ish dial-entry table +
       the "TWENTY_FOUR_HOUR is untested" note) with the verified payload + provenance.
    5. Sanity-check on hardware: the documented repro cycle should now show the 24h sub-eye
       position instead of being skipped.
  Until captured, consider hiding/flagging 24h as experimental in the Android dial-mode picker so
  it doesn't silently break a user's configured cycle (see `ButtonDialModes` /
  `ButtonsScreen.DialModeOrderEditor`).

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
- [ ] Support per-contact notification filtering (SENDER_NAME field 0x02 in filter)
- [x] Fix: removed TimezoneOffsetConfigItem (0x0011) from syncConfiguration(). See FINDINGS.md #21a.
- [x] `second-timezone` command — write config 0x0011 (short, minutes from UTC, 1024=disabled)
- [x] `goal-config` command — write config 0x0017 (task goal target) + 0x0018 (current value). No visual effect on watch (blind counter only). See FINDINGS.md #22.

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
