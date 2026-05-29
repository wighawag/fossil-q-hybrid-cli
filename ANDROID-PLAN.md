# Fossil Q Hybrid Android App — Implementation Plan

This document outlines the detailed architecture, database design, feature-by-feature implementation plan, and background service strategy for a dedicated, lightweight Android companion app for **Fossil Q Hybrid (coin-cell) watches** (e.g., Q Commuter, Q Activist).

The design focuses on offering maximum customizability, offline dependability, and a beautiful, modern UX, while reusing the existing Java protocol adapters and keeping the Gadgetbridge request code vendored and untouched.

---

## 1. Core Objectives & Architectural Paradigm

### Lightweight & Focused UX
Unlike Gadgetbridge (which supports 100+ devices with generic menus), this app is custom-tailored for Fossil Q Hybrid watches. Every UI screen, graph, and toggle is explicitly designed around the unique features of coin-cell watches.

### Shared Protocol Library (The Module Split)
We will refactor the current codebase into a Gradle multi-module project:

```
                  ┌──────────────────────────────────────────────┐
                  │              :protocol (Java)                │
                  │                                              │
                  │  - Vendored GB request classes (untouched)   │
                  │  - FossilQAdapter (queue, init, etc.)        │
                  │  - BleTransport (interface)                  │
                  │  - ButtonConfigBuilder & ActivityParser      │
                  │  - ButtonGestureDetector                     │
                  └──────────────┬────────────────┬──────────────┘
                                 │                │
                                 ▼                ▼
                     ┌──────────────────┐  ┌──────────────────┐
                     │   :cli (Java)    │  │ :android (Kotlin)│
                     │                  │  │                  │
                     │  - Linux CLI     │  │  - Android App   │
                     │  - DbusTransport │  │  - AndroidBle    │
                     │  - JLine TUI     │  │    Transport     │
                     │  - JSON files    │  │  - Room / Compose│
                     └──────────────────┘  └──────────────────┘
```

- **`:protocol`**: A pure-Java module with zero external platform dependencies. Compiles against Android stubs (for characteristic classes) on CLI/Linux, and against real Android classes on Android.
- **`BleTransport`**: The clean boundary.
  - CLI implements it via `DbusTransport` / `BluezTransport`.
  - Android implements it via `AndroidBleTransport` wrapping Android's native `BluetoothGatt` API.

### Keep Gadgetbridge Code Untouched (Compilation Flow)
All protocol-level command classes in `gadgetbridge/` remain vendored, unmodified, and clean. We bridge them in `:protocol` via our shims and `FossilQAdapter`, just as we did for the Linux CLI.

To support both the **Linux CLI** and the **Android App** in one unified multi-project layout, we will build a dual-target Gradle setup that reuses Gadgetbridge's official compilation pattern for the Android module:
- **Android Module (`:android`)**: This module will directly reuse Gadgetbridge's official Android Gradle build setup (including standard Android plugins, build types, and packaging rules). It compiles the required Fossil Q Hybrid classes directly from `gadgetbridge/app/src/main/java` and the shared protocol classes by declaring them in its `srcDirs`:
  ```groovy
  android {
      sourceSets {
          main {
              java {
                  srcDirs = ['../src/main/java', '../gadgetbridge/app/src/main/java']
              }
          }
      }
  }
  ```
  This is extremely clean: the Android app compiles exactly like Gadgetbridge does out-of-the-box, ensuring zero-configuration builds on checkout.
- **CLI Module (`:cli`)**: The Linux-based CLI will be a pure Java application module in the same multi-project tree, compiling our CLI code against the shared protocol code using the existing JVM source set configuration.
- This dual-target structure keeps the code unified and ensures we can continue using `sync.sh` to update the vendored Gadgetbridge files without breaking any platform-specific compilation flows or requiring manual file restructuring.

---

## 2. Platform Permissions & Setup Carousel (Gadgetbridge Paradigm)

To ensure a seamless initial setup, the app will feature an introductory carousel/wizard modeled after Gadgetbridge's permission request flow, but built using modern **Jetpack Compose** components.

```
 [1. Welcome] ──► [2. Bluetooth & Scan] ──► [3. Notification Access] ──► [4. Calendar Access] ──► [5. Battery Optimizations]
```

### Required Permissions & Rationale
1. **Bluetooth & Scan (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`)**:
   - Required on Android 12+ to find, connect, and pair (bond) with the watch.
   - For Android 10 and 11, `ACCESS_FINE_LOCATION` is requested as standard BLE scans can reveal coarse location.
2. **Notification Listener Access (`BIND_NOTIFICATION_LISTENER_SERVICE`)**:
   - Extremely critical. Required for the background service to intercept phone notifications, extract package names, and trigger vibrations/hand movements on the watch.
3. **Calendar Access (`READ_CALENDAR`)**:
   - Required to fetch upcoming events for the Google Calendar 7-day sync.
4. **Ignore Battery Optimizations (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)**:
   - Essential to prevent Android's Doze mode from killing the background GATT connection.

### Companion Device Manager (The Seamless Reconnection Secret)
Instead of running continuous, battery-heavy BLE scans in the background, we will use Android's **`CompanionDeviceManager`** (API 26+).
- The user pairs the watch through the app using `CompanionDeviceManager.associate()`.
- Once associated, the Android system grants our app special background execution privileges.
- Whenever the watch advertises nearby (e.g. after the user comes back into range), the Android system **automatically wakes our background service** and delivers a connection callback. This ensures instant, robust auto-reconnection without draining the phone's battery.

---

## 3. Database Schema & State Management (Room)

To support multiple watches, independent settings, and settings cloning, the app will store all local data in a **Room SQLite Database**. 

```
                                      ┌──────────────────────┐
                                      │       Watches        │
                                      │  (MAC, Name, Active) │
                                      └──────────┬───────────┘
                                                 │ 1
                                                 │
                        ┌────────────────────────┼────────────────────────┐
                        │ N                      │ N                      │ N
              ┌─────────▼──────────┐   ┌─────────▼──────────┐   ┌─────────▼──────────┐
              │    WatchAlarms     │   │ NotificationRules  │   │   ButtonMappings   │
              │ (Slot, Time, Days) │   │ (PkgName,Vibe,Hand)│   │ (Button, Actions)  │
              └────────────────────┘   └────────────────────┘   └────────────────────┘
```

### Entity Schemas

#### 1. Watch Entity (`watches` table)
Tracks all registered watches.
```kotlin
@Entity(tableName = "watches")
data class WatchEntity(
    @PrimaryKey val macAddress: String,
    val name: String,
    val model: String?,
    val firmwareVersion: String?,
    val batteryLevel: Int,
    val isActive: Boolean = false, // Active watch receives live notifications
    val stepGoal: Int = 10000,
    val vibrationStrength: Int = 50,
    val lastSyncTime: Long = 0
)
```

#### 2. Alarm Entity (`watch_alarms` table)
```kotlin
@Entity(
    tableName = "watch_alarms",
    primaryKeys = ["watchMac", "slotId"],
    foreignKeys = [ForeignKey(
        entity = WatchEntity::class,
        parentColumns = ["macAddress"],
        childColumns = ["watchMac"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class WatchAlarmEntity(
    val watchMac: String,
    val slotId: Int,             // 0 to 15 (Standard User Alarms)
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean,
    val daysMask: Int,           // Day bitmask (bit0=Sun, bit1=Mon ... bit6=Sat)
    val isRepeating: Boolean,    // true = repeats weekly, false = one-shot
    val label: String? = null
)
```

#### 3. Notification Rule Entity (`notification_rules` table)
```kotlin
@Entity(
    tableName = "notification_rules",
    primaryKeys = ["watchMac", "packageName"],
    foreignKeys = [ForeignKey(
        entity = WatchEntity::class,
        parentColumns = ["macAddress"],
        childColumns = ["watchMac"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class NotificationRuleEntity(
    val watchMac: String,
    val packageName: String,      // App package ID (e.g. "com.whatsapp")
    val vibePattern: Int,         // 0 to 9 (0=AUTO, 1=CALL, 2=TEXT, 3=EMAIL, 4=DEFAULT, etc.)
    val hourHandDegrees: Int,     // 0 to 359 (precise hand location)
    val minuteHandDegrees: Int    // 0 to 359
)
```

#### 4. Button Mapping Entity (`button_mappings` table)
```kotlin
@Entity(
    tableName = "button_mappings",
    primaryKeys = ["watchMac", "buttonId"],
    foreignKeys = [ForeignKey(
        entity = WatchEntity::class,
        parentColumns = ["macAddress"],
        childColumns = ["watchMac"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class ButtonMappingEntity(
    val watchMac: String,
    val buttonId: Int,            // 0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM
    val modeType: String,         // "SINGLE_ACTION" vs "MUSIC_MULTIMODE" vs "CUSTOM_TOGGLE"
    val actionsJson: String       // JSON list of actions / payloads (DATE, MUSIC, PHONE_RING, etc.)
)
```

### Settings Transfer / Clone Pattern
To implement **Settings Transfer** from Watch A to Watch B:
1. Fetch all `WatchAlarmEntity`, `NotificationRuleEntity`, and `ButtonMappingEntity` items where `watchMac == watchAMac`.
2. Map each item into a new instance, replacing `watchMac` with `watchBMac`.
3. Perform a database bulk-insert with `OnConflictStrategy.REPLACE` into the respective tables.
4. On the next BLE sync with Watch B, the app will compile and upload the newly copied button files, alarm slots, and notification filters!

---

## 4. Feature-by-Feature Implementation Details

### A. Core BLE Connection & Handshake (`AndroidBleTransport`)
- **Native BLE**: `AndroidBleTransport` handles `BluetoothGattCallback` natively.
- **Fossil Handshake**: After a successful GATT connection, the `FossilQAdapter` runs `GET_USER_AUTHORIZATION_STATUS` (`01 07`) on characteristic `3dda0005`.
- **Bonding Step**: If authorization is pending, the watch vibrates and the user clicks the top button. Once authorized (`03 06 00 01`), the app calls `device.createBond()` on Android to pair.
- **Auto-Sync**: On every successful reconnect/handshake, the app automatically triggers:
  1. Time Sync (combining UTC epoch in `0x000C` and correct Timezone Offset in `0x0011`).
  2. Step Count reading (`GetCurrentStepCountRequest`).
  3. Pending activity data download (`0x0100`) to store in the local app database.

### B. Alarm Management & The 16/16 Split
As verified in real-hardware binary searches, the watch firmware has **exactly 32 alarm slots** in its independent storage.
To deliver advanced functionality, we divide these slots into two zones:

```
                  ┌──────────────────────────────────────────────┐
                  │                 ALARM TABLE                  │
                  │              (32 slots, 96 bytes)            │
                  ├──────────────────────┬───────────────────────┤
                  │     Slots 0 - 15     │     Slots 16 - 31     │
                  │                      │                       │
                  │   Standard Alarms    │  Calendar Sync Alarms │
                  │  (User configured)   │   (Auto-generated)    │
                  └──────────────────────┴───────────────────────┘
```

1. **Slots 0 to 15 (Standard Alarms)**:
   - Configured directly by the user in the UI.
   - Uploaded as standard weekly-repeating alarms (`[0x80|days] [minute|0x80] [hour]`) or one-shot alarms (`[0xFF] [minute] [hour]`).
2. **Slots 16 to 31 (Calendar Sync Alarms)**:
   - Generated dynamically by the Google Calendar Background Sync.
   - Set as **non-repeating weekday alarms** using the undocumented mode verified in testing:
     `[0x80|days] [minute] [hour]` (without bit7 of byte1 set).
     These alarms fire exactly once on the target weekday (e.g. next Friday at 14:00) and then automatically de-activate on the watch without ever repeating!

### C. App Notifications per App (Vibe Pattern & Precise Hands)
Instead of forcing a single default vibration pattern like the official app, this app allows full granular customization of how the watch behaves for each separate app on the phone:

1. **Notification Interception (`NotificationListenerService`)**:
   - The background listener captures phone notifications.
   - If `com.whatsapp` sends a notification, the app looks up the corresponding `NotificationRuleEntity` in the database for the active watch.
2. **The 32-Byte Multi-Entry Filter**:
   - During watch synchronization, the app compiles all database-configured notification rules into a single concatenated byte array (32 bytes per app rule) using `buildNotificationFilterData` (using the package CRC derived from `"qhybrid.linux." + rule.name`).
   - The compiled file is uploaded to handle `FileHandle.NOTIFICATION_FILTER` (`0x0300`).
3. **Triggering the Vibration & Hands**:
   - When a WhatsApp notification is intercepted, the app dynamically constructs an official-format notification file (`buildOfficialNotificationFile`) specifying the target package name (`"qhybrid.linux.whatsapp"`).
   - The file is written to characteristic `3dda0003` under handle `FileHandle.NOTIFICATION_PLAY`.
   - The watch immediately parses the file, matches the package CRC against the uploaded filter table, and executes the **exact vibration pattern (0-9)** and **precise hand movement degree (0-359)** defined by the user!

### D. Google Calendar Sync (Offline 7-Day Alarms & Dual-Trigger Sync)
To warn the user about upcoming meetings and events even when they are physically separated from their phone:

1. **How Cloud Changes are Captured (Laptop/Web/Emails)**:
   - When you create an event on your laptop, accept an invite on the web, or confirm an event via email, the official Google Calendar app (or your account's sync adapter) syncs these updates down to your Android phone in the background.
   - When these sync events write new data to the local Android Calendar database, the Android system fires a content change notification.
   - Our **`ContentObserver`** listening on `CalendarContract.Events.CONTENT_URI` will **instantly detect** these sync writes, triggering a compile-and-upload cycle.

2. **The "Sync-on-Connect" Trigger (Companion Device Manager Integration)**:
   - To make the syncing bulletproof and ensure zero-lag even if a calendar event is modified while the watch is disconnected, we will implement an active **Sync-on-Connect** trigger.
   - Every time the watch reconnects or is detected nearby via the `CompanionDeviceManager` callback (`onDeviceAppeared`), the background service immediately:
     1. Performs a fresh Calendar query.
     2. Updates the local SQLite/Room alarm cache.
     3. Compiles and uploads the calendar alarms (slots 16–31) to the watch.
   - This ensures the watch is always synchronized with the absolute latest calendar state known to the phone the moment the BLE connection is established.

3. **Periodic Safety Job (`WorkManager`)**:
   - A background Worker runs every 4 hours (or once a day as a robust fallback) to capture any missed updates and perform routine cleanup.
   - It queries the system Calendar Provider via `ContentResolver` for events occurring over the **next 7 days** and filters/sorts the nearest 16 calendar events.

4. **Alarm Compilation & 16-Slot Limit**:
   - For each event, the app extracts the local date/time (e.g., Friday at 10:15 AM).
   - Maps it to a **non-repeating weekday alarm** in slots 16-31.
     - `byte0 = 0x80 | (1 << fridayBit)` -> `0x80 | 32` -> `0xA0`.
     - `byte1 = 15` (minutes).
     - `byte2 = 10` (hour).

5. **Upload**:
   - If the watch is currently connected, the database is updated and the alarm file is instantly compiled and uploaded to handle `FileHandle.ALARMS` (`0x0A00`).
   - If disconnected, the upload is queued and executes the moment the watch comes back in range.
   - Result: As long as the user connects their watch at least once a week, they will receive precise, vibrating physical watch alerts for their calendar events offline, with **zero phone connection required** during the event itself!

### E. Powerful Button Mapping & Dial Modes
The app utilizes our `ButtonConfigBuilder` to allow users to assign complex, rich functions to the watch buttons and understand the distinct roles of physical dial modes vs. phone-side actions.

1. **Physical Dial Modes (Sub-eye / Dial Indicator)**:
   - Only specific "modes" are supported as actual watch-face configurations because they physically drive the sub-dial/hands to indicate metadata. These modes are:
     - **Alert**: Shows the pointer indicating the last notification app.
     - **Timezone 2**: Drives hands to indicate a secondary time zone.
     - **Alarm**: Shows the pointer pointing to the next active alarm time.
     - **Date**: Moves the hands to indicate the current day of the month.
     - **24-Hour**: Displays the current 24-hour hour.
   - Note that due to varying physical watch faces and dials (e.g., 5-position dials on Q Activist vs 3-position dials on Q Commuter), certain watch models may physically ignore or lack support for specific modes. The app will hide incompatible modes based on the paired watch's hardware model info.

2. **Phone-Side Music Controls (Virtual Multi-Mode Button & MediaSession Integration)**:
   - Music control is **not** a physical dial/sub-eye mode. Instead, it is implemented entirely phone-side.
   - We map a watch button (e.g. MIDDLE) to trigger short, double-short, or long press events (`FORWARD_TO_PHONE` triggers on characteristic `3dda0006`).
   - The background service intercepts these events and passes them to our `ButtonGestureDetector`.
   - The detector buffers clicks within a configurable window (e.g., 400ms) to distinguish single/double presses and triggers standard Android Media Session controls.
   - **Android Implementation (The Notification Listener Synergy)**:
     - Because the user already granted **Notification Listener Access** (`NotificationListenerService`) for forward notifications, our app **automatically inherits permission** to use Android's **`MediaSessionManager`** without any extra permission prompts!
     - The background service will query `MediaSessionManager.getActiveSessions()` to find active media sessions (e.g., Spotify, YouTube, VLC, or Apple Music).
     - Instead of sending generic key events which are often ignored or swallowed by the system, our app will bind directly to the active session's **`MediaController`** and send precise transport controls:
       - **Single-Click**: `mediaController.getTransportControls().play()` or `pause()` depending on current playback state.
       - **Double-Click**: `mediaController.getTransportControls().skipToNext()`.
       - **Triple-Click / Long Press**: `mediaController.getTransportControls().skipToPrevious()` or custom mapping.
     - If no active media session is found (e.g. the music app was force-closed), the app falls back to a **Preferred Music App Auto-Launcher**:
       - The user can select a **Preferred Music App** (e.g., Bandcamp, Spotify, Poweramp, etc.) in settings.
       - If no active media session is running, the app will:
         1. Query the package manager and launch the target music app (using standard launcher `Intent`).
         2. Dispatch an explicit, targeted `ACTION_MEDIA_BUTTON` broadcast containing `KEYCODE_MEDIA_PLAY` aimed directly at that specific app's package and media button receiver. On Android, this targeted broadcast forces the OS to wake that specific player's background media service and start streaming playback immediately!
       - If no preferred app is configured, the app falls back to dispatching global key events (`KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE` via the system `AudioManager`) to launch and resume the last active player.
   - This keeps music controls highly responsive, extremely robust, and flexible without requiring any physical watch face support.

3. **Gesture Actions**:
   - **Phone Ring (Find My Phone)**: Triggers a loud, looping ringtone on the phone (bypassing Do Not Disturb). Double-clicking stops it.
   - **Custom Task/Goal Tracking**: Increment a custom metric (e.g., water cups drunk, habits tracked) which updates progress in the database and vibrates the watch to confirm.

### F. Interactive Calibration UX
An analog watch requires calibration so its internal computer knows where "12 o'clock" actually is. We will implement a visual, friendly calibration wizard in Compose:

```
          Visual Hand Alignment UI
             
                    ▲ [12:00]
                .   │   .
             .      │      .
            .       @       .    <-- Drag or Nudge hands
             .             .         until the real watch
                .       .            hands point straight up!
                    '
                
         [ HOUR ]   [ MINUTE ]   [ SUB-EYE ]
         [-] [+]     [-] [+]       [-] [+]
         
                  [ SAVE & SYNC ]
```

1. **Hand Control**: Tap "Start Calibration". The app sends `RequestHandControlRequest` to freeze the watch hands, then moves them to absolute 0° (approximate 12 o'clock) using `MoveHandsRequest`.
2. **Nudge Interaction**: The user can drag on-screen hands or tap `+` / `-` buttons. Each tap sends a relative/absolute move request to nudge the physical watch hands in real time.
3. **Save**: Clicking "Confirm" sends `SaveCalibrationRequest` to tell the watch "where the hands are pointing right now is your absolute 12:00:00 reference".
4. **Sync**: The app releases control (`ReleaseHandsControlRequest`) and immediately syncs the current time. The hands elegantly sweep to the correct current local time.

### G. Inactivity Nudge & Vibration Strength
To match the CLI capabilities and offer standard watch customization features:
1. **Inactivity Nudge Setting**:
   - The CLI supports enabling/disabling a sedentary "nudge" warning.
   - We will build a Settings screen allowing users to:
     - Toggle the nudge feature On/Off.
     - Select inactivity duration (minutes, 1-255).
     - Configure scheduling range: "From" (default: 08:00) and "To" (default: 20:00).
   - Changes will write a `ConfigurationPutRequest` specifying the `InactivityWarningItem` parameters to characteristic `3dda0003` via `:protocol`.
2. **Vibration Strength Slider**:
   - A standard slider (0% to 100%) in Settings will trigger `SetVibrationStrengthRequest` on change, adjusting how hard the watch vibrates for notifications and alarms.

### H. Sleep & Calorie Tracking (Native Hardware Data)
Instead of writing complex, inaccurate software estimators, we will extract sleep and calorie analytics natively from the watch's binary files:
1. **Calorie Calculations**:
   - Our `ActivityParser` reverse-engineers the raw calorie counts stored by the watch's onboard microprocessor in byte 3 of each minute record (`int calories = b3 & 0x3F`).
   - The app will save these exact calories in the Room database, displaying active calories burned alongside daily step counts in the dashboard. No third-party steps-to-calorie conversion algorithms are required since the watch hardware does it directly!
2. **Sleep Tracking Analysis**:
   - The watch's binary file tracks minute-by-minute activity steps, variability, and active flag state.
   - We will invoke the shared `ActivityParser.detectSleep(...)` logic directly inside the Android app to automatically parse slept blocks:
     - Detects sleep periods where steps are 0 and variability is below the adaptive median threshold.
     - Classifies restless sleep minutes (e.g., tossing/turning) vs quiet deep sleep.
     - Displays historical sleep charts (duration, sleep timeline, and overall sleep quality: "Good", "Fair", or "Restless") inside a dedicated Sleep Dashboard.

### I. App Log Viewer Screen (Operational vs. Debug Logs)
To provide transparency and a seamless developer/debugging experience, we will implement an in-app Log Viewer:
1. **Visual Log Console**:
   - A scrollable terminal-style view in Settings allowing real-time viewing of application logs.
2. **Level Filtering**:
   - **INFO Level**: Shows user-friendly high-level operational steps:
     - `"Connecting to Fossil Watch (D9:20:71:11:74:2A)..."`
     - `"Connected! Battery: 88%"`
     - `"Fossil Handshake success (Authenticated)."`
     - `"Synchronizing local watch time (UTC offset: +60 mins)..."`
     - `"Calendar Sync: Compiled 6 calendar events into alarms 16-21. Sync success."`
     - `"Notification: Intercepted WhatsApp. Play filter vibe 4, hands 90°/90°."`
   - **DEBUG Level**: Shows low-level details for troubleshooting:
     - GATT write requests, CCCD notification alerts, received raw hex byte arrays, database insert/updates, and internal exception stack traces.
3. **Log Export**:
   - Quick "Copy to Clipboard" and "Export to Text File" buttons to easily share logs for bug reports.

### J. Bidirectional Find-My-Phone & Animations
- **Find Watch (Phone-to-Watch)**: A prominent "Find Watch" button in the app dashboard triggers a repeating call notification or a specific hand choreography to locate a lost watch.
- **Find Phone (Watch-to-Phone)**: Intercepting double-clicks of buttons configured as `FORWARD_TO_PHONE` will bypass Silent/DND modes to play a loud, repeating loop of the user's ringtone until stopped in the app.
- **Hand Animations (Diagnostics)**: Expose a diagnostic utility sub-menu allowing users to play watch animations (`AnimationRequest` commands) to verify motor health and test physical hands movement.

---

## 5. Technical Stack

| Category | Choice | Rationale |
|---|---|---|
| **Language** | Kotlin / Java | Modern standard; easily compiles shared Java protocol classes. |
| **UI Framework** | Jetpack Compose | Modern declarative UI, lightweight, reactive, no XML layouts. |
| **Local Database** | Room (SQLite) | Standard Android abstraction; handles complex relational data. |
| **Preferences** | Jetpack DataStore | Thread-safe, coroutine-powered replacement for SharedPreferences. |
| **BLE APIs** | Native `android.bluetooth` | High performance, no bulky wrapper dependencies; direct GATT control. |
| **Background Jobs**| WorkManager | Handles system-integrated background scheduling & battery constraints. |
| **Service Control**| `NotificationListenerService` + `CompanionDeviceManager` | Guarantees connection reliability in the background. |

### UI Approach & Rationale (Decision: Jetpack Compose)
We deliberately build the entire app UI with **Jetpack Compose + Kotlin**, Google's official recommended toolkit for all new Android apps since 2021. This is the current best practice, not a legacy choice.

- **Modern, declarative UI**: Screens are described as a function of state (`@Composable`). When state changes (e.g. battery level, connection status, alarm list), the UI updates automatically. No XML layouts, no `findViewById`, no `RecyclerView`/Adapter/ViewHolder boilerplate — a scrolling list is simply `LazyColumn { items(...) }`.
- **Why NOT the older "XML Views + RecyclerView" approach (which Gadgetbridge uses)**: Gadgetbridge predates Compose (~2015) and carries a large, mature Views-based codebase for 100+ devices; rewriting it would be pointless for them. That is a *historical* fact about *their* codebase, not a technical recommendation. Building our own focused app gives us a clean, modern, Compose-native foundation tailored to one watch family — which is itself a core reason for not just extending Gadgetbridge.
- **Clean language split (no friction)**: `:protocol` stays **Java** (the reused, untouched Gadgetbridge protocol classes + `FossilQAdapter`); `:android` is **Kotlin + Compose**. Kotlin/Compose calls Java seamlessly, so reusing the Java protocol layer costs us nothing.
- **Reusing Gadgetbridge's UI logic, not its screens**: Because GB's permission/welcome wizard is built in XML Views + RecyclerView, we do **not** drop its screens into our Compose app (the paradigms don't mix cleanly). Instead we copy GB's permission *logic* (e.g. which permissions, how to check them, Android-version branching from `PermissionsUtils`) and rebuild the *UI* in Compose. This applies to all borrowed Gadgetbridge UI: borrow the logic, re-express the UI in Compose.
- **State management**: MVVM with `ViewModel` + `StateFlow`, the standard Compose architecture pattern, backed by Room (data) and DataStore (preferences).

---

## 6. Detailed Implementation Phases

We will execute the implementation in the following sequential milestones:

### Phase 1: Gradle Restructuring & CLI Alignment
- **Step 1**: Split the Gradle build into `:protocol` and `:cli` modules.
- **Step 2**: Ensure the Linux CLI remains 100% functional with the new layout, reading standard library functions from `:protocol`.
- **Step 3**: Verify compiling of `:protocol` classes against standard JVM shims without compilation errors.

### Phase 2: Android Module Scaffolding & Native BLE
- **Step 1**: Create the `:android` module with minimum SDK 26 (Android 8.0) and target SDK 34 (Android 14).
- **Step 2**: Implement `AndroidBleTransport` implementing the shared `BleTransport` interface.
- **Step 3**: Verify core BLE connection, pairing/bonding sequence, and the Fossil Authentication Handshake on real watch hardware.

### Phase 3: Database & Multi-Watch Management
- **Step 1**: Set up Room entities, DAOs, and database migrations.
- **Step 2**: Implement multi-watch configuration loading and settings clone/transfer database operations.
- **Step 3**: Verify database persistence of standard user alarms, custom notification rules, and button mappings.

### Phase 4: UI Development (Compose Dashboard)
- **Step 1**: Build the main setup carousel, guiding the user through Bluetooth, Notification, and Calendar permissions.
- **Step 2**: Build the Main Dashboard featuring watch connection status, battery level, step count progress, and the active watch selector.
- **Step 3**: Build the interactive Hand Calibration visual screen.

### Phase 5: Notification Service & Custom Buttons
- **Step 1**: Implement `NotificationListenerService` to intercept incoming phone notifications.
- **Step 2**: Write the package-name rule resolver, mapping intercepted apps to customized vibration and hand degree configs.
- **Step 3**: Implement the button configuration screen, generating and uploading multi-entry button binary payloads to handle `0x0600` via `ButtonConfigBuilder`.

### Phase 6: Google Calendar Background Sync Worker
- **Step 1**: Write the Google Calendar query service (`ContentResolver` read) to capture primary calendar events.
- **Step 2**: Write the scheduler compiling events into non-repeating weekday alarms (slots 16-31).
- **Step 3**: Integrate `WorkManager` to run the periodic sync task, verifying that alarms ring completely offline.
- **Step 4**: Extensive end-to-end testing, bug fixing, and package release.

---

## 7. Crucial Guardrails & Safety Precautions (Project Instructions)

- **Do Not Autocommit**: All code changes and Gradle module modifications will be left staged/unstaged for the developer to inspect. We will not commit or push any changes.
- **Do Not Auto-Uninstall/Stop Services**: If cleaning up BLE adapters, we will explicitly ask the developer before executing commands like stopping system BlueZ services or killing processes.
- **Safe Port Cleanup**: No processes will be terminated without identifying them via `lsof` first.
- **User Confirmation**: This plan must be thoroughly reviewed and accepted by the user before code changes begin.
