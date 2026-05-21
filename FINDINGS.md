# Fossil Q Hybrid CLI — Implementation Findings

Discoveries made during real-hardware testing with a Fossil Q Commuter (HW.0.0),
firmware HW0.0.2.9r.v3, on Linux (BlueZ 5.82).

---

## 1. BLE Read Values from busctl are Decimal

**Problem:** First run showed garbled firmware/model strings (`r‡HFHFPFWFQ`).

**Root cause:** `busctl call ... ReadValue` returns byte arrays in **decimal**, not hex:
```
ay 13 72 87 48 46 48 46 50 46 57 114 46 118 51
```
Our parser was treating these as hex values (e.g. parsing `72` as 0x72=114 instead of decimal 72='H').

**Fix:** Parse values as decimal integers in `parseBusctlByteArray()`. The `72 87 48 46...` correctly decodes to `HW0.0.2.9r.v3`.

---

## 2. BLE Write Type Must Match Characteristic Flags

**Problem:** Writes to `3dda0002` (write+notify) failed with "Operation not supported".

**Root cause:** The watch's GATT server rejects ATT Write Request (write-with-response) on characteristics flagged `write + notify`. It expects ATT Write Command (write-without-response).

**Characteristic write type mapping for this watch:**

| UUID | Flags | Required write type |
|------|-------|-------------------|
| `3dda0002` | write, notify | `command` (write-without-response) |
| `3dda0003` | write, indicate | `request` (write-with-response) |
| `3dda0004` | write-without-response, notify | `command` |
| `3dda0005` | write, indicate | `request` |
| `3dda0006` | write, notify | `command` |
| `3dda0007` | write-without-response, notify | `command` |

**Fix:** Read the `Flags` property during characteristic discovery. Use `type=command` for characteristics with `notify` or `write-without-response`, `type=request` for `indicate` characteristics. Falls back to `command` if `request` fails.

**busctl syntax:**
```bash
# Write-with-response (request):
busctl call --system org.bluez <path> ... WriteValue "aya{sv}" <len> <bytes...> 1 type s request

# Write-without-response (command):
busctl call --system org.bluez <path> ... WriteValue "aya{sv}" <len> <bytes...> 1 type s command
```

---

## 3. Persistent D-Bus Connection Required for Notifications (Critical)

**Problem:** `StartNotify` via busctl appeared to succeed (exit code 0) but `Notifying` property immediately went back to `false`. CCCD writes showed "Write not permitted". No notifications were received.

**Root cause:** `busctl` is a one-shot tool. Each invocation creates a new D-Bus connection, executes the method call, then exits — **destroying the D-Bus connection**. When the connection that called `StartNotify` closes, BlueZ un-registers the notification subscription and sets `Notifying = false`.

This is the fundamental reason `busctl call ... StartNotify` doesn't work for persistent notifications. The Notifying=true→false transition we observed was BlueZ registering then immediately un-registering when the busctl process exited.

**Evidence:**
- `busctl` StartNotify: `Notifying` = `true` for ~100ms then `false`
- `bluetoothctl` (persistent process) with `notify on`: `Notifying` = `true` and stays `true`

**Fix:** Spawn a persistent `bluetoothctl` process with stdin/stdout pipes. Send `menu gatt`, `select-attribute <uuid>`, `notify on` commands through the pipe. The process stays alive → D-Bus connection stays alive → notifications stay registered.

```java
// Start persistent bluetoothctl
ProcessBuilder pb = new ProcessBuilder("bluetoothctl");
btctlProcess = pb.start();
btctlStdin = btctlProcess.getOutputStream();

// Send commands through pipe (connection stays alive)
sendBtctlCommand("menu gatt");
sendBtctlCommand("select-attribute 3dda0003-...");
sendBtctlCommand("notify on");  // Notifying stays true!
```

**For receiving notifications:** Use `gdbus monitor --system --dest org.bluez` in a separate process. This catches `PropertiesChanged` signals including `Value` updates on characteristics, regardless of which D-Bus connection called StartNotify.

---

## 4. Time: UTC Epoch + TimezoneOffsetConfigItem

### The Three Time-Related Fields

| Field | Config ID | What we send | Purpose |
|-------|-----------|-------------|--------|
| **TimeConfigItem** epoch | 0x000C | UTC epoch (`millis/1000`) | Sets the watch's internal RTC |
| **TimeConfigItem** offset | 0x000C | TZ offset in minutes | Metadata (activity timestamps) |
| **TimezoneOffsetConfigItem** | 0x0011 | TZ offset in minutes | Watch uses this to shift displayed time |

### GadgetBridge Code

```java
// FossilWatchAdapter.generateTimeConfigItemNow():
return new TimeConfigItem(
    (int)(millis / 1000 + getTimeOffset() * 60), // UTC + manual adj (default 0)
    (short)(millis % 1000),
    (short)(zone.getOffset(millis) / 60000)      // TZ offset in TimeConfigItem
);

// syncConfiguration():
new TimezoneOffsetConfigItem((short) timezoneOffset)
// timezoneOffset comes from SharedPreferences, defaults to 0
```

GadgetBridge defaults: UTC epoch, TimezoneOffsetConfigItem=0. Many users likely
have incorrect time display and either set the preference manually or calibrate hands.

### Our Implementation

Send **UTC epoch** and set **TimezoneOffsetConfigItem to the full TZ offset** (including DST):

```java
// TimeConfigItem: UTC epoch + offset as metadata
new TimeConfigItem((int)(millis / 1000), (short)(millis % 1000), offsetMinutes);
// TimezoneOffsetConfigItem: watch uses this to shift display
new TimezoneOffsetConfigItem(offsetMinutes); // e.g. 60 for BST
```

This is the semantically correct approach: the RTC stores UTC, and the timezone
offset tells the watch how to convert to local time for display.

### Testing Note

Initial test showed time 1 hour ahead of local (BST, UTC+1). This was because
the hands had been calibrated with a 1-hour offset during earlier Python testing
(which used local epoch). After recalibrating hands with the UTC-based approach,
time should display correctly.

**If time displays wrong after sync:** use `fossil-q hands` to position hands at
12:00:00, then `fossil-q calibrate` to save as reference point. Then sync time again.

### Java Timezone Gotcha: `getRawOffset()` vs `getOffset(millis)`

For `Europe/London` in summer:
- `getRawOffset()` = **0** (GMT base, excludes DST)
- `getOffset(millis)` = **60** (includes BST +1h DST)

Always use `getOffset(millis)` to get the actual current offset including DST.

---

## 5. RequestMtuRequest Has Empty Payload

**Problem:** The Fossil init sequence sent a 0-byte write to `3dda0003`, which the watch rejected.

**Root cause:** `RequestMtuRequest.getStartSequence()` returns `new byte[0]`. In GadgetBridge, this request is handled specially — `FossilWatchAdapter.queueWrite(RequestMtuRequest, boolean)` calls `createTransactionBuilder("requestMtu").requestMtu(512).queue()` instead of writing the request data to BLE. The MTU negotiation happens at the ATT layer, not as a GATT characteristic write.

**Fix:** Intercept `RequestMtuRequest` in `FossilQAdapter.queueWrite()` before it reaches `executeRequest()`. On Linux, BlueZ auto-negotiates MTU during connection (5.50+), so we just read the current MTU from the characteristic's `MTU` property.

---

## 6. Device Information from Real Hardware

| Field | Value | Source |
|-------|-------|--------|
| BLE Address | D9:20:71:11:74:2A | Public address |
| Name | Fossil / FossilQ Hybrid | Changes after pairing |
| Model | HW.0.0 | Q Commuter |
| Firmware | HW0.0.2.9r.v3 | Position 6 = '2' → Fossil protocol |
| Hardware | HW.B0.A0 | |
| Manufacturer | Misfit | (historical — Misfit was acquired by Fossil) |
| MfgData | W0FF0511MK (key 0x00DF) | |
| Battery | 26% | |
| MTU | 185 | Auto-negotiated by BlueZ |
| Protocol | Fossil (2.x) | Based on firmware major version |
| Characteristics | 22 total, 6 Fossil-specific | |

---

## 7. Pairing & Agent Requirements

**BlueZ agent is required** for initial pairing. Without an agent registered, `bluetoothctl pair` fails with "AuthenticationFailed" and the journal shows:
```
No agent available for request type 2
device_confirm_passkey: Operation not permitted
```

The watch uses "Just Works" BLE pairing (no PIN/passkey), but BlueZ still needs an agent to auto-confirm the pairing request.

**Our approach:** The persistent `bluetoothctl` process registers as agent with `agent on` + `default-agent`. This handles both pairing and encryption negotiation.

**Single-bond limitation confirmed:** The watch can only be bonded to one device. If bonded to a phone, it must be unpaired first (or factory reset via battery removal).

**Directed advertising:** After pairing, the watch only advertises to its bonded device. General BLE scans won't find it. Connect using the known MAC address directly.

---

## 8. Fossil Protocol Flow Confirmed Working

The complete Fossil protocol init sequence executes successfully:

```
1. Connect + discover characteristics + negotiate MTU (185)
2. Enable notifications via persistent bluetoothctl (3dda0002..0007)
3. AnimationRequest → 3dda0002 (write-without-response) ✅
4. RequestMtuRequest → handled locally (BlueZ auto-negotiates) ✅
5. GetDeviceInfoRequest → write to 3dda0003, receive file on 3dda0003+0004 ✅
   - Receives SupportedFileVersionsInfo
   - Receives DeviceSecurityVersionInfo
6. ConfigurationPutRequest → file upload to 3dda0003+0004 ✅
   - Step goal, vibration strength, timezone offset, time
7. FilePutRequest (button settings) → file upload ✅
8. Device state: INITIALIZED ✅
```

File transfer protocol (used by steps 5-7):
```
Write file-put header to 3dda0003 → receive acceptance (type 3)
Write data chunks to 3dda0004 → receive CRC confirmation (type 8)  
Write file-close to 3dda0003 → receive close confirmation (type 4)
```

---

## 9. JSON Messages on 3dda0006

The watch spontaneously sends JSON messages on `3dda0006` (background events characteristic):
```json
{"req":{"id":43,"buddyChallengeApp":{"type":"sync_pkg"}}}
```

This is described as "HR-style" behavior in the Python research. The Q Commuter sends these despite being classified as a coin-cell (non-HR) watch by `WatchAdapterFactory`. These messages are informational and can be safely ignored for basic functionality.

---

## 10. gdbus Monitor Output Format

`gdbus monitor --system --dest org.bluez` outputs PropertiesChanged signals as:
```
/org/bluez/.../charNNNN: org.freedesktop.DBus.Properties.PropertiesChanged 
  ('org.bluez.GattCharacteristic1', {'Value': <[byte 0x01, 0x02, 0x03]>}, @as [])
```

Key parsing details:
- Path is on the first line, before the colon
- Byte values are hex with `0x` prefix, comma-separated inside `[byte ...]`
- Connected state changes appear as `'Connected': <true|false>`
- Notifying state changes appear as `'Notifying': <true|false>`

---

## 12. Alarm Slot Count

**GadgetBridge limits to 5 alarm slots** (`QHybridCoordinator.getAlarmSlotCount() = 5`). This is an artificial software limit.

**The official Fossil app allows at least 12 alarms** — confirmed on real hardware (Q Commuter HW.0.0). The actual firmware limit is unknown but > 12.

Each alarm is 3 bytes in the old format (file version 2), so 12 alarms = 36 bytes of payload — well within any reasonable file size limit. The new format (version 3, with labels) is ~17+ bytes per alarm.

The watch firmware validates uploads and returns `SIZE_OVER_LIMIT (7)` or `OVERFLOW (6)` if the limit is exceeded. No risk of bricking — the upload simply fails and existing alarms remain unchanged. Sending 0 alarms clears all.

**Testing plan:** increment alarm count (1, 5, 10, 12, 15, 20...) and find the exact firmware limit by watching for error codes.

---

## 14. Fossil Authentication Handshake (PROCESS_USER_AUTHORIZATION_V2)

**Date:** 2026-05-20

**Problem:** Notification filters uploaded successfully but were silently ignored by the watch.
Vibration only worked via the `findDevice()` workaround on `3dda0005`. Hand animation from
file-based notifications never triggered.

**Root cause:** The watch requires a Fossil-level authentication handshake on `3dda0005`
before it will honor notification filters. Without auth, filter uploads succeed but are
silently ignored.

**Protocol (PROCESS_USER_AUTHORIZATION_V2):**
```
1. App → Watch:  01 07                        (GET_USER_AUTHORIZATION_STATUS)
   Watch → App:  03 07 [00]                   (needs auth — 00 may be omitted)
              or: 03 07 01                     (already authorized)

2. If needs auth:
   App → Watch:  02 06 30 75 00 00 01         (confirm auth, 30s timeout, removeOtherPhones)
   [Watch vibrates — user must press TOP button within 30 seconds]
   Watch → App:  03 06 00 01                  (ACCEPTED)
              or: 03 06 00 00                  (REJECTED / timed out)
```

**Implementation challenges:**

1. **gdbus byte literal format (GLib 2.84+):** Small byte arrays (2-3 bytes) from auth
   indications are output as `b'\003\007'` (Python byte literal) instead of
   `[byte 0x03, 0x07, ...]`. Added `parseGdbusByteLiteral()` parser to handle octal
   (`\NNN`), hex (`\xNN`), and raw ASCII escapes.

2. **Thread deadlock:** `performAuthentication()` was initially called on the `bluez-monitor`
   thread (via the `handleDeviceInfos` callback). This blocked the same thread that reads
   gdbus output and delivers indications via `CompletableFuture`, causing a deadlock.
   Fix: auth + post-auth init runs on a dedicated `fossil-auth` thread.

3. **2-byte status response:** The watch sends `03 07` (2 bytes) for status=0x00 instead
   of `03 07 00` (3 bytes). The trailing null byte is either omitted by the watch or
   stripped by gdbus. Code handles both 2-byte and 3-byte responses.

4. **CCCD not required for indication delivery:** Despite `Notifying` showing `false` for
   all Fossil characteristics (CCCD writes fail without BLE bonding), the watch still
   sends indications/notifications. The persistent `bluetoothctl` `notify on` command
   keeps the D-Bus subscription alive, and gdbus monitor catches the PropertiesChanged
   signals regardless of the `Notifying` property state.

**Result:** After auth, file-based notifications produce vibration + hand movement to the
filter position (90°/90° = 3:15). Auth persists across reconnects — subsequent connections
get `03 07 01` (already authorized) and skip the button press.

---

## 15. Connection Speed Optimizations (2026-05-20)

**Problem:** First connection from clean state took ~63 seconds. Reconnects took ~17 seconds.

**Root causes identified:**

1. **GATT resolve timeout too long (15s):** On first connect after `bluetoothctl remove`, GATT
   service discovery often fails. We waited the full 15s before retrying.

2. **GATT retry did full re-scan:** After GATT failure, the code disconnected, re-scanned for
   the device, and reconnected. Unnecessary — the device is already known+trusted.

3. **Notification enable too slow:** Each characteristic took ~700ms (200ms select + 500ms
   notify-on). With 6 characteristics, that's 4.2s + overhead.

4. **Scan/connect polling too slow:** 1000ms scan polling, 500ms connect polling.

5. **Stale "In Progress" connections:** A previous failed connect leaves BlueZ in "In Progress"
   state, blocking all subsequent connect attempts. Must disconnect first to clear.

**Official Fossil app comparison (from bugreport3 BLE capture):**
- Android does full GATT service discovery too (~1.5s)
- But GATT always resolves on first try (Android Bluetooth stack is more reliable)
- Connection to GATT-ready: ~4.4s on Android
- The GATT retry issue is specific to BlueZ, not the watch

**Optimizations applied:**

| Change | Savings |
|--------|--------|
| GATT timeout: 15s → 5s (fail fast) | ~10s when retry needed |
| GATT retry: direct `Connect` instead of full re-scan | ~15s when retry needed |
| GATT retry loop: up to 3 attempts | Handles BlueZ first-connect issue |
| Notification enable: 700ms → 200ms per char | ~3.5s |
| Scan polling: 1000ms → 500ms | ~1-2s |
| Connect/GATT polling: 500ms → 250ms | ~1s |
| Various sleeps reduced (trust, agent, scan-off) | ~2s |
| Disconnect before connect (clear "In Progress") | Prevents hangs |
| Direct connect fast-path with 5s timeout | Skip scan for known devices |

**Results:**

| Scenario | Before | After |
|----------|--------|-------|
| Reconnect (device known) | ~17s | **~8s** |
| Clean state (best case) | ~63s | **~13s** |
| Clean state (GATT retry) | ~63s | **~25-30s** |

---

## 17. Official Fossil App: Add/Remove Watch BLE Capture (2026-05-20)

**Source:** bugreport4 — full pairing → remove cycle on Pixel 8a + Fossil Q Commuter (HW.0.0)

### Auth De-registration

**The official app does NOT send a de-auth command.** To "remove" the watch:
1. Normal disconnect
2. Android deletes the BLE link key (`Delete Stored Link Key` HCI command)
3. The watch detects the bond is gone → clears its auth state

**UPDATE (tested 2026-05-20):** Auth clearance is triggered by the bonded partner
deleting its link key, causing the watch's auto-reconnect to be rejected.

Experiment results:
- `bluetoothctl remove` from laptop + immediate reconnect: auth NOT cleared (`03 07 01`)
  → Watch didn't try to auto-reconnect to laptop, never detected missing bond
- Pair with phone → Android "Forget" (no Fossil app remove) → connect from laptop:
  auth CLEARED (`03 07 00`) → Watch tried to auto-reconnect to phone, got rejected
- The Fossil app's remove sequence writes identical files as setup — no de-auth command

**Conclusion:** Auth clears when the watch attempts auto-reconnect to its bonded partner
and the partner rejects it (link key deleted). No Fossil protocol de-auth command exists.
The trigger is purely at the BLE/SMP layer.

Our CLI now pairs via `pair` command or post-auth. Bond provides:
- Encrypted link (CCCD writes work properly)
- WakeAllowed=yes for auto-reconnect
- Faster reconnect (no re-scan needed, device stays known)
- To clear auth: remove bond from a device the watch actively auto-reconnects to

### Auth Flow (Fresh Pairing)

The official app skips `GET_USER_AUTHORIZATION_STATUS` (`01 07`) entirely.
On fresh pairing it goes straight to `PROCESS_USER_AUTHORIZATION` (`02 06`):
```
App → Watch:  02 06 30 75 00 00 01   (30s timeout, removeOtherPhones=true)
[5.4 seconds pass — user presses button]
Watch → App:  03 06 00 01             (ACCEPTED)
```

### Notification Filter (7 entries, 224 bytes)

The official app uploads the notification filter during initial setup:

| # | CRC | Vibe | Hand Position | Likely App |
|---|-----|------|--------------|------------|
| 1 | 0xBA3DC156 | DEFAULT (4) | 359°/359° | `bundleId.all` (catch-all) |
| 2 | 0xD1BE8F35 | DEFAULT (4) | 30°/30° | Unknown app |
| 3 | 0xB7590080 | CALL (1) | 60°/60° | Phone/Dialer |
| 4 | 0x8B56BE06 | TEXT (2) | 60°/60° | SMS/Messages |
| 5 | 0x40C7ED7C | DEFAULT (4) | 90°/90° | Unknown app |
| 6 | 0xA515ECD5 | DEFAULT (4) | 120°/120° | Unknown app |
| 7 | 0x5B2EB595 | DEFAULT (4) | 330°/330° | Unknown app |

All entries: group=0, priority=0, subeye=-1 (no move), duration=10000ms, subeye2=-2 (device default).

### Other Observations

- **4 pending notifications replayed** immediately after filter upload (WhatsApp messages)
- **Activity data downloaded and deleted** before disconnect (file handle 0x0002)
- **Config re-synced** after activity download (full device info + config + buttons)
- **No notification settings page visited** but filter was still uploaded with 7 entries
  (default filter from app's initial setup wizard)

---

## 16. dbus-java Transport (2026-05-20)

**Date:** 2026-05-20

**Goal:** Eliminate subprocess overhead (busctl/bluetoothctl/gdbus) by using
dbus-java + bluez-dbus for direct D-Bus access to BlueZ.

**Implementation:** `DbusTransport.java` using `bluez-dbus` 0.3.2 (which wraps
`dbus-java` 5.x). Single persistent `DBusConnection` replaces 3 subprocess
processes:

| Subprocess replaced | dbus-java equivalent |
|--------------------|---------------------|
| `busctl call ... ReadValue/WriteValue` | `BluetoothGattCharacteristic.readValue()/writeValue()` |
| `busctl tree/get-property` (discovery) | `BluetoothDevice.getGattServices()` (one call) |
| persistent `bluetoothctl` (StartNotify) | `BluetoothGattCharacteristic.startNotify()` (D-Bus connection persists) |
| `gdbus monitor` (notifications) | `PropertiesChanged` signal handler (direct callback) |

**Key implementation details:**

1. **DeviceManager singleton:** `DeviceManager.createInstance(false)` opens the system bus.
   `scanForBluetoothAdapters()` + `findBtDevicesByIntrospection()` for device lookup.

2. **MTU property type:** `Properties.Get()` returns `UInt16` directly (not `Variant<UInt16>`).
   Must handle both unwrapped and wrapped forms.

3. **StartNotify on Fossil chars:** `BluezNotPermittedException` is expected (CCCD write fails
   without BLE bonding), but notifications still work via the signal handler. Count as success.

4. **PropertiesChanged handler:** Registered globally on the D-Bus connection. Filters by
   path → UUID mapping for characteristic Value changes, and by device path for Connected changes.

5. **Value extraction:** D-Bus `Value` property arrives as `byte[]` or `List<Byte>` — both
   must be handled.

6. **GATT retry:** Same BlueZ GATT resolution issue as subprocess transport. First connect
   after `bluetoothctl remove` sometimes fails GATT discovery. Retry up to 3 times.

**Performance comparison (reconnect, no GATT retry):**

| Transport | Reconnect | Notes |
|-----------|-----------|-------|
| dbus-java | **~5-7s** | Direct D-Bus calls, ~1ms per BLE op |
| subprocess | ~8s | fork/exec per call, ~50-100ms per BLE op |

**Tested commands:** info, time, find, notify — all working.

---

## 13. BlueZ Quirks Encountered

| Issue | Impact | Workaround |
|-------|--------|------------|
| bluetoothd SEGV crash | Lost agent registration, pairing broke | Restart bluetooth service, re-register agent |
| CCCD "Write not permitted" | Notifications don't enable via busctl | Use persistent bluetoothctl (finding #3) |
| No `MTU` property on Device1 | Can't read device-level MTU | Read from characteristic MTU property instead |
| `busctl` one-shot D-Bus connections | StartNotify immediately un-registers | Persistent process (finding #3) |
| `dbus-monitor` AccessDenied | Can't use BecomeMonitor API | Use `gdbus monitor` instead (falls back to eavesdropping) |
| Directed advertising after pairing | Device invisible to general scan | Connect using known MAC directly |

---

## 18. Async Event Protocol on 3dda0006 (2026-05-20)

**Date:** 2026-05-20

**Source:** Decompiled official Fossil app (`DeviceEventParser.java`, `AsyncEventType.java`,
`AsyncOperationCode.java`) + real-hardware testing with `monitor` command.

The watch sends async events on characteristic `3dda0006`. These include button presses,
heartbeats, JSON messages, music control, battery updates, and more.

### Binary format

```
[opCode(1)] [eventType(1)] [sequence(1)] [data...]
```

- **opCode**: `0x01` = REQUEST, `0x02` = NOTIFY
- **sequence**: incrementing counter per event type (wraps at 255)
- **eventType**: see table below
- **data**: event-specific payload (may be empty)

### Event types (from `AsyncEventType.java`)

| Byte | Name | Data format | Notes |
|------|------|-------------|-------|
| 0x01 | JSON_FILE_EVENT | UTF-8 JSON string | e.g. `{"req":{"id":43,"buddyChallengeApp":{"type":"sync_pkg"}}}` |
| 0x02 | HEARTBEAT_EVENT | (empty) | Periodic keep-alive |
| 0x03 | CONNECTION_PARAM_CHANGE | (unknown) | Not observed on coin-cell |
| 0x04 | APP_NOTIFICATION_EVENT | notifId(4 LE) + actionType(1) + ... | Dismiss/accept/reject/reply |
| 0x05 | MUSIC_EVENT | actionByte(1) | 0=PLAY,1=PAUSE,2=TOGGLE,3=NEXT,4=PREV,5=VOL_UP,6=VOL_DOWN |
| 0x06 | BACKGROUND_SYNC_EVENT | chains of 3-byte frames | Activity sync frames |
| 0x07 | SERVICE_CHANGE_EVENT | (empty) | GATT service change notification |
| 0x08 | MICRO_APP_EVENT | 9+ bytes (see below) | Button press → micro app dispatch |
| 0x09 | AUTHENTICATION_REQUEST | (empty) | Watch requesting re-auth |
| 0x0B | TIME_SYNC_EVENT | (empty) | Watch requesting time sync |
| 0x0C | BATTERY_EVENT | state(1) + level(1) | state: 0=DISCHARGING,1=CHARGING,2=FULL |
| 0x0D | ENCRYPTED_DATA | type(1)+method(1)+key(1)+data | Encrypted payload |
| 0x0F | ALARM_SYNC_EVENT | (variable) | Alarm sync |
| 0x10 | WATCH_APP_SYNC_EVENT | (variable) | Watch app config sync |
| 0x11 | WATCH_APP_EVENT | (variable) | Watch app runtime event |

### Micro App Event format (eventType=0x08)

This is how **button presses** are delivered when a button is configured with
a micro app (RING_PHONE, MUSIC_CONTROL, etc.).

```
[version(1)] [declarationId(2 LE)] [variationNumber(1)] [contextNumber(1)]
[activityId(1)] [eventId(1)] [requestId(1)] [microAppEvent(1)]
```

**Button identification:** `eventId >> 4` = button index:
- `0x10` = TOP button
- `0x20` = MIDDLE button
- `0x30` = BOTTOM button

**App identification:** `declarationId` maps to a micro app:

| declarationId | MicroAppId | Variant |
|---------------|------------|--------|
| 1025 | GOAL_TRACKING | STANDARD |
| 3073 | RING_PHONE | STANDARD |
| 4097 | SELFIE | STANDARD |
| 4609 | MUSIC_CONTROL | PLAY_PAUSE |
| 4610 | MUSIC_CONTROL | NEXT |
| 4611 | MUSIC_CONTROL | PREVIOUS |
| 4612 | MUSIC_CONTROL | VOLUME_UP |
| 4613 | MUSIC_CONTROL | VOLUME_DOWN |
| 4614 | MUSIC_CONTROL | STANDARD |
| 5121 | DATE | STANDARD |
| 5122 | DATE | SEQUENCED |
| 5633 | TIME2 | STANDARD |
| 5634 | TIME2 | SEQUENCED |
| 6145 | ALERT | STANDARD |
| 6146 | ALERT | SEQUENCED |
| 6657 | ALARM | STANDARD |
| 6658 | ALARM | SEQUENCED |
| 7169 | PROGRESS | STANDARD |
| 7170 | PROGRESS | SWEEP |
| 7681 | TWENTY_FOUR_HOUR | STANDARD |
| 7682 | TWENTY_FOUR_HOUR | SEQUENCED |
| 8193 | STOPWATCH | STANDARD |
| 8705 | WEATHER | STANDARD |
| 9217 | COMMUTE_TIME | TRAVEL |
| 9218 | COMMUTE_TIME | ETA |

**`contextNumber` and `requestId`** are incrementing sequence counters, not button identifiers.

### Real hardware test results

With TOP=STOPWATCH, MIDDLE=FORWARD_TO_PHONE, BOTTOM=FORWARD_TO_PHONE:
- Pressing MIDDLE → micro_app event with declarationId=3073 (RING_PHONE), eventId=0x20 (MIDDLE) ✅
- Pressing BOTTOM → micro_app event with declarationId=3073 (RING_PHONE), eventId=0x30 (BOTTOM) ✅
- Pressing TOP → **no event sent** — STOPWATCH runs entirely on watch firmware ✅

**Key insight:** Built-in watch functions (STOPWATCH, DATE, SECOND_TIMEZONE, etc.) run
on the watch firmware and do NOT send events over BLE. Only phone-dependent functions
(RING_PHONE/FORWARD_TO_PHONE, MUSIC_CONTROL, etc.) send micro_app events.

---

## 19. Button Configuration & Built-in Watch Functions (2026-05-20)

**Date:** 2026-05-20

**Source:** GadgetBridge `ConfigPayload.java`, official app `WatchAppId.java`,
`MicroAppUtility.java`, `Action.java`, real-hardware testing.

### Button config file format

Button assignments are stored in file handle `SETTINGS_BUTTONS` (0x0600). The binary
format is built by `ConfigFileBuilder.java`:

```
[version: 01 00 00] [buttonCount(1)]
For each button:
  [buttonIndex(1)] [entryCount(1)] [header(4-6 bytes)] [null(1)]
[payloadCount(1)]
For each distinct payload:
  [payloadData(variable)]
[customizationCount: 00]
[CRC32(4)]
```

- `buttonIndex`: `0x10`=TOP, `0x20`=MIDDLE, `0x30`=BOTTOM
- Button config persists on the watch across disconnects — no need to re-upload on reconnect
- **Bug fixed:** init no longer overwrites button config (was resetting all to FORWARD_TO_PHONE)

### Available button functions (coin-cell Q Commuter HW.0.0)

**Built-in (run entirely on watch firmware, no phone needed):**

| Function | ConfigPayload | Header bytes | What it does |
|----------|--------------|-------------|-------------|
| Stopwatch | `STOPWATCH` | `02 01 20 01` | Start/stop/lap — hands show elapsed time |
| Date | `DATE` | `01 01 14 00` | Hands sweep to show current date |
| Second timezone | `SECOND_TIMEZONE` | `01 01 16 00` | Hands show time in second timezone |
| Step goal | `STEP_GOAL_COMPLETION` | `01 02 1C 00` | Hands show step goal progress |
| Last notification | `LAST_NOTIFICATION` | `01 01 18 00` | Hands show last notification position |

These do NOT send BLE events — the watch handles everything locally.

**Phone-dependent (send events via BLE for the phone to handle):**

| Function | ConfigPayload | Header bytes | What it does |
|----------|--------------|-------------|-------------|
| Forward to phone | `FORWARD_TO_PHONE` | `01 01 0C 00` | Single press → micro_app event (RING_PHONE) |
| Ring phone | `RING_PHONE` | `01 01 0C 00` | Same binary as above — app interprets differently |
| Music control | `MUSIC_CONTROL` | `01 06 12 00` | Single=play/pause, double=next, long=previous |
| Forward multi | `FORWARD_TO_PHONE_MULTI` | `01 06 12 00` | Same binary as MUSIC_CONTROL — app interprets differently |
| Volume up | `VOLUME_UP` | `01 04 12 00` | Single=vol up, double=vol up (repeat), long=mute(?) |
| Volume down | `VOLUME_DOWN` | `01 05 12 00` | Single=vol down, double=vol down (repeat), long=mute(?) |

Note: `FORWARD_TO_PHONE` / `RING_PHONE` and `MUSIC_CONTROL` / `FORWARD_TO_PHONE_MULTI`
have **identical binary payloads** — the distinction is only in how the companion app
interprets the received events.

### Double/long press detection

The **watch firmware** handles multi-press detection for `MUSIC_CONTROL` and `VOLUME_UP/DOWN`
configs. The binary payload encodes multiple gesture→action mappings:
- Action 0x02 = single press
- Action 0x03 = double press
- Action 0x04 = long press

For `FORWARD_TO_PHONE` (simple), only single press is detected by the firmware.
Double/long press would need to be detected in software by the CLI/app (timing-based).

### Mode Toggle (from Fossil app)

The official Fossil app offers "mode toggle" as a button action. From the decompiled code:

- `Action.DisplayMode.TOGGLE_MODE = 2006`
- `WatchAppId.BTN_MODE_TOGGLE = 1` (protobuf config)
- Legacy migration maps it to the string `"toggle-mode"`

**What it does:** Cycles the sub-eye (small hand) through display modes:
- Activity/step progress (2001)
- Last notification (2002)
- Date (2003)
- Second timezone (2004)
- Alarm (2005)

Each press cycles to the next mode. The sub-eye hand moves to show the selected
data. This is a **firmware-built-in function** — no phone needed.

**Status:** Not yet available in our CLI. The binary payload for mode toggle is not
in GadgetBridge's `ConfigPayload` enum. To support it, we'd need to either:
1. Capture the binary payload from the official app (BLE sniff while setting mode toggle)
2. Reverse-engineer the payload format from the protobuf definitions
3. Build the payload manually based on the header pattern

The header bytes likely follow the same pattern: `[type] [variant] [appId LE]` where
the app ID would correspond to the mode toggle declaration.

### Functions in the official Fossil app vs our CLI

| Fossil app | Our CLI | Status |
|-----------|---------|--------|
| Date | `date` | ✅ Working |
| Goal tracking | `step_goal` | ✅ Working |
| Mode toggle | — | ❌ Not yet (needs binary payload capture) |
| Music control | `music` | ✅ Working |
| Volume up | `volume_up` | ✅ Working |
| Volume down | `volume_down` | ✅ Working |
| Notifications | `last_notification` | ✅ Working |
| Ring phone | `ring_phone` / `forward_to_phone` | ✅ Working |
| Take a photo | — | ❌ Not yet (needs phone-side camera trigger) |
| Second timezone | `second_timezone` | ✅ Working |
| Stopwatch | `stopwatch` | ✅ Working |

### CLI usage

```bash
# Set buttons: TOP=stopwatch, MIDDLE=music, BOTTOM=forward_to_phone
fossil-q -d D9:20:71:11:74:2A buttons stopwatch music forward_to_phone

# Monitor button events (only phone-dependent buttons send events)
fossil-q -d D9:20:71:11:74:2A monitor
```
