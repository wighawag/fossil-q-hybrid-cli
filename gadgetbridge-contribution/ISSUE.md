Title: Fossil Q Hybrid: Technical Findings, Real-Hardware Bug Reports, and Undocumented Handshakes (non-HR/Coin-cell Models)

#### Context & Scope

I have been developing and testing a standalone, native Linux client (`fossil-q-hybrid-cli`) for Fossil Q Hybrid watches on real hardware. My primary testbed is a Fossil Q Commuter (HW.0.0) running firmware HW0.0.2.9r.v3 (Fossil protocol 2.x).

During this development, I integrated the Gadgetbridge codebase's `qhybrid` service package verbatim and performed side-by-side comparative testing against the official Fossil companion app using BLE snoop/HCI captures.

Below is a catalog of critical bugs, missing protocol requirements, and hardware discoveries I observed on real hardware. I plan to follow up with safe, minimal PRs targeting the specific, easily correctable bugs. These proposed fixes have the significant benefit of requiring absolutely no UI changes to Gadgetbridge.

────────────────────────────────────────────────────────────────────────────────

### 1. Confirmed Code Bugs (PR Candidates — No UI Changes Required)

These bugs are safe, self-contained, and can be resolved entirely in the protocol/adapter backend without requiring any user interface changes or preference redesigns in Gadgetbridge.

#### A. Swapped Weekday Bitmask in Alarm Configuration

- **File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/alarm/Alarm.java`
- **Root Cause:**
  ```java
  public final int WEEKDAY_THURSDAY = 3;
  public final int WEEKDAY_WEDNESDAY = 4;
  ```
- **Hardware Evidence:** Real-hardware Tuesday, Wednesday, and Thursday alarm testing shows that alarms set for Wednesday actually fire on Thursday, and vice versa.
- **Correction:** Wednesday must map to bit 3 (value 8) and Thursday to bit 4 (value 16) to match the watch's internal alarm bitmask.

#### B. Package Name CRC Mismatch (Silent Failure of Notification Vibrations)

- **Files:**
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/notification/NotificationFilterPutRequest.java`
  - `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/notification/PlayNotificationRequest.java`
- **Root Cause:**
  In both files, the CRC32 checksum for the package name filter is computed over the raw package name string:
  ```java
  crc.update(config.getPackageName().getBytes());
  ```
- **Hardware Evidence:** In BLE captures, the official Fossil app calculates the package name CRC32 over the null-terminated package name string (e.g., `com.whatsapp\0` instead of `com.whatsapp`). Because of this mismatch, the watch uploads the filter, accepts notification files, but silently ignores them. Adding a `\0` terminator to the package bytes before updating the CRC engine resolves the issue.

#### C. Notification File Format (`lbl=10` vs `lbl=12`)

- **File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/notification/PlayNotificationRequest.java`
- **Root Cause:** GB writes the notification play file using a legacy/simplified header format of length 10 (`lengthBufferLength = 10`).
- **Hardware Evidence:** On `HW.0.0` coin-cell firmware, notification play files built with `lbl=10` are processed without error, but the vibration motor fails to trigger. The official app builds notification play files using a 12-byte header (`lengthBufferLength = 12`) with two appended metadata fields: an `0xFFFFFFFF` sentinel (4 bytes) and a LE Unix epoch timestamp (4 bytes). Upgrading to `lbl=12` restores reliable motor vibrations.

#### D. Overwriting Second Timezone Settings on Every Sync

- **File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/adapter/fossil/FossilWatchAdapter.java`
- **Root Cause:** In `syncConfiguration()`, the adapter adds `TimezoneOffsetConfigItem` (config ID `0x0011` / `17`) to the configuration payload written on every connection.
- **Hardware Evidence:** Decompiled official app source codes verify that config `17` is `DeviceConfigKey.SECOND_TIMEZONE_OFFSET`. The watch's primary display timezone is automatically driven by the offset field inside `TimeConfigItem` (config ID `0x000C` / `12`) sent during `setTime()`. Writing config `0x0011` during daily synchronization overwrites and destroys whatever second timezone the user manually set on their watch. (Note: config `17` uses `-720` to `840` minutes, and `1024` as a "disabled" sentinel). Removing this item from configuration sync prevents the overwrite.

#### E. Artificial Alarm Limit (5 slots vs 32)

- **File:** `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/qhybrid/QHybridCoordinator.java`
- **Root Cause:** `getAlarmSlotCount` is hardcoded to `5` for non-HR devices.
- **Hardware Evidence:** Systematic limit testing on the Q Commuter shows the hardware natively supports up to 32 alarms (exactly 96 bytes at 3 bytes per alarm). Setting 33+ alarms causes a silent transaction write timeout, but any number up to 32 works. The official app restricts this to 12 via software, but Gadgetbridge can safely expose the full 32 without any changes to the alarms UI.

────────────────────────────────────────────────────────────────────────────────

### 2. Protocol & Handshake Discoveries

#### A. Fossil Authentication Handshake (PROCESS_USER_AUTHORIZATION_V2)

Without a Fossil-level handshake over characteristic `3dda0005` (AUTH), the watch accepts files but silently refuses to act on notification filters or play commands.

- **The Handshake Protocol:**
  1. App writes `01 07` (GET_USER_AUTHORIZATION_STATUS) to `3dda0005`.
     - Watch replies `03 07 00` (Needs auth) or `03 07` (2-byte equivalent) or `03 07 01` (Already authorized).
  2. If status is `0` (Needs auth):
     - App writes `02 06 30 75 00 00 01` (confirm authorization request, 30s timeout).
     - The watch begins vibrating. The user must press the TOP button within 30 seconds.
     - The watch responds with `03 06 00 01` (Accepted) or `03 06 00 00` (Rejected/Timeout).
  3. Once authorized (`03 07 01` on subsequent connects), the watch persists the state, executes notification filters, and triggers vibrations.

*Implementation Note:* This authorization handshake can be cleanly implemented completely in the background without requiring any Android UI configuration changes. For example, it can be triggered automatically during initial device pairing/connection setup, displaying a standard Toast or background system Notification prompting the user to "Press the top button of your watch to authorize notifications" (and skipping silently on subsequent connections when `03 07 01` is received).

#### B. Button Configuration Multi-Entry Modes (Mode Toggle)

- **Discovery:** The Fossil "mode toggle" button function is built by sending multiple sequential 4-byte headers for a single button to `SETTINGS_BUTTONS` (handle `0x0600`), separated by `0x00` null bytes per entry.
- **Details:** The firmware cycles through the entries dynamically. If an entry has no data (e.g., an alarm is not set, or there are no unread notifications), the firmware silently skips that step.

#### C. AppId `0x1A` is ALARM, not STEP_GOAL_PROGRESS

In Gadgetbridge's `ConfigPayload` enum, AppId `0x1A` is labeled `STEP_GOAL_PROGRESS`. Real-hardware testing confirms AppId `0x1A` actually refers to `ALARM` (specifically, the sequenced display variant used in mode toggles to point the sub-eye hand to indicator C).

────────────────────────────────────────────────────────────────────────────────

### 3. Documentation / Code Cleanup

#### PlayCrazyShitRequest and Hand Actions

- **Findings:** `PlayCrazyShitRequest` writes to handle `0x0600` (which is shared with button configs). The watch distinguishes them by magic bytes (`01 00 00` for buttons, `01 00 08` for hand actions).
- **Hardware Behavior:** Standard coin-cell watch firmware returns success but produces zero haptic or physical hand movement on upload. It is currently unused/dead code in the repository.

────────────────────────────────────────────────────────────────────────────────

### Next Steps

I hope this context helps the core development team. I plan to follow up by posting a clean, self-contained PR resolving the bugs in Section 1 (Alarm days, CRC null terminators, `lbl=12` formatting, second timezone sync overwriting, and the alarm slot count limit).
