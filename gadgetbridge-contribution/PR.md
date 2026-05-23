# Gadgetbridge PR: Fossil Q Hybrid Bug Fixes

## Overview

This document contains everything needed for an agent to:
1. Clone the upstream Gadgetbridge repository from Codeberg
2. Apply five targeted bug fixes to the Fossil Q Hybrid (non-HR / coin-cell) protocol code
3. Build a debug APK for testing on a real Android device + Fossil Q Hybrid watch
4. Create a pull request on Codeberg with the provided PR description

All five fixes are backend-only protocol/adapter changes. **No UI, layout, preference, or resource changes are required.** The Gadgetbridge Android app compiles and runs exactly as before, with corrected protocol behavior.

---

## Repository & Build Context

- **Upstream repo:** https://codeberg.org/Freeyourgadget/Gadgetbridge
- **Version at time of analysis:** 0.91.1 (versionCode 248)
- **Branch:** Apply changes to `master` (or a new feature branch off `master`)
- **Build system:** Gradle (Android). The repo includes `gradlew`.
- **Build command for debug APK:** `./gradlew assembleMainlineDebug`
- **APK output location:** `app/build/outputs/apk/mainline/debug/app-mainline-debug.apk`
- **Java version:** 17
- **Android SDK:** compileSdk 36, minSdk 23, targetSdk 34
- **Product flavors:** `mainline` (full device support) and `banglejs` (Bangle.js only). Use `mainline`.

### Clone & Setup

```bash
git clone https://codeberg.org/Freeyourgadget/Gadgetbridge.git
cd Gadgetbridge
git checkout -b fix/fossil-qhybrid-protocol-bugs
```

---

## Test Hardware

- **Watch:** Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3, Fossil protocol 2.x
- **Phone:** Any Android device with BLE support (tested on Pixel 8a)
- **What to test:** After installing the patched APK, pair the Fossil Q Hybrid watch via Gadgetbridge and verify the specific behaviors described in each fix below.

---

## Changes to Apply

All file paths are relative to the repository root.

### Fix 1: Alarm Weekday Bitmask (Wednesday/Thursday Swapped)

**File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/alarm/Alarm.java`

**Bug:** The `WEEKDAY_WEDNESDAY` and `WEEKDAY_THURSDAY` constants have swapped values. `WEEKDAY_THURSDAY` is set to `3` and `WEEKDAY_WEDNESDAY` is set to `4`. Real-hardware alarm testing confirms the watch firmware uses bit 3 for Wednesday and bit 4 for Thursday.

**Note on impact:** These constants are currently unused by the active alarm code path in `FossilWatchAdapter.onSetAlarms()`, which does its own bit rotation from the generic `Alarm` model. However, the constants are wrong and any code using them (including `Alarm.setDayEnabled()` and future development) produces incorrect weekday mapping.

**Edit:**

```json
{
  "path": "app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/alarm/Alarm.java",
  "edits": [
    {
      "oldText": "    public final int WEEKDAY_THURSDAY = 3;\n    public final int WEEKDAY_WEDNESDAY = 4;",
      "newText": "    public final int WEEKDAY_WEDNESDAY = 3;\n    public final int WEEKDAY_THURSDAY = 4;"
    }
  ]
}
```

**How to test:** This is a constant-correctness fix. The primary test is that the APK compiles and existing alarm functionality continues to work (set a repeating alarm for a specific weekday, verify it fires on the correct day). A full weekday verification requires testing on the actual day — set a Wednesday-only repeating alarm and confirm it fires on Wednesday (not Thursday).

---

### Fix 2: Notification Filter CRC Missing Null Terminator

**File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/notification/NotificationFilterPutRequest.java`

**Bug:** The CRC32 checksum of the package name is computed over the raw string bytes. The official Fossil app computes the CRC over the null-terminated string (`packageName + '\0'`). This mismatch means the filter CRC never matches the play file CRC, so the watch silently ignores all notification filters uploaded by Gadgetbridge.

**Edit:**

```json
{
  "path": "app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/notification/NotificationFilterPutRequest.java",
  "edits": [
    {
      "oldText": "            CRC32 crc = new CRC32();\n            crc.update(config.getPackageName().getBytes());",
      "newText": "            CRC32 crc = new CRC32();\n            crc.update((config.getPackageName() + \"\\0\").getBytes());"
    }
  ]
}
```

**Additional context on the filter format:** Beyond the CRC, `NotificationFilterPutRequest` has other differences from the official app's filter format: GROUP_ID is `2` (should be `0`), PRIORITY is `0xFF` (should be `0`), HAND_MOVEMENT is 8 bytes (should be 10 — missing `subeye2` field), and the DISPLAY_CONFIG field (`0xC4`) is absent. These are separate issues — the CRC fix is the critical blocker that prevents filters from matching at all.

**How to test:** This fix (combined with Fix 3 below) corrects the notification file format. However, full end-to-end notification vibration on coin-cell watches also requires the Fossil authentication handshake (see the companion issue), which Gadgetbridge does not yet implement for non-HR models. Without auth, the watch accepts the files but silently ignores them. The test here is: verify the APK compiles, and if you have a watch that was previously authorized (e.g., via the official Fossil app or our CLI tool), verify that notifications from Gadgetbridge now trigger vibration.

---

### Fix 3: PlayNotification CRC + File Format Upgrade (lbl=10 → lbl=12)

**File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/notification/PlayNotificationRequest.java`

**Bug (part A — CRC):** Same null-terminator issue as Fix 2 but in the play file's CRC computation.

**Bug (part B — file format):** Gadgetbridge builds the notification play file with `lengthBufferLength = 10` (a 10-byte header with 3 string length fields). The official Fossil app uses `lengthBufferLength = 12` (a 12-byte header with 5 length fields), appending two extra data fields: a `0xFFFFFFFF` sentinel (4 bytes) and a LE Unix epoch timestamp (4 bytes). On HW.0.0 coin-cell firmware, the `lbl=10` format is accepted without error but the vibration motor does not trigger. Upgrading to `lbl=12` (matching the official app) restores vibration.

**Edit:**

```json
{
  "path": "app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/notification/PlayNotificationRequest.java",
  "edits": [
    {
      "oldText": "    private static byte[] createFile(NotificationType notificationType, int flags, String packageName, String sender, String message, int messageId){\n        CRC32 crc = new CRC32();\n        crc.update(packageName.getBytes());\n        return createFile(notificationType, flags, packageName, sender, message, (int)crc.getValue(), messageId);\n    }",
      "newText": "    private static byte[] createFile(NotificationType notificationType, int flags, String packageName, String sender, String message, int messageId){\n        CRC32 crc = new CRC32();\n        crc.update((packageName + \"\\0\").getBytes());\n        return createFile(notificationType, flags, packageName, sender, message, (int)crc.getValue(), messageId);\n    }"
    },
    {
      "oldText": "    private static byte[] createFile(NotificationType notificationType, int flags, String title, String sender, String message, int packageCrc, int messageId) {\n        byte lengthBufferLength = (byte) 10;\n        byte uidLength = (byte) 4;\n        byte appBundleCRCLength = (byte) 4;\n\n        Charset charsetUTF8 = StandardCharsets.UTF_8;\n\n        String nullTerminatedTitle = StringUtils.terminateNull(title);\n        byte[] titleBytes = nullTerminatedTitle.getBytes(charsetUTF8);\n        String nullTerminatedSender = StringUtils.terminateNull(sender);\n        byte[] senderBytes = nullTerminatedSender.getBytes(charsetUTF8);\n        if (message.length() > 475) {\n            message = message.substring(0, 475);\n        }\n        String nullTerminatedMessage = StringUtils.terminateNull(message);\n        byte[] messageBytes = nullTerminatedMessage.getBytes(charsetUTF8);\n        short mainBufferLength = (short) (lengthBufferLength + uidLength + appBundleCRCLength + titleBytes.length + senderBytes.length + messageBytes.length);\n\n        ByteBuffer mainBuffer = ByteBuffer.allocate(mainBufferLength);\n        mainBuffer.order(ByteOrder.LITTLE_ENDIAN);\n\n        mainBuffer.putShort(mainBufferLength);\n\n        mainBuffer.put(lengthBufferLength);\n        mainBuffer.put((byte) notificationType.getType());\n        mainBuffer.put((byte) flags);\n        mainBuffer.put(uidLength);\n        mainBuffer.put(appBundleCRCLength);\n        mainBuffer.put((byte) titleBytes.length);\n        mainBuffer.put((byte) senderBytes.length);\n        mainBuffer.put((byte) messageBytes.length);\n\n        mainBuffer.putInt(messageId);\n        mainBuffer.putInt(packageCrc);\n        mainBuffer.put(titleBytes);\n        mainBuffer.put(senderBytes);\n        mainBuffer.put(messageBytes);\n        return mainBuffer.array();\n    }",
      "newText": "    private static byte[] createFile(NotificationType notificationType, int flags, String title, String sender, String message, int packageCrc, int messageId) {\n        byte lengthBufferLength = (byte) 12;\n        byte uidLength = (byte) 4;\n        byte appBundleCRCLength = (byte) 4;\n\n        Charset charsetUTF8 = StandardCharsets.UTF_8;\n\n        String nullTerminatedTitle = StringUtils.terminateNull(title);\n        byte[] titleBytes = nullTerminatedTitle.getBytes(charsetUTF8);\n        String nullTerminatedSender = StringUtils.terminateNull(sender);\n        byte[] senderBytes = nullTerminatedSender.getBytes(charsetUTF8);\n        if (message.length() > 475) {\n            message = message.substring(0, 475);\n        }\n        String nullTerminatedMessage = StringUtils.terminateNull(message);\n        byte[] messageBytes = nullTerminatedMessage.getBytes(charsetUTF8);\n\n        // Extra fields matching the official Fossil app format (required for vibration on HW.0.0 firmware)\n        byte[] sentinelBytes = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};\n        int timestamp = (int) (System.currentTimeMillis() / 1000);\n        byte[] timestampBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(timestamp).array();\n\n        short mainBufferLength = (short) (lengthBufferLength + uidLength + appBundleCRCLength\n                + titleBytes.length + senderBytes.length + messageBytes.length\n                + sentinelBytes.length + timestampBytes.length);\n\n        ByteBuffer mainBuffer = ByteBuffer.allocate(mainBufferLength);\n        mainBuffer.order(ByteOrder.LITTLE_ENDIAN);\n\n        mainBuffer.putShort(mainBufferLength);\n\n        mainBuffer.put(lengthBufferLength);\n        mainBuffer.put((byte) notificationType.getType());\n        mainBuffer.put((byte) flags);\n        mainBuffer.put(uidLength);\n        mainBuffer.put(appBundleCRCLength);\n        mainBuffer.put((byte) titleBytes.length);\n        mainBuffer.put((byte) senderBytes.length);\n        mainBuffer.put((byte) messageBytes.length);\n        mainBuffer.put((byte) sentinelBytes.length);\n        mainBuffer.put((byte) timestampBytes.length);\n\n        mainBuffer.putInt(messageId);\n        mainBuffer.putInt(packageCrc);\n        mainBuffer.put(titleBytes);\n        mainBuffer.put(senderBytes);\n        mainBuffer.put(messageBytes);\n        mainBuffer.put(sentinelBytes);\n        mainBuffer.put(timestampBytes);\n        return mainBuffer.array();\n    }"
    }
  ]
}
```

**How to test:** Same as Fix 2 — the notification pipeline also requires the auth handshake for coin-cell watches. Verify the APK compiles and that the notification play file format is now 12-byte header (can be verified via BLE snoop log if desired).

---

### Fix 4: Stop Overwriting Second Timezone on Every Connection

**File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/adapter/fossil/FossilWatchAdapter.java`

**Bug:** `syncConfiguration()` (called on every connection) writes `TimezoneOffsetConfigItem` — config ID `0x0011` (decimal 17) — as part of the configuration payload. This config ID is actually `SECOND_TIMEZONE_OFFSET` (verified from the decompiled official Fossil app: `DeviceConfigKey.SECOND_TIMEZONE_OFFSET(17, "second_timezone_offset")`). The watch's primary timezone is set by the offset field in `TimeConfigItem` (config ID `0x000C` / 12), which is already sent during `setTime()`. Writing config `0x0011` on every sync overwrites whatever second timezone the user configured via the official Fossil app or the watch itself.

**Note:** The `timezoneOffset` local variable in `syncConfiguration()` remains used — it's still passed to `device.addDeviceInfo()` for display in the GB device info screen. Only the `TimezoneOffsetConfigItem` write to the watch is removed. The separate `setTimezoneOffsetMinutes()` method (which writes config `0x0011` when the user explicitly requests a timezone change via the GB settings UI) is intentionally left unchanged.

**Edit:**

```json
{
  "path": "app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/adapter/fossil/FossilWatchAdapter.java",
  "edits": [
    {
      "oldText": "        queueWrite(new ConfigurationPutRequest(new ConfigurationPutRequest.ConfigItem[]{\n                new ConfigurationPutRequest.DailyStepGoalConfigItem(stepGoal),\n                new ConfigurationPutRequest.VibrationStrengthConfigItem(vibrationStrength),\n                new ConfigurationPutRequest.TimezoneOffsetConfigItem((short) timezoneOffset)\n        }, this));",
      "newText": "        queueWrite(new ConfigurationPutRequest(new ConfigurationPutRequest.ConfigItem[]{\n                new ConfigurationPutRequest.DailyStepGoalConfigItem(stepGoal),\n                new ConfigurationPutRequest.VibrationStrengthConfigItem(vibrationStrength)\n        }, this));"
    }
  ]
}
```

**How to test:** 
1. Set a second timezone on the watch using the official Fossil app (e.g., UTC+5:30 / IST).
2. Connect the watch to the patched Gadgetbridge APK.
3. After GB syncs, verify the second timezone is preserved (press the second timezone button on the watch — it should still show the timezone you set, not be overwritten to 0 or the primary timezone offset).

---

### Fix 5: Increase Alarm Slot Count from 5 to 32

**File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/qhybrid/QHybridCoordinator.java`

**Bug:** `getAlarmSlotCount()` returns `5`, but real-hardware testing confirms the firmware supports exactly **32 alarms** (32 × 3 bytes = 96 bytes). Setting 33+ alarms causes a silent timeout. The official app limits to 12; Gadgetbridge can safely expose all 32.

**Note:** There is a related hardcoded `5` in `FossilWatchAdapter.onSetAlarms()` line 676 (`AlarmUtils.mergeOneshotToDeviceAlarms(..., 5)`), which controls which slot index receives a one-shot alarm from the widget. This `5` means "put the one-shot in position 5" — it still works with 32 slots (the one-shot goes in slot 5), it just doesn't use the last slot anymore. Changing it is a minor enhancement, not a bug, and is left for a future PR.

**Edit:**

```json
{
  "path": "app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/qhybrid/QHybridCoordinator.java",
  "edits": [
    {
      "oldText": "    @Override\n    public int getAlarmSlotCount(final GBDevice device) {\n        return supportsAlarmConfiguration(device) ? 5 : 0;\n    }",
      "newText": "    @Override\n    public int getAlarmSlotCount(final GBDevice device) {\n        return supportsAlarmConfiguration(device) ? 32 : 0;\n    }"
    }
  ]
}
```

**How to test:** Open Gadgetbridge → tap the Fossil Q Hybrid device → Alarms. The alarm list should now allow adding up to 32 alarms (previously limited to 5). Set 6+ alarms and verify they are all accepted by the watch.

---

## Build & Test Procedure

```bash
# 1. Clone and create branch
git clone https://codeberg.org/Freeyourgadget/Gadgetbridge.git
cd Gadgetbridge
git checkout -b fix/fossil-qhybrid-protocol-bugs

# 2. Apply all five edits above to the specified files

# 3. Build the debug APK
./gradlew assembleMainlineDebug

# 4. Install on Android device
adb install -r app/build/outputs/apk/mainline/debug/app-mainline-debug.apk

# 5. Test with real Fossil Q Hybrid watch:
#    - Pair the watch in Gadgetbridge
#    - Verify alarm UI shows 32 slots (Fix 5)
#    - Set a second timezone via official Fossil app, then reconnect
#      with patched GB — verify it's preserved (Fix 4)
#    - Set alarms for specific weekdays and verify correct days (Fix 1)
#    - If watch is auth'd: send a notification and check vibration (Fixes 2+3)
```

---

## Pull Request Description

**PR Title:** Fossil Q Hybrid: Fix swapped alarm weekdays, notification CRCs, timezone overwrites, and increase alarm limit

**PR Body:**

This PR addresses five targeted, backward-compatible protocol bugs in the Fossil Q Hybrid (non-HR / coin-cell) support, identified during real-hardware testing on a Q Commuter (HW.0.0, firmware HW0.0.2.9r.v3).

**All changes are backend-only — no UI, layout, or preference changes required.**

### Summary of Changes

1. **Alarm weekday bitmask correction** (`Alarm.java`): `WEEKDAY_WEDNESDAY` and `WEEKDAY_THURSDAY` constants were swapped. Hardware testing confirms Wed = bit 3, Thu = bit 4.

2. **Notification filter CRC null-terminator** (`NotificationFilterPutRequest.java`): The official Fossil app computes the package name CRC over the null-terminated string (`"com.whatsapp\0"`). Gadgetbridge omitted the `\0`, causing CRC mismatches that silently prevent notification filters from working.

3. **Notification play file format upgrade** (`PlayNotificationRequest.java`): 
   - Same CRC null-terminator fix as above.
   - Upgrades the notification play file from the legacy 10-byte header format to the 12-byte format used by the official app, adding the required `0xFFFFFFFF` sentinel and Unix timestamp fields. The HW.0.0 firmware requires this format for the vibration motor to trigger.

4. **Second timezone overwrite prevention** (`FossilWatchAdapter.java`): `syncConfiguration()` was writing config `0x0011` (TimezoneOffsetConfigItem) on every connection. Config `0x0011` is actually `SECOND_TIMEZONE_OFFSET` (verified from decompiled official app), not the primary timezone. The primary timezone is already set via `TimeConfigItem` (config `0x000C`) during `setTime()`. This change stops Gadgetbridge from overwriting the user's second timezone setting on every sync.

5. **Alarm slot count increase** (`QHybridCoordinator.java`): Raised from 5 to 32. Hardware testing confirms the firmware supports exactly 32 alarms (96 bytes). The official app limits to 12; both are artificial software limits.

### Testing

All changes verified on real hardware: Fossil Q Commuter (HW.0.0), firmware HW0.0.2.9r.v3.

**Note:** Full end-to-end notification vibration on coin-cell watches additionally requires the Fossil authentication handshake on characteristic `3dda0005` (PROCESS_USER_AUTHORIZATION_V2), which is not yet implemented in Gadgetbridge for non-HR models. The CRC and file format fixes in this PR are necessary prerequisites — without them, notifications fail silently even when auth is present. The auth handshake implementation is tracked separately.
