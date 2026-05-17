# Fossil Q Hybrid — Linux Desktop CLI

**Goal**: Reuse GadgetBridge's Fossil Q Hybrid protocol layer in a Linux desktop CLI app.

**Target devices**: Fossil Q Commuter (HW.0.0), Q Activist (HL.0.0), Hybrid HR (IV.0.0), Hybrid HR Collider (DN.1.0), Gen 6 Hybrid (WA.0.0/VA.0.0)

**Repo strategy**: Vendor fork in `gadgetbridge/` subdirectory, exact upstream paths. Sync = `cp -r` from upstream. Our code in `src/` — clean separation.

---

## Analysis Results (from cloned upstream @ a0948ee)

### Protocol layer is remarkably clean

~8,200 lines across ~50 files in `requests/`, `file/`, `parser/`, `encoder/`, `buttonconfig/`.

**Zero imports from outside the qhybrid package.** The protocol layer is fully self-contained — no references to `GB.`, `GBApplication`, `DBHandler`, or any other GadgetBridge internal.

**Only 7 files have Android imports, all trivial:**

| File | Android dep | Fix |
|------|-------------|-----|
| `Request.java` | `android.util.Log` + `BluetoothGattCharacteristic` in `handleResponse()` signature | Replace Log with slf4j (already imported). Change `handleResponse(BluetoothGattCharacteristic)` → `handleResponse(byte[])`. 19 call sites call `characteristic.getValue()` — move that extraction up to the caller. |
| `AlarmsGetRequest.java` | `android.widget.Toast` (import only, never used in code) | Delete import |
| `FileCloseAndPutRequest.java` | `android.widget.Toast` (import only, never used) | Delete import |
| `DeviceSecurityVersionInfo.java` | `android.util.Log` | Replace with slf4j |
| `EraseFileRequest.java` | `android.util.Log` | Replace with slf4j |
| `GetCountdownSettingsRequest.java` | `android.util.Log` | Replace with slf4j |
| `RequestMtuRequest.java` | `android.os.Build` + `@RequiresApi` | Remove — MTU request doesn't need Android version check on Linux |
| `FirmwareFilePutRequest.java` | `android.content.Context` + `GB.updateInstallNotification` + `R.string.*` | Stub out progress callbacks. This file is only needed for firmware flashing — low priority, can skip initially. |

**Total protocol-layer changes: ~15 lines across 7 files.** Everything else compiles as-is.

### FossilWatchAdapter is the integration point

911 lines. ~100 references to GB internals. Key BLE touchpoints:

- `queueWrite()` (4 overloads, lines 813-891) — calls `getDeviceSupport().getCharacteristic()` + `TransactionBuilder`
- `onCharacteristicChanged()` (line 641) — dispatches incoming data to request handlers
- `onCharacteristicWrite()` (line 226) — handles write confirmations
- `onConnectionStateChange()` (line 243) — handles connect/disconnect
- `onMtuChanged()` (line 794) — tracks MTU for file transfers
- `handleHeartRateCharacteristic()` (line 695) — parses heart rate data
- `handleBackgroundCharacteristic()` (line 744) — handles background image upload responses

**Decision: Write FossilWatchAdapterLinux from scratch (~500 lines), not subclass.**

Rationale: `FossilWatchAdapter` has ~100 references to `GBDevice`, `GB.toast`, `GB.log`, `SharedPreferences`, `TransactionBuilder`, `DBHandler`, `HybridHRActivitySampleProvider`, `AlarmUtils`, `PackageConfigHelper`, etc. Subclassing would require stubbing all of these. The actual protocol logic (request queue, file transfer orchestration, config push/pull) is ~400 lines and straightforward to reimplement cleanly using the same request classes.

### QHybridSupport is Android glue

854 lines. Heavily coupled to Android (`TransactionBuilder`, `Intent`, `BroadcastReceiver`, `AudioManager`, `NotificationListener`). We write `QHybridLinux` from scratch (~300 lines) — it's just device initialization, notification routing, and time sync orchestration.

---

## Repo Structure

```
fossil-qhybrid-linux/
├── gadgetbridge/                          # Vendor fork — read-only
│   └── app/src/main/java/nodomain/freeyourgadget/gadgetbridge/
│       └── service/devices/qhybrid/
│           ├── requests/
│           │   ├── Request.java           # PATCHED (handleResponse signature)
│           │   ├── fossil/                # VERBATIM (minus 3 import fixes)
│           │   └── misfit/                # VERBATIM (minus 2 import fixes)
│           ├── file/FileHandle.java       # VERBATIM
│           ├── parser/                    # VERBATIM
│           ├── encoder/RLEEncoder.java    # VERBATIM
│           └── buttonconfig/             # VERBATIM
│
├── src/main/java/qhybrid/linux/
│   ├── Main.java                          # CLI entry point (picocli)
│   ├── BleTransport.java                  # Interface
│   ├── BluezTransport.java                # BlueZ D-Bus via busctl subprocess
│   ├── FossilWatchAdapterLinux.java       # Clean reimplementation (~500 lines)
│   ├── QHybridLinux.java                  # Device orchestration (~300 lines)
│   └── ConfigStore.java                   # JSON file config (replaces SharedPreferences)
│
├── build.gradle                           # Gradle with application plugin
├── gradlew                                # Gradle wrapper
├── sync.sh                                # Script: cp -r from upstream + re-apply patches
├── PATCHES.md                             # Documented patch list for re-application
└── README.md
```

---

## Phase 1: Vendor Fork + Patch Protocol Layer

### 1a. Copy upstream

```bash
cp -r gadgetbridge-upstream/.../qhybrid/requests/   gadgetbridge/.../qhybrid/requests/
cp -r gadgetbridge-upstream/.../qhybrid/file/        gadgetbridge/.../qhybrid/file/
cp -r gadgetbridge-upstream/.../qhybrid/parser/      gadgetbridge/.../qhybrid/parser/
cp -r gadgetbridge-upstream/.../qhybrid/encoder/     gadgetbridge/.../qhybrid/encoder/
cp -r gadgetbridge-upstream/.../qhybrid/buttonconfig/ gadgetbridge/.../qhybrid/buttonconfig/
```

### 1b. Apply documented patches

**`Request.java`** — the only structural change:
```java
// OLD
import android.bluetooth.BluetoothGattCharacteristic;
public void handleResponse(BluetoothGattCharacteristic characteristic) {}

// NEW
public void handleResponse(byte[] data) {}
```

All 19 `handleResponse` implementations call `characteristic.getValue()` as their first line. The caller (FossilWatchAdapterLinux) extracts bytes before dispatching.

**6 trivial import fixes** — replace `android.util.Log` with slf4j (already imported in those files), delete unused `Toast` imports, remove `Build`/`@RequiresApi` from `RequestMtuRequest.java`.

**`FirmwareFilePutRequest.java`** — skip initially (only needed for firmware flashing). If needed later, stub the progress callbacks.

### 1c. Create `sync.sh`

```bash
#!/bin/bash
# Sync from a local Gadgetbridge clone
GB_REPO=${1:-../gadgetbridge-upstream}
SRC="$GB_REPO/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid"
DST="gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid"

for dir in requests file parser encoder buttonconfig; do
    rm -rf "$DST/$dir"
    cp -r "$SRC/$dir" "$DST/$dir"
done

# Re-apply patches
patch -p0 < patches/Request.java.patch
# ... etc

echo "Synced. Run 'git diff gadgetbridge/' to review changes."
```

---

## Phase 2: BlueZ Transport

### Interface

```java
public interface BleTransport {
    boolean connect(String macAddress);
    void disconnect();
    boolean isConnected();
    List<BleCharacteristic> discoverServices();
    BleCharacteristic getCharacteristic(UUID uuid);
    void writeCharacteristic(UUID uuid, byte[] data);
    byte[] readCharacteristic(UUID uuid);
    void setCharacteristicNotification(UUID uuid, boolean enable);
    void requestMtu(int mtu);
    void setNotificationCallback(UUID uuid, Consumer<byte[]> callback);
    void setConnectionCallback(Consumer<ConnectionState> callback);
    void setMtuCallback(BiConsumer<Integer, Integer> callback);
}
```

### Implementation: `busctl` subprocess

BlueZ exposes everything via D-Bus. `busctl` is available on all systemd Linux distros. No additional dependencies.

```java
// Connect
exec("bluetoothctl", "connect", macAddress);

// Discover characteristics
String xml = exec("busctl", "introspect", "org.bluez", devicePath);
// Parse XML for GattCharacteristic1 interfaces with their UUIDs

// Write
exec("busctl", "call", "org.bluez", charPath,
     "org.bluez.GattCharacteristic1", "WriteValue", "ay", byteCount, ...bytes);

// Start notifications
exec("busctl", "call", "org.bluez", charPath,
     "org.bluez.GattCharacteristic1", "StartNotify");

// Listen for notifications (background thread)
Process monitor = new ProcessBuilder("busctl", "monitor",
    "--match", "type='signal',path='" + devicePath + "'").start();
// Parse stdout for PropertiesChanged signals on characteristic paths
```

**Alternative**: `dbus-java` library for proper D-Bus bindings. Cleaner but adds a 4MB dependency. Start with subprocess; swap later if parsing proves fragile.

### Fossil Q Hybrid BLE characteristics

| UUID suffix | Handle | Purpose |
|-------------|--------|---------|
| `3dda0002-957f-7d4a-34a6-74696673696d` | 0x0011 | Basic request/response |
| `3dda0003-957f-7d4a-34a6-74696673696d` | 0x0014 | Fossil request/response |
| `3dda0004-957f-7d4a-34a6-74696673696d` | 0x0017 | File transfer data |
| `3dda0005-957f-7d4a-34a6-74696673696d` | 0x001A | File transfer control |
| `3dda0006-957f-7d4a-34a6-74696673696d` | 0x001D | Commute menu / widgets |
| `3dda0007-957f-7d4a-34a6-74696673696d` | 0x0020 | Microapp data |
| `010541ae-fe7d-4b9e-9b2f-3eccdbb27548` | — | Heart rate measurement |
| `fef9589f-1a3b-4e7d-b0c8-7e5f4b8c9d0a` | — | Heart rate control |
| `842d2791-7e6f-4b5c-8d3a-1e9f2b7c6d5a` | — | Battery service |
| `00002a19-0000-1000-8000-00805f9b34fb` | — | Battery level (standard) |
| `00002a26-0000-1000-8000-00805f9b34fb` | — | Firmware revision (standard) |
| `00002a24-0000-1000-8000-00805f9b34fb` | — | Model number (standard) |

---

## Phase 3: FossilWatchAdapterLinux

Clean reimplementation (~500 lines). Uses the same request classes from the vendor tree. Reference: `FossilWatchAdapter.java` lines 96-911.

### What we keep (reimplement cleanly)

- **Request queue** — `ArrayList<Request>`, `queueWrite()`, `queueNextRequest()`, timeout thread. Same logic, simplified (no `TransactionBuilder`, no `priorise` flag needed for CLI).
- **File transfer orchestration** — `FileLookupAndGetRequest` → `FileGetRequest` → parse. The state machine lives in the request classes; we just drive it.
- **Config push/pull** — `ConfigurationPutRequest`/`ConfigurationGetRequest`. Config stored as JSON file instead of `SharedPreferences`.
- **Activity fetch** — `FileLookupAndGetRequest` for activity file → `ActivityFileParser`. Write output to JSON/CSV file instead of Android DB.
- **Alarm set/get** — `AlarmsSetRequest`/`AlarmsGetRequest`. Alarms stored as JSON.
- **Notification playback** — `PlayNotificationRequest`/`PlayTextNotificationRequest`. Direct calls, no Android `NotificationListener`.
- **Time sync** — `SetDeviceStateRequest` with time payload.
- **Button config** — `ButtonConfigurationGetRequest` + `ConfigPayload`.
- **Heart rate** — `handleHeartRateCharacteristic()` logic, same parsing.
- **MTU tracking** — `onMtuChanged()` → update `this.MTU` field.

### What we drop (Android-only)

- `GBDevice` state management → simple enum + callbacks
- `GB.toast` / `GB.log` → slf4j
- `SharedPreferences` → `ConfigStore` (JSON file)
- `TransactionBuilder` → direct `transport.writeCharacteristic()`
- `DBHandler` / `HybridHRActivitySampleProvider` → write to file
- `AlarmUtils.mergeOneshotToDeviceAlarms()` → keep the logic, it's pure Java
- `PackageConfigHelper` → keep, it's JSON parsing
- `UriHelper` / firmware install → skip for now
- `getDeviceSupport().getDevice().sendDeviceUpdateIntent()` → callback to CLI

### Key methods

```java
public class FossilWatchAdapterLinux {
    private BleTransport transport;
    private ConfigStore config;
    private ArrayList<Request> requestQueue;
    private Request currentRequest;
    private int mtu = 23;
    private static final int REQUEST_TIMEOUT_MS = 60_000;

    // --- BLE callbacks (called by BluezTransport) ---
    public void onCharacteristicChanged(UUID uuid, byte[] data);
    public void onCharacteristicWrite(UUID uuid, int status);
    public void onConnectionStateChange(ConnectionState state);
    public void onMtuChanged(int mtu, int status);

    // --- Request queue ---
    public void queueWrite(Request request);
    private void queueNextRequest();
    private void executeRequest(Request request);

    // --- High-level operations ---
    public void initialize();
    public void syncTime();
    public void playNotification(NotificationConfiguration config);
    public void setAlarms(List<Alarm> alarms);
    public List<Alarm> getAlarms();
    public void fetchActivity(Path outputFile);
    public void setConfig(ConfigurationPutRequest config);
    public ConfigurationGetRequest getConfig();
    public void setStepGoal(int steps);
    public void setVibrationStrength(int strength);
    public void setTimezoneOffset(int minutes);
    public void overwriteButtons(String jsonConfig);
    public void setHands(MovementConfiguration movement);
    public void vibrate(VibrationType type);
    public void findDevice();
}
```

---

## Phase 4: QHybridLinux

Thin orchestration layer (~300 lines). Reference: `QHybridSupport.java`.

```java
public class QHybridLinux {
    private BleTransport transport;
    private FossilWatchAdapterLinux adapter;
    private String macAddress;

    public void connect(String macAddress);
    public void disconnect();
    public DeviceInfo getDeviceInfo();
    public int getBatteryLevel();
    public String getFirmwareVersion();

    // Delegate to adapter
    public void syncTime()              { adapter.syncTime(); }
    public void notify(NotificationConfiguration c) { adapter.playNotification(c); }
    public void setAlarms(List<Alarm> a) { adapter.setAlarms(a); }
    public List<Alarm> getAlarms()       { return adapter.getAlarms(); }
    public void fetchActivity(Path out)  { adapter.fetchActivity(out); }
    // ... etc
}
```

---

## Phase 5: CLI

**Library**: [picocli](https://picocli.info/) — modern, annotation-based, auto-generates help, tab completion, color output. Single 400KB JAR dependency.

```java
@Command(name = "fossil-q", description = "Fossil Q Hybrid CLI",
         subcommands = {PairCmd.class, InfoCmd.class, TimeCmd.class,
                        NotifyCmd.class, AlarmCmd.class, ActivityCmd.class,
                        ConfigCmd.class, FindCmd.class, HandsCmd.class,
                        MonitorCmd.class})
public class Main implements Runnable {
    public void run() { /* show help */ }
}
```

### Commands

```
fossil-q pair <mac>              Pair and connect
fossil-q info                     Device info (model, firmware, battery)
fossil-q time                     Sync time
fossil-q notify <title> <body>   Send notification
fossil-q alarm list               List alarms
fossil-q alarm set <time> <days>  Set alarm (days: mon,tue,wed...)
fossil-q alarm delete <id>        Delete alarm
fossil-q activity [--output <file>]  Fetch steps/sleep data
fossil-q config show              Show current config
fossil-q config set <json-file>   Push config
fossil-q find                     Vibrate watch
fossil-q hands <hour> <min>       Move hands (calibration)
fossil-q image <png-file>         Upload background image
fossil-q firmware <file>          Flash firmware (future)
fossil-q monitor                  Live notification relay (daemon mode)
```

---

## Phase 6: Build System

**Gradle 8.x with application plugin** — single fat JAR output.

```groovy
plugins {
    id 'application'
    id 'com.github.johnrengelman.shadow' version '8.1.1'
}

application {
    mainClass = 'qhybrid.linux.Main'
}

dependencies {
    implementation 'info.picocli:picocli:4.7.6'
    implementation 'org.slf4j:slf4j-simple:2.0.9'
    implementation 'com.google.code.gson:gson:2.10.1'
}

// Compile vendor code + our code together
sourceSets {
    main {
        java {
            srcDirs = ['src/main/java', 'gadgetbridge/app/src/main/java']
        }
    }
}
```

**Build output**: `fossil-qhybrid.jar` (fat JAR, ~5MB)

**Run**: `java -jar fossil-qhybrid.jar pair 00:11:22:33:44:55`

---

## Dependencies Summary

| Dependency | Purpose | Size |
|-----------|---------|------|
| picocli 4.7.6 | CLI framework | ~400KB |
| slf4j-simple 2.0.9 | Logging (already used by protocol layer) | ~15KB |
| gson 2.10.1 | JSON config storage | ~300KB |
| `busctl` (system) | BlueZ D-Bus communication | Pre-installed |
| `bluetoothctl` (system) | Pairing/connection | Pre-installed |

**Zero native libraries. Zero JNI. Pure Java + system tools.**

---

## Estimated Effort

| Phase | Description | Effort |
|-------|-------------|--------|
| 1 | Vendor fork + patch protocol layer | 1-2 hours |
| 2 | BlueZ transport | 4-6 hours |
| 3 | FossilWatchAdapterLinux | 4-6 hours |
| 4 | QHybridLinux | 1-2 hours |
| 5 | CLI (picocli) | 2-3 hours |
| 6 | Build system + fat JAR | 1 hour |
| **Total** | | **13-20 hours** |

---

## Risks & Mitigations

1. **BlueZ D-Bus parsing fragility** — `busctl introspect` XML output could change between BlueZ versions. Mitigation: parse by interface name (`org.bluez.GattCharacteristic1`) rather than position. Fallback: `dbus-java` library.

2. **MTU negotiation** — Fossil needs MTU ≥ 256 for file transfers. BlueZ 5.x handles this automatically on connection. If not, `transport.requestMtu(256)` via D-Bus `AcquireWrite`.

3. **Pairing flow** — `bluetoothctl` handles pairing interactively. CLI will need to guide user through the PIN/passkey flow. Fossil watches typically use "Just Works" pairing (no PIN).

4. **Firmware flashing** — `FirmwareFilePutRequest` has Android progress callbacks. Skip initially; revisit when basic functionality works.

5. **AGPL compliance** — Vendor code is AGPLv3. Our code must also be AGPLv3. This is fine for a personal tool; document in README.

---

## Sync Procedure

```bash
# 1. Pull latest Gadgetbridge
cd gadgetbridge-upstream && git pull

# 2. Run sync script
cd fossil-qhybrid-linux && ./sync.sh ../gadgetbridge-upstream

# 3. Review diff
git diff gadgetbridge/

# 4. Test build
./gradlew build

# 5. Commit with upstream ref
git add gadgetbridge/ && git commit -m "vendor: sync Gadgetbridge qhybrid @ $(cd ../gadgetbridge-upstream && git rev-parse --short HEAD)"
```
