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

## 12. Alarm Slot Count — Maximum 32 (Confirmed)

**Date:** 2026-05-21

**GadgetBridge limits to 5 alarm slots** (`QHybridCoordinator.getAlarmSlotCount() = 5`). This is
an artificial software limit. **The official Fossil app allows 12** (also artificial).

**The actual firmware limit is exactly 32 alarms** — confirmed by binary search on real hardware
(Q Commuter HW.0.0, firmware HW0.0.2.9r.v3).

### Test results

| Count | File size | Result |
|-------|-----------|--------|
| 1 | 3 bytes | ✅ SUCCESS |
| 5 | 15 bytes | ✅ SUCCESS |
| 10 | 30 bytes | ✅ SUCCESS |
| 12 | 36 bytes | ✅ SUCCESS |
| 15 | 45 bytes | ✅ SUCCESS |
| 20 | 60 bytes | ✅ SUCCESS |
| 30 | 90 bytes | ✅ SUCCESS |
| 32 | 96 bytes | ✅ SUCCESS |
| 33 | 99 bytes | ❌ TIMEOUT (no response) |
| 35 | 105 bytes | ❌ TIMEOUT |
| 40 | 120 bytes | ❌ TIMEOUT |
| 50 | 150 bytes | ❌ TIMEOUT |

32 alarms × 3 bytes = 96 bytes — a clean power-of-two slot count, suggesting a fixed
32-entry alarm table in firmware.

### Failure mode

When the limit is exceeded, the watch does **not** return an error code like
`SIZE_OVER_LIMIT (7)` or `OVERFLOW (6)`. Instead, it simply **does not respond** to the
file-put request at all (no acceptance on type 3). The upload times out. Existing alarms
remain unchanged — confirmed by clearing alarms successfully after a failed 33-alarm upload.

### Storage independence

32 alarms succeed even with activity data accumulated on the watch. Each file handle
(ALARMS 0x0A00, ACTIVITY 0x0100, SETTINGS_BUTTONS 0x0600, etc.) has **independent storage**.
Alarm capacity is not affected by other file handles.

### Alarm file read-back

`AlarmsGetRequest` (FileGetRequest on handle 0x0A00) returns `INVALID_OPERATION_DATA (1)` on
this firmware — the watch does not support reading back the alarm file. Alarms are write-only.
The companion app must track alarm state locally.

### Non-repeating weekday alarms (undocumented format, hardware-verified)

**Date:** 2026-05-21

The standard alarm wire format has two known modes:
- **One-shot** (`repeat=false`): `[0xFF] [minute] [hour]` — fires at next HH:MM regardless of day
- **Repeating** (`repeat=true`): `[0x80|days] [minute|0x80] [hour]` — fires on specified weekdays, repeats weekly

**Discovery:** There is a third, undocumented mode that combines weekday targeting with non-repeat:
- **Non-repeating weekday**: `[0x80|days] [minute] [hour]` — fires once on the specified weekday, then stops

The key difference from repeating is that byte1 does NOT have bit7 (0x80) set.

**Hardware-verified test sequence (Q Commuter HW.0.0, Thursday 2026-05-21):**

| Test | byte0 | byte1 | byte2 | Format | Result |
|------|-------|-------|-------|--------|--------|
| One-shot 11:01 | FF | 01 | 0B | standard one-shot | ✅ Rang |
| Repeat Thu(bit3=8) 11:04 | 88 | 84 | 0B | repeat+wrong bit | ❌ Silent |
| Repeat Thu(bit4=16) 11:07 | 90 | 87 | 0B | repeat+correct bit | ✅ Rang |
| Non-repeat Thu(bit4) 11:10 | 10 | 0A | 0B | days without 0x80 marker | ❌ Silent |
| Non-repeat Thu(0x80+bit4) 11:14 | 90 | 0E | 0B | **non-repeat weekday** | ✅ Rang |
| Non-repeat Fri(0x80+bit5) 11:17 | A0 | 11 | 0B | wrong day (Friday≠Thursday) | ❌ Silent (correct) |
| `alarm at today 11:21` | 90 | 15 | 0B | via CLI command | ✅ Rang |
| `alarm at today 11:24` | 90 | 18 | 0B | via CLI command | ✅ Rang |

**Conclusions:**
1. The 0x80 marker in byte0 is required for weekday bits to be recognized
2. Byte1 bit7 controls repeat (1) vs one-shot (0)
3. Weekday bits without byte0's 0x80 marker are ignored (alarm doesn't fire)
4. The non-repeating weekday format is not used by the official Fossil app or GadgetBridge

### Corrected weekday bitmask (hardware-verified)

GadgetBridge documents bit3=Thursday, bit4=Wednesday. **This is wrong.** Real hardware testing
confirms the opposite:

| Bit | Value | Actual weekday | GB Alarm.java says |
|-----|-------|---------------|--------------------|
| 0 | 1 | Sunday | Sunday |
| 1 | 2 | Monday | Monday |
| 2 | 4 | Tuesday | Tuesday |
| 3 | 8 | **Wednesday** | Thursday (WRONG) |
| 4 | 16 | **Thursday** | Wednesday (WRONG) |
| 5 | 32 | Friday | Friday |
| 6 | 64 | Saturday | Saturday |

The CONTEXT.md note "Thursday/Wednesday swapped in day bitmask (bit3=Thu, bit4=Wed) — GB bug"
was itself backwards. The actual hardware has bit3=Wed, bit4=Thu.

### Alarm time interpretation

Alarms use **local time** (not UTC). The watch applies the TimezoneOffsetConfigItem internally.
Verified: setting alarm for 11:01 local (UTC 10:01) rang at 11:01 local.

### CLI usage

```bash
# Set alarms (replaces all existing)
fossil-q alarm set 07:30 08:00 12:00
fossil-q alarm set 07:30 --days 30    # Mon-Fri (2+4+8+16=30, corrected bits)

# One-shot alarm for a specific date/time (within 7 days)
fossil-q alarm at tomorrow 07:30
fossil-q alarm at friday 14:30
fossil-q alarm at 2026-05-23T09:00

# Clear all alarms
fossil-q alarm clear

# Test max count (for experimentation)
fossil-q alarm test 32                 # max that works
fossil-q alarm test 32 --start 06:00   # stagger from 06:00
```

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

---

## 20. Activity File Format (version 22, no-HR coin-cell variant) (2026-05-21)

**Date:** 2026-05-21

**Source:** GadgetBridge `ActivityFileParser.java` (not vendored — heavy entity deps) +
binary analysis of real activity data from Fossil Q Commuter (HW.0.0).

### File layout

```
[0-1]   File marker (01 01)
[2-3]   Version (22 LE)
[4-7]   Reserved (FFFFFFFF)
[8-11]  Timestamp (LE Unix epoch, UTC)
[12-13] Timezone offset (LE, minutes)
[14-15] Interval (LE, seconds — always 60)
[16-17] File ID (LE, incrementing per fetch)
[18-31] Device metadata (14 bytes, constant per device)
[32+]   Segments (one or more)
Trailing bytes after last valid record are ignored.
```

### Segment format

```
[E2] [type(1)] [timestamp(4 LE)] [tz_offset(2 LE)] [interval(2 LE)] [FE FE]
[records: 4 bytes each until byte[2] != 0xFF]
```

- **type 0x04**: current/latest segment (timestamp matches file header)
- **type 0x03**: historical segment (earlier data, can overlap with 0x04)
- Segments can be out of chronological order in the file
- `FE FE` marker separates header from records

### Record format (4 bytes, 1-minute intervals)

```
[b0] [b1] [b2=0xFF] [b3]
```

- **b2** must be `0xFF` (no-HR marker). Non-0xFF terminates the record stream.
- **b3**: bits 0-5 = calories, bit 6 = isActive flag

**Step count decoding (two modes based on b0 bit 0):**

| Mode | Condition | Steps | Variability |
|------|-----------|-------|-------------|
| Low-step | b0 bit 0 = 1 | `b0 & 0x0E` (0-14) | Complex: uses b0 bits 4-7 + b1 |
| High-step | b0 bit 0 = 0 | `b0 & 0xFE` (0-254) | `b1² × 64` |

Low-step mode provides fine-grained variability data (wrist movement intensity).
High-step mode is used when many steps occur in one minute.

### Real data characteristics

- **activity.bin** (3488 bytes): 857 records across 2 segments, 14-hour span, 2 steps
  (watch was sitting on desk during development — almost zero physical activity)
- **activity-test.bin** (120 bytes): 18 records, 1 segment, 17-minute span, 6 steps
- Most records are idle: `01 00 FF 00` (0 steps, 0 calories, not active)
- File is deleted from watch after fetch (official app behavior). `--keep` flag preserves it.

### Implementation note: Java ByteBuffer.wrap() gotcha

`ByteBuffer.wrap(array, offset, length)` creates a buffer where **absolute-index
methods** (`getInt(index)`, `getShort(index)`) use indices relative to **the array
start**, not the offset. For reading segment headers at variable positions, use
a single `ByteBuffer.wrap(file)` and compute absolute offsets:
```java
ByteBuffer buf = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
long ts = buf.getInt(pos + 2) & 0xFFFFFFFFL;       // correct
int interval = buf.getShort(pos + 8) & 0xFFFF;     // correct
```

### GadgetBridge differences

- GB's `ActivityFileParser` only handles the first segment (E2 04 at offset 32);
  our parser handles multiple segments including E2 03 historical segments
- GB's no-HR variant parser reads records from fixed offset 44; ours follows
  FEFE markers for each segment
- GB uses entity classes (`HybridHRActivitySample`, `BaseActivitySummary`);
  ours uses standalone POJOs with no Android/DB dependencies

### CLI usage

```bash
# Fetch activity, show summary (default: deletes data from watch)
fossil-q -d D9:20:71:11:74:2A activity

# Keep data on watch + save raw binary
fossil-q -d D9:20:71:11:74:2A activity --keep -o activity.bin

# NDJSON output (records with steps only)
fossil-q -d D9:20:71:11:74:2A activity --raw

# NDJSON output (all records including idle)
fossil-q -d D9:20:71:11:74:2A activity --raw --all
```

---

## 21. BLE Capture Analysis — Official Fossil App (bugreport5, 2026-05-21)

**Date:** 2026-05-21

**Source:** BLE HCI snoop log from Pixel 8a running official Fossil app, connected to
Fossil Q Commuter (HW.0.0). Full session: pairing → second timezone → mode toggle →
alarms → notification filters → notification triggers. 496 Fossil ATT operations over
~780 seconds.

**ATT handle map (from GATT discovery):**

| Handle | UUID | Name |
|--------|------|------|
| 0x0042 | 3dda0002 | CMD (commands, write+notify) |
| 0x0045 | 3dda0003 | CTL (file control, write+indicate) |
| 0x0048 | 3dda0004 | DAT (file data, write) |
| 0x004b | 3dda0005 | AUTH (authentication, write+indicate) |
| 0x004e | 3dda0006 | EVT (async events, write+notify) |
| 0x0051 | 3dda0007 | CHR7 (notification play data, write) |

### 21a. Config 0x0011 Is SECOND TIMEZONE, Not Primary

**The official app does NOT send config 0x0011 during initial time sync.**

Initial config upload (t=43.1s) contains:
- 0x0001 (BIOMETRIC_PROFILE): 7 bytes
- 0x0002 (DAILY_STEP): current step count
- 0x0003 (DAILY_STEP_GOAL): 5700
- 0x0004 (DAILY_CALORIE): 0
- 0x0005 (DAILY_CALORIE_GOAL): 240
- 0x0006 (DAILY_TOTAL_ACTIVE_MIN): 0
- 0x0007 (DAILY_ACTIVE_MIN_GOAL): 30
- 0x000A (VIBE_STRENGTH): 75 (NOT 100)
- 0x000C (TIME): UTC epoch + millis + TZ offset (60 min = BST)
- 0x0009 (INACTIVE_NUDGE): disabled

Config 0x0011 is sent **only** when the user configures a second timezone:
```
t=103.8s: Config write 0x0011, length=2, value=0x014A (330 minutes = UTC+5:30)
```

From decompiled app: `DeviceConfigKey.SECOND_TIMEZONE_OFFSET(17, "second_timezone_offset")`
Special value 1024 = disabled. Range: -720 to 840 minutes.

Watch's stored config (read at t=41.5s) showed 0x0011=60 — this was set by **our CLI**
during previous sessions, incorrectly overwriting the second timezone with the primary
offset.

**Impact:** Our `syncConfiguration()` has been sending `TimezoneOffsetConfigItem(localOffset)`
as config 0x0011 on every connect. This overwrites any second timezone the user set.
The watch gets primary timezone from `TimeConfigItem` (0x000C) offset field — it does NOT
need 0x0011 for primary time display.

**Fix needed:** Remove `TimezoneOffsetConfigItem` from `syncConfiguration()`. Add a
dedicated `second-timezone` command that writes config 0x0011.

### 21b. Mode Toggle = Multi-Entry Button Config

Mode toggle is **not** a single function with a unique binary payload. It's a button
assigned **multiple entries** — the watch firmware cycles through them on each press.

Captured button config at t=169.6s (TOP button set to Mode Toggle):
```
Button TOP: 3 entries
  [0] header: 01 01 16 00 = SECOND_TIMEZONE
  [1] header: 01 02 14 00 = DATE
  [2] header: 01 02 1a 00 = STEP_GOAL_PROGRESS (appId=0x001a=26)

Button MIDDLE: 1 entry
  [0] header: 01 01 16 00 = SECOND_TIMEZONE

Button BOTTOM: 1 entry
  [0] header: 01 01 0c 00 = FORWARD_TO_PHONE
```

Each press cycles: SECOND_TIMEZONE → DATE → STEP_GOAL_PROGRESS → repeat.

The 3rd entry (appId 0x001a) has a dedicated payload blob:
```
01 00 01 02 1a 36 00 00 00 01 00 08 00 04 00 00
07 02 00 00 01 01 1d 00 89 02 01 04 b0 03 00 89
05 01 07 b0 03 00 b0 03 00 b0 03 00 08 01 50 00
01 00 a6 79 57 cc
```

**Implementation:** No new GadgetBridge code needed. `ConfigFileBuilder` already supports
multiple entries per button (`entryCount` field). We just need to:
1. Add a `ConfigPayload` entry for appId 0x001a with the captured payload
2. Allow the `buttons` command to accept `+`-separated multi-functions
3. Example: `buttons "second_timezone+date+step_goal_progress" music forward_to_phone`

**Customization section** at end of button config file:
```
[count=5] then for each entry:
  [header(4)] [0a 00 01 02 01 00]  // 6 bytes per button×entry combination
```

### 21c. Alarm Format — Official App Confirmed

Three sequential alarm uploads captured:

| Upload | Time | Data | Decoded |
|--------|------|------|---------|
| 1 | t=216.7s | `FF 26 0C` | One-shot at 12:38 |
| 2 | t=235.0s | `FF 26 0C` `BE A7 0C` | + Mon-Fri repeat at 12:39 |
| 3 | t=262.5s | `FF 26 0C` `BE A7 0C` `A0 A7 09` | + Fri repeat at 09:39 |

Byte decode:
- `0xFF` = one-shot marker (standard)
- `0xBE` = `10111110` = 0x80 flag + bits 1-5 (Mon-Fri)
- `0xA0` = `10100000` = 0x80 flag + bit 5 (Fri)
- `0xA7` = `10100111` = 0x80 repeat flag + 0x27 (39 minutes)

**Confirmed:**
- Official app uses standard one-shot (`0xFF`) and repeating (`0x80|days`, `0x80|minute`) formats
- Does NOT use our discovered non-repeating weekday format (undocumented, hardware-only)
- Alarms uploaded atomically — all alarms replaced on each write
- Day bits match our hardware-verified mapping: bit1=Mon, bit2=Tue, bit3=Wed, bit4=Thu, bit5=Fri

### 21d. Notification Filter — Variable-Length Entries with Sender Name

Filter entries can include a **SENDER_NAME field (0x02)** for per-contact filtering.
This makes entries variable-length.

**Entry format:**
```
[packetLength(2 LE)] [field...]...
Field: [id(1)] [length(1)] [data(length)]

  0x04: PACKAGE_CRC    (4 bytes) — CRC32(packageName + '\0')
  0x80: GROUP_ID       (1 byte)  — always 0
  0x02: SENDER_NAME    (var)     — null-terminated contact name (OPTIONAL)
  0xC1: PRIORITY       (1 byte)  — always 0
  0xC2: HAND_MOVEMENT  (10 bytes)— hour°(2) min°(2) subeye(2) duration(2) subeye2(2)
  0xC4: DISPLAY_CONFIG (1 byte)  — always 0
  0xC3: VIBRATION      (1 byte)  — pattern (CALL=1, TEXT=2, DEFAULT=4, etc.)
```

Multiple entries can share the same package CRC with different senders, hand positions,
and vibration patterns. Example from capture:

| CRC | Sender | Hand | Vibe | Notes |
|-----|--------|------|------|-------|
| 0xB7590080 | `4 Cumnock Place, Dundee` | 359° | CALL(1) | Contact-specific |
| 0xB7590080 | `247HomeResc` | 240° | CALL(1) | Different contact, different position |
| 0xB7590080 | *(none)* | 60° | CALL(1) | Default for this app |
| 0x8B56BE06 | `4 Cumnock Place, Dundee` | 359° | TEXT(2) | Same contact, different app |
| 0x8B56BE06 | `247HomeResc` | 240° | TEXT(2) | Same contact, different app |
| 0xBA3DC156 | *(none)* | 300° | DEFAULT(4) | Google Calendar |
| 0x40C7ED7C | *(none)* | 90° | DEFAULT(4) | WhatsApp |

**CRC identification** (null-terminated CRC32):
- `0xBA3DC156` = `com.google.android.calendar`
- `0x40C7ED7C` = `com.whatsapp`
- `0xB7590080` = Phone/Dialer (package name unknown — Fossil app preset)
- `0x8B56BE06` = SMS/Messages (package name unknown — Fossil app preset)
- Others unmatched — Fossil app may use internal identifiers

**Vibration patterns observed:** Only CALL(1), TEXT(2), and DEFAULT(4) are used.
The Fossil app UI does not let users pick vibe patterns. Patterns are hardcoded:
CALL for phone, TEXT for SMS, DEFAULT for everything else.

### 21e. Notification Play — Type Field and CHR7 Delivery

The lbl=12 notification play file has a `type` byte that controls behavior:

| Type | Name | Vibration | Hand animation |
|------|------|-----------|----------------|
| 3 | NOTIFICATION | ✅ Yes | ✅ Yes |
| 5 | DISMISSAL | ❌ No | ✅ Return to normal |
| 7 | UPDATE/REPLAY | ❌ No | ✅ Yes |

`flags` byte: `0x02` = live, `0x06` = pending (queued while disconnected).

**Key discovery:** The official app writes notification play data to **3dda0007 (CHR7)**,
not 3dda0004 (DAT). The file-put sequence opens on CTL (3dda0003) with the notification
file handle (0x5D09, 0x5E09, ...), then the actual play data goes to CHR7.

Our implementation writes to 3dda0004 via the standard `FilePutRequest` — this appears to
work (vibration triggers), but the official app's use of 3dda0007 for notification payloads
suggests there may be a reason for the separate characteristic (perhaps better delivery
guarantees or different buffering).

Notification file handles increment: 0x5D09, 0x5E09, 0x5F09, 0x6009, 0x6109, ...

### 21f. Full DeviceConfigKey Map

From decompiled `DeviceConfigKey.java`:

| ID | Key | Type | Notes |
|----|-----|------|-------|
| 1 | BIOMETRIC_PROFILE | 7 bytes | Age, height, weight, gender |
| 2 | DAILY_STEP | int(4) | Current step count |
| 3 | DAILY_STEP_GOAL | int(4) | Step goal |
| 4 | DAILY_CALORIE | int(4) | Current calories |
| 5 | DAILY_CALORIE_GOAL | int(4) | Calorie goal |
| 6 | DAILY_TOTAL_ACTIVE_MIN | short(2) | Current active minutes |
| 7 | DAILY_ACTIVE_MIN_GOAL | short(2) | Active minute goal |
| 8 | DAILY_DISTANCE | — | Daily distance |
| 9 | INACTIVE_NUDGE | 6 bytes | fromH, fromM, toH, toM, minutes, enabled |
| 10 | VIBE_STRENGTH | byte(1) | 0-100 (official app default: 75) |
| 11 | DO_NOT_DISTURB | — | DND schedule |
| 12 | TIME | 8 bytes | epoch(4) + millis(2) + offset(2) |
| 13 | BATTERY | 3 bytes | voltage(2) + percent(1) — read-only |
| 14 | HEART_RATE_MODE | byte(1) | HR sensor mode |
| 15 | DAILY_SLEEP | — | Sleep data |
| 16 | DISPLAY_UNIT | int(4) | Metric/imperial |
| 17 | SECOND_TIMEZONE_OFFSET | short(2) | Minutes from UTC. 1024=disabled. Range: [-720, 840] |
| 18 | CURRENT_HEART_RATE | — | Current HR reading |
| 20 | AUTO_WORKOUT_DETECTION | 30 bytes | Running/biking/walking/rowing detection |
| 21 | CYCLING_CADENCE | — | Cycling cadence |
| 22 | DAILY_SLEEP_GOAL | — | Sleep goal |
| 23 | DAILY_TASK_TRACKING_GOAL | — | Task goal |
| 24 | DAILY_TASK_TRACKING_VALUE | — | Task value |

---

## 22. Multi-Entry Button Config (Mode Toggle) — Binary Format & Testing (2026-05-21)

**Date:** 2026-05-21

**Source:** BLE capture analysis (bugreport5, bugreport6) + real-hardware testing on
Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

### Button config binary format (corrected)

The format documented in #19 had an error: each header entry is followed by a null byte.
Corrected format:

```
[01 00 00]          version (3 bytes)
[buttonCount]       1 byte
For each button:
  [buttonIndex]     0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM
  [entryCount]      number of function entries
  For each entry:
    [header(4)]     [type, variant, appId_lo, appId_hi]
    [0x00]          null byte PER ENTRY (not just one at the end!)
[payloadCount]      1 byte (one per button×entry, NOT deduplicated)
For each payload:
  [payloadData]     variable length (byte[5] = total payload length)
[customizationCount] 1 byte (one per button×entry)
For each customization:
  [header(4)]       same 4-byte header as the corresponding entry
  [0a 00 01 02 01 00]  constant 6-byte suffix
[CRC32]             4 bytes LE (over all preceding bytes)
```

**Key corrections from #19/#21b:**
1. **Null byte per entry** — each `header(4)` is followed by `0x00`. ConfigFileBuilder
   puts 1 entry per button so the difference is invisible there. Multi-entry buttons need
   a null after each header, not just one at the end.
2. **Payloads are NOT deduplicated** — the official app sends one payload per button×entry
   even when two buttons use the same function (e.g. SECOND_TIMEZONE appears twice).
3. **Customization section is required** — one entry per button×entry, each is the 4-byte
   header + constant suffix `0a 00 01 02 01 00`. Sending customizationCount=0 causes
   error -107 (0x95) on file close.
4. **DATE has two variants** — standalone DATE uses variant=0x01 (header `01 01 14 00`,
   45-byte payload). Inside mode toggle, DATE uses variant=0x02 (header `01 02 14 00`,
   52-byte payload, different internal structure).

### Captured payloads (from bugreport5 & bugreport6)

| Function | Header | Payload size | Variant | Source |
|----------|--------|-------------|---------|--------|
| SECOND_TIMEZONE | `01 01 16 00` | 47 bytes | STANDARD (0x01) | ConfigPayload enum |
| DATE (standalone) | `01 01 14 00` | 45 bytes | STANDARD (0x01) | ConfigPayload enum |
| DATE (in toggle) | `01 02 14 00` | 52 bytes | SEQUENCED (0x02) | bugreport5 t=169.6s |
| **ALARM (in toggle)** | `01 02 1a 00` | 54 bytes | SEQUENCED (0x02) | bugreport5 t=169.6s |
| STEP_GOAL_COMPLETION | `01 02 1c 00` | 60 bytes | 0x02 | ConfigPayload enum |
| LAST_NOTIFICATION | `01 01 18 00` | 47 bytes | STANDARD (0x01) | ConfigPayload enum |
| FORWARD_TO_PHONE | `01 01 0c 00` | 46 bytes | STANDARD (0x01) | ConfigPayload enum |
| GOAL_TRACKING | `01 01 04 00` | 33 bytes | STANDARD (0x01) | bugreport6 t=80.9s |

**CORRECTION (2026-05-22):** The entry previously labeled "STEP_GOAL_PROGRESS" (header
`01 02 1a 00`, appId 0x001a) is actually **ALARM (SEQUENCED)**. The MicroAppId table shows
appId 0x1a (26) = ALARM, declarationId 6658. AppId 0x1c (28) = PROGRESS. The official
Fossil app's mode toggle uses TZ + DATE + ALARM, matching DisplayMode enum values
2004/2003/2005. Sub-eye indicator C = ALARM (confirmed on hardware with alarm set).

### Sub-eye indicator positions (A, B, C)

The watch dial has three labeled positions (A, B, C) for the sub-eye hand.
Testing reveals these are **hardwired to specific display functions**, not to
entry position within the toggle list:

| Indicator | Function | Confirmed |
|-----------|----------|-----------|
| A | SECOND_TIMEZONE | ✅ Always points to A regardless of entry order |
| B | DATE | ✅ Always points to B regardless of entry order |
| C | **ALARM** | ✅ Points to C when alarm is set (home/zero if no alarm) |
| 0 (home) | STEP_GOAL_COMPLETION, LAST_NOTIFICATION, GOAL_TRACKING | ✅ All point to sub-eye home/zero or are skipped entirely |

### Multi-entry toggle testing results

| Test config (TOP entries) | Result |
|--------------------------|--------|
| `mode_toggle` (TZ+DATE_v2+ALARM_SEQ) | ✅ 3 states: A→B→C (with alarm set), cycles correctly |
| `second_timezone+date+last_notification` | ✅ 3 states: A→B→hands at 4:20 (notification pos) |
| `last_notification+second_timezone+alarm_seq+date` | 3 states: hands 4:20→A→B (alarm skipped—no alarm set?) |
| `second_timezone+date+alarm_seq+step_goal` | 3 states: A→B→home (one entry skipped) |
| `second_timezone+date+alarm_seq+last_notification` | 3 states: A→B→hands 4:20 (alarm skipped—no alarm set?) |
| `alarm_seq+second_timezone+date` | 2 states: A→B (alarm skipped as first entry—no alarm set?) |
| `second_timezone+date+goal_tracking` (0x17=8) | 2 states: A→B→normal (goal_tracking skipped, small vibe on return) |
| `mode_toggle` with step-goal=20 + real steps (no alarm set) | 2 states: A→B→normal (ALARM skipped—no alarm!) |
| `mode_toggle` with alarm set | ✅ 3 states: A→B→C, cycles correctly |

**Observations:**
- The firmware accepts up to 4 entries without error, but **only displays 3 at most**
  before cycling back to normal time.
- **ALARM (SEQUENCED) gets skipped when no alarm is set.** Earlier tests that showed
  it being "skipped" were because no alarm was configured on the watch. With an alarm
  set, it works as the 3rd toggle entry and points sub-eye to indicator C.
- **Entry order doesn't affect A/B/C mapping** — SECOND_TIMEZONE always→A, DATE always→B,
  ALARM always→C.
- **LAST_NOTIFICATION moves the main hands** to the cached notification position (4:20 in
  our test) but sub-eye goes to home/zero, not C.
- The official mode_toggle combo (TZ + DATE_v2 + ALARM_SEQ) works reliably as a unit
  when an alarm is set.

### GOAL_TRACKING button function

**Header:** `01 01 04 00` (appId=0x0004, variant=STANDARD)
**Payload:** 33 bytes (captured from bugreport6)

```
01 00 01 01 04 21 00 0a 00 01 00 05 00 01 00 01
00 01 01 0b 00 8d 00 ff 93 00 01 01 00 9d e0 2b 40
```

**Behavior (hardware-verified):**
- Pressing the button **does NOT send a BLE event** — firmware-only, like stopwatch/date.
- Each press **increments an on-watch counter** visible in activity sync data.
- The app reads the count during sync and displays it in the goal tracking dashboard.
- No goal/value configs (0x0017/0x0018) were sent by the official app during the test
  session — the watch tracks presses autonomously.
- With no goal config set, pressing the button shows **no visible feedback** on the watch
  (no hand movement or sub-eye animation).

### Config 0x0017/0x0018: Task tracking goal & value (hardware-tested, 2026-05-22)

From `DeviceConfigKey.java`:
- **0x0017 (23)** = `DAILY_TASK_TRACKING_GOAL` — int32, target count (e.g. 8 for "8 glasses")
- **0x0018 (24)** = `DAILY_TASK_TRACKING_VALUE` — int32, current count

**Test results (Q Commuter HW.0.0, firmware HW0.0.2.9r.v3):**

| Test | Config sent | Button setup | Result |
|------|-------------|-------------|--------|
| Goal standalone | 0x17=8, 0x18=0 | TOP=goal_tracking | ❌ Small vibration on press, no hand/sub-eye movement |
| Goal with current | 0x17=8, 0x18=4 | TOP=goal_tracking | ❌ No change in behavior |
| Goal in toggle | 0x17=8 | TOP=tz+date+goal_tracking | ❌ Only 2 states (A→B→normal), goal_tracking skipped |

**Conclusion:** Config 0x0017/0x0018 does NOT enable visual feedback for GOAL_TRACKING
on this firmware. The button is a blind counter — it vibrates to confirm the press and
increments an internal counter, but never moves hands or the sub-eye. The configs are
likely only read during activity sync (the companion app displays the dashboard).

### BLE captures inventory

| File | Content |
|------|---------|
| `tmp/bugreport5/` | Full Fossil app: 2nd TZ, mode toggle, alarms, notif filters |
| `tmp/bugreport6/` | Button config: TOP=goal_tracking, MID=last_notification, BOT=mode_toggle |
| `tmp/bugreport7/` | Goal tracking "add" events + sync (no config 0x17/0x18 sent) |
| `tmp/bugreport8/` | Button presses on goal_tracking + last_notification (no BLE events) |

### ALARM in mode toggle requires alarm to be set (hardware-tested, 2026-05-22)

| Test | Setup | Alarm set? | Result |
|------|-------|------------|--------|
| mode_toggle, no alarm | step-goal=20, TOP=mode_toggle | No | 2 states: A→B→normal (ALARM skipped) |
| mode_toggle, alarm set | alarm 23:59, TOP=mode_toggle | Yes | ✅ 3 states: A→B→C, cycles correctly |

**Conclusion:** The 3rd mode_toggle entry (ALARM SEQUENCED, appId 0x1a) is **skipped
when no alarm is configured.** With an alarm set, it works correctly and points the
sub-eye to indicator C. This explains all previous "SGP gets skipped" observations —
the entry was actually ALARM, and we never had an alarm set during those tests.

**Notable difference:** With `tz+date+goal_tracking` (real goal_tracking, appId 0x04),
there IS a small vibration on the return step. GOAL_TRACKING is acknowledged by firmware
(vibration) but has no visual sub-eye component — it's always skipped in toggle.

### Sub-eye behavior (hardware-verified, 2026-05-22)

The sub-eye is a **passive step progress indicator** that always displays
`current_steps / step_goal` as a fraction of its range. No button assignment needed.

**Test results:**

| Config change | Sub-eye result | Notes |
|---------------|---------------|-------|
| `step-goal 10` (had ≥10 steps) | Moved to 100% (max position) | + celebration vibration! |
| `step-goal 99999` | Returned to zero | ~0% progress |
| `goal-config 4 4` (100% task progress) | No movement | 0x17/0x18 does NOT drive sub-eye |
| `goal-config 8 0` | No movement | Confirmed: sub-eye is step-only |

**Celebration vibration:** When step progress reaches 100% (steps ≥ goal), the watch
produces a short congratulatory vibration (shorter/weaker than alarm). This is automatic
firmware behavior triggered by config 0x0003 (step goal) being met.

**Button-activated positions (A, B, C) — ALL CONFIRMED:**

| Indicator | Function | Status |
|-----------|----------|--------|
| A | SECOND_TIMEZONE | ✅ Confirmed (always, regardless of entry order) |
| B | DATE | ✅ Confirmed (always, regardless of entry order) |
| C | **ALARM** | ✅ Confirmed! Requires at least one alarm set. Shows zero/home if no alarm. |
| (default) | Step progress | ✅ Passive, proportional to steps/goal |

When no button function is active, the sub-eye shows step progress. When a button
activates SECOND_TIMEZONE, DATE, or ALARM, the sub-eye temporarily moves to A, B,
or C respectively, then returns to showing step progress.

**Mode toggle (official app default):** SECOND_TIMEZONE → DATE → ALARM = A → B → C.
All three indicators are exercised by the default mode toggle configuration.

### Resolved experiments (2026-05-22)

1. ✅ **ALARM = indicator C** — confirmed! The 3rd mode_toggle entry (appId 0x1a) is
   ALARM SEQUENCED, not "step goal progress". Sub-eye points to C when alarm is set.
2. ✅ **ALARM skipped when no alarm set** — explains all previous "skipped" observations.
3. ✅ **Config 0x17/0x18 does NOT affect sub-eye** — task tracking is invisible on watch.
4. ✅ **Sub-eye is passive step progress** — always shows steps/goal, no button needed.
5. ✅ **Step goal celebration vibration** — watch vibrates when step goal is met.

### Further experiments to try

1. **ALARM STANDARD (appId 0x1a, variant 0x01, header `01 01 1a 00`)** — does it work
   as a standalone button (like DATE standalone)? Would show next alarm time on press.
2. **What does indicator C display?** — does sub-eye position encode the alarm time
   (e.g. proportional to hours until alarm)? Test with alarms at different times.
3. **4-entry toggle** — `second_timezone+date+alarm_toggle+last_notification` —
   confirm the 3-entry display limit vs. 4th being skipped.
