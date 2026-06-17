# Fossil Q Hybrid CLI — Implementation Findings

Discoveries made during real-hardware testing with a Fossil Q Commuter (HW.0.0),
firmware HW0.0.2.9r.v3, on Linux (BlueZ 5.82).

---

## SCHEDULED / OPEN (do not forget)

### Bug 3 (OPEN, scheduled) — stop the NOTIFICATION_PLAY area wedging (0x86 = no buzz)

Symptom: after a while every buzz fails. Each buzz write on `3dda0003` gets `NOTIFY <- 83 .. 09 86 00`
(PUT-accept status `0x86 = NOT_ENOUGH_MEMORY`), the buzz bytes leave the phone but the watch refuses
the open, so the user feels nothing. A plain reconnect does NOT clear it; today the only confirmed
recovery is a full re-provision (or a watch-side reset).

ROOT CAUSE (confirmed by the 0x86 + the buzz code): every buzz does a FULL FilePUT of a fresh ~79-byte
play file (`playNotification*` -> `FilePutRequest(NOTIFICATION_PLAY, ...)`), and we NEVER delete the
old one. The watch reclaims a play file's space only when a PUT reaches VERIFY -> SUCCESS. PUTs that
get cut short (queue churn / reconnect / the next buzz interrupting) never VERIFY and leave an orphan
squatting on the 0x0900 area. After enough orphans the area is FULL and every new open returns 0x86.
The handle low-byte rotation (0900, 0901, ...) only SPREADS the files, it frees nothing, so the ring
still fills. A BLE re-pair does not run the watch's file-area GC; only a watch reset reclaims it.

WHY IT KEEPS HAPPENING: the de-dup RACE (Bug 1, fixed 2026-06-17) let one press fire a storm of
buzzes, multiplying the orphaned PUTs. The Bug 1 fix should sharply cut how often we wedge, but it is
NOT a full cure: even clean single buzzes still leak one file each and never delete the old one.

OFFICIAL-APP SOURCE CHECK (2026-06-17, tmp/FossilOfficialApp-deobf): the official app does the SAME
thing we do and NO delete/erase around a buzz. `FileHandleManager.getFileHandleToPut` (m11016b) for
`FileType.NOTIFICATION` ROTATES the low byte exactly like us: `index = (index + 1) % 255`, returning
the CURRENT index before increment (so 0x0900, 0x0901, ...). The play itself is
`SendAppNotificationPhase extends TransmitDataPhase` — a single file PUT to that rotated handle, with
NO EraseFile/Delete before or after. So the official app frees the area purely by (a) rotating and
(b) each PUT reliably reaching VERIFY -> SUCCESS, which is when the watch reclaims the slot. It never
deletes. CONCLUSION: there is no secret "delete/overwrite" move to copy. Our wedge is a RELIABILITY
problem, not a missing-command problem — orphaned PUTs that never VERIFY (storms + cut-short puts)
accumulate faster than rotation reclaims, until the area is full. A btsnoop capture is NO LONGER
needed to choose the approach (the source already answers it); it would only confirm timing.

FIX, in priority order:
1. PROPER FIX (still OPEN) — make every buzz PUT reliably reach VERIFY -> SUCCESS so the watch
   reclaims each rotated slot (the official app's actual mechanism), AND stop ever leaving a play
   PUT half-open. Concretely: (a) ensure the play PUT is not interrupted/abandoned by the NEXT buzz
   or by queue churn before VERIFY (serialize / drop-if-busy rather than open-and-abandon); (b)
   confirm our FilePut drives EOF -> VERIFY -> SUCCESS for the play file the same way it does for
   other files; (c) once an area is ALREADY wedged, reclaim it (see #2 — needs the RIGHT delete
   target or a watch-side erase of the whole NOTIFICATION area, since deleting the about-to-open
   index returns NOT_FOUND). No btsnoop required; the official source (above) is the reference.
2. AUTO-RECOVERY (IMPLEMENTED 2026-06-17, awaiting on-device confirmation of the ERASE variant) — on
   a play-PUT `0x86` for the NOTIFICATION_PLAY handle, `FilePutRawRequest` now ERASES the whole
   NOTIFICATION area then retries the open ONCE (`eraseAreaThenReopen`), bounded to one attempt,
   scoped to the 0x09xx major + status 0x86 only. The erase is a FileControl `DELETE_FILE(0x0B)` of
   the file-TYPE handle `0x09FF` (major 0x09, minor 0xFF = all minors), mirroring the official app's
   `CleanUpDevicePhase` / `EraseFileRequest` (which is the SAME 0x0B op, just targeting the area
   handle). The watch's erase-ack (0x8B for 0x09FF) is suppressed during recovery. Covered by
   `NotificationPlayMemoryRecoveryTest` (erase-area+reopen+succeed, and bounded-to-one).
   v1 (deleting the rotated index 0x09xx) was INERT — the watch returned NOT_FOUND because that index
   was never created. v2 (erasing the AREA handle 0x09FF) is ALSO inert on-device (2026-06-17,
   second test): `WRITE 0b ff 09` -> `NOTIFY 8b ff 09 83` (status 0x83 = NOT_FOUND), re-open still
   0x86. So 0x09FF is NOT the area-wipe handle on this firmware (HW0.0.2.9r.v3).

   DECISIVE NEW EVIDENCE (same log): DELETE_FILE(0x0B) DOES work on this watch — but only for a
   handle that EXISTS. Right before the buzzes, the activity-file read did
   `WRITE 0b 01 01` (delete activity file 0x0101) -> `NOTIFY 8b 01 01 00` (status 0x00 = SUCCESS).
   So the op is honoured; our two targets just don't exist: the fresh rotated index (never created)
   and 0x09FF (not a real minor). The orphans that DO exist are the PREVIOUSLY-USED minors
   (0x0900, 0x0901, ... up to the last opened index). FIX v3 (IMPLEMENTED 2026-06-17): on 0x86,
   `FilePutRawRequest.sweepPriorPlayMinorsThenReopen()` DELETE-sweeps a bounded window
   (`MEMORY_RECOVERY_SWEEP = 16`) of the play minors just BELOW the current handle's minor on major
   0x09 (wrapping mod 0xFF to match the rotation, skipping the current/never-created minor), then
   retries the open ONCE. Each existing orphan -> SUCCESS + reclaimed; absent ones -> NOT_FOUND
   harmlessly. Stayed inside `FilePutRawRequest` (derives the window from `this.handle`'s minor, no
   adapter history needed). Acks (0x8B) suppressed during recovery; still bounded to one attempt.
   Covered by `NotificationPlayMemoryRecoveryTest` (sweep targets prior minors, never 0x0900/0x09FF;
   bounded-to-one). NEXT on-device: wedge the watch, expect logcat
   `delete-sweep 16 prior play minors + retry open`, several `8b 09 .. 00` SUCCESS acks for the real
   orphans, then ideally `83 .. 09 00 00` (accept) = self-healed buzz. If 16 is too small (area
   still full), raise the window; if even a wide sweep can't reclaim it, fall back to prevention (#1).
   ON-DEVICE RESULT (2026-06-17, fresh APK): the prototype FIRES correctly but the experiment's
   answer is NO — deleting THIS handle does not reclaim the area. Per-buzz trace:
       WRITE  03 01 09 ...      PUT_FILE open 0x0901
       NOTIFY 83 01 09 86 00    reject 0x86 NOT_ENOUGH_MEMORY
       WRITE  0b 01 09          our FileDelete(0x0B) of 0x0901
       WRITE  03 01 09 ...      our re-open of 0x0901
       NOTIFY 8b 01 09 83       delete response status 0x83 = 131 = NOT_FOUND
       NOTIFY 83 01 09 86 00    re-open STILL 0x86
   The delete returns NOT_FOUND because we delete the FRESH rotated index (0x0901) that was never
   created — the orphans squat on OTHER indices. So FileDelete may be honoured, but we point it at
   the wrong (empty) slot. The area stays full; the buzz still fails. (Also confirmed Bug 1 is fixed:
   each of the 4 presses gave exactly ONE press + ONE advance, rotation 2->3->0 clean, no doubles.)

   IMPLICATIONS / NEXT (pick one, needs the btsnoop to choose well):
   - The rotating-handle scheme is the LEAK: each buzz opens a NEW index and the failed/never-
     VERIFIED ones pile up across 0x0900..0x09FE. Deleting the about-to-open index is useless.
   - Option A: STOP rotating — reuse a FIXED play handle (e.g. 0x0900) and DELETE-then-PUT it each
     buzz, so there is only ever ONE play file and it is overwritten in place. Needs to confirm the
     watch lets a fixed handle be deleted+reopened repeatedly (the official app may do exactly this).
   - Option B: on 0x86, delete a RANGE/the PRIOR indices (0x0900..lastUsed) to GC the orphans, then
     open. Heavier; only if A is not what the official app does.
   - DECIDE from the official-app btsnoop (tmp/bug3-buzz-capture-steps.md): does it rotate at all,
     or reuse+overwrite a fixed handle, and does it DELETE before the play PUT?
   The current prototype (delete the rotated index) is INERT on a wedged watch — keep it for now (it
   is harmless: one extra delete+reopen that fails the same way) or revert the rotated-index delete
   once the proper fix lands. Recovery stays NARROW; do NOT broaden to all non-success codes.

   SIDE FIX shipped with the prototype: `handlePutAccept` was calling `ResultCode.fromCode` with a
   sign-extended `byte`, so every firmware-internal status (0x80..0x8D, incl. 0x86) resolved to
   UNKNOWN instead of its real code. Now masked to unsigned (`value[3] & 0xFF`). Fail-fast behaviour
   is unchanged for genuinely-unrecoverable codes; only the classification/logging is now correct
   (and the 0x86 recovery branch can actually match).

LOG TELL for a recurrence (tags FossilQ-Svc AndroidBleTransport): `NOTIFY 3dda0003 <- 83 .. 09 86 00`
on a buzz = the play area is full/wedged.

See the deeper write-ups below: "Wedged play handle (0x86)" and the 2026-06-15 NOT_ENOUGH_MEMORY
correction.

### Bug 1 (FIXED 2026-06-17) — de-dup race let one press switch the mode twice + storm the buzz

The watch repeats one button press as ~2 byte-identical frames `01 08 <seq> ...` a few ms apart.
`FossilQAdapter.isDuplicateAsyncEvent` de-dups them on `(opcode,eventType,sequence)`, but its
check-then-update of `lastAsyncEventKey`/`lastAsyncEventAtMs` was NOT atomic. On Android,
`onCharacteristicChanged` is dispatched on a binder thread POOL, so the two repeats ran CONCURRENTLY,
both read the stale key, both passed the duplicate check, and one press advanced the rotation TWICE
(observed: `[3] TIMER` then `[0] MUSIC_PHONE` from a single press) while doubling the buzz PUTs that
wedge the play area (Bug 3). FIX: `isDuplicateAsyncEvent` is now `synchronized` so the
check-and-update is atomic and the second copy is reliably dropped. The old "single-threaded on the
BLE callback, no locking needed" comment was WRONG for this transport.

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
Write PUT_FILE(op 3) header to 3dda0003 → receive accept (resp 0x83)
Write data chunks to 3dda0004 (write-WITHOUT-response, paced)
Watch reports EOF_REACH(resp 0x88) / WAITING_REQUEST(resp 0x8A): sizeWritten + its CRC32
Write VERIFY_FILE(op 4) to 3dda0003 → receive VERIFY success (resp 0x84) == COMMITTED
```

> ⚠️ **CORRECTION (2026-05-30, see §30 below).** An earlier version of this section described the
> handshake as "data chunks → CRC confirmation (type 8) → file-close (type 4) → close
> confirmation". That was a **mislabel decoded by guessing**, and it caused a long Android buzz
> debugging saga. The op `8` frame is **EOF_REACH** (a progress/finish report carrying the watch's
> CRC), NOT the final ack; op `4` is **VERIFY_FILE**, a request we must SEND and then WAIT for its
> `0x84` success response (with retries) — it is the real "file committed" signal, NOT a
> fire-and-forget "close". The authoritative decode (from the official Fossil app) is in §30.

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

**UPDATE (2026-05-22):** Alarm read-back WORKS when using `FileLookupAndGetRequest` (type 0x02)
instead of `FileGetRequest` (type 0x01). The `INVALID_OPERATION_DATA` error was caused by the
file access method, not a firmware limitation. See FINDINGS.md #25.

~~`AlarmsGetRequest` (FileGetRequest on handle 0x0A00) returns `INVALID_OPERATION_DATA (1)` on
this firmware — the watch does not support reading back the alarm file. Alarms are write-only.
The companion app must track alarm state locally.~~

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

### 18a. The official app ACKs async events on 3dda0006 (2026-06-15, from disassembly)

**Source:** official-app disassembly under `tmp/FossilOfficialApp-deobf/sources` (authoritative,
same class of evidence as the play-file-rotation fix). The official app has a dedicated
`RequestId.SEND_ASYNC_EVENT_ACK` that writes an acknowledgement back to the watch after an async
event. This is the missing ack our `FossilQAdapter.handleButtonEvent` never sends (the suspected
root cause of the ~10-18x REQUEST-opcode re-send storm; see TODO "async-event re-send" and
WP-EVENT-ACK-CAPTURE-PLAN.md).

**Ack wire format (recovered from disassembly, NOT guessed):**

```
02 <eventType> <sequence> [optional payload]
```

- byte 0 = opcode, **ALWAYS `0x02` (NOTIFY)** for the ack.
- byte 1 = **eventType**, echoed from the received event (e.g. `0x08` MICRO_APP, `0x05` MUSIC,
  `0x04` APP_NOTIFICATION).
- byte 2 = **sequence**, echoed from the received event.
- bytes 3.. = payload, **default EMPTY**; only `TimeSyncEvent` and `HeartbeatEvent` override it. For
  our discrete-action event types the ack is therefore exactly **3 bytes: `02 <eventType> <seq>`**.

**Target characteristic: `3dda0006`** (`GattCharacteristic.CharacteristicId.ASYNC`).
**Write type: write-WITHOUT-response** ("command") - `SendAsyncEventAckRequest` extends
`SingleCommandWithoutNotificationRequest`. Consistent with the write-type table in §2.

**Code provenance:**
- `asyncevent/AsyncEvent.java` `m10799a()` (metadata name `getAckResponseData`): allocates
  `payload.length + 3` LE bytes, `put((byte)2)`, `put(eventType)`, `put(sequence)`, `put(payload)`.
- `logic/request/SendAsyncEventAckRequest.java` `mo11158j()`:
  `WriteCharacteristicCommand(CharacteristicId.ASYNC, asyncEvent.m10799a(), ...)`.
- `logic/phase/SendAsyncEventAckPhase.java`: runs that request (requires `ResourceType.ASYNC`).
- `logic/request/RequestId.java`: `SEND_ASYNC_EVENT_ACK`.
- `event/DeviceEventParser.java` `m11009a()`: parses the INCOMING frame as `rawData[0]`=opcode,
  `[1]`=eventType, `[2]`=sequence - confirming the §18 layout.
- `device/DeviceImplementation.java`: `sendAsyncEventAck$blesdk_productionRelease` (~line 7460)
  builds the phase; the async listener dispatches to it (~line 1171).

**STILL OPEN (capture-gated): the TRIGGER condition.** Whether the official app acks EVERY async
event or ONLY REQUEST-opcode (`0x01`) events is decided in
`DeviceImplementation$streamingAsyncEventListener$1.C18181.invokeSuspend`, which **jadx FAILED to
decompile** ("Method not decompiled, instruction units count: 978"). The static source cannot show
the gate. Confirm with a short btsnoop (press a REQUEST-opcode button, check exactly one
`02 <type> <seq>` ack follows; check whether `0x02` NOTIFY events are acked too). Until confirmed,
the conservative impl is to ack ONLY `opCode == 0x01` (REQUEST) events. Do NOT widen the ack to all
events, or change the de-dup, on the strength of the bytes alone - the trigger is the open question.

#### On-wire PARTIAL confirmation from existing btsnoop captures (2026-06-15)

Mined the 7 existing official-app captures under `tmp/bugreportN/FS/data/misc/bluetooth/logs/`
with `tshark` (filter `btatt.uuid128 == 3d:da:00:06:...696d`, handle `0x004e` = `3dda0006`). Three
captures (bugreport3/4/5) carried `3dda0006` traffic; the ack FORMAT is confirmed on the wire:

- **bugreport5** frame 2012 `NOTIF 01 0b 4d` (watch->phone, REQUEST, type=0x0b TIME_SYNC, seq=0x4d)
  -> frame 2013 (+15 ms) `WRITE 02 0b 4d fedf0e6a cc00 3c00` (phone->watch). Opcode `02` (NOTIFY),
  type+seq **echoed**.
- **bugreport4** frame 1773 `NOTIF 01 0b 13` -> frame 1774 (+24 ms) `WRITE 02 0b 13 7d950d6a ...`.
  Same echo pattern.

This confirms the disassembly format: `02 <eventType> <sequence> [payload]` on `3dda0006`, ~15-24 ms
after a REQUEST-opcode event. The `02 01 ...` JSON events (opcode `02` NOTIFY) in br3/4/5 get NO
echoing ack, consistent with "NOTIFY events are not acked".

**Caveats - these captures do NOT close the trigger gap, for three reasons:**
1. **No button-press events were captured.** Every `3dda0006` event in these logs is TIME_SYNC
   (`0x0b`), config (`0x0c`), background-sync (`0x06`), or JSON (`0x01`) - all connection-setup /
   sync traffic. There is ZERO `0x05` (MUSIC) or `0x08` (MICRO_APP) event in any capture, so we
   have NOT yet seen the official app ack the button events that actually cause our re-send storm.
2. **The gate is narrower than "ack all REQUESTs".** Some `0x01`-opcode events here went UNacked
   (bugreport3 `01 0b 0e`, bugreport4 `01 0c 15` and `01 06 47`). So "ack every REQUEST" is too
   broad; the real condition is per-event-type (the still-undecompiled gate).
3. **Write-type discrepancy to reconcile.** On the wire the ack used **Write Request (`0x12`,
   WITH response)** followed by a `0x13` Write Response - NOT write-without-response, even though
   `SendAsyncEventAckRequest` extends `SingleCommandWithoutNotificationRequest`. Confirm which the
   button-path ack uses before relying on the write type.

Also note: there is a SECOND phone->watch write shape on type `0x0b` that is NOT an ack - a
time-PUSH `02 0b 00 <ts:4> <ms:2> 03 3c 00` with seq `0x00` and an 8-byte payload (the phone
pushing time; matches `TimeSyncEvent.mo10797b()`'s timestamp+partial-second override). Do not mistake
it for an ack: its seq is always `00` and it does not follow/echo a specific event.

**Net:** the ack BYTES + characteristic + echo are confirmed; the BUTTON-path ack and the exact
trigger gate (and write type) still require a fresh capture WITH a button press (MICRO_APP `0x08` /
MUSIC `0x05`).

#### MUSIC-button ack confirmed on the wire - and it is NOT the generic shape (2026-06-15)

Fresh capture `tmp/bugreport-eventack.zip` (btsnoop
`tmp/eventack-extract/FS/data/misc/bluetooth/logs/btsnoop_hci.log`, presses ~12:57-12:58 BST,
official app paired). TOP button = Music control was pressed 3x. Handle `0x004e` = `3dda0006`
(verified against bugreport5 discovery: `btatt.handle==0x004e` -> uuid `3dda0006957f7d4a...696d`).
The MUSIC events AND their acks were captured:

```
t=122.809  NOTIF 01 05 09 02   (watch->phone: REQUEST, type=05 MUSIC, seq=09, action=02 TOGGLE)
t=122.939  WRITE 02 05 02 00 00 00 00 00   (phone->watch ack, +130 ms, Write-Request 0x12 + 0x13)
t=130.939  NOTIF 01 05 0a 02   (seq=0a, action=02)
t=130.982  WRITE 02 05 02 00 00 00 00 00   (+43 ms)
t=135.635  NOTIF 01 05 0b 04   (seq=0b, action=04 PREV)
t=135.678  WRITE 02 05 04 00 00 00 00 00   (+43 ms)
```

**CORRECTION to the assumed ack shape.** For MUSIC events the ack is NOT the generic
`02 <eventType> <sequence>`. It is a MUSIC RESPONSE frame:

```
02 05 <action> 00 00 00 00 00
^opcode 02 (NOTIFY)
   ^type 05 (MUSIC)
      ^action byte -- ECHOES THE EVENT'S ACTION (data byte), NOT the sequence
         ^5-byte payload (all zero here)
```

Proof it echoes ACTION not SEQUENCE: event `01 05 09 02` (seq=09, action=02) -> ack third byte `02`
(= action, not 09); event `01 05 0b 04` (seq=0b, action=04) -> ack third byte `04` (= action). This
is `MusicAsyncEvent`'s own `mo10797b()` override (cf. `RequestId.NOTIFY_MUSIC_EVENT`), not the
empty-payload base `AsyncEvent.m10799a()`. So **the ack layout is per-event-type**: the generic
`02 <type> <seq>` (empty payload) is only the BASE; MUSIC overrides byte 3 + adds a 5-byte body.

**Other confirmations from this capture:**
- **Write type = WITH response.** The ack is ATT Write Request (`0x12`) + Write Response (`0x13`),
  NOT write-without-response - despite `SendAsyncEventAckRequest` extending
  `SingleCommandWithoutNotificationRequest`. The wire is authoritative: use write-with-response.
- **NOTIFY (`0x02`) events are not acked.** The one `02 01 ...` JSON event got no reply.
- **One ack per press** (no storm) - because the ack is sent, the watch did NOT re-send. This
  supports the hypothesis that the missing ack is what causes our ~10-18x re-send.

**STILL NOT captured (in that first capture): the MICRO_APP (`0x08`) ack.** No `0x08` event
occurred there. Captured in the follow-up below.

#### MICRO_APP (0x08) is NOT acked on 3dda0006 - the app writes a BUTTON-CONFIG FILE back (2026-06-15)

Second capture `tmp/bugreport-eventack2.zip` (btsnoop
`tmp/eventack2-extract/FS/data/misc/bluetooth/logs/btsnoop_hci.log`, ~13:29 BST). User pressed the
MIDDLE button = **RING_PHONE** (rang the phone, then pressed again to stop), then volume-up
(BOTTOM) twice. All on handle `0x004e` = `3dda0006`:

```
t=31.705  NOTIF 01 08 22 01 01 0c 00 6d 01 20 b7 00   (REQUEST, MICRO_APP, seq=22, RING_PHONE)
t=34.358  NOTIF 01 08 23 01 01 0c 00 6f 01 20 ba 00   (REQUEST, MICRO_APP, seq=23)
t=37.509  NOTIF 01 05 0c 05                            (MUSIC, seq=0c, action=05 VOLUME_UP)
t=37.588  WRITE 02 05 05 00 00 00 00 00                (music ack, action 05 echoed)
t=39.774  NOTIF 01 05 0d 05                            (MUSIC, seq=0d, VOLUME_UP)
t=39.829  WRITE 02 05 05 00 00 00 00 00
```

**KEY RESULT: the two `0x08` RING_PHONE events got NO `02 08 ...` ack on `3dda0006`.** There is no
phone->watch write on `3dda0006` after either `0x08` event. So the ack model is per-event-type, and
MICRO_APP is NOT acked the way MUSIC is.

**Instead, the app responds to each RING_PHONE press with a BUTTON-CONFIG FILE write** (FileHandle
`SETTINGS_BUTTONS` = `0x06`; control `3dda0003`/`0x0045`, data `3dda0004`/`0x0048`):

```
t=31.866  WRITE 0x0045  03 0006 00000000 24000000 24000000   (PUT_FILE open, file 0x06, size 0x24)
t=32.613  WRITE 0x0048  00 0006 02 00000000 00 14000000 01 00 08 01010c006d0120b7 ff 05 0001 00 ...
                                                          ^^^^^^^^^^^^^^^^^^ echoes the event payload
t=32.638  WRITE 0x0045  04 0006                              (VERIFY_FILE)
```

The data frame embeds the event's own micro-app bytes (`01 01 0c 00 6d 01 20 b7`) plus a trailer
`ff 05 00 01 00`. This matches our existing note that **RING_PHONE uses software gesture detection**:
the watch reports the raw press, the PHONE decides the gesture and writes a buttons/micro-app file
back (not a lightweight 3dda0006 ack). VOLUME_UP, by contrast, is a MUSIC variant (action `0x05`)
and gets the normal `02 05 05 ...` music ack.

#### "MUSIC" (0x05) is really a GENERIC event channel; the meaning is OUR app's choice

The `0x05` event type is named MUSIC_EVENT after the official app's intent, but on the wire it is
just `[01][05][seq][action]` and the WATCH attaches no music semantics - it only reports "button
gave action N." What action N MEANS is entirely the companion app's choice. Two consequences:

1. **Button assignment vs event are separate layers.** At CONFIG time, MUSIC_CONTROL is a family of
   distinct `declarationId`s under one MicroAppId, and they are NOT interchangeable on a button:
   - `4614` MUSIC_CONTROL/STANDARD = the multi-gesture button: single/double/long produce THREE
     different action bytes (capture 1: `02`/`02`/`04` = TOGGLE/TOGGLE/PREV).
   - `4612` MUSIC_CONTROL/VOLUME_UP and `4613` VOLUME_DOWN = ONE-SHOT buttons: a single action each
     (`05` / `06`). Volume-up cannot also do volume-down - that is a different button/declarationId.
   - `4609`/`4610`/`4611` = PLAY_PAUSE / NEXT / PREVIOUS one-shots.
   So "music control" (3 gestures, one button) and "volume up/down" (one-shot, separate buttons) are
   different button assignments even though both emit `0x05` events. (See the `MICRO_APP_MAP`
   declarationId table in `FossilQAdapter.java`.)
2. **At EVENT time the watch only sends the action byte; we map it freely.** Our code already does
   this: the same `0x05` action is interpreted as a TIMER gesture or tracker waypoint, not music
   (see `FossilQAdapter` line ~2105 "play/pause/next/prev/volume + tracker/timer", and the TIMER-
   mode storm note where action `02` drove the TIMER SHORT gesture). So we have FULL freedom: a
   `0x05` action can mean music, timer, tracker, or anything we choose.

**Why this matters for the ack:** the ack `02 05 <action> 00 00 00 00 00` is a TRANSPORT-level
"received action N" - it echoes the type+action and is INDEPENDENT of what we do with the action.
So we ack every `0x05` REQUEST by echoing its action byte regardless of whether we treat it as
music / volume / timer / tracker. We do NOT need (and must not add) per-meaning ack variants; one
`0x05` ack rule covers all uses. The semantic mapping (music vs timer vs tracker) stays purely on
our side, after the ack.

**Implications for our re-send / ack work (re-frames the root cause):**
- For MUSIC events: replicate the `02 05 <action> 00 00 00 00 00` ack on `3dda0006`
  (write-with-response). Confirmed by both captures.
- For MICRO_APP `0x08` events: there is NO simple `3dda0006` ack to copy. The official app's
  response is a SETTINGS_BUTTONS file write. So the fix for the mode-switch/RING_PHONE `0x08`
  re-send is NOT "send an ack frame"; it is either (a) replicate the buttons-file write-back the
  app does, or (b) confirm whether our existing de-dup is the only practical mitigation for `0x08`.
  Do NOT wire a guessed `02 08 <seq>` ack - the capture shows the official app does not send one.
- The `0x08` data payload format `01 01 0c 00 <microAppId:2?> 01 20 <decl?>` is now captured for
  RING_PHONE; decode against `MicroAppEvent.java` if we pursue option (a).

**OPEN:** what does the buttons-file write-back actually achieve (does it stop the `0x08` re-send,
or is it RING_PHONE-specific gesture bookkeeping)? Our captures show only TWO `0x08` events here (no
10-18x storm), so on THIS firmware/config the `0x08` did not storm even without a 3dda0006 ack -
worth reconciling with the earlier on-device storm reports before deciding the `0x08` fix.

#### Third capture confirms VOLUME_DOWN + which buttons are internal (2026-06-15)

`tmp/bugreport-eventack3` (btsnoop
`tmp/bugreport-eventack3/FS/data/misc/bluetooth/logs/btsnoop_hci.log`, ~13:42 BST). Config: TOP =
goal tracking, MIDDLE = volume down, BOTTOM = take-a-picture. Pressed TOP several times, MIDDLE
twice, BOTTOM once+. Only the MIDDLE presses produced `3dda0006` events:

```
t=26.516  NOTIF 01 05 0e 06   ->  t=26.626 WRITE 02 05 06 00 00 00 00 00   (VOLUME_DOWN, action 06)
t=28.105  NOTIF 01 05 0f 06   ->  t=28.159 WRITE 02 05 06 00 00 00 00 00
```

Confirmations:
- **VOLUME_DOWN = action `0x06`**, acked `02 05 06 00*5` (write-with-response). Completes the volume
  pair (UP=`05`, DOWN=`06`); both share the `0x05` event type and the standard music ack.
- **GOAL_TRACKING (TOP) emitted nothing on `3dda0006`** - internal/firmware-only, as expected for a
  built-in tracker micro-app.
- **TAKE-A-PICTURE (BOTTOM) emitted nothing on `3dda0006`** either - the camera trigger is a
  HID/system path, not a Fossil micro-app event. (User noted it acts as a volume key inside the
  camera UI - that is the phone's camera app mapping a volume key, not a watch `0x05` event.)
  CONFIRMED 2026-06-15 (capture 4, §26a): it fires a HID consumer-control report (`08`/`00`) on
  the HID Report char `0x2a4d` (handle `0x007a`), NOT on `3dda0006`.
- **No `SETTINGS_BUTTONS` (0x06) write-back** accompanied the volume presses, UNLIKE RING_PHONE.
  So the buttons-file write-back is specific to software-gesture micro-apps (RING_PHONE), not a
  generic `0x05`/button response.
- Again **no storm**: two presses = two events, each acked once.

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

#### UPDATE (2026-06-15): the mode-toggle button-config payload WAS captured

The `bugreport-eventack` btsnoop
(`tmp/eventack-extract/FS/data/misc/bluetooth/logs/btsnoop_hci.log`) was taken while a watch
configured by the OFFICIAL app had a mode-toggle button set. During connect the app PUT the full
button-config file (FileHandle **`SETTINGS_BUTTONS` = major `0x06`**; control on `3dda0003` handle
`0x0045`, data on `3dda0004` handle `0x0048`). So item 1 above ("capture the payload") is DONE -
the bytes are on disk; what remains is a careful field-by-field DECODE, not a new capture.

The PUT data chunks (phone->watch on handle `0x0048`, each prefixed by the file-data-frame header
`00 00 06 <offset:2LE>`) were, in order (t=51.293..51.326 s):

```
chunk0 (off 0x0000): 02 04 00 00 01 00 00 03 10 03 01 06 12 00 00 01 06 12 00 00 01 06 12 00 00 20 05
                     01 02 16 00 00 01 02 18 00 00 01 02 1a 00 00 01 02 1e 00 00 01 02 14 00 00 30
                     03 01 01 10 00 00 01 01 10 00 00 01 01 10 00 00 0b 01 00 01 06 12 63 ... (cont)
chunk1 (off 0x...) : 01 12 63 00 00 00 01 00 06 00 01 01 01 03 00 05 01 1d 00 85 01 f6 00 00 85 01 42 02
                     00 85 01 43 03 00 85 01 44 04 00 08 01 1e 00 01 00 02 0d 00 8c 01 cd 00 ... (cont)
... (chunks at t=51.307, 51.313, 51.320 carry per-app entries 01 16 36..., 01 18 36..., 01 1a 36...,
    01 1e 34... (0x1e = 30 = TWENTY_FOUR_HOUR), 01 10 5e... (0x10 = 16))
last chunk (t=51.326): ...0b 01 06 12 00 0a 00 01 02 01 00 01 06 12 00 0a 00 01 02 01 00 01 06 12 00
                       0a 00 01 02 01 00 01 02 16 00 0a 00 01 02 01 00 01 02 18 00 0a 00 01 02 01 00
                       01 02 1a 00 0a ... 01 02 1e 00 0a ... 01 02 14 00 0a ... 01 01 10 00 0a ...
                       01 01 10 00 0a ... 01 01 10 00 0a 00 01 02 01 00 <crc>
```

(Full untruncated bytes: re-run
`tshark -r <btsnoop> -Y 'btatt.handle==0x0048 && (btatt.opcode==0x52||btatt.opcode==0x12) && frame.time_relative>=51 && frame.time_relative<=51.4' -T fields -e btatt.value`.
The btsnoop itself stays under gitignored `tmp/`.)

**What is already legible (NOT a full decode - bytes preserved above so the decode is verifiable):**
- The file is the standard `SETTINGS_BUTTONS` (0x06) microapp config, same family as
  `ButtonConfigBuilder` entries (header pattern `01 <variant> <appId> 00`).
- It contains a `TWENTY_FOUR_HOUR` entry: `01 1e ...` (appId `0x1e` = 30), confirming our
  by-analogy `ButtonConfigBuilder.TWENTY_FOUR_HOUR_*` targets the right appId.
- The mode-toggle button appears as a **cycle/sequence list** keyed `0b 01 06 12 ...` enumerating
  several display modes in order: `06 12` (music?), `02 16`, `02 18`, `02 1a`, `02 1e` (24h),
  `02 14`, `01 10` - i.e. one button that steps the sub-eye through these modes. This is the
  `TOGGLE_MODE` payload we lacked.

**STILL TODO (decode, not capture):** map each `<group><appId>` pair in the toggle list to its
mode name, confirm the per-entry length bytes (`0x36`/`0x34`/`0x5e`), verify the data-frame
offset/reassembly, and reconcile against `ButtonConfigBuilder` so we can EMIT a mode-toggle entry.
Do NOT wire a mode-toggle builder from the partial decode above until the field boundaries are
confirmed against the full reassembled file. The raw bytes are the authoritative source.

### Functions in the official Fossil app vs our CLI

| Fossil app | Our CLI | Status |
|-----------|---------|--------|
| Date | `date` | ✅ Working |
| Goal tracking | `step_goal` | ✅ Working |
| Mode toggle | — | 🟡 Payload CAPTURED (2026-06-15, see above); decode + builder pending |
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

The watch dial has labeled positions for the sub-eye hand. The number of labeled
positions varies by watch model:

**Q Commuter (HW.0.0) — 3-position dial: A, B, C**

| Indicator | Function | Confirmed |
|-----------|----------|-----------|
| A | SECOND_TIMEZONE | ✅ Always points to A regardless of entry order |
| B | DATE | ✅ Always points to B regardless of entry order |
| C | **ALARM** | ✅ Points to C when alarm is set (home/zero if no alarm) |
| 0 (home) | STEP_GOAL_COMPLETION, LAST_NOTIFICATION, GOAL_TRACKING | ✅ All point to sub-eye home/zero or are skipped entirely |

**Other models (e.g. Q Activist) — 5-position dial: Alert, Time 2, Alarm, Date, 24HR**

| Dial label | Function | Maps to Q Commuter |
|------------|----------|--------------------|
| Time 2 | SECOND_TIMEZONE (appId 0x16) | = A |
| Date | DATE (appId 0x14) | = B |
| Alarm | ALARM (appId 0x1A) | = C |
| Alert | ALERT / LAST_NOTIFICATION (appId 0x18) | No labeled position on Q Commuter |
| 24HR | TWENTY_FOUR_HOUR (appId 0x1E) | No labeled position on Q Commuter |

**Key discovery:** ALERT and LAST_NOTIFICATION are the **same firmware app** (appId
0x18). LAST_NOTIFICATION header = `01 01 18 00`, ALERT declarationId 6145 = 0x1801 →
both decode to appId 0x18. GadgetBridge calls it "LAST_NOTIFICATION"; the official
app dial labels it "Alert". On 5-position dials, it has a dedicated sub-eye position.

**TWENTY_FOUR_HOUR** (appId 0x1E): the SEQUENCED variant is now CAPTURED + corrected (see
§28 "Captured SEQUENCED payload"); it uses display position `B0 07 00`. STANDARD still uncaptured.
Likely shows the current hour in 24h format on the labeled "24HR" sub-eye of 5-position watches.

Positions are **hardwired to specific display functions**, not to
entry position within the toggle list:

### Multi-entry toggle testing results

| Test config (TOP entries) | Result |
|--------------------------|--------|
| `mode_toggle` (TZ+DATE_v2+ALARM_SEQ) | ✅ 3 states: A→B→C (with alarm set), cycles correctly |
| `second_timezone+date+last_notification` | ✅ 3 states: A→B→hands at 4:20 (notification pos) |
| `last_notification+second_timezone+alarm_seq+date` | 3 states: hands 4:20→A→B (alarm skipped—no alarm set?) |
| `second_timezone+date+alarm_seq+step_goal` | 3 states: A→B→home (one entry skipped) |
| `second_timezone+date+alarm_seq+last_notification` | 3 states: A→B→hands 4:20 (alarm skipped—no alarm set?) |
| `alarm_seq+second_timezone+date` | 2 states: A→B (alarm skipped as first entry—no alarm set?) |
| `second_timezone+date+goal_tracking` (0x17=8) | 2 states: A→B→normal + error vibe (goal_tracking skipped) |
| `second_timezone+goal_tracking+date` | 1 state: A→normal + error vibe (goal_tracking breaks chain, date never shows) |
| `mode_toggle` with no alarm set | 2 states: A→B→normal (ALARM skipped—no alarm!) |
| `mode_toggle` with alarm set | ✅ 3 states: A→B→C, cycles correctly |
| `tz+date+step_goal` (step-goal=10, ≥10 steps) | ✅ 3 states: A→B→sub-eye 100% |
| `tz+date+alarm+step_goal` (alarm set, step-goal=10) | ✅ 4 states: A→B→C→sub-eye 100% |
| `tz+date+alarm+step_goal+last_notification` (all data present) | ✅ **5 states**: A→B→C→sub-eye step%→hands to notif pos |
| `tz+date+alarm+last_notification` (no cached notif) | 3 states: A→B→C (last_notification skipped) |
| `tz+date+last_notification+alarm` (no cached notif) | 3 states: A→B→C (last_notification skipped) |

**Observations:**
- **No fixed entry limit.** The firmware displays all entries that have data. Entries
  with no data (no alarm, no notification, 0% step progress) are silently skipped.
  Earlier tests appeared to show a "3-entry limit" because entries lacked required data.
- **5-entry toggle confirmed working** (2026-05-22): TZ + DATE + ALARM + STEP_GOAL +
  LAST_NOTIFICATION → all 5 displayed when each had data to show.
- **Entries without data are skipped:** ALARM (no alarm set), LAST_NOTIFICATION (no
  cached notification), STEP_GOAL_COMPLETION (0% progress) — all silently omitted.
- **GOAL_TRACKING (appId 0x04) breaks the toggle chain** — produces error vibration
  and aborts remaining entries. Not usable in toggle.
- **Entry order doesn't affect A/B/C mapping** — SECOND_TIMEZONE always→A, DATE always→B,
  ALARM always→C.
- **LAST_NOTIFICATION moves the main hands** to the cached notification position (3:15 in
  our test) but sub-eye goes to home/zero.
- **STEP_GOAL_COMPLETION moves sub-eye** to show step progress (proportional), main hands
  stay at previous position.

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

### Toggle entries: skip behavior and data requirements (hardware-tested, 2026-05-22)

Toggle entries are **silently skipped** when they have no data to display.
This is not a firmware limit on entry count — all entries with data are shown.

| Entry | Requires | Skip behavior when no data |
|-------|----------|---------------------------|
| SECOND_TIMEZONE | Second TZ configured (config 0x0011) | Shows home/zero if disabled (1024) |
| DATE | Nothing | Always works |
| ALARM (SEQUENCED) | At least one alarm set | Silently skipped |
| STEP_GOAL_COMPLETION | Steps > 0 toward goal | Silently skipped at 0% |
| LAST_NOTIFICATION | Cached notification | Silently skipped if none |
| GOAL_TRACKING | N/A | **Error: produces vibration and breaks toggle chain** |

**GOAL_TRACKING (appId 0x04) is not usable in toggle.** When placed in the middle
of a toggle sequence (e.g. `tz+goal_tracking+date`), it produces an error vibration
and aborts — subsequent entries (date) never display. As a standalone button it only
vibrates (blind counter), and config 0x17/0x18 does not help. Changing the variant
byte to 0x02 (SEQUENCED) with recomputed CRC also fails — the internal payload
structure is fundamentally incompatible with toggle mode. GOAL_TRACKING's payload
lacks the `B0 XX 00` display-mode blocks and `08 01 50` section that toggle-compatible
entries share.

**5-entry toggle confirmed:** TZ + DATE + ALARM + STEP_GOAL + LAST_NOTIFICATION all
display when their data requirements are met. No firmware entry count limit observed.

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

1. ✅ **ALARM = indicator C** — confirmed! The mode_toggle entry (appId 0x1a) is
   ALARM SEQUENCED, not "step goal progress". Sub-eye points to C when alarm is set.
2. ✅ **Entries skipped when no data** — ALARM (no alarm), LAST_NOTIFICATION (no notif),
   STEP_GOAL_COMPLETION (0% progress) are all silently skipped. Not an entry count limit.
3. ✅ **5-entry toggle works** — TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIFICATION all display.
4. ✅ **Config 0x17/0x18 does NOT affect sub-eye** — task tracking is invisible on watch.
5. ✅ **Sub-eye is passive step progress** — always shows steps/goal, no button needed.
6. ✅ **Step goal celebration vibration** — watch vibrates when step goal is met.
7. ✅ **GOAL_TRACKING breaks toggle chain** — error vibration, aborts remaining entries.
8. ✅ **STEP_GOAL_COMPLETION works in toggle** — sub-eye shows step %, hands stay.

---

## 23. Notification Vibration Patterns — Hardware Test Results (2026-05-22)

**Date:** 2026-05-22

**Source:** CLI `notify-test` command on Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

The notification filter's vibration field (0xC3) accepts byte values 0-9, corresponding
to `NotificationVibePattern` in the official Fossil app. All 10 patterns tested.

### Results

| Pattern | Byte | Name              | Actual vibration                        |
|---------|------|-------------------|-----------------------------------------|
| 0       | 0x00 | AUTO              | No vibration (silent, hands move only)  |
| 1       | 0x01 | CALL              | Triple vibration                        |
| 2       | 0x02 | TEXT              | Double vibration                        |
| 3       | 0x03 | EMAIL             | Single vibration                        |
| 4       | 0x04 | DEFAULT_OTHER_APPS| Single vibration (same as EMAIL/3)      |
| 5       | 0x05 | ONE_SHORT_VIBE    | Strong single vibration                 |
| 6       | 0x06 | TWO_SHORT_VIBES   | Strong double vibration                 |
| 7       | 0x07 | THREE_SHORT_VIBES | Strong triple vibration                 |
| 8       | 0x08 | ONE_LONG_VIBE     | Long vibration                          |
| 9       | 0x09 | NO_VIBE           | No vibration (silent, hands move only)  |

### Observations

- **Patterns 3 and 4 are identical** — EMAIL and DEFAULT produce the same single vibration.
- **Patterns 5-7 are "strong" versions** of 3/2/1 (single/double/triple). The difference
  is noticeable — the strong variants have more motor intensity.
- **Pattern 8 (ONE_LONG) is distinct** — a sustained single vibration, noticeably longer
  than the short patterns.
- **Patterns 0 (AUTO) and 9 (NO_VIBE) are both silent** — hands animate to the filter
  position but no motor vibration occurs.
- The official Fossil app only uses patterns 1 (CALL), 2 (TEXT), and 4 (DEFAULT).
  Patterns 5-8 (strong/long variants) are fully functional on HW.0.0 firmware.

### Timing constraint

A notification play file is **ignored if the hands haven't returned** from the previous
notification. The hand animation (move to filter position → hold → return) takes
approximately 10 seconds. A minimum **12-second gap** between notifications is required
for reliable delivery. The `notify-test` command uses 12s gap by default.

### CLI usage

```bash
# Send notification with specific vibe pattern
fossil-q -d <MAC> notify SINGLE_SHORT --vibe call        # triple vibe
fossil-q -d <MAC> notify SINGLE_SHORT --vibe 5            # strong single
fossil-q -d <MAC> notify SINGLE_SHORT -v one_long         # long vibe
fossil-q -d <MAC> notify SINGLE_SHORT -v no_vibe          # silent (hands only)

# Test all patterns sequentially (12s gap)
fossil-q -d <MAC> notify-test
fossil-q -d <MAC> notify-test --from 5 --to 8             # only strong/long
fossil-q -d <MAC> notify-test --gap 15                    # 15s gap
```

---

## 24. Notification Hand Positions — Hardware Test Results (2026-05-22)

**Date:** 2026-05-22

**Source:** CLI `notify` and `position-test` commands on Fossil Q Commuter (HW.0.0),
firmware HW0.0.2.9r.v3.

The notification filter's HAND_MOVEMENT field (0xC2) controls where both hands move
when a notification is triggered. The official Fossil app assigns different positions
per app (see FINDINGS.md #17 & #21d). We now support configurable hand positions.

### How it works

The notification filter contains a HAND_MOVEMENT field with:
- `hourDeg` (2 bytes LE): hour hand position in degrees (0-359)
- `minDeg` (2 bytes LE): minute hand position in degrees (0-359)
- `subEye` (2 bytes LE): sub-eye position (-1 = no move)
- `duration` (2 bytes LE): how long to hold position (10000ms default)
- `subEye2` (2 bytes LE): device default (-2)

Both hands move independently to their specified positions.

### Test results

All positions tested successfully with single vibration (DEFAULT pattern):

| Position | Hour° | Min° | Hands moved to | Notes |
|----------|-------|------|----------------|-------|
| 3:00 | 90 | 90 | ✅ Both at 3 o'clock | Default position |
| 6:00 | 180 | 180 | ✅ Both at 6 o'clock | |
| 9:00 | 270 | 270 | ✅ Both at 9 o'clock | |
| 12:00 | 0 | 0 | ✅ Both at 12 o'clock | |
| 4:00/8:00 | 120 | 240 | ✅ Hour at 4, minute at 8 | Independent hand positioning |
| 2:00/10:00 | 60 | 300 | ✅ Hour at 2, minute at 10 | |
| 6:00/12:00 | 180 | 0 | ✅ Hour at 6, minute at 12 | |
| 11:00/1:00 | 330 | 30 | ✅ Hour at 11, minute at 1 | |

**Key observations:**
- Both hands move **independently** — you can have hour at one position and minute at another
- After the duration (10s), hands smoothly return to showing the current time
- Minimum 12s gap between notifications still applies (hands must return first)
- Combining with vibration patterns works perfectly (e.g. CALL triple vibe + 9:00 position)
- 0° = 12 o'clock, 90° = 3 o'clock, 180° = 6 o'clock, 270° = 9 o'clock

### Official Fossil app position mapping (from captures)

| Degrees | Clock position | Official app usage |
|---------|---------------|--------------------|
| 30°/30° | 1:00 | Unknown app |
| 60°/60° | 2:00 | Phone, SMS |
| 90°/90° | 3:00 | WhatsApp (and our default) |
| 120°/120° | 4:00 | Unknown app |
| 240°/240° | 8:00 | Per-contact ("247HomeResc") |
| 300°/300° | 10:00 | Google Calendar |
| 330°/330° | 11:00 | Unknown app |
| 359°/359° | ~12:00 | Catch-all (bundleId.all) |

### CLI usage

```bash
# Notification with custom hand position (degrees)
fossil-q -d <MAC> notify SINGLE_SHORT --vibe call --position 6:00
fossil-q -d <MAC> notify SINGLE_SHORT --vibe text --position 120/240  # hour=4, min=8
fossil-q -d <MAC> notify SINGLE_SHORT -v long -p 270               # both at 9 o'clock

# Position presets (matching official Fossil app)
fossil-q -d <MAC> notify SINGLE_SHORT --vibe call --position phone     # 60°/60° (2:00)
fossil-q -d <MAC> notify SINGLE_SHORT --vibe text --position sms       # 60°/60° (2:00)
fossil-q -d <MAC> notify SINGLE_SHORT --position whatsapp              # 90°/90° (3:00)
fossil-q -d <MAC> notify SINGLE_SHORT --position email                 # 120°/120° (4:00)
fossil-q -d <MAC> notify SINGLE_SHORT --position calendar              # 300°/300° (10:00)

# Position-only notification (DEFAULT vibe, custom position)
fossil-q -d <MAC> notify SINGLE_SHORT --position 9:00

# Test all clock positions (1:00 through 12:00)
fossil-q -d <MAC> position-test
fossil-q -d <MAC> position-test --from 3 --to 6            # subset
fossil-q -d <MAC> position-test --vibe call                # with triple vibe
fossil-q -d <MAC> position-test --positions "60/300,180/0,330/30"  # custom list
```

---

### Further experiments to try

1. **ALARM STANDARD (appId 0x1a, variant 0x01, header `01 01 1a 00`)** — does it work
   as a standalone button (like DATE standalone)? Would show next alarm time on press.
2. **What does indicator C display?** — does sub-eye position on C encode the alarm time
   (e.g. proportional to hours until alarm)? Test with alarms at different times.
3. **6+ entries** — is there an upper limit? Could add STOPWATCH or other functions.
4. **ALERT (appId 0x18, declarationId 6145/6146)** — this IS LAST_NOTIFICATION.
   ALERT and LAST_NOTIFICATION share appId 0x18 (header `01 01 18 00`, declarationId
   6145 = 0x1801). Same firmware app, different naming (GadgetBridge = "LAST_NOTIFICATION",
   official app/dial = "Alert"). On watches with 5-position dials (e.g. Q Activist),
   ALERT has its own labeled sub-eye position. On Q Commuter (3-position: A/B/C),
   LAST_NOTIFICATION has no labeled position (sub-eye goes to home/zero, hands move
   to notification position). **Resolved — no further testing needed.**
5. **TWENTY_FOUR_HOUR (appId 0x1E, declarationId 7681/7682)** — SEQUENCED variant CAPTURED
   from the official app and corrected 2026-06-15 (display position `B0 07 00`, len 0x34); the
   old by-analogy guess was malformed, which is why the firmware skipped it. See §28. Has a
   labeled sub-eye position ("24HR") on 5-position dial watches. 24h is toggle-only (no
   standalone button), so the STANDARD variant is moot — the app never emits it.

---

## 25. Configuration Read-Back & File Access Methods (2026-05-22)

**Date:** 2026-05-22

**Source:** Real-hardware testing on Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

### Direct FileGet (type 0x01) vs FileLookup (type 0x02)

The Fossil protocol has two file access methods:

| Method | Start sequence | How it works |
|--------|---------------|-------------|
| FileGetRequest (type 0x01) | `[01] [minor] [major] [offset(4)] [size(4)]` | Direct get by full handle |
| FileLookupRequest (type 0x02) | `[02 FF] [majorHandle]` | Lookup by major, get resolved handle |

**GadgetBridge uses `FileGetRequest` (type 0x01)** for `ConfigurationGetRequest` and
`AlarmsGetRequest`. On HW.0.0 firmware, both return `INVALID_OPERATION_DATA (1)`.

**FileLookupAndGetRequest (type 0x02)** works for all file handles tested:
- Configuration (0x08) ✅
- Alarms (0x0A) ✅
- Activity (0x01) ✅ (was already using this method)

**Fix applied:** Switched `readConfig()` and `getAlarms()` from `FileGetRequest` to
`FileLookupAndGetRequest`. Both now work on real hardware.

### Configuration file contents (hardware-verified)

The config file (handle 0x0800) returns 84 bytes of TLV payload containing 12 entries.
Format: `[id(2 LE)] [length(1)] [data(length)]...`

| ID | Name | Length | Value (from test) |
|----|------|--------|-------------------|
| 0x0001 | BIOMETRIC_PROFILE | 7 | age=42, male, 172cm, 75kg |
| 0x0002 | DAILY_STEP | 4 | 36 steps |
| 0x0003 | DAILY_STEP_GOAL | 4 | 10000 steps |
| 0x0004 | DAILY_CALORIE | 4 | 1 cal |
| 0x0005 | DAILY_CALORIE_GOAL | 4 | 240 cal |
| 0x0006 | DAILY_TOTAL_ACTIVE_MIN | 2 | 0 min |
| 0x0007 | DAILY_ACTIVE_MIN_GOAL | 2 | 30 min |
| 0x0009 | INACTIVE_NUDGE | 6 | disabled (every 0 min, 00:00-00:00) |
| 0x000A | VIBE_STRENGTH | 1 | 100% |
| 0x000C | TIME | 8 | 2026-05-22T15:53:18 (UTC epoch, offset=+60 min) |
| 0x000D | BATTERY | 4 | 24% (voltage=2574 mV, CHARGING) |
| 0x0011 | SECOND_TIMEZONE_OFFSET | 2 | UTC+5.5 (330 min) |

**Notable:** Config IDs 0x08 (DAILY_DISTANCE), 0x0B (DO_NOT_DISTURB), 0x0E (HEART_RATE_MODE),
0x0F (DAILY_SLEEP), 0x10 (DISPLAY_UNIT), 0x14 (AUTO_WORKOUT_DETECTION), 0x17/0x18
(TASK_TRACKING) are absent — either not supported by this firmware or not configured.

### Biometric profile format (hardware-verified)

Config 0x0001 (BIOMETRIC_PROFILE), 7 bytes:
```
[age(1)] [gender(1)] [height_cm(2 LE)] [weight_kg(2 LE)] [unknown(1)]
```
- Gender: 1=male, 0=female
- Height/weight stored as 16-bit LE integers
- Unknown byte 6: always 0x00 (padding or unused field)

**Verified:** age=42 (`0x2A`), male (`0x01`), 172cm (`0xAC 0x00`), 75kg (`0x4B 0x00`).

### Battery config format correction

Config 0x0D (BATTERY) is **4 bytes** on this firmware, not 3 as in GadgetBridge's
`BatteryConfigItem`. The 4th byte is the charging state:
```
[voltage(2 LE)] [percent(1)] [state(1)]
```
State: 0=DISCHARGING, 1=CHARGING, 2=FULL.

GadgetBridge's `BatteryConfigItem` only parses 3 bytes, missing the state field.

### Alarm read-back now works

Previously documented as "not supported" (FINDINGS.md #12). The issue was the file
access method, not a firmware limitation. With `FileLookupAndGetRequest`, alarm
read-back works:
```
$ fossil-q alarm list
1 alarm(s):
  [1] 23:59  not repeating
```

### CLI usage

```bash
# Read all config entries
fossil-q -d <MAC> read-config

# With raw hex bytes
fossil-q -d <MAC> read-config --raw

# Read alarms (now works!)
fossil-q -d <MAC> alarm list
```

---

## 26. BLE HID Service (0x1812) — Dormant, Not Used by Watch Firmware (2026-05-22)

> **CORRECTED 2026-06-15: the HID service is NOT dormant. It DOES fire when a button is configured
> for a HID action.** See "26a. HID consumer-control DOES fire" below. The original
> "firmware never sends HID reports / ZERO consumer events" conclusion was an artifact of testing
> with buttons that had NO HID action assigned. The body below is kept for history; treat its
> "dormant/vestigial" framing as WRONG.

**Date:** 2026-05-22

**Source:** Direct GATT enumeration via BlueZ + gdbus monitor + BLE capture analysis
across 7 bugreport sessions (official Fossil app and our CLI) on Fossil Q Commuter
(HW.0.0), firmware HW0.0.2.9r.v3.

### Summary

The watch exposes a fully-structured BLE HID service with a valid Report Map descriptor
defining both a Keyboard report and a Consumer Control (media keys) report. However,
**the watch firmware never sends HID reports**. All button events are delivered exclusively
through the Fossil proprietary protocol on characteristic 3dda0006. The HID service
appears to be a vestigial/placeholder from Misfit's original firmware platform, shared
across multiple hybrid watch models but unused on the Q Commuter.

### Service structure

BLE Service UUID: `00001812-0000-1000-8000-00805f9b34fb` (Human Interface Device)

| Characteristic | UUID | Flags | Purpose |
|---------------|------|-------|---------|
| char0071 | 0x2A4A | read | HID Information |
| char0073 | 0x2A4B | read | Report Map |
| char0075 | 0x2A4D | read, notify | Report (Input, ID 1 = Keyboard) |
| char0079 | 0x2A4D | read, notify | Report (Input, ID 2 = Consumer Control) |
| char007d | 0x2A4C | write-without-response | HID Control Point |

Descriptors on Report characteristics:
- 0x2902 (CCCD) — enables notifications. Works correctly when bonded (Notifying=true).
- 0x2908 (Report Reference) — identifies report ID and type.

Not present: Protocol Mode (0x2A4E), Output Reports, Feature Reports.

### HID Information (0x2A4A)

```
Raw: 00 00 00 00
  bcdHID:        0x0000 (version 0.0 — unusual, should be 0x0111 for HID v1.11)
  bCountryCode:  0x00 (Not Localized)
  Flags:         0x00 (no RemoteWake, no NormallyConnectable)
```

The zero version number suggests this was never properly configured for production use.

### Report Reference Descriptors (0x2908)

| Characteristic | Report ID | Report Type | Decoded |
|---------------|-----------|-------------|----------|
| char0075 | 1 | 1 (Input) | Keyboard report |
| char0079 | 2 | 1 (Input) | Consumer Control report |

### Report Map (0x2A4B) — Full Decode

90-byte HID Report Descriptor:
```
05 01 09 07 a1 01 85 01 05 07 19 e0 29 e7 15 00
25 01 75 01 95 08 81 02 95 01 75 08 81 01 95 08
75 01 15 00 25 01 05 07 09 05 09 1a 09 4f 09 50
09 90 09 4b 09 4e 09 58 81 02 c0 05 0c 09 01 a1
01 85 02 15 00 25 01 95 08 75 01 09 b5 09 b6 09
cd 09 e9 09 ea 09 e2 81 62 c0
```

#### Report ID 1: Keyboard (Usage Page 0x07 = Keyboard/Keypad)

Application Collection: Generic Desktop → Keypad (0x07)

| Byte | Bits | Type | Content |
|------|------|------|---------|
| 0 | — | — | Report ID = 0x01 |
| 1 | 0-7 | Data, Variable, Absolute | Modifier keys (Ctrl/Shift/Alt/GUI, left+right) |
| 2 | 0-7 | Constant | Reserved/padding |
| 3 | 0-7 | Data, Variable, Absolute | 8 individual key bits (see below) |

Modifier key bits (byte 1):
```
  Bit 0: Left Control   (0xE0)
  Bit 1: Left Shift     (0xE1)
  Bit 2: Left Alt       (0xE2)
  Bit 3: Left GUI       (0xE3)
  Bit 4: Right Control  (0xE4)
  Bit 5: Right Shift    (0xE5)
  Bit 6: Right Alt      (0xE6)
  Bit 7: Right GUI      (0xE7)
```

Key bits (byte 3) — unusual selection for a watch:
```
  Bit 0: Keyboard B         (0x05)  — iOS camera shutter
  Bit 1: Keyboard W         (0x1A)  — unknown purpose
  Bit 2: Right Arrow        (0x4F)  — presentation/navigation
  Bit 3: Left Arrow         (0x50)  — presentation/navigation
  Bit 4: Keyboard LANG1     (0x90)  — Hangul/English toggle (Asian market)
  Bit 5: Page Up            (0x4B)  — e-reader/presentation
  Bit 6: Page Down          (0x4E)  — e-reader/presentation
  Bit 7: Keypad Enter       (0x58)  — confirmation
```

#### Report ID 2: Consumer Control (Usage Page 0x0C)

Application Collection: Consumer Control (0x01)

| Byte | Bits | Type | Content |
|------|------|------|---------|
| 0 | — | — | Report ID = 0x02 |
| 1 | 0-7 | Data, Variable, Absolute, No Preferred, Null State | Media key bits |

Media key bits (byte 1):
```
  Bit 0: Scan Next Track     (0xB5)
  Bit 1: Scan Previous Track (0xB6)
  Bit 2: Play/Pause          (0xCD)
  Bit 3: Volume Increment    (0xE9)
  Bit 4: Volume Decrement    (0xEA)
  Bit 5: Mute                (0xE2)
  Bits 6-7: (unused padding)
```

The Consumer Control report has `No Preferred State` and `Null State` flags (Input 0x62),
meaning all-zero = no keys pressed (null state). Standard for momentary media controls.

### Static report values (never change)

| Report | Read value | Meaning |
|--------|-----------|----------|
| ID 1 (Keyboard) | `0x00` | No keys pressed |
| ID 2 (Consumer) | `0x01` | Scan Next Track bit stuck high |

Report 2's stuck `0x01` value is likely an uninitialized default — it never changes
regardless of button presses or connection state. This confirms the firmware never
actively writes to these report characteristics.

### Test methodology

1. **Enabled HID notifications** on both Report characteristics via persistent bluetoothctl.
   Confirmed `Notifying = true` on both (CCCD writes succeed because device is bonded).

2. **Pressed all buttons** (TOP=MUSIC_CONTROL, MIDDLE=FORWARD_TO_PHONE, BOTTOM=STOPWATCH)
   with single press, double press, and long press gestures. Hardware-verified at 21:49 UTC.

3. **Monitored all GATT characteristic changes** via `gdbus monitor --system --dest org.bluez`
   for the full device path.

4. **Results (with timestamps):**
   ```
   21:49:47 — TOP single  → MUSIC_EVENT TOGGLE_PLAY_PAUSE  on char004d (3dda0006)
   21:49:51 — TOP double  → MUSIC_EVENT NEXT               on char004d (3dda0006)
   21:49:53 — TOP long    → MUSIC_EVENT PREVIOUS            on char004d (3dda0006)
   21:49:55 — MID single  → MICRO_APP RING_PHONE            on char004d (3dda0006)
   (BOTTOM = STOPWATCH — firmware-only, no BLE event expected or received)

   HID char0075 (keyboard): ZERO events
   HID char0079 (consumer): ZERO events
   ```

5. **Checked 7 BLE captures** (bugreport1-8) from the official Fossil app on Android.
   All show the same pattern: Android's Bluetooth stack discovers the HID service,
   reads Report Map/HID Info/Report References, writes CCCDs — but zero HID notifications
   are ever received across hundreds of seconds of active use with button presses.

### Why the HID service exists (likely explanation)

The Fossil Q Hybrid series is based on Misfit's wearable platform. Misfit's earlier
products (e.g. Misfit Flash Link) were advertised as BLE remotes that could control
music and take photos via standard HID. The HID service with its keyboard + consumer
control reports is almost certainly **shared firmware infrastructure** from those products.

The key selection in the keyboard report supports this theory:
- **Keyboard B (0x05)** — iOS uses volume keys (mapped to certain keyboard keys) as
  camera shutter triggers
- **Arrows + Page Up/Down** — presentation remote / e-reader control (Misfit Flash Link feature)
- **LANG1 (0x90)** — Korean market requirement (Hangul/English toggle)
- **Consumer Control media keys** — standard play/pause/next/previous/volume

On the Q Commuter (and likely all Fossil Q Hybrid models), the firmware uses the
proprietary Fossil protocol exclusively. The HID service structure remains in the
GATT server but the firmware never populates the reports.

### 26a. CORRECTION (2026-06-15): HID consumer-control DOES fire for HID-action buttons

Capture `tmp/bugreport-eventack4` directly contradicts the "never sends HID reports" conclusion.
With a button configured (in the official app) as **take-a-picture / volume** (which acts as a
volume key), pressing it produced HID consumer-control reports - NOT a `3dda0006` event:

```
handle 0x007a (UUID 0x2a4d = HID Report, the Consumer Control report), btatt opcode 0x1b NOTIFY:
  t=11.0754  08   (key down: consumer usage volume-up)
  t=11.0765  00   (key up)
  t=12.5747  08
  t=12.5756  00
  ... 4 press/release pairs total, matching the 4 button presses
```

(Handle->UUID confirmed from bugreport5 discovery: `0x007a` -> `0x2a4d` HID Report, under the
Consumer Control report reference `0x2a4c`/`0x2803` group.)

**So the corrected model is:**
- A button assigned a **Fossil micro-app** action (MUSIC_CONTROL, RING_PHONE, etc.) sends a
  **proprietary `3dda0006`** event (`0x05` / `0x08`).
- A button assigned a **HID action** (volume up/down, camera shutter via volume key) sends a
  **standard HID-over-GATT report** on the Consumer Control report char (`0x2a4d`, handle `0x007a`
  here), `08` = volume-up-down / `00` = release. The earlier captures saw ZERO HID events only
  because no button was assigned a HID action at the time.
- This explains the user observation that the "take a picture" button moves the phone volume: it
  IS a HID volume key; Android's camera app maps volume keys to the shutter.

**Why the earlier note in §18a ("take-a-picture emitted nothing on 3dda0006") was right but
misleading:** it emits nothing on the FOSSIL channel, but it DOES emit on the HID channel. Both
statements hold; the HID path was simply not being watched.

**Implication for us:** if we want to consume volume/camera buttons in our Linux/Android companion,
we read them from the **HID Report characteristic `0x2a4d`** (consumer control), not from
`3dda0006`. The watch firmware is NOT HID-dormant; it routes HID-action buttons through HID.

### 26b. How to SET the take-picture button (config payload captured 2026-06-15)

Capture `tmp/bugreport-eventack5` was taken WHILE setting TOP=volume-up, MIDDLE=volume-down,
BOTTOM=take-picture in the official app, so it contains the `SETTINGS_BUTTONS` (file `0x06`) PUT for
all three. On pressing them: TOP -> `01 05 10 05` (Fossil VOLUME_UP), MIDDLE -> `01 05 11 06`
(Fossil VOLUME_DOWN), BOTTOM -> HID `08`/`00` on `0x2a4d` (take-picture). Confirms the three are
distinct button actions.

**The take-picture button = the SELFIE micro-app, declarationId 4097 (`0x1001`), header `01 01 10`.**
Its config entry is **byte-identical to VOLUME_UP except the declarationId**. Diffing the captured
VOLUME_UP entry against the captured take-picture entry, the ONLY differing bytes are the
declarationId (header bytes 1-2): `04 12` (=`0x1204`=4612 VOLUME_UP) vs `01 10` (=`0x1001`=4097
SELFIE). The body is the same template:

```
captured VOLUME_UP    : 01 04 12 5e 0000 0001 0006 0001 0101 0300 05 011d00 8501f60000 85014202 00 85014303 00 85014804 00 08011e0001 00 020d008c01 e9 000193000101 00 030b008c01 e9 0000930001 040a008c0100 000101 00 fe0800930002 0100 <crc>
captured TAKE_PICTURE : 01 01 10 5e 0000 0001 0006 0001 0101 0300 05 011d00 8501f60000 85014202 00 85014303 00 85014804 00 08011e0001 00 020d008c01 e9 000193000101 00 030b008c01 e9 0000930001 040a008c0100 000101 00 fe0800930002 0100 <crc>
                          ^^ ^^ ^^ only the declarationId differs (04 12 -> 01 10)
```

**So we do NOT need a fresh capture to add take-picture** - build a `ConfigPayload.TAKE_PICTURE`
(or `SELFIE`) from the existing `VOLUME_UP` entry by swapping the declarationId to `01 01 10`
(header `01 01 10 00`) and RE-COMPUTING the trailing CRC (last 4 bytes; the file/entry uses the
same CRC32 the rest of the button config uses - confirm against `ButtonConfigBuilder`'s CRC helper,
do not copy VOLUME_UP's CRC). The per-button gesture/index map in the file header also showed the
assignment: `... 30 03 01 01 10 ...` = BOTTOM button (`0x30`) -> declId `01 10` (selfie).

**Also validated:** our existing `ConfigPayload.VOLUME_UP` (declId 4612) and `VOLUME_DOWN` (4613)
match the official app's bytes exactly (same body `8c01 e9` / `8c01 ea`, same trailer) - they were
correct, not guesses.

**STILL TODO (impl, not capture):** add the TAKE_PICTURE/SELFIE `ConfigPayload` entry + CLI keyword,
verify the CRC recompute against a round-trip, and (separately) wire READING the HID `0x2a4d` report
so our companion can act on a take-picture/volume press. The payload bytes are now known; only the
builder + CRC + read-path remain.

### 26c. The button-config entry carries the HID CONSUMER USAGE byte (`8c 01 <usage>`)

Diffing the three captured entries (eventack5) byte-for-byte:

```
off:    1  2        ...  51       64
VUP  : 04 12  ...  8c 01 e9  ...  8c 01 e9     (declId 4612, usage E9)
VDN  : 05 12  ...  8c 01 ea  ...  8c 01 ea     (declId 4613, usage EA)
TPIC : 01 10  ...  8c 01 e9  ...  8c 01 e9     (declId 4097, usage E9)
```

- VOLUME_UP vs VOLUME_DOWN differ at the declId low byte AND at offsets 51 & 64 (`e9`<->`ea`).
- VOLUME_UP vs TAKE_PICTURE differ ONLY in the declId; both keep usage `e9`.

The `8c 01 <usage>` field is the **HID Consumer Control usage** the button emits. The values match
exactly the usages the watch's HID Report Map (§26, Report ID 2) advertises:

| usage byte | HID Consumer usage | observed in |
|-----------|--------------------|-------------|
| `0xB5` | Scan Next Track | (advertised, not yet set) |
| `0xB6` | Scan Previous Track | (advertised, not yet set) |
| `0xCD` | Play/Pause | (advertised, not yet set) |
| `0xE9` | Volume Increment | VOLUME_UP, TAKE_PICTURE |
| `0xEA` | Volume Decrement | VOLUME_DOWN |
| `0xE2` | Mute | (advertised, not yet set) |

This explains why TAKE_PICTURE moves the phone volume UP (it emits usage `0xE9`, the same as
VOLUME_UP; Android's camera maps a volume key to the shutter). VOLUME_UP and TAKE_PICTURE are the
same HID key with different declIds (UI label/icon: SELFIE vs VOLUME_UP).

**Hypothesis (NOT yet confirmed):** changing the `8c 01 <usage>` byte to another advertised usage
(e.g. `b5` next-track, `cd` play/pause, `e2` mute) would make the button emit that HID key. The
menu is FIXED to what the Report Map declares - you cannot invent usages outside that list.

**UNCONFIRMED / must round-trip before relying on it:**
1. Does the firmware honor an arbitrary `(declId, usage)` combination, or does it validate the pair
   and reject/ignore a mismatch (e.g. declId=SELFIE + usage=`b5`)?
2. What does the declId carry independently of the usage (watch-face label/icon? dial behaviour?) -
   vol-up and take-picture differ only in declId yet behave the same on the HID wire.

**Safe experiment to settle it (reversible):** build one button entry from VOLUME_UP with the usage
byte set to `b5` (next track) - both offset 51 and 64 - recompute the CRC, PUT it to a spare button,
and watch `0x2a4d`: if the watch accepts the PUT and emits the next-track HID report on press, the
field is firmware-honored and we can expose arbitrary advertised usages. If it rejects/ignores it,
the usage is tied to the declId and we are limited to the official pairs. Worst case: re-set the
button in the official app. Do NOT ship a configurable-key feature until this round-trip is green.

### Could we make it work?

The HID service is **read-only from our perspective** — there are no Output or Feature
reports to write to, and no way to trigger the firmware to start sending HID reports.
The firmware would need to be modified to actually populate these reports on button press.

To use the watch as a standard BLE media controller without a custom app, we would need
to either:
1. **Act as a proxy** — our CLI receives MUSIC_EVENT on the Fossil characteristic and
   translates it to synthetic HID input events via `uinput` (Linux) or equivalent.
   This works but requires our software running.
2. **Custom firmware** — modify the watch firmware to send HID reports on button press.
   Not feasible without firmware source code / flash access.

The proxy approach (#1) is practical and would let the watch control any media player
on Linux (MPRIS), macOS, or Windows via synthetic HID events.

### Official Fossil app interaction with HID

The official Fossil app (decompiled) has **zero references** to the HID service UUID
(0x1812), Report Map (0x2A4B), or any HID-related characteristics. Android's Bluetooth
stack automatically discovers and subscribes to HID services on bonded devices (visible
in BLE captures), but the Fossil app never reads or processes HID data.

### Answers to investigation questions

| Question | Answer |
|----------|--------|
| Does the watch send HID media key events on MUSIC_CONTROL? | **No.** A MUSIC_CONTROL button uses proprietary Fossil events on 3dda0006. (But a button assigned a HID *volume/camera* action DOES send HID - see §26a.) |
| Does FORWARD_TO_PHONE produce HID output? | **No.** Only MICRO_APP_EVENT on 3dda0006. |
| Are there Feature Reports to configure HID behavior? | **No.** Only Input reports (read+notify). No Output or Feature reports. |
| Could the HID profile make the watch a standard BLE media controller? | **Not directly.** The firmware never sends HID reports. A proxy translating Fossil events → synthetic HID input would work. |

---

## 27. Toggle Entry Limit — Maximum 6 Entries (Hardware-Verified) (2026-05-22)

**Date:** 2026-05-22

**Source:** Real-hardware testing on Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

### Summary

The maximum number of toggle entries per button is **6**. This is not a firmware-enforced
limit — it's the number of distinct display-only micro apps available. With 7+ entries
containing duplicates, the firmware enters an infinite loop.

### Test results

| Entries | Config | Result |
|---------|--------|--------|
| 5 | TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF | ✅ 6 presses to return (confirmed #22) |
| 6 | TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF+24HR | ✅ 7 presses to return |
| 7 | TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF+24HR+TZ | ❌ Infinite loop |
| 7 | TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF+24HR+DATE | ❌ Infinite loop |

### Infinite loop mechanism

With 7 entries containing a duplicate function:
- Ending with duplicate TZ: after the 7th entry (TZ→sub-eye A), the cycle
  restarts from entry 1 (TZ again) — loops from the beginning.
- Ending with duplicate DATE: after the 7th entry (DATE→sub-eye B), the cycle
  skips forward to entry 3 (ALARM→sub-eye C) — skips past TZ and DATE since
  they were just shown.

The firmware appears to track which display modes were recently shown and skips
forward to the next *different* one. When duplicates create a cycle where the
firmware never finds a "done" state, it loops indefinitely.

### Why 6 is the practical maximum

There are exactly 6 distinct display-only micro apps suitable for toggle entries:

| # | Function | AppId | Display mode | Sub-eye position |
|---|----------|-------|-------------|------------------|
| 1 | SECOND_TIMEZONE | 0x16 | B0 01 00 | A |
| 2 | DATE | 0x14 | B0 00 00 | B |
| 3 | ALARM (SEQUENCED) | 0x1A | B0 03 00 | C |
| 4 | STEP_GOAL_COMPLETION | 0x1C | B0 08 00 | step % |
| 5 | LAST_NOTIFICATION | 0x18 | B0 02 00 | hands to notif pos |
| 6 | TWENTY_FOUR_HOUR | 0x1E | B0 04 00 | no visible effect |

Other micro apps are either interactive (STOPWATCH, MUSIC_CONTROL, FORWARD_TO_PHONE)
or break the toggle chain (GOAL_TRACKING). None can serve as additional toggle entries.

To test 7+ distinct entries would require a 7th display-only micro app, which doesn't
exist in the firmware's repertoire.

### File size scaling

| Entries | Button config file size |
|---------|------------------------|
| 3 (mode_toggle) | ~280 bytes |
| 5 | ~470 bytes |
| 6 | 531 bytes |
| 7 | 593 bytes |

The firmware accepted all file sizes without error. The infinite loop is a runtime
behavior issue with duplicate display modes, not a file size or entry count rejection.

---

## 28. TWENTY_FOUR_HOUR Function (appId 0x1E) — Accepted but Invisible (2026-05-22)

> **CORRECTED 2026-06-15:** the SEQUENCED variant's payload below was a wrong by-analogy
> guess, which is exactly why the firmware accepted-but-skipped it. The real SEQUENCED entry
> was captured from the official app; see the "Captured SEQUENCED payload" block at the end of
> this section. The STANDARD variant is still uncaptured (likely also wrong).

**Date:** 2026-05-22

**Source:** Real-hardware testing on Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

### Summary

The TWENTY_FOUR_HOUR function (appId 0x1E, MicroAppId 9) is recognized by the firmware
but produces no visible output on the Q Commuter's 3-position dial. (Original hypothesis;
note the SEQUENCED payload was also malformed, see the correction below.)

### Payload construction (ORIGINAL by-analogy guess — superseded)

Constructed by analogy with SECOND_TIMEZONE (47-byte STANDARD pattern):

```
Header: 01 01 1E 00 (appId=0x1E, variant=STANDARD)
Payload: 47 bytes — identical structure to SECOND_TIMEZONE with:
  - declarationFileId = 0x1E01 (7681 = TWENTY_FOUR_HOUR STANDARD)
  - Display mode B0 04 00 (next sequential after ALARM B0 03 00)
  - Data source byte = 0x02 (same as SECOND_TIMEZONE)
```

Also constructed SEQUENCED variant (54 bytes, header `01 02 1E 00`) by analogy
with ALARM_SEQUENCED. **This guess was WRONG** (see correction below).

### Test results

| Test | Result |
|------|--------|
| Standalone button (TOP=24hr) | ❌ No vibration, no hand movement, no sub-eye movement |
| In 6-entry toggle | ✅ Accepted — adds 1 press to cycle but no visible change on its turn |
| Does not break toggle chain | ✅ Subsequent entries display normally |
| Error vibration? | No — silent, unlike GOAL_TRACKING which produces error vibe |

### Analysis

The firmware processes the TWENTY_FOUR_HOUR entry (it counts as a toggle step and
doesn't break the chain), but has nothing to display. This is likely because:

1. **The Q Commuter (HW.0.0) has a 3-position sub-eye dial** (A, B, C) with no
   labeled "24HR" position. The 5-position dial watches (e.g. Q Activist) have
   positions for Time2, Date, Alarm, Alert, and 24HR.

2. **Display mode B0 04 00 may be correct** but the firmware has no rendering
   logic for it on 3-position hardware — it would need a 4th or 5th sub-eye
   position that doesn't physically exist.

3. **The data source byte (0x02 = timezone) might be wrong** — 24HR might need
   a different data source, but without a 5-position dial watch to test on,
   we can't determine the correct value.

### Conclusion

TWENTY_FOUR_HOUR is a Q Activist (5-position dial) feature. On Q Commuter
(3-position dial), the firmware accepts it but has no way to display it.
The function likely shows the current hour in 24h format on the sub-eye's
"24HR" labeled position — but only on watches that have that position.

### CLI support

Added `24hr` and `24hr_seq` as button function names:
```bash
fossil-q buttons 24hr second_timezone forward_to_phone      # standalone
fossil-q buttons "tz+date+alarm_seq+step_goal+last_notification+24hr" ...  # in toggle
```

### Captured SEQUENCED payload (CORRECTION, 2026-06-15)

**Source:** official-app btsnoop `tmp/eventack-extract/FS/data/misc/bluetooth/logs/btsnoop_hci.log`,
file-PUT on SETTINGS_BUTTONS data char `3dda0004` (handle `0x0048`), chunks at t=51.30-51.31s.
The official app had a mode-toggle cycle that included the 24HR position. Re-derive with:
```
tshark -r <btsnoop> -Y 'btatt.handle==0x0048 && btatt.opcode==0x52' -T fields -e btatt.value
```
Data chunks carry a 1-byte sequence prefix (00,01,02,...); strip it, concatenate, and the
SEQUENCED entries appear in order `01 02 16 36` (TZ), `01 02 18 36` (alert), `01 02 1a 36`
(alarm), then `01 02 1e 34` (24HR), then `01 02 14 34` (date).

The captured 24HR SEQUENCED entry follows the **DATE_TOGGLE** shape, NOT ALARM:

```
Wire entry (50 bytes, header 01 02 1E + len 0x34=52 incl. the 01 00 file prefix):
  01 02 1E 34 00 00 00 01 00 06 00 02 00 00 07 00 01 01 1D 00
  89 02 01 04 B0 07 00 89 05 01 07 B0 07 00 B0 07 00 B0 07 00
  08 01 50 00 01 00 E0 19 B8 E8

ButtonConfigBuilder.TWENTY_FOUR_HOUR_SEQ_DATA (52 bytes = 01 00 prefix + the above):
  01 00 01 02 1E 34 00 00 00 01 00 06 00 02 00 00 07 00 01 01
  1D 00 89 02 01 04 B0 07 00 89 05 01 07 B0 07 00 B0 07 00 B0
  07 00 08 01 50 00 01 00 E0 19 B8 E8
```

**Diffs vs the old by-analogy guess (which copied ALARM_SEQUENCED):**

| Field | Old (wrong) | Captured |
|-------|-------------|----------|
| total length / len byte | 54 / `0x36` | 52 / `0x34` |
| group byte | `08` | `06` |
| data-source byte | `02` | `00` |
| display-mode position | `B0 04 00` | `B0 07 00` |
| trailing CRC | `A9 21 D4 C7` | `E0 19 B8 E8` |

The wrong display position (`04` vs `07`) and malformed length are why the firmware accepted
the entry but rendered nothing. Locked as a golden vector in
`protocol/.../golden/Wp7ButtonCompilerTest.java`. **STANDARD variant is moot:** 24h is a
toggle-only sub-eye mode — there is realistically no standalone "24h button" to assign, so the
official app never emits a STANDARD (`01 01 1E 00`) 24h entry. The `TWENTY_FOUR_HOUR_DATA`
guess is kept only for API symmetry and should not be trusted (it was not captured).

---

## 29. PlayCrazyShitRequest & Hand Action Choreography — Dead Code (2026-05-22)

**Date:** 2026-05-22

**Source:** GadgetBridge vendored source analysis + real-hardware testing on
Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

### What PlayCrazyShitRequest is

`PlayCrazyShitRequest` is an experimental class in GadgetBridge (by Daniel Dakhno)
that uploads a hand animation choreography to the watch. It extends `FilePutRequest`
and writes to `FileHandle.HAND_ACTIONS` (0x0600).

**Critical discovery:** `HAND_ACTIONS` and `SETTINGS_BUTTONS` share the **same file
handle** (0x06, 0x00) = 0x0600. Uploading a hand action **overwrites the button
configuration**.

### Hand action file format

```
[01 00 08]              magic bytes (vs button config: 01 00 00)
[buttonHeader(8)]       copied from button config appData[3:11]
[FF]                    separator
[payloadLength(2 LE)]   command bytes + 3
[commands...]           MicroAppCommand sequence
[CRC32(4)]              over all preceding bytes
```

### MicroAppCommand system

The `MicroAppCommand` interface defines a choreography instruction set:

| Command | Bytes | Description |
|---------|-------|-------------|
| StartCritical | `03 00` | Begin critical section (prevent interruption) |
| Close | `01 00` | End choreography |
| Vibrate(type) | `93 00 XX` | Vibrate (XX=04 for NORMAL) |
| Delay(seconds) | `08 01 XX XX` | Delay in 0.1s units (LE short) |
| Animation(h,m) | `09 04 01 03 [ctrl h h] [ctrl m m]` | Move hands to absolute position |
| RepeatStart(n) | `86 00 XX` | Begin loop, repeat XX times |
| RepeatStop | `07 00` | End loop |
| Stream(mask) | `8B 00 XX` | Streaming mode (purpose unclear) |

Animation control byte: `(direction << 6) | (absolute << 5) | speed`
- Direction: 0=CLOCKWISE, 1=COUNTER_CLOCKWISE, 2=SHORTEST
- Absolute: 0=relative, 1=absolute position
- Speed: 0=MAX, 1=HALF, 2=QUARTER, 3=EIGHTH, 4=SIXTEENTH

### Hardware test results

| Test | Payload | Result |
|------|---------|--------|
| `vibrate` preset | StartCritical→Vibrate→Delay(1s)→Close (11 cmd bytes) | ❌ No effect |
| `dance` preset | StartCritical→Vibrate→Anim(3:00)→Delay→Anim(9:00)→Vibrate→Close (41 cmd bytes) | ❌ No effect |

Both uploads returned `success` from the firmware (file accepted, CRC valid),
but produced zero visible or haptic output.

### Why it doesn't work

1. **Dead code in GadgetBridge:** `PlayCrazyShitRequest` is never instantiated or
   called anywhere in the GadgetBridge codebase. No adapter, service, or activity
   references it. It was a developer experiment that was never completed or tested.

2. **Wrong magic bytes for coin-cell firmware:** The `01 00 08` magic may only be
   recognized by HR (screen) model firmware. The coin-cell Q Commuter firmware
   likely only recognizes `01 00 00` (standard button config format) for handle
   0x0600. The hand action format may have been designed for a different firmware
   branch.

3. **Shared file handle is destructive:** Since HAND_ACTIONS and SETTINGS_BUTTONS
   share handle 0x0600, uploading a hand action replaces the button config. The
   firmware may interpret the `01 00 08` magic as an invalid button config and
   silently ignore it.

4. **No trigger mechanism:** Even if the format were recognized, hand actions may
   need to be triggered by a button press event (the `appData` parameter copies
   button header context). Our standalone upload had no button press context.

### The Misfit AnimationRequest (separate system)

The vendored `AnimationRequest` (Misfit protocol, not Fossil) is a simple 3-byte
command (`02 F1 05`) written directly to the command characteristic (3dda0002).
This is the animation sent during Fossil protocol init — it's a low-level hand
calibration/test command, not a choreography system.

### Notification play as the real "hand animation" system

The working hand animation system on this firmware is **notification play files**
(handle 0x0900 = `NOTIFICATION_PLAY`). These move hands to specified degree
positions with configurable vibration patterns and duration. See FINDINGS.md
#23 and #24. This is the mechanism the official Fossil app uses for all hand
animations — there is no separate choreography system on coin-cell watches.

### Conclusion

`PlayCrazyShitRequest` is dead experimental code that targets a firmware feature
not present on the Q Commuter (HW.0.0). The MicroAppCommand choreography system
(with its elegant animation/repeat/vibrate instruction set) either:
- Only works on Fossil Hybrid HR (screen) models with different firmware
- Was never implemented in any shipping firmware
- Requires a trigger mechanism we haven't discovered

For practical hand animations on coin-cell watches, use the notification play
system (`notify` command with `--position` and `--vibe` flags).

---

## 27. Toggle Entry Limit — Maximum 6 (Confirmed) (2026-05-22)

**Date:** 2026-05-22

**Source:** Real-hardware testing on Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

### Summary

The maximum number of entries in a button toggle is **6**. This is not a firmware-imposed
hard limit but a **practical ceiling**: there are exactly 6 distinct display-only micro
apps available, and duplicate entries cause infinite cycling.

### Test results

| Entries | Config | Result |
|---------|--------|--------|
| 5 | TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF | ✅ 6 presses to return to normal (confirmed #22) |
| 6 | TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF+24HR | ✅ **7 presses to return to normal** |
| 7 | TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF+24HR+TZ | ❌ **Infinite loop** — wraps to entry 1 |
| 7 | TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF+24HR+DATE | ❌ **Infinite loop** — wraps to entry 3 (alarm) |

### Infinite loop mechanism

With 7 entries containing a duplicate, the firmware never reaches a termination state.
The cycle behavior depends on which function is duplicated:

- Duplicate SECOND_TIMEZONE at position 7: after showing it, firmware continues to
  DATE (position 2's display mode), then ALARM, then loops from there.
- Duplicate DATE at position 7: after showing it, firmware skips to ALARM (position 3),
  then continues through the remaining entries and loops.

The firmware appears to track which display modes have been shown and skips forward
past recently-shown ones. With all 6 distinct modes exhausted and a duplicate starting
a new cycle, it never finds a "done" condition.

### Why 6 is the maximum

There are exactly 6 distinct display-only micro apps that work in toggle mode:

| # | Function | AppId | Display mode | Sub-eye | Works in toggle |
|---|----------|-------|-------------|---------|----------------|
| 1 | SECOND_TIMEZONE | 0x16 | B0 01 00 | A | ✅ |
| 2 | DATE | 0x14 | B0 00 00 | B | ✅ |
| 3 | ALARM (SEQUENCED) | 0x1A | B0 03 00 | C | ✅ (needs alarm set) |
| 4 | STEP_GOAL_COMPLETION | 0x1C | B0 08 00 | step % | ✅ (needs steps > 0) |
| 5 | LAST_NOTIFICATION | 0x18 | B0 02 00 | hands move | ✅ (needs cached notif) |
| 6 | TWENTY_FOUR_HOUR | 0x1E | B0 04 00 | (none) | ✅ (no visible effect) |

Other micro apps are either interactive (STOPWATCH, MUSIC_CONTROL, FORWARD_TO_PHONE)
or break the toggle chain (GOAL_TRACKING). None can serve as a 7th display-only entry.

### TWENTY_FOUR_HOUR behavior

The 6th entry (TWENTY_FOUR_HOUR, appId 0x1E) is **accepted by the firmware** — it counts
as a toggle step (7 presses total with 6 entries), does not produce an error vibration,
and does not break the chain. However, it produces **no visible output** on the
Q Commuter (no hand movement, no sub-eye movement, no vibration).

This is likely because the Q Commuter has a 3-position sub-eye dial (A/B/C) with no
labeled "24HR" position. On 5-position dial watches (e.g. Q Activist), TWENTY_FOUR_HOUR
has a dedicated "24HR" label and would presumably show the current hour in 24h format.

The firmware silently processes the entry and moves to the next one (or returns to
normal if it's the last entry).

---

## 28. TWENTY_FOUR_HOUR Function — Firmware Accepted, No Visual on Q Commuter (2026-05-22)

**Date:** 2026-05-22

**Source:** Real-hardware testing on Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

### What it is

TWENTY_FOUR_HOUR (MicroAppId 9) is a display function with:
- AppId: 0x1E (30)
- DeclarationId: 7681 (STANDARD), 7682 (SEQUENCED)
- Sub-eye label: "24HR" on 5-position dial watches (Q Activist)

### Payload construction

Constructed by analogy with SECOND_TIMEZONE (same 47-byte STANDARD pattern):

```
Header: 01 01 1E 00
Payload (47 bytes):
  01 00 01 01 1E 2F 00 00 00 01 00 08 00 04 00 00
  07 02 02 00 01 01 1E 00 89 05 01 07 B0 04 00 B0
  04 00 B0 04 00 08 01 50 00 01 00 8B 95 15 80
```

Display mode byte `B0 04 00` chosen as next sequential after ALARM (`B0 03 00`).

### Test results

| Test | Result |
|------|--------|
| Standalone button (TOP=24hr) | ❌ No visible effect — no vibration, no hands, no sub-eye |
| Inside 6-entry toggle | ✅ Accepted as entry, counts as toggle step, no visible output |
| Does it break toggle chain? | No — subsequent entries still display correctly |
| Does it cause error vibration? | No — silent (unlike GOAL_TRACKING which errors) |

### Conclusion

The Q Commuter firmware **recognizes** appId 0x1E but has **nothing to display** for it.
The 3-position sub-eye dial has no 24HR position, and the firmware doesn't fall back to
any alternative visualization (unlike LAST_NOTIFICATION which moves the main hands).

On a 5-position dial watch (Q Activist HL.0.0), TWENTY_FOUR_HOUR would likely show the
current hour in 24-hour format on the sub-eye's "24HR" labeled position. Testing on
such hardware would confirm this.

### CLI support added

```bash
# Standalone (no visible effect on Q Commuter)
fossil-q -d <MAC> buttons 24hr second_timezone forward_to_phone

# In toggle (counts as a step, no visible output)
fossil-q -d <MAC> buttons "second_timezone+date+alarm_seq+step_goal+last_notification+24hr" ...
```

Aliases: `24hr`, `24h`, `24_hour`, `twenty_four_hour`.
Sequenced variant: `24hr_seq`, `24h_seq`.

---

## 29. PlayCrazyShitRequest & Hand Action Files — Dead Code, No Effect on Coin-Cell (2026-05-22)

**Date:** 2026-05-22

**Source:** GadgetBridge source code analysis + real-hardware testing on Fossil Q Commuter
(HW.0.0), firmware HW0.0.2.9r.v3.

### What it is

`PlayCrazyShitRequest` is a class in GadgetBridge's vendored source that uploads a
choreographed hand animation sequence to the watch via file handle `HAND_ACTIONS` (0x0600).
Despite its memorable name, it is **dead code** — never called from anywhere in
GadgetBridge.

### File handle collision

`HAND_ACTIONS` and `SETTINGS_BUTTONS` share the **same file handle** `(0x06, 0x00)` = `0x0600`
in `FileHandle.java`. The firmware distinguishes them by the first 3 bytes:

| Format | Magic bytes | Purpose |
|--------|------------|----------|
| Button config | `01 00 00` | Standard button assignments |
| Hand actions | `01 00 08` | Choreographed animation sequence |

Uploading a hand action file **overwrites** the current button config.

### MicroAppCommand system

The hand action format supports a command language:

| Command | Bytes | Description |
|---------|-------|-------------|
| StartCritical | `03 00` | Begin critical section |
| Close | `01 00` | End sequence |
| Vibrate(type) | `93 00 type` | Vibrate (type 0x04 = NORMAL) |
| Delay(seconds) | `08 01 LE16` | Delay in 0.1s units |
| Animation(h,m) | `09 04 01 03 ctrl h16 ctrl m16` | Move hands to position |
| RepeatStart(n) | `86 00 count` | Start loop |
| RepeatStop | `07 00` | End loop |
| Stream(mask) | `8B 00 mask` | Streaming mode |

Animation control byte: `(direction << 6) | (absolute << 5) | speed`
- Direction: 0=CLOCKWISE, 1=COUNTER_CLOCKWISE, 2=SHORTEST
- Absolute: 0=relative, 1=absolute position
- Speed: 0=MAX, 1=HALF, 2=QUARTER, 3=EIGHTH, 4=SIXTEENTH

### Payload format

```
[01 00 08]                    magic (hand action type)
[buttonHeaderContext(8)]      8 bytes copied from button config payload[3:11]
[FF]                          separator
[payloadLength(2 LE)]         length of commands + 3
[commands...]                 concatenated MicroAppCommand bytes
[CRC32(4)]                    over all preceding bytes
```

### Test results on Q Commuter

| Test | Upload | Effect |
|------|--------|--------|
| `vibrate` (StartCritical + Vibrate + Delay + Close) | ✅ Success | ❌ No vibration |
| `dance` (Vibrate + Animation 3:00 + Animation 9:00) | ✅ Success | ❌ No movement |

Both uploads were accepted by the firmware (file-put succeeded), but **no observable
effect** on the watch — no vibration, no hand movement, nothing.

### Why it doesn't work

1. **Dead code in GadgetBridge** — `PlayCrazyShitRequest` is never instantiated.
   The developer (Daniel Dakhno) was experimenting but never completed integration.

2. **Coin-cell firmware may not support hand action format** — The `01 00 08` magic
   might only be recognized by HR (screen) model firmware. The coin-cell Q Commuter
   firmware accepts the file write (no error) but ignores the contents.

3. **Missing trigger mechanism** — The hand action file might need to be associated
   with a specific button press event. `PlayCrazyShitRequest` takes `appData` from
   a micro app event (button press), suggesting the choreography is a **response**
   the phone sends after receiving a button event. Without the correct button context
   and event trigger, the firmware has no reason to execute the stored choreography.

4. **File handle collision** — Writing to `0x0600` overwrites the button config.
   The firmware may only parse this handle as button config format (`01 00 00`)
   and discard anything with a different magic.

### Conclusion

The MicroAppCommand choreography system is an interesting protocol feature with a rich
command language (absolute/relative hand positioning, speed control, loops, vibration
patterns). However, it appears to be **non-functional on coin-cell Fossil Q Hybrid
watches**. It may work on Fossil Hybrid HR models (which have displays and more
sophisticated firmware), but that's untestable with our hardware.

The `hand-anim` CLI command remains available for experimentation but should be
considered non-functional on Q Commuter:

```bash
# Available presets (all produce no visible effect on Q Commuter)
fossil-q -d <MAC> hand-anim vibrate
fossil-q -d <MAC> hand-anim dance
fossil-q -d <MAC> hand-anim sweep
fossil-q -d <MAC> hand-anim spin
fossil-q -d <MAC> hand-anim all

# WARNING: Overwrites button config! Restore with:
fossil-q -d <MAC> buttons stopwatch second_timezone forward_to_phone
```

---

## 30. File-PUT Protocol — Authoritative Decode from the Official Fossil App (2026-05-30)

**Source:** deobfuscated official Fossil app under `tmp/FossilOfficialApp-deobf`, packages
`com/fossil/blesdk/device/logic/{request,phase}`. This is **ground truth** and supersedes the
guessed handshake previously documented in §8.

### Control channel & operation codes

- Control = **FTC characteristic `3dda0003`** (write-with-response / INDICATE).
- Data = **`3dda0004`** (write-WITHOUT-response).
- `FileControlOperationCode` (request byte; **response byte = request | 0x80**):

| Op | Code | Response | Meaning |
|----|------|----------|---------|
| GET_FILE | 1 | 0x81 | read a file |
| LIST_FILE | 2 | 0x82 | list files |
| **PUT_FILE** | **3** | **0x83** | open a write (offset + length) |
| **VERIFY_FILE** | **4** | **0x84** | finalize/verify — **this is "committed"** |
| GET_SIZE_WRITTEN | 5 | 0x85 | query bytes written |
| VERIFY_DATA | 6 | 0x86 | verify a data segment |
| ERASE_DATA | 7 | 0x87 | erase |
| **EOF_REACH** | **8** | **0x88** | watch's progress/finish report (sizeWritten + CRC32) |
| **ABORT_FILE** | **9** | **0x89** | abort (e.g. the watch's PUT timeout) |
| **WAITING_REQUEST** | **10** | **0x8A** | watch tells the app how long to wait (uint32 timeout) |
| DELETE_FILE | 11 | 0x8B | delete a file |

Control frame layout (little-endian): `[opByte][handle:2][status:1][...]`. Status SUCCESS is checked
at offset 3; `WAITING_REQUEST` carries a firmware-proposed timeout (uint32) at offset 5
(`FileControlRequest.java`).

### The actual PUT sequence (`TransmitDataPhase.java`)

1. **PUT_FILE(3)** request → watch accepts (**0x83**).
2. Transfer data chunks on `3dda0004` (write-without-response, MTU-bounded, paced).
3. Watch reports **EOF_REACH(0x88)** / **WAITING_REQUEST(0x8A)** carrying **sizeWritten + a CRC32 of
   the bytes it received**. The app compares that CRC to its OWN `Crc32Calculator.CRC32` of the data
   it sent (`TransmitDataPhase.m11139a`):
   - bytes complete **and** CRC matches → step 4.
   - otherwise → **loop back to PUT_FILE(3)** to resume from the written offset (bounded: aborts after
     3 no-progress retries → `DATA_TRANSFER_RETRY_REACH_THRESHOLD`, `m11144q`).
4. **VERIFY_FILE(4)** request (`VerifyFileRequest`, a `FileControlRequest` with **retryThreshold = 3**):
   write `[0x04][handle]` on `3dda0003`, then **WAIT for the 0x84 response with SUCCESS status**.
   **0x84-success is the only real "file committed" confirmation.**

The official app sets the write type on the **characteristic** (via properties), not per-write
(`WriteCharacteristicCommand.java`): `3dda0003` = write-with-response, `3dda0004` =
write-without-response. It does NOT send any extra "close" beyond VERIFY_FILE.

### Why our earlier implementation failed on Android (the buzz saga)

Our `FilePutRawRequest` was implemented by guessing and **mislabeled the handshake**:
- treated **op 8 (EOF_REACH)** as completion — it's only a progress/CRC report;
- treated **op 4 (VERIFY_FILE)** as a fire-and-forget "close" — it's the finalize **request** that
  must be sent and whose **0x84 success** must be awaited (with retries);
- never compared the watch-reported CRC to ours;
- ignored WAITING_REQUEST's proposed timeout.

Over **BlueZ (CLI)** this still worked: BlueZ paces writes and delivers `0x84` reliably/in-order, so
even the mislabeled path eventually completed. Over **Android GATT** it did not: we never sent a
proper VERIFY, so the watch sat in its internal timeout (~10s) and then **ABORT_FILE (0x89)** — the
"type-9 timeout" we kept seeing — so the file never committed and the manual buzz never fired.

### Consequence / action

The fix is to re-own `FilePutRawRequest` to this real flow (PUT → data → EOF/CRC-check →
**VERIFY(0x84)**), shared by CLI + Android. This makes **every** file-PUT (notification filter,
buttons, alarms, config, and the buzz's play file) reliably verifiable on Android, not just BlueZ.
The FILE byte formats are unchanged — only the control handshake. Tracked in
`WP-FILEPUT-RELIABLE-PROMPT.md`.

---

## Buttons go dead (no local confirmation buzz) — fixed by a clean re-provision, NOT battery (2026-06-14)

### Symptom (observed on hardware)
On a watch that had been in use for a while, the physical buttons stopped doing ANYTHING:
- pressing a "send-to-phone" button (mode switch / "music" trigger) produced **no internal
  confirmation buzz** (the short buzz the firmware normally fires the instant such a button is
  pressed, BEFORE the phone is involved);
- the middle button configured to cycle the dial (time2 / last-notif / alarm / 24h / date) did
  **nothing** — no hand movement, no state change;
- yet the BLE link was healthy: the app could still send a buzz and the watch vibrated, i.e.
  **phone -> watch downlink worked fine**, only the watch's own button handling was dead.

### What it was NOT
- **Not the battery.** The same OLD battery was kept and the buttons came back after recovery.
  (The buzz-works-but-buttons-dead asymmetry initially looked like a low-coin-cell degraded mode,
  where the radio/motor still run but the button/hand subsystems brown out — that theory was wrong
  here.)
- Not an app gesture/debounce bug: removing the mode-switch buzz debounce was unrelated.

### Recovery (worked, same battery)
A full clean re-provision restored normal button behaviour:
1. delete the app's storage,
2. forget the watch (clear association),
3. reopen the app and reconnect (fresh auth handshake: vibration + top-button confirm),
4. re-import app settings + the watch config (the new Backup & restore feature),
5. sync each section to the watch.
After this the buttons (incl. the dial-cycle middle button) + their local confirmation buzz worked
again.

### Interpretation
The watch's on-device button/provisioning state (most likely the `SETTINGS_BUTTONS` 0x0C00 config
and/or the auth/provisioning state) had drifted into a bad state where presses produced no events
and no local buzz. A fresh provision rewrites it cleanly. This is a **watch-side state corruption**,
recoverable in software — no hardware fault.

This also re-frames the earlier "**mode-switch button starts sending multiple buzz patterns in
succession, impossible to tell which mode**" symptom: most likely the SAME corrupted/stale button
config (garbled/duplicated entries -> repeated/confused events), not a debounce regression and not
the battery.

### TODO / follow-up
- Consider a one-tap "**Re-provision / repair this watch**" action (it already exists as the
  WP-WATCHADMIN remove+re-add path) and surface it as the recommended fix when buttons misbehave.
- Worth understanding WHAT corrupts the on-watch button state over time (a failed/partial
  `SETTINGS_BUTTONS` file-put? an auth drift?) so it can be prevented, not just recovered from.

---

## Multi-buzz per press: the WATCH re-sends the same async event ~10x (2026-06-14)

### Symptom
One physical button press triggered ~7-10 identical effects (e.g. in TIMER mode: arm timer + an
ALARMS sync, repeated), so the watch buzzed several patterns in succession and a sync storm
followed (an alarm upload even timed out). Intermittent; worse after the watch has been used a
while.

### Root cause (logcat, decisive)
Captured with the transport tag enabled. ONE short press produced, at the GATT layer, BEFORE any
app logic:
```
D/AndroidBleTransport: NOTIFY 3dda0006 <- 01 05 14 02   (x10, within ~6 ms)
```
i.e. the **watch firmware re-sent the SAME async-event frame ~10 times** on the button/event
characteristic (`3dda0006`): `op=01` (NOTIFY-ish), `eventType=05` (MUSIC), `sequence=0x14`,
`data=02` (TOGGLE_PLAY_PAUSE → mapped to the TIMER/tracker SHORT gesture). All 10 copies identical.

So it is NOT duplicate Android controllers/transports (a single transport delivered 10 frames) and
NOT the battery. The watch increments the per-event `sequence` byte for DISTINCT user actions, so a
same-`sequence` identical frame is a retransmit/firmware-repeat of ONE press. This is consistent
with the watch-state drift documented above ("Buttons go dead") that a clean re-provision clears.

### Fix
De-duplicate at the adapter chokepoint `FossilQAdapter.handleButtonEvent`: drop an async-event
frame that is byte-identical (opcode+eventType+sequence+data) to the previous one AND arrived within
a 750ms window. Scoped to the discrete-ACTION event types only (MUSIC 0x05 / MICRO_APP 0x08 /
APP_NOTIFICATION 0x04); heartbeats, sync frames, JSON-file, auth, time/battery/alarm-sync events are
never de-duped. A different `sequence` (a genuine next press) or a repeat after the window is always
handled. No wire/format change. Covered by `AdapterAsyncEventDedupTest`.

This also explains the earlier "mode-switch button sends multiple buzz patterns in succession":
same firmware re-send, the rotation advanced several steps (one per duplicate), each buzzing its
mode's pattern. Removing the switch-buzz debounce was still correct — the duplication is upstream
of it, and is now fixed at the source.

### RECONCILIATION (2026-06-15): the storm is NOT caused by the missing ack

After capturing the OFFICIAL app (three btsnoop sessions, see §18a) we can test the earlier
hypothesis that "the watch storms because we never send the ack the official app sends." The
evidence says that hypothesis is WRONG, or at best incomplete:

1. **The official app did NOT ack a `0x08` event, and the watch still did NOT storm.** In capture 2
   two RING_PHONE (`0x08`) presses produced exactly TWO events; the official app sent NO `3dda0006`
   ack for either (it wrote a SETTINGS_BUTTONS file instead). If "no ack -> retransmit" were the
   mechanism, the unacked `0x08` should have stormed under the official app too. It did not. So a
   missing ack does not, by itself, trigger the storm.
2. **The storm timing is wrong for an ack-timeout retransmit.** Our storm was ~10 identical frames
   in **~6 ms**. A firmware waiting for an app ack would retransmit on a timeout of tens to
   hundreds of ms, not at ~0.6 ms spacing. ~10 frames in 6 ms looks like a burst/flush, not a
   wait-and-retry loop.
3. **The storm correlated with DRIFT, not with every press.** It was "intermittent, worse after
   the watch has been used a while", tied to the "Buttons go dead" drift state, and cleared by a
   re-provision - i.e. a watch-state condition, not a property of an un-acked press. In a fresh,
   healthy session (our de-dup APK after re-provision, and all three official captures) one press =
   one event.

**Revised understanding.** The ~10-18x re-send is a symptom of the watch's drifted/wedged state
(same family as "buttons go dead" and the wedged `0x0900`/alarm handles), NOT of a missing
3dda0006 ack. Sending the ack the official app sends (for `0x05`) is still worth doing for protocol
fidelity and may matter for OTHER reasons, but it should NOT be expected to stop the storm. The
de-dup (commit a2d1db1) remains the correct mitigation for the storm-of-effects; the real cure for
the storm itself is preventing the drift (the open "Buttons go dead" root-cause question), not
acking.

**Corrects** the note later in this file ("Wedged play handle ... the watch ... EXPECTS an app
acknowledgement ... so under drift the firmware re-requests the same frame ~14x"): the "expects an
ack" framing is contradicted by capture 2 (unacked `0x08` did not storm). Treat that line's
"missing-ack -> re-send" causal claim as SUPERSEDED by this reconciliation; the re-send is a
drift symptom.

**Still OPEN (the real question):** what drifts the watch into the re-send/buttons-dead state, and
how to prevent it (auth/provisioning drift? a file-handle wedge? a missing periodic write the
official app does that we omit?). The official-app captures are the place to look next - diff the
periodic/background writes the official app makes during a long idle connection against ours.

### Storm-drift step #1 (2026-06-15): the official app sends a periodic CMD the watch never gets from us

Mined the longest official-app capture (`tmp/bugreport5`, 784 s / ~13 min, mostly idle) for
phone->watch writes that recur on a timer. Findings on the **CMD characteristic `3dda0002`**
(handle `0x0042`):

- CMD frames are DeviceConfig ops: `[opId][paramId][data...]`, `opId` 01=GET / 02=SET / 03=RESPONSE
  (`DeviceConfigOperationCode` in the disassembly). paramId `0xf1` = `-15` =
  `DeviceConfigParameterId.DIAGNOSTIC_FUNCTIONS`.
- The official app sends **`02 f1 05`** at t=41, 370, 763 s. Decoded against the disassembly:
  `op=SET(02)`, `param=DIAGNOSTIC_FUNCTIONS(f1)`, `data=05` = `DiagnosticFunctionParametersData`
  `PAIR_ANIMATION` -> the DeviceConfigOperationCode named **`PLAY_ANIMATION`**. So `02 f1 05` is the
  app telling the watch to **play the little hand-sweep animation**, NOT a keep-alive. At connect it
  also does `01 f1 28` (GET) -> resp `03 f1 28 b6 00`, and `02 f1 23 00 01 00 00 00` once.
- **Our CLI/adapter sends NOTHING to `3dda0002` periodically** - no DeviceConfig SET, no scheduled
  keep-alive at all (grep of `cli/` + `FossilQAdapter`: only UUID defs and event READING).

**HONEST correction (I initially over-read this).** `02 f1 05` is PLAY_ANIMATION, so it is almost
certainly the app playing a hand-sweep at moments it did a background SYNC (t=370 and t=763 sit
right next to the file-sync bursts), NOT a periodic connection keep-alive. The "f1 is the keep-alive"
idea is therefore WEAK. The real periodic signal is the **background sync itself**, not `f1`.

What the official app actually does periodically that we omit, ranked by plausibility as the
drift-preventer:
  1. **Background SYNC bursts** every several minutes (GET/LIST on activity/config file handles at
     t=300-480 and t=720 in bugreport5), each followed by the PLAY_ANIMATION. A periodic sync is the
     most likely thing that keeps the watch's state "fresh". We do NOT sync on a timer.
  2. **TIME re-push** `02 0b 00 <ts:4LE> <ms:2> 03 3c 00` on `3dda0006`. We push time on connect
     (commit dfb609e) but not on a long-idle timer.
  3. (`02 f1 05` PLAY_ANIMATION is a CONSEQUENCE of the sync, not an independent keep-alive - do not
     replicate it in isolation.)

**NEXT (step #2):** the strongest lead is the periodic **background sync**. Experiment: run our
long-lived connection and trigger a lightweight sync (or just a time re-push) on a timer (~every
5 min), leave the watch idle-connected for >15-20 min, and see whether the buttons-dead/re-send
drift still appears vs an idle connection with no periodic activity. That tests "does periodic
activity prevent the drift" directly. Do NOT wire anything as a "fix" until an idle soak test shows
it helps - the causal link (periodic activity -> no drift) is still unproven, and step #1 only
established WHAT the official app does differently, not that it is the cause.

**AUTH/provisioning drift (investigated 2026-06-15) - LARGELY RULED OUT as a we-differ-from-them
cause.** The buttons-dead state is cleared by a full re-provision (not a reconnect), which pointed
at auth/secret-key/config state. Investigated:

- Across ALL FIVE captures (bugreport3/4/5 + eventack2/5), the official app's auth on `3dda0005`
  (handle `0x004b`) is exactly `02 06 30 75 00 00 01` -> watch `03 06 00 01`, i.e.
  **PROCESS_USER_AUTHORIZATION_V2** (30 s timeout, removeOtherLinkedPhones=1). That is BYTE-IDENTICAL
  to what `FossilQAdapter.performAuthentication()` sends (the `confirmAuth` bytes).
- The official SDK's `AuthenticatePhase` CAN do a secret-key crypto exchange
  (`SendPhoneRandomNumberRequest` / `SendBothSidesRandomNumbersRequest`, AES128), but it **never
  does so on this Q Hybrid** in any capture - no `02 01` / `02 02` / `02 03` secret-key package
  types ever appear on `0x004b`. That crypto path is for other (HR) models. AUTHENTICATION-PLAN.md's
  "the official app performs the handshake during every reconnect" is satisfied by the SAME V2
  button-auth we already do.

**Conclusion:** auth is NOT a way we differ from the official app on this watch - we authenticate
identically. So neither "missing ack" (falsified earlier) NOR "different/missing auth" explains the
drift. Both of the two leading hypotheses are now ruled out by the captures.

**What is left for the drift (genuinely open, weaker leads):**
1. CONFIG/state we write differently at provision time (the re-provision-clears-it clue still points
   at some persistent watch-side state, but it is NOT the auth handshake). Candidate: diff the exact
   CONFIGURATION (file 0x08) / DEVICE_INFO / button-config bytes we write at init vs the official
   app, for a field that drifts.
2. The periodic background-SYNC difference (WP-PERIODIC-SYNC-EXPERIMENT-PLAN.md) - still unproven,
   still a coin-flip.
3. Connection PARAMETERS / MTU / subscription order differences at connect (we have not diffed
   these). The official app SETs connection params (`02 09 ...` CONNECTION_PARAMETERS_REQUEST,
   `02 0c ...` STREAMING_CONFIG on `3dda0002`) at connect - do we?
4. It may be a firmware bug with no app-side prevention, only the de-dup mitigation + re-provision
   recovery we already have.

Given both strong hypotheses are now falsified, the HONEST status is: we do not know what causes the
drift, the de-dup remains the mitigation, and the next cheap analysis step is the connect-time CONFIG
+ connection-params diff (#1, #3) before spending effort on the periodic-sync soak test.

---

## File-PUT 12s stall: watch rejects the open (status 0x02 OPERATION_IN_PROGRESS), we ignored it (2026-06-14)

### Symptom
The TIMER alarm push (ALARMS file, handle 0x0A) timed out after exactly 12s (`UPLOAD_TIMEOUT_MS`),
every time, even with the de-dup + debounce fixes. Buzz 2 (the "armed" confirmation) therefore never
fired and the Alarms screen showed the timer as not-synced.

### Root cause (logcat, decisive)
The buzz file-put (handle 0x09) succeeded with the full handshake; the alarm file-put did not:
```
WRITE 3dda0003 -> 03 00 0a ...            (PUT_FILE open, handle 0x0a)
NOTIFY 3dda0003 <- 83 00 0a 02 00         (PUT accept — but STATUS byte = 0x02, NOT 0x00)
WRITE 3dda0004 -> 00 00 0a 02 ...         (we transmit the data anyway)
<silence ~12s>                            (watch never sends EOF_REACH 0x88)
alarm upload timed out / failed
```
Status `0x02` = `ResultCode.OPERATION_IN_PROGRESS` (success=false): the watch did NOT open the file
(a PRIOR timed-out put left handle 0x0A half-open on the watch, so the next open is rejected as
"busy"). `FilePutRawRequest.handlePutAccept` ignored the accept STATUS byte and transmitted data into
a rejected transfer, so the watch discarded it and never reported back → the caller's 12s timeout.
It is self-perpetuating: one timed-out put poisons the next (the buzz on handle 0x09 is unaffected,
which is why buzzes kept working while the alarm never landed).

### Fix
`FilePutRawRequest.handlePutAccept` now checks the PUT-accept status byte (offset 3). On a
non-SUCCESS code it does NOT transmit data — it fails fast (`state=FAILED`, `onFilePut(false)`), so
the caller gets an immediate honest failure and the serial queue advances at once instead of
stalling 12s. Status 0 (SUCCESS) is unchanged. Covered by
`AdapterFilePutTest.putAcceptWithBusyStatus_failsFastWithoutTransmitting`.

### Recovery (follow-up DONE)
The stale handle survives even a full disconnect/reconnect: on a FRESH connection the very FIRST
alarm put already got `83 .. 0a 02` (logcat 2026-06-14, 20:37), so fail-fast alone meant the ALARMS
file could NEVER upload. Fix: on an OPERATION_IN_PROGRESS accept, `FilePutRawRequest` now sends
**ABORT_FILE(9)** to release the handle, then re-sends **PUT_FILE(3)** to retry the open ONCE
(`abortThenReopen`). The watch's ack to our OWN abort (0x89) is ignored during recovery
(`recoveringFromBusy`) so it isn't mistaken for a watch-initiated failure. Still busy after the
retry → fail fast. Covered by `AdapterFilePutTest.putAcceptBusy_abortsAndReopens_thenCompletesOnRetry`
and `putAcceptBusyTwice_failsAfterOneRetry`.

Still the same underlying watch-state drift as "Buttons go dead" + the async-event re-send (a clean
re-provision clears all of them); the abort+reopen makes the alarm path self-heal without one.

### Confirmed on-device (2026-06-14 20:40)
The recovery fired exactly as designed on the first timer press of a fresh connection:
```
WRITE  03 00 0a ...        (open)
NOTIFY 83 00 0a 02 00      (busy)
WRITE  09 00 0a            (ABORT_FILE 9)
WRITE  03 00 0a ...        (re-open)
NOTIFY 89 00 0a 00         (abort ack — ignored during recovery)
NOTIFY 83 00 0a 00 00      (re-open ACCEPTED, status 00)
WRITE  00 00 0a ...        (data) -> 88 (EOF) -> 04 (VERIFY) -> 84 (SUCCESS)
sync done performed=[ALARMS];  timer armed on watch — duration buzz 5
```
And it was a ONE-TIME clear: every subsequent press got `83 00 0a 00 00` (status 00) directly — no
further abort needed. The full two-buzz timer UX works (received tick + duration buzz 5/7 for
short/long), and the alarm now shows on-watch in the Alarms screen.

---

## Async-event de-dup must key on SEQUENCE, not the whole frame (2026-06-14, 21:49)

The whole-frame de-dup (FilePut/dedup commit) worked for 0x05 MUSIC events but FAILED for 0x08
MICRO_APP (the mode-switch / RING_PHONE button): pressing the switch button once made the watch
re-send ~14 frames `01 08 25 ...` with the SAME sequence (0x25) but VARYING trailing
payload/checksum bytes, so `Arrays.equals(lastFrame, frame)` was false and all 14 passed through.
That advanced the rotation 14 times and queued 14 buzz play-file puts; the watch then started
rejecting the buzz PUT-accept with status `0x86` (CORRECTED 2026-06-15: 0x86 = 134 =
FIRMWARE_INTERNAL_ERROR_NOT_ENOUGH_MEMORY, NOT "NOT_SUPPORT" which is 0x88 = 136 — its play
file area FILLED UP under the storm), and the puts churned re-opening `03 00 09` for many seconds. No
"received"/confirmation buzz ever landed.

FIX: de-dup keys on `(opcode, eventType, SEQUENCE)` (the 3 leading bytes) within the 750ms window,
NOT the full frame. The watch increments `sequence` for a DISTINCT user action, so same-sequence ==
same press regardless of trailing bytes. This collapses the 0x08 storm (and is strictly more robust
for 0x05 too). Covered by AdapterAsyncEventDedupTest.sameSequence_differentTrailingBytes_isDeduped
and microAppButtonRepeats_sameSeq_varyingTrailingBytes_dedupToOnePress. The buzz `0x86` storm was a
downstream symptom of the missing de-dup; collapsing 14 -> 1 press removes the trigger.

---

## ROOT CAUSE + FIX (2026-06-15, from official-app disassembly): rotate the NOTIFICATION_PLAY handle low byte

The official app does NOT reuse a fixed play-file handle. In
`tmp/FossilOfficialApp-deobf/.../device/logic/FileHandleManager.java` (`getFileHandleToPut`,
method `m11016b`), the file handle is `(fileTypeId << 8) | index`. For `FileType.NOTIFICATION`
(id = 9, i.e. our NOTIFICATION_PLAY) it falls in the ROTATING group together with UI_SCRIPT /
LUTS_FILE / RATE_FILE / DATA_COLLECTION_FILE / etc., which does:
```
index = map.get((mac, fileType)) ?: 0
map.put((mac, fileType), (byte)((index + 1) % 255))   // increment, wrap at 0xFF
return (9 << 8) | index                                // 0x0900, 0x0901, 0x0902, ... 0x09FE, wrap
```
So each notification/play PUT goes to a NEW handle index: `0x0900`, then `0x0901`, `0x0902`, ...
wrapping `% 255`. By contrast NOTIFICATION_FILTER (id 12) and the other CONFIG/singleton types use
a FIXED index 0. The firmware keeps a small ring of NOTIFICATION file slots; rotating the low byte
lets it reclaim/overwrite cleanly, so a fast burst of buzzes never exhausts the area.

OUR BUG: `qhybrid.protocol.file.FileHandle.NOTIFICATION_PLAY(0x09, 0x00)` is a FIXED handle
(subHandle always 0x00) and `playNotificationByPackageName` / `playNotificationWithPattern` PUT
every single buzz to `0x0900`. Reusing the same slot while the watch still holds the prior file
there fills it → `0x86` NOT_ENOUGH_MEMORY (and is the same family as the `0x02`
OPERATION_IN_PROGRESS we saw earlier on the ALARMS 0x0A handle).

FIX (SHIPPED 2026-06-15, no btsnoop needed — disassembly is authoritative): the play path now
rotates the NOTIFICATION_PLAY handle low byte, mirroring FileHandleManager. `FossilQAdapter` keeps a
`notificationPlayIndex` and PUTs each buzz/notification-play to `(0x09 << 8) | (index++ % 255)` via a
new `FilePutRequest(short explicitHandle, FileHandle, ...)` constructor; NOTIFICATION_FILTER + the
config/alarm singletons stay on fixed index 0. Covered by `NotificationPlayHandleRotationTest`
(consecutive play puts open 0x0900, 0x0901, 0x0902; the counter wraps %255 and never emits 0x09FF).
This should remove NOT_ENOUGH_MEMORY in normal use; a watch-side reset is still the way to reclaim a
ring that is ALREADY full from before this fix.

NOTE: this fix is per-CONNECTION (the index resets to 0 on a fresh adapter), which matches the
official app's per-(mac,fileType) map being seeded at 0; if a single connection ever PUTs > 254
play files it wraps back through 0x0900, by which point the early ones are long reclaimed.

CONFIRMED ON-DEVICE (2026-06-15, new APK, after a one-time battery pull to clear the already-full
ring): two consecutive button-press buzzes both completed cleanly, and the play handle ROTATED as
designed:
```
Press 1: WRITE 03 00 09 ... 4f 00 00 00   -> NOTIFY 83 00 09 00 00 (accept OK, was 0x86)
         WRITE 00 00 09 02 ... (data)     -> 88 00 09 (EOF) -> WRITE 04 00 09 (verify)
                                          -> NOTIFY 84 00 09 00 (VERIFY success -> buzz fired)
Press 2: WRITE 03 01 09 ... 4f 00 00 00   -> NOTIFY 83 01 09 00 00   (handle low byte 0x0900->0x0901)
         ... -> 84 01 09 00 (success)
```
Status byte is 00 (not 0x86) on both, the handle stepped 0x0900 -> 0x0901, and each press was a
SINGLE 01 08 <seq> frame (de-dup holding) -> one buzz, no storm. The battery pull was the one-time
reclaim of the ring the OLD build had filled; the rotation keeps it from refilling. (There is no
button-combo factory reset on these hybrids; a coin-cell pull / full power-off is the only hard
reset, and a BLE re-pair does NOT clear the file ring.)

---

## CORRECTION (2026-06-15): 0x86 = NOT_ENOUGH_MEMORY, not a "wedged handle" — the play file area is FULL

The code below mislabeled `0x86`. Against `ResultCode.java`: 0x86 = 134 =
`FIRMWARE_INTERNAL_ERROR_NOT_ENOUGH_MEMORY` (NOT_SUPPORT is 0x88 = 136). So the buzz failure is NOT
a semantically "wedged handle" — the watch's NOTIFICATION_PLAY (0x0900) file area is OUT OF MEMORY.

New on-device evidence (2026-06-15, fresh APK with the SEQUENCE de-dup, AFTER a full re-provision):
- A manual buzz from Settings (fresh session, no button press) → `WRITE 03 00 09 ... 4f 00 00 00`
  (PUT_FILE open, handle 0x0900, size 0x4f=79 bytes) → `NOTIFY 83 00 09 86 00`. FIRST open fails.
- A notification "Play" from the Notifications tab (package com.ageofconquest...) → identical
  `03 00 09 ... 4f ...` → `83 00 09 86 00`.
So it is NOT the button/de-dup path and NOT a transient storm: EVERY NOTIFICATION_PLAY open is
rejected up-front for lack of memory.

WHY: every buzz/notification play does a FULL FilePUT of a fresh ~79-byte notification file to
0x0900 (`buildOfficialNotificationFile(...) -> FilePutRequest(NOTIFICATION_PLAY, ...)`). We NEVER
delete the old play file; the watch reclaims it only when a put completes (EOF -> VERIFY -> SUCCESS).
The earlier 18-per-press storm opened many play files that never reached VERIFY, so each orphan
squats on the 0x0900 area until it is full. After that, every new open gets NOT_ENOUGH_MEMORY.

WHY the re-provision did NOT clear it: a BLE re-pair / re-provision re-seeds CONFIG files but does
not run the watch's file-area garbage collection. Only a WATCH-SIDE reset reclaims the play area.
NEXT: confirm by resetting the watch itself (not just re-pairing) — expect the first buzz to get
`83 00 09 00 00`. The proper fix is to stop leaking play files: delete/overwrite the play file the
way the OFFICIAL app does (to be captured via btsnoop) instead of PUT-ing a brand-new file per buzz.

The (now-superseded) original note is kept below for context; treat its "NOT_SUPPORT" / "wedged
handle" framing as INCORRECT and its "re-provision clears it" recovery as UNCONFIRMED/likely wrong.

---

## Wedged play handle (0x86) survives a reconnect — only a re-provision clears it (2026-06-14, verified)

On-device confirmation after shipping the SEQUENCE-keyed de-dup (commit a2d1db1). A fresh connect's
logcat showed the de-dup working — five mode-switch presses each produced EXACTLY ONE `01 08 <seq>`
frame on `3dda0006`, ONE `Path-2 button press`, and ONE `multi-function advanced` (rotation cycled
1->2->3->0->1, no skips, no 14x storm). So the 0x08 path is healthy and the storm-of-effects is
gone.

BUT every buzz still failed: the `NOTIFICATION_PLAY` (handle 0x0900) play-PUT got
`NOTIFY 3dda0003 <- 83 00 09 86 00` — PUT-accept status `0x86` (FIRMWARE_INTERNAL_ERROR_NOT_SUPPORT)
— once per press, with NO re-open churn (the de-dup removed the churn; `handlePutAccept` correctly
fails fast because 0x86 is not OPERATION_IN_PROGRESS, so it does NOT trigger the abort+reopen
recovery). The watch's play subsystem was WEDGED by an EARLIER storm (before the de-dup APK) and the
wedged state SURVIVED a full BLE reconnect — same "handle wedged across reconnect" behaviour already
documented for the ALARMS 0x0A handle.

RECOVERY (confirmed working): a plain reconnect did NOT clear it. The user cleared it by a full
re-provision — export watch settings, remove the watch in-app, "Forget" in Android Bluetooth,
re-add, re-import settings. After that buzzes worked again.

> SUPERSEDED (2026-06-15): the "EXPECTS an app acknowledgement ... missing ack -> re-requests ~14x"
> causal claim below is contradicted by the official-app capture (§18a) where an UNACKED `0x08`
> event did NOT storm, and by the ~6 ms burst timing (too fast for an ack-timeout retry). See
> "RECONCILIATION (2026-06-15): the storm is NOT caused by the missing ack". The re-send is a
> DRIFT symptom, not a missing-ack consequence. Kept below for history.

WHAT TRIGGERS THE STORM (root cause, still un-acked): the watch sends button/event frames on
`3dda0006` with `opCode = 0x01 = REQUEST` (the storm frames were `01 08 ...` / `01 05 ...`), i.e. it
EXPECTS an app acknowledgement. `FossilQAdapter.handleButtonEvent` READS + logs the opcode but NEVER
writes an ack back, so under drift the firmware re-requests the same frame (same sequence) ~14x until
it times out. That storm of play-PUTs is what wedges the 0x0900 handle. The de-dup absorbs the storm
of EFFECTS (one press -> one effect) but cannot stop the watch re-sending, and cannot un-wedge an
already-stuck handle.

TWO LOG TELLS for a recurrence (tags: FossilQ-Tracker FossilQ-Svc AndroidBleTransport):
- `NOTIFY 3dda0006 <- 01 08 <seq> ...` repeated many times with the SAME seq in a few ms = the watch
  is storming again.
- `NOTIFY 3dda0003 <- 83 00 09 86 00` (status 0x86) on a buzz = the play handle is wedged; only a
  re-provision (not a reconnect) clears it.

FIXES, in priority order (none applied yet):
1. ROOT CAUSE — send the ack the official app sends after a `3dda0006` REQUEST-opcode event (capture
   it from a btsnoop: what does the phone WRITE back to `3dda0006` in the ~tens of ms after a button
   event, echoing eventType+sequence). Replicate in `handleButtonEvent` for opCode 0x01. This stops
   the re-send at the source, so the handle never wedges. (Already on TODO.)
2. DEFENSIVE (narrow) — see the 2026-06-15 CORRECTION above: 0x86 is NOT_ENOUGH_MEMORY (134), not
   NOT_SUPPORT. An ABORT_FILE(9)+reopen will NOT help if the area is genuinely full; the real fix is
   to delete/overwrite the play file (stop leaking one file per buzz) so the area never fills. Do
   NOT broaden recovery to all non-success codes.
3. KEEP the de-dup (shipped, a2d1db1) — the seatbelt: even mid-storm, one press = one effect.

---

## Watch time goes 1h behind because time is pushed ONLY at provision/calibration (2026-06-14)

Symptom (UK, BST): the watch went 1 hour behind real local time, "not sure when it happened", and
the user had NOT crossed a GMT<->BST boundary since getting the watch. So it is NOT a DST-flip and
NOT a timezone-math bug — `generateTimeConfigItem()` computes the offset correctly. It is that time
is essentially never re-sent, so any way the watch's stored offset ends up 1h wrong stays wrong.

Most likely 1h cause here (no DST crossing): the watch was provisioned while the phone's effective
UTC offset was off by an hour for a moment — e.g. provisioned right around when the phone applied
BST, or the phone briefly reported GMT (offset 0) instead of BST (+60). A clean 1h error is an
OFFSET error, not RTC crystal drift (which would be seconds/minutes over months, not a tidy hour).
Whatever set it wrong, nothing ever corrected it because:

How time reaches the watch:
- `FossilQAdapter.generateTimeConfigItem()` is correct: it sends UTC epoch (`millis/1000`) plus the
  CURRENT offset via `zone.getOffset(millis)/60000` (which INCLUDES DST — e.g. +60 for BST), as the
  TimeConfigItem (0x000C) offset the watch uses to shift the displayed time. (See #4, #21a.)
- That config is written ONLY in two places:
    (a) protocol `initialize(fullInit=true)` (FossilQAdapter:254), and
    (b) `controller.syncTime()`, which on Android is called ONLY from `CalibrationSync` (the
        calibration flow).

Why nothing re-corrects it:
- WP-PULLSYNC: connecting NO LONGER auto-pushes the full config (`WatchConnectionService` only runs a
  ONE-TIME full provision for a brand-new watch, or a user-requested pending sync). A KNOWN watch
  connects WITHOUT a full init, so (a) does not re-push time.
- `TIME` is NOT a `SyncSection` (the enum is ALARMS / NOTIFICATION_FILTER / BUTTONS / VIBRATION /
  NUDGE / SECOND_TIMEZONE). So even an explicit "Sync all" / per-screen save NEVER re-pushes time.
- Net: after the initial provision, the watch keeps whatever offset it was given, forever, until the
  next provision/calibration. So ANY one-time wrong offset (wrong-offset-at-provision, a DST flip,
  or travel across timezones) silently leaves the displayed time wrong until a manual re-provision
  (which is what fixed it this time).

FIX APPLIED (2026-06-14): re-push time on EVERY connect. `runOnLinkUp` (the single on-link-up hook
shared by the foreground `connectAndInit` and the auto-reconnect `onAutoConnectLinkUp`) now calls
`controller.syncTime()` right after publishing INITIALIZED, for any Fossil watch. This is one small
CONFIGURATION put (TimeConfigItem 0x000C) and is deliberately exempt from the WP-PULLSYNC
"don't re-push config on connect" policy, because time is the one config that genuinely goes stale
and we never re-push it otherwise. It self-heals the 1h-behind case and also covers travel + DST.

POTENTIAL OPTIMIZATION (later, noted not done): store the last-pushed UTC offset per watch and skip
the put when the phone's current offset matches it, so a normal reconnect with a correct watch does
NOT write at all. Until then we accept one extra tiny write per connect (negligible vs the win).
