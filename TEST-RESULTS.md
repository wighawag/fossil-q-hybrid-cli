# Test Results — Fossil Q Hybrid CLI

**Watch:** Fossil Q Commuter HW.0.0, Firmware HW0.0.2.9r.v3, Fossil protocol 2.x  
**Date:** 2026-05-19

---

## Test Summary

| Command | Status | Notes |
|---------|--------|-------|
| `info` | ✅ Works | Clean output, no errors |
| `time` | ✅ Works | Time sync successful |
| `find` | ✅ Works | Vibration works |
| `hands` | ✅ Works | Hand movement works |
| `calibrate` | ✅ Works | Calibration saved |
| `step-goal` | ✅ Works | Set to 12000, watch confirmed |
| `vibration` | ✅ Works | Strength config accepted |
| `timezone` | ✅ Works | Set to UTC+1 |
| `notify` | ✅ Works | Vibration via 3dda0005 characteristic write. Also sends lbl=12 file notification (no visible effect without Fossil app's notification filters on watch). |
| `notify --direct` | ⚠️ Partial | Misfit-style direct notification: hand rotation only, no vibration on HW.0.0 firmware. |
| `alarm` | ✅ Works | Set 07:30 one-shot, watch vibrated at alarm time |
| `find` | ✅ Works | 2 vibration bursts + hand rotation over 3 seconds |
| `activity` | ✅ Works | Downloads valid timestamped activity data (60s interval records) |
| `step-goal` | ✅ Works | Goal 50 → sub-eye at ~19, goal 99999 → sub-eye at zero |
| `vibration` | ✅ Works | Strength 10 → weak feedback buzz, 100→100 (no change) → no buzz |
| `timezone` | ✅ Works | Offset 0 → hour hand shifted back 1h, offset 60 → restored correct time |

---

## Bugs Found & Fixed

### Bug 1: NPE on Reconnection During Init

**Symptom:** `NullPointerException` in `FileGetRawRequest.handleResponse()` — `fileBuffer` is null because stale notification data arrives during a brief disconnect/reconnect cycle.

**Fix:** Added connection state guard in `onCharacteristicChanged()` (ignores notifications when `!transport.isConnected()`) and a disconnect callback that clears `currentFossilRequest` and `requestQueue`.

**Status:** ✅ Fixed.

---

### Bug 2: File Versions Not Stored in Shim Adapter

**Symptom:** `SupportedFileVersionsInfo` callback in `initFossilProtocol()` only logged — never called `shimAdapter.setSupportedFileVersion()`.

**Fix:** Iterate over all `FileHandle` values from the parsed `SupportedFileVersionsInfo` and store non-zero versions in the shim adapter.

**Status:** ✅ Fixed.

---

### Bug 3: VERIFICATION_FAIL on File Upload (NOTIFICATION_PLAY, ALARMS, etc.)

**Symptom:** Every file upload (`notify`, `alarm`, `activity`) failed with `status=5` (VERIFICATION_FAIL).

**Root Cause:** Two-part bug:
1. File versions were never stored (Bug 2).
2. Even after storing them, `FossilWatchAdapter.getSupportedFileVersion()` had a **`Byte` vs `Short` HashMap key mismatch** — `getMajorHandle()` returns `byte`, auto-boxed to `Byte`, but the map used `Short` keys. `Byte(10).equals(Short(10))` is `false` in Java, so lookups always missed and returned version `0`. The watch rejected version `0` in the file header.

**Fix:** Cast `handle.getMajorHandle()` to `(short)` in `getSupportedFileVersion()`:
```java
return fileVersions.getOrDefault((short) handle.getMajorHandle(), (short) 0);
```

**Status:** ✅ Fixed. All file uploads now succeed.

---

### Bug 4: `SupportedFileVersionsInfo.getSupportedFileVersion()` Throws NPE

**Symptom:** Vendored `getSupportedFileVersion(byte)` auto-unboxes `null` to `short` for handles not in the watch's map.

**Fix:** Wrapped in try/catch for NPE during the iteration in `initFossilProtocol()`. The vendored code is zero-patch, so the guard lives in our adapter code.

**Status:** ✅ Fixed (workaround).

---

## File Versions Reported by Watch

From `GetDeviceInfoRequest` → `SupportedFileVersionsInfo`:

| File Handle | Major | Version |
|-------------|-------|---------|
| OTA_FILE | 0x00 | 0 |
| ACTIVITY_FILE | 0x01 | 1 |
| HARDWARE_LOG_FILE | 0x02 | 2 |
| MUSIC_INFO | 0x04 | 4 |
| HAND_ACTIONS | 0x06 | 6 |
| SETTINGS_BUTTONS | 0x06 | 6 |
| CONFIGURATION | 0x08 | 8 |
| NOTIFICATION_PLAY | 0x09 | 9 |
| ALARMS | 0x0A | 10 |
| NOTIFICATION_FILTER | 0x0C | 12 |
| WATCH_PARAMETERS | 0x0E | 14 |
| REPLY_MESSAGES | 0x13 | 2 (hardcoded) |
| APP_CODE | 0x15 | 3 (hardcoded) |
