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

## 13. BlueZ Quirks Encountered

| Issue | Impact | Workaround |
|-------|--------|------------|
| bluetoothd SEGV crash | Lost agent registration, pairing broke | Restart bluetooth service, re-register agent |
| CCCD "Write not permitted" | Notifications don't enable via busctl | Use persistent bluetoothctl (finding #3) |
| No `MTU` property on Device1 | Can't read device-level MTU | Read from characteristic MTU property instead |
| `busctl` one-shot D-Bus connections | StartNotify immediately un-registers | Persistent process (finding #3) |
| `dbus-monitor` AccessDenied | Can't use BecomeMonitor API | Use `gdbus monitor` instead (falls back to eavesdropping) |
| Directed advertising after pairing | Device invisible to general scan | Connect using known MAC directly |
