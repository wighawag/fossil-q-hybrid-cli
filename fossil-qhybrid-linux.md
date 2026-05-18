# Fossil Q Hybrid — Linux Desktop CLI

**Goal**: Reuse GadgetBridge's Fossil Q Hybrid protocol layer in a Linux desktop CLI app.

**Target devices**: Fossil Q Commuter (HW.0.0) and Q Activist (HL.0.0) — coin-cell battery watches only.

These watches use **two protocol variants** depending on firmware version (detected at runtime):
- Firmware 0.x / 1.x → "Misfit" protocol (simpler, request/response)
- Firmware 2.x → "Fossil" protocol (file-based, more capable)

We do **NOT** target Hybrid HR (IV.0.0), Collider (DN.1.0), or Gen 6 Hybrid (WA/VA) — those are rechargeable watches with encrypted protocols, displays, and a much more complex `FossilHRWatchAdapter` (2216 lines).

**Repo strategy**: Vendor fork in `gadgetbridge/` subdirectory, exact upstream paths. Sync = `cp -r` from upstream. Our code in `src/` — clean separation.

---

## Analysis Results (from cloned upstream @ a0948ee)

### What we need from upstream

Since we only target coin-cell watches, we need:

| Layer | Files | Lines | Purpose |
|-------|-------|-------|---------|
| `requests/Request.java` | 1 | 70 | Base class |
| `requests/misfit/` | ~25 | ~1200 | Misfit protocol requests |
| `requests/fossil/` (excl. `fossil_hr/`) | ~34 | ~2600 | Fossil protocol requests |
| `requests/fossil_hr/file/ResultCode.java` | 1 | 55 | Pure enum, needed by `fossil/file/*.java` |
| `requests/fossil_hr/file/FileEncryptedInterface.java` | 1 | 5 | Empty interface, needed by `fossil/file/*.java` |
| `file/FileHandle.java` | 1 | ~60 | File handle enum |
| `encoder/RLEEncoder.java` | 1 | ~50 | RLE encoding (hand animations) |
| `buttonconfig/` | 2 | ~150 | Button config file builder |
| **Total** | ~66 | ~4200 | |

**NOT vendored:**
- `parser/` (3 files, ~300 lines) — heavy GB entity deps (`HybridHRActivitySample`, `BaseActivitySummary`, `ActivityKind`, `DateTimeUtils`, `BLETypeConversions`). Not imported by any request or adapter class. We parse activity data ourselves.
- `adapter/` — we write our own adapter; provide shim classes for compile-time references.
- `QHybridSupport.java`, `QHybridBaseSupport.java` — we provide shim replacements.

### Android coupling in our target files

The `handleResponse` method signature is:
```java
public void handleResponse(BluetoothGattCharacteristic characteristic, byte[] value)
```

The `characteristic` parameter is used **only** for `characteristic.getUuid()` — to distinguish which BLE characteristic the data arrived on (control `3dda0003` vs data `3dda0004`). The `value` (byte array) is already passed separately — **no file calls `characteristic.getValue()`**.

#### Approach: Shim classes (zero patches to vendor code)

Instead of patching vendored files, we provide **stub/shim classes** on the classpath that satisfy the compile-time signatures. This means `sync.sh` is a pure copy with zero patch step — massively simpler.

### Complete shim inventory

Every import in the vendored `fossil/` and `misfit/` request code has been traced. This is the exhaustive list.

**Android API shims** (in `src/main/java/android/` and `src/main/java/androidx/`):

| Class | What it provides | Effort |
|-------|-----------------|--------|
| `android.bluetooth.BluetoothGattCharacteristic` | Holds a UUID, provides `getUuid()` | 10 lines |
| `android.bluetooth.BluetoothGatt` | Constants only (`GATT_SUCCESS = 0`, `STATE_CONNECTED = 2`, `STATE_DISCONNECTED = 0`) | 8 lines |
| `android.content.Context` | Empty class — used only as parameter/return type, never invoked. Needed by `WatchAdapter.getContext()` and `GBDevice.sendDeviceUpdateIntent(Context)`. | 3 lines |
| `android.os.Build` | Stub (referenced by `RequestMtuRequest`, unused at runtime for our path) | 5 lines |
| `android.widget.Toast` | Constants only (`LENGTH_SHORT = 0`, `LENGTH_LONG = 1`) | 5 lines |
| `androidx.annotation.NonNull` | Empty retention annotation | 3 lines |
| `androidx.annotation.Nullable` | Empty retention annotation | 3 lines |
| `androidx.annotation.RequiresApi` | Empty retention annotation (used by `RequestMtuRequest`) | 3 lines |

**GB utility shims** (in `src/main/java/nodomain/.../util/`):

| Class | What it provides | Effort |
|-------|-----------------|--------|
| `util.GB` | No-op `toast()`, `updateTransferNotification()`, `updateInstallNotification()`, `signalActivityDataFinish()`. Logs toast messages via slf4j. | 20 lines |
| `util.StringUtils` | Only `terminateNull(String)` needed (by `PlayNotificationRequest`). Real class imports `commons-lang3.ArrayUtils` — not worth vendoring. `bytesToHex()` used only by excluded `fossil_hr/` classes. | 8 lines |

**GB utility files vendored verbatim** (in `gadgetbridge/.../util/`):

| Class | Notes | Effort |
|-------|-------|--------|
| `util.CRC32C` | Pure Java, 633 lines, zero android deps. Used by `FilePutRequest`, `NotificationFilterGetRequest`. | 0 (copy) |
| `util.Version` | Pure Java + `@NonNull`, 95 lines. Dead import in `AlarmsSetRequest` but must exist to compile. | 0 (copy) |

**GB model/impl shims** (in `src/main/java/nodomain/.../`):

| Class | What it provides | Effort |
|-------|-----------------|--------|
| `impl.GBDevice` | `State` enum (`NOT_CONNECTED`, `CONNECTING`, `CONNECTED`, `INITIALIZING`, `INITIALIZED`, `AUTHENTICATION_REQUIRED`, `WAITING_FOR_RECONNECT`). Methods: `addDeviceInfo(GenericItem)` stores in map, `sendDeviceUpdateIntent(Context)` no-op, `setUpdateState(State, Context)` no-op. | 25 lines |
| `model.GenericItem` | Key/value data holder. Constructor `GenericItem(String name, String details)`, getters. Used by `ConfigurationGetRequest`. | 10 lines |
| `model.NotificationSpec` | Public fields: `String body`, `int dndSuppressed`, `int getId()`. Used by `PlayTextNotificationRequest`. | 15 lines |
| `service.DeviceSupport` | Empty interface. Dead import in `FileCloseAndPutRequest` (imported but not used). | 3 lines |

**GB BLE shims** (in `src/main/java/nodomain/.../service/btle/`):

| Class | What it provides | Effort |
|-------|-----------------|--------|
| `service.btle.TransactionBuilder` | **Key shim.** Accumulates writes, flushes to `BleTransport` on `.queue()`. Methods: `write(BluetoothGattCharacteristic, byte...)`, `write(UUID, byte...)`, `requestMtu(int)`, `setProgress(int, boolean, int)` (no-op), `queue()`. | 40 lines |
| `service.btle.AbstractBTLEDeviceSupport` | Static method only: `calcMaxWriteChunk(int mtu)` → `Math.min(512, Math.max(23, mtu) - 3)`. Used by `FilePutRawRequest` via static import. | 8 lines |

**Adapter shims** (in `src/main/java/nodomain/.../service/devices/qhybrid/`):

| Class | What it provides | Effort |
|-------|-----------------|--------|
| `QHybridSupport` | Constants: `ITEM_STEP_COUNT = "STEP_COUNT: "`, `ITEM_TIMEZONE_OFFSET = "TIMEZONE_OFFSET_COUNT: "`. Methods: `createTransactionBuilder(String)`, `getCharacteristic(UUID)`, `isConnected()`, `getDevice()`, `getContext()`. Delegates to `BleTransport` and `GBDevice`. | 30 lines |
| `adapter.WatchAdapter` | Abstract base. Holds `QHybridSupport` reference. Methods: `getDeviceSupport()`, `getContext()`. | 20 lines |
| `adapter.fossil.FossilWatchAdapter` | Extends `WatchAdapter`. **Critical bridge**: `queueWrite(FossilRequest, boolean)` delegates to `FossilQAdapter`. Also: `getMTU()`, `getSupportedFileVersion(FileHandle)`, `log(String)`. | 40 lines |
| `adapter.fossil_hr.FossilHRWatchAdapter` | Empty stub extending `FossilWatchAdapter`. Only needed because `FileGetRawRequest` has a dead import. | 5 lines |

**Vendored data file** (in `gadgetbridge/.../devices/qhybrid/`):

| Class | Notes | Effort |
|-------|-------|--------|
| `devices.qhybrid.NotificationConfiguration` | Pure Java + Serializable. Imports `PlayNotificationRequest.VibrationType` (vendored). | 0 (copy) |

### Import verification: every vendored import is covered

Complete list of non-Java/non-qhybrid imports found in vendored `requests/fossil/` code:

| Import | Covered by |
|--------|-----------|
| `android.bluetooth.BluetoothGattCharacteristic` | Shim |
| `android.content.Context` | Shim (only in excluded `FirmwareFilePutRequest`) |
| `android.os.Build` | Shim |
| `android.widget.Toast` | Shim |
| `androidx.annotation.NonNull` | Shim |
| `androidx.annotation.RequiresApi` | Shim |
| `nodomain...util.GB` | Shim |
| `nodomain...util.CRC32C` | Vendored verbatim |
| `nodomain...util.Version` | Vendored verbatim |
| `nodomain...util.StringUtils` | Shim (terminateNull only) |
| `nodomain...impl.GBDevice` | Shim |
| `nodomain...model.GenericItem` | Shim |
| `nodomain...model.NotificationSpec` | Shim |
| `nodomain...service.DeviceSupport` | Shim |
| `nodomain...service.btle.TransactionBuilder` | Shim |
| `nodomain...service.btle.AbstractBTLEDeviceSupport` | Shim (static import of `calcMaxWriteChunk`) |
| `nodomain...devices.qhybrid.NotificationConfiguration` | Vendored verbatim |
| `nodomain...adapter.fossil.FossilWatchAdapter` | Shim |
| `nodomain...adapter.fossil_hr.FossilHRWatchAdapter` | Shim (empty stub) |
| `nodomain...qhybrid.QHybridSupport` | Shim |
| `nodomain...GBApplication` | Only in EXCLUDED `FirmwareFilePutRequest` |
| `nodomain...R` | Only in EXCLUDED `FirmwareFilePutRequest` |

Vendored `requests/misfit/` code only imports `android.bluetooth.BluetoothGattCharacteristic` (shimmed) and intra-qhybrid classes.

### Known dead imports in vendored code

These imports exist but the class/method is never actually used in the file. They still must compile, so our shims must exist:

| File | Dead import | Why it's dead |
|------|-------------|---------------|
| `FileGetRawRequest.java` | `FossilHRWatchAdapter` | Imported but only `FossilWatchAdapter` is used |
| `AlarmsSetRequest.java` | `GBDevice` | Imported but never referenced in code |
| `AlarmsSetRequest.java` | `Version` | Imported but never referenced in code |
| `FileCloseAndPutRequest.java` | `DeviceSupport` | Imported but never referenced in code |
| `FileCloseAndPutRequest.java` | `GBDevice` | Imported but never referenced in code |
| `FileCloseAndPutRequest.java` | `NotificationConfiguration` | Imported but never referenced in code |

### FossilWatchAdapter.queueWrite() overload details

The real `FossilWatchAdapter` has 5 overloads. Vendored request classes only call 2 of them:

| Call site | Method called | Signature |
|-----------|--------------|-----------|
| `FileCloseAndPutRequest.onPrepare()` | `adapter.queueWrite(new FilePutRequest(...), false)` | `queueWrite(FossilRequest, boolean)` — `FilePutRequest` extends `FossilRequest` |
| `FileLookupAndGetRequest.handleFileLookup()` | `getAdapter().queueWrite(new FileGetRawRequest(...), true)` | `queueWrite(FossilRequest, boolean)` — `FileGetRawRequest` extends `FossilRequest` |

So our shim needs only: `public void queueWrite(FossilRequest request, boolean prioritise)`.

The `boolean prioritise` parameter controls queue insertion order: `true` = insert at front, `false` = append. Our shim delegates to `FossilQAdapter.queueWrite(request, prioritise)`.

### TransactionBuilder overload details

The vendored code uses two `write()` overloads:

| Call site | Overload | Notes |
|-----------|----------|-------|
| `FilePutRawRequest` line 88-93 | `write(BluetoothGattCharacteristic, byte[])` | Gets characteristic via `getCharacteristic(UUID)` first |
| `FilePutRawRequest` line 129-133 | `write(UUID, byte...)` | Writes file-close directly by UUID |

The real `TransactionBuilder.write(UUID, byte...)` internally calls `mDeviceSupport.getCharacteristic(uuid)` then delegates to `write(bgc, data)`. Our shim can skip this indirection — both overloads just call `transport.writeCharacteristic(uuid, data)`.

#### The TransactionBuilder Problem

`FilePutRawRequest.handleResponse()` calls back into the BLE stack mid-response:
```java
TransactionBuilder transactionBuilder = adapter.getDeviceSupport().createTransactionBuilder("file upload");
BluetoothGattCharacteristic uploadCharacteristic = adapter.getDeviceSupport().getCharacteristic(UUID.fromString("3dda0004-..."));
for (byte[] packet : packets) {
    transactionBuilder.write(uploadCharacteristic, packet);
}
transactionBuilder.queue();
```

Our `TransactionBuilder` shim accumulates writes and flushes them to our `BleTransport` on `.queue()`:
```java
public class TransactionBuilder {
    private final BleTransport transport;
    private final List<Runnable> ops = new ArrayList<>();

    public TransactionBuilder(String name, BleTransport transport) {
        this.transport = transport;
    }

    public TransactionBuilder write(BluetoothGattCharacteristic characteristic, byte... data) {
        UUID uuid = characteristic.getUuid();
        ops.add(() -> transport.writeCharacteristic(uuid, data));
        return this;
    }

    public TransactionBuilder write(UUID uuid, byte... data) {
        ops.add(() -> transport.writeCharacteristic(uuid, data));
        return this;
    }

    public TransactionBuilder requestMtu(int mtu) {
        ops.add(() -> transport.requestMtu(mtu));
        return this;
    }

    public void setProgress(int resId, boolean ongoing, int percent) { /* no-op */ }

    public void queue() {
        for (Runnable op : ops) op.run();
    }
}
```

Note: `.queue(BluetoothGattCallback)` overload exists in dead code (commented-out case 9 in `FilePutRawRequest`). We don't need it.

#### Complete shim dependency chain for `FossilWatchAdapter`

`FossilWatchAdapter` calls `getDeviceSupport()` which returns our `QHybridSupport` shim:
```java
// What vendored request classes call via adapter:
adapter.getDeviceSupport().createTransactionBuilder(name)  → returns our TransactionBuilder
adapter.getDeviceSupport().getCharacteristic(uuid)         → returns new BluetoothGattCharacteristic(uuid)
adapter.getDeviceSupport().isConnected()                   → boolean from BleTransport
adapter.getDeviceSupport().getDevice()                     → our GBDevice shim
adapter.getContext()                                       → returns null (Context shim; never dereferenced for our code path)
adapter.queueWrite(FossilRequest, boolean)                 → delegates to FossilQAdapter
adapter.getMTU()                                           → returns MTU from BleTransport
adapter.getSupportedFileVersion(FileHandle)                → returns version map populated during init
```

### What `MisfitWatchAdapter` uses (simpler path)

The Misfit adapter is much simpler — it just calls:
```java
getDeviceSupport().createTransactionBuilder(name).write(uuid, data).queue();
```

For file uploads (`UploadFileRequest`), the request class **does not call back into the adapter** from `handleResponse`. Instead, it builds packets in a public `packets` field, and the adapter drives the upload:
```java
// In MisfitWatchAdapter.handleFileUploadCharacteristic():
case UPLOAD:
    for (byte[] packet : this.uploadFileRequest.packets) {
        getDeviceSupport().createTransactionBuilder("File upload")
            .write(characteristic, packet).queue();
    }
```
This is a clean pattern — no `TransactionBuilder` coupling inside the request class itself.

### What `FossilWatchAdapter` additionally uses

- `FilePutRawRequest` — multi-packet file upload with TransactionBuilder callback (described above)
- `FileLookupAndGetRequest` → `FileGetRawRequest` — multi-packet file download (no TransactionBuilder needed — purely reception-based)
- `ConfigurationPutRequest` extends `FilePutRequest` extends `FilePutRawRequest` — config is pushed as file
- `NotificationFilterPutRequest` extends `FilePutRequest` — notification settings pushed as file
- `AlarmsSetRequest` — alarms pushed as file
- `GetDeviceInfoRequest` — device info read as file

---

## Repo Structure

```
fossil-qhybrid-linux/
├── gadgetbridge/                          # Vendor fork — READ-ONLY, zero patches
│   └── app/src/main/java/nodomain/freeyourgadget/gadgetbridge/
│       ├── service/devices/qhybrid/
│       │   ├── requests/
│       │   │   ├── Request.java           # VERBATIM
│       │   │   ├── fossil/                # VERBATIM (all subdirs)
│       │   │   ├── fossil_hr/file/        # Only ResultCode.java + FileEncryptedInterface.java compiled
│       │   │   └── misfit/                # VERBATIM
│       │   ├── file/FileHandle.java       # VERBATIM
│       │   ├── encoder/RLEEncoder.java    # VERBATIM
│       │   └── buttonconfig/              # VERBATIM
│       │   (adapter/, parser/, QHybridSupport.java, QHybridBaseSupport.java NOT copied)
│       ├── devices/qhybrid/
│       │   └── NotificationConfiguration.java  # VERBATIM
│       └── util/
│           ├── CRC32C.java                # VERBATIM (pure Java)
│           └── Version.java               # VERBATIM (pure Java + @NonNull)
│
├── src/main/java/
│   ├── android/                           # Android API shims
│   │   ├── bluetooth/
│   │   │   ├── BluetoothGatt.java         # Constants: GATT_SUCCESS, STATE_CONNECTED, STATE_DISCONNECTED
│   │   │   └── BluetoothGattCharacteristic.java  # Holds UUID, getUuid()
│   │   ├── content/
│   │   │   └── Context.java              # Empty class (parameter type only)
│   │   ├── os/Build.java                  # Empty stub
│   │   └── widget/Toast.java             # Constants: LENGTH_SHORT, LENGTH_LONG
│   │
│   ├── androidx/annotation/               # Annotation stubs
│   │   ├── NonNull.java
│   │   ├── Nullable.java
│   │   └── RequiresApi.java
│   │
│   ├── nodomain/freeyourgadget/gadgetbridge/   # GB shims
│   │   ├── impl/GBDevice.java             # State enum + addDeviceInfo/sendDeviceUpdateIntent no-ops
│   │   ├── model/
│   │   │   ├── GenericItem.java           # Key/value data holder
│   │   │   └── NotificationSpec.java      # body, dndSuppressed, getId()
│   │   ├── service/
│   │   │   ├── DeviceSupport.java         # Empty interface
│   │   │   └── btle/
│   │   │       ├── TransactionBuilder.java      # Key shim: accumulates writes → BleTransport
│   │   │       └── AbstractBTLEDeviceSupport.java  # Static calcMaxWriteChunk()
│   │   ├── service/devices/qhybrid/
│   │   │   ├── QHybridSupport.java        # Shim: constants, createTransactionBuilder, getDevice
│   │   │   └── adapter/
│   │   │       ├── WatchAdapter.java      # Shim base: getDeviceSupport(), getContext()
│   │   │       ├── fossil/
│   │   │       │   └── FossilWatchAdapter.java  # Shim: queueWrite, getMTU, getSupportedFileVersion
│   │   │       └── fossil_hr/
│   │   │           └── FossilHRWatchAdapter.java  # Empty stub (dead import)
│   │   └── util/
│   │       ├── GB.java                    # No-op toast/notification methods
│   │       └── StringUtils.java           # Only terminateNull() — real class needs commons-lang3
│   │
│   └── qhybrid/linux/                    # Our code
│       ├── Main.java                      # CLI entry point (picocli)
│       ├── BleTransport.java              # Interface
│       ├── BluezTransport.java            # BlueZ D-Bus implementation
│       ├── FossilQAdapter.java            # Our adapter (~400 lines)
│       ├── ConfigStore.java               # JSON file config (replaces SharedPreferences)
│       └── DeviceState.java               # Simple state enum + callbacks
│
├── build.gradle                           # Gradle with application plugin + shadow
├── settings.gradle                        # Project name
├── gradlew / gradlew.bat                  # Gradle wrapper
├── sync.sh                                # Script: cp -r from upstream, no patches
└── README.md
```

---

## Connection & Pairing Flow

### How GadgetBridge does it (Android)

1. **Discovery**: BLE scan filtered by service UUID `3dda0001-957f-7d4a-34a6-74696673696d`
2. **BLE bonding**: `BONDING_STYLE_ASK` (default) — standard Android `createBond()`. Fossil coin-cell watches use "Just Works" BLE pairing (no PIN/passkey). Bonding creates an encrypted BLE link.
3. **GATT connect + service discovery**: Android's `BluetoothGatt.connectGatt()` + automatic service discovery
4. **Initialize characteristics**: Enable notifications on `3dda0002` through `3dda0007`, plus three vendor-specific UUIDs. Read battery (`00002a19`), firmware version (`00002a26`), and model number (`00002a24`).
5. **Firmware read triggers adapter creation**: When `00002a26` is read, the firmware version string (e.g. `HW0.0.2.13`) is parsed. `WatchAdapterFactory` picks `MisfitWatchAdapter` (firmware 0.x/1.x) or `FossilWatchAdapter` (2.x).
6. **Adapter initialization**: 
   - **Misfit**: Queue requests for step goal, vibration strength, activity point, time sync, animation, step count.
   - **Fossil**: Play pairing animation → request MTU 512 → get device infos → sync config (step goal, vibration, timezone, time) → sync notification filter → sync button settings → set state INITIALIZED.
7. **No app-level authentication** for coin-cell watches. The HR models have an encrypted key exchange (`CheckDevicePairingRequest` → `PerformDevicePairingRequest` → `ConfirmOnDeviceRequest`), but this is entirely in `FossilHRWatchAdapter` which we don't use.

### How we do it (Linux / BlueZ)

1. **Discovery**: `bluetoothctl scan le` — look for devices advertising `3dda0001-957f-7d4a-34a6-74696673696d`. Or user provides MAC address directly.
2. **BLE bonding + connect**: 
   ```bash
   bluetoothctl pair <mac>      # "Just Works" — no PIN prompt expected
   bluetoothctl connect <mac>   # GATT connection + service discovery
   ```
   BlueZ automatically discovers GATT services on connect. Bonding persists across reboots. If already bonded, `connect` alone suffices.
3. **Map characteristics**: After connect, BlueZ exposes characteristics under D-Bus at `/org/bluez/hci0/dev_XX_XX_XX_XX_XX_XX/serviceNNNN/charNNNN`. We enumerate them via `busctl` and build a UUID → D-Bus path map.
   ```bash
   # For each characteristic path:
   busctl get-property org.bluez <char_path> org.bluez.GattCharacteristic1 UUID
   ```
4. **Enable notifications**: For each characteristic that needs monitoring:
   ```bash
   busctl call org.bluez <char_path> org.bluez.GattCharacteristic1 StartNotify
   ```
5. **Read initial values**: Battery, firmware version, model number:
   ```bash
   busctl call org.bluez <char_path> org.bluez.GattCharacteristic1 ReadValue "a{sv}" 0
   ```
6. **Listen for notification data**: Start a background `dbus-monitor` process listening for `PropertiesChanged` signals on characteristic paths. Parse the `Value` property updates.
7. **Protocol initialization**: Same as GB — create adapter based on firmware version, run the init sequence.

### Potential pairing issues on Linux

| Issue | Likelihood | Mitigation |
|-------|-----------|-------------|
| BLE bonding fails silently | Medium | BlueZ 5.x handles "Just Works" well. Verify with `bluetoothctl info <mac>` → check `Bonded: yes`. If it fails, try `bluetoothctl remove <mac>` first. |
| Characteristics not visible after connect | Low | BlueZ caches GATT database. If stale, `bluetoothctl remove <mac>` + re-pair forces re-discovery. |
| Watch already bonded to phone | High | Fossil Q watches support only one bond at a time. User must unpair from phone first (or factory reset watch by holding buttons 5s). Document this clearly. |
| MTU negotiation doesn't happen | Medium | BlueZ 5.50+ auto-negotiates MTU on connect. Check `MTU` property on characteristic. If stuck at 23, use `AcquireWrite` D-Bus method which returns negotiated MTU. |
| Notification delivery via D-Bus is slow | Low | `dbus-monitor` adds ~1ms latency. Acceptable for watch protocol. If problematic, switch to `AcquireNotify` which gives a raw file descriptor. |

### Single-bond limitation

Fossil Q watches can only be bonded to **one device at a time**. If the watch is currently paired with a phone, the Linux CLI cannot connect. The user must:
1. Unpair from phone (in Gadgetbridge or phone Bluetooth settings), OR
2. Factory reset the watch (hold all buttons for 5 seconds)

Then pair with the Linux machine. To switch back to the phone, reverse the process. This is a hardware/firmware limitation, not something we can work around.

---

## Phase 1: Vendor Fork + Shim Layer + Build Verification

### Implementation order

This is the recommended implementation sequence. Each step should end with `./gradlew build` passing (or at least getting closer).

```
Step 1:  Create build.gradle, settings.gradle, gradle wrapper
Step 2:  Create sync.sh and run it → vendor files appear in gadgetbridge/
Step 3:  First build attempt → massive compile errors (expected)
Step 4:  Create Android shims (BluetoothGattCharacteristic, BluetoothGatt, Context, Build, Toast)
Step 5:  Create annotation shims (NonNull, Nullable, RequiresApi)
Step 6:  Create GB utility shims (GB, StringUtils)
Step 7:  Create GB model shims (GBDevice, GenericItem, NotificationSpec, DeviceSupport)
Step 8:  Create BLE shims (TransactionBuilder, AbstractBTLEDeviceSupport)
         → At this point BleTransport interface must exist (TransactionBuilder references it)
Step 9:  Create adapter shims (QHybridSupport, WatchAdapter, FossilWatchAdapter, FossilHRWatchAdapter)
Step 10: Build → fix any remaining gaps discovered iteratively
Step 11: Vendored code compiles ✓ → Phase 1 complete
```

### 1a. sync.sh — copy upstream (zero patches)

```bash
#!/bin/bash
# sync.sh — pure copy, no patches needed
set -euo pipefail

GB_REPO="${1:-tmp/Gadgetbridge}"
BASE="app/src/main/java/nodomain/freeyourgadget/gadgetbridge"
SRC_SVC="$GB_REPO/$BASE/service/devices/qhybrid"
DST_SVC="gadgetbridge/$BASE/service/devices/qhybrid"

# Protocol layer (requests, file handles, encoder, button config)
# NOTE: parser/ is NOT copied — heavy GB entity deps, we parse activity data ourselves
# NOTE: adapter/ is NOT copied — we provide shim classes instead
# NOTE: QHybridSupport.java / QHybridBaseSupport.java are NOT copied — we provide shims
for dir in requests file encoder buttonconfig; do
    rm -rf "$DST_SVC/$dir"
    cp -r "$SRC_SVC/$dir" "$DST_SVC/$dir"
done

# NotificationConfiguration (data class, pure Java + Serializable)
mkdir -p "gadgetbridge/$BASE/devices/qhybrid"
cp "$GB_REPO/$BASE/devices/qhybrid/NotificationConfiguration.java" \
   "gadgetbridge/$BASE/devices/qhybrid/"

# Utility classes (pure Java, no android deps — vendored verbatim)
mkdir -p "gadgetbridge/$BASE/util"
cp "$GB_REPO/$BASE/util/CRC32C.java" "gadgetbridge/$BASE/util/"
cp "$GB_REPO/$BASE/util/Version.java" "gadgetbridge/$BASE/util/"
# StringUtils is NOT vendored — real class imports commons-lang3. We provide a minimal shim.

echo "Synced from $(cd "$GB_REPO" && git rev-parse --short HEAD). No patches needed."
```

**What gets copied:** `requests/` (entire tree including `fossil_hr/`), `file/`, `encoder/`, `buttonconfig/`, `NotificationConfiguration.java`, `CRC32C.java`, `Version.java`.

**What is NOT copied:** `adapter/`, `parser/`, `QHybridSupport.java`, `QHybridBaseSupport.java`, `TransactionBuilder.java`, `AbstractBTLEDeviceSupport.java`, `StringUtils.java`, any other GB top-level classes.

**Key:** since `adapter/` is never copied, our shim files at `src/main/java/.../adapter/` don't conflict. No `**/adapter/**` exclude needed in Gradle.

### 1b. build.gradle with source sets and excludes

```groovy
plugins {
    id 'application'
    id 'com.github.johnrengelman.shadow' version '8.1.1'
}

group = 'qhybrid.linux'
version = '0.1.0'

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass = 'qhybrid.linux.Main'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'info.picocli:picocli:4.7.6'
    implementation 'org.slf4j:slf4j-api:2.0.9'
    implementation 'org.slf4j:slf4j-simple:2.0.9'
    implementation 'com.google.code.gson:gson:2.10.1'
}

sourceSets {
    main {
        java {
            srcDirs = ['src/main/java', 'gadgetbridge/app/src/main/java']

            // === fossil_hr subdirectories: exclude everything except fossil_hr/file/ ===
            // These are copied as part of requests/ tree but are HR-only
            exclude '**/fossil_hr/adapter/**'
            exclude '**/fossil_hr/alexa/**'
            exclude '**/fossil_hr/application/**'
            exclude '**/fossil_hr/async/**'
            exclude '**/fossil_hr/authentication/**'
            exclude '**/fossil_hr/buttons/**'
            exclude '**/fossil_hr/commute/**'
            exclude '**/fossil_hr/configuration/**'
            exclude '**/fossil_hr/image/**'
            exclude '**/fossil_hr/json/**'
            exclude '**/fossil_hr/menu/**'
            exclude '**/fossil_hr/music/**'
            exclude '**/fossil_hr/notification/**'
            exclude '**/fossil_hr/quickreply/**'
            exclude '**/fossil_hr/theme/**'
            exclude '**/fossil_hr/translation/**'
            exclude '**/fossil_hr/widget/**'
            exclude '**/fossil_hr/workout/**'

            // === fossil_hr/file/: keep ResultCode.java + FileEncryptedInterface.java only ===
            // The encrypted request classes need FossilHRWatchAdapter crypto methods
            exclude '**/fossil_hr/file/FileEncryptedGetRequest.java'
            exclude '**/fossil_hr/file/FileEncryptedPutRequest.java'
            exclude '**/fossil_hr/file/FileEncryptedLookupAndGetRequest.java'
            exclude '**/fossil_hr/file/AssetFile.java'
            exclude '**/fossil_hr/file/AssetFilePutRequest.java'

            // === Individual files with heavy deps ===
            exclude '**/FirmwareFilePutRequest.java'    // needs GBApplication, R.string.*
        }
    }
}

// Fat JAR
shadowJar {
    archiveBaseName.set('fossil-q')
    archiveClassifier.set('')
    archiveVersion.set('')
}
```

**Critical note on excludes:** We do NOT use `**/adapter/**` or `**/QHybridSupport.java` in excludes — those files don't exist in the vendored tree (sync.sh doesn't copy them), and using `**` patterns would accidentally exclude our shim files in `src/main/java/`.

### 1c. Shim class implementations

#### `android.bluetooth.BluetoothGattCharacteristic`
```java
package android.bluetooth;
import java.util.UUID;

public class BluetoothGattCharacteristic {
    private final UUID uuid;
    public BluetoothGattCharacteristic(UUID uuid) { this.uuid = uuid; }
    public UUID getUuid() { return uuid; }
}
```

#### `android.bluetooth.BluetoothGatt`
```java
package android.bluetooth;
public class BluetoothGatt {
    public static final int GATT_SUCCESS = 0;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_DISCONNECTED = 0;
}
```

#### `android.content.Context`
```java
package android.content;
public class Context {}
```

#### `android.os.Build`
```java
package android.os;
public class Build {
    public static class VERSION {
        public static final int SDK_INT = 33;
    }
    public static class VERSION_CODES {
        public static final int LOLLIPOP = 21;
    }
}
```

#### `android.widget.Toast`
```java
package android.widget;
public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;
}
```

#### `androidx.annotation.NonNull`
```java
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
public @interface NonNull {}
```

#### `androidx.annotation.Nullable`
```java
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE_USE})
public @interface Nullable {}
```

#### `androidx.annotation.RequiresApi`
```java
package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface RequiresApi { int value() default 0; int api() default 0; }
```

#### `nodomain.freeyourgadget.gadgetbridge.util.GB`
```java
package nodomain.freeyourgadget.gadgetbridge.util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GB {
    private static final Logger LOG = LoggerFactory.getLogger("GB");
    public static final int INFO = 0, WARN = 1, ERROR = 2;

    public static void toast(String msg, int duration, int severity) {
        LOG.info("[toast] {}", msg);
    }
    public static void toast(String msg, int duration, int severity, Throwable e) {
        LOG.error("[toast] {}", msg, e);
    }
    public static void toast(Object context, String msg, int duration, int severity) {
        LOG.info("[toast] {}", msg);
    }
    public static void toast(Object context, String msg, int duration, int severity, Throwable e) {
        LOG.error("[toast] {}", msg, e);
    }
    public static void updateTransferNotification(String a, String b, boolean c, int d, Object ctx) {}
    public static void updateInstallNotification(String a, boolean b, int c, Object ctx) {}
    public static void signalActivityDataFinish(Object device) {}
}
```

#### `nodomain.freeyourgadget.gadgetbridge.util.StringUtils`
```java
package nodomain.freeyourgadget.gadgetbridge.util;

// Minimal shim — only terminateNull() is used by non-HR vendored code.
// Real class imports commons-lang3 (ArrayUtils.subarray) — not worth vendoring.
public class StringUtils {
    public static String terminateNull(String input) {
        if (input == null || input.isEmpty()) return "\0";
        if (input.charAt(input.length() - 1) == 0) return input;
        return input + "\0";
    }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.impl.GBDevice`
```java
package nodomain.freeyourgadget.gadgetbridge.impl;
import android.content.Context;
import nodomain.freeyourgadget.gadgetbridge.model.GenericItem;
import java.util.HashMap;
import java.util.Map;

public class GBDevice {
    public enum State {
        NOT_CONNECTED, CONNECTING, CONNECTED, INITIALIZING,
        INITIALIZED, AUTHENTICATION_REQUIRED, WAITING_FOR_RECONNECT
    }

    private State state = State.NOT_CONNECTED;
    private final Map<String, String> deviceInfos = new HashMap<>();

    public void setState(State state) { this.state = state; }
    public State getState() { return state; }
    public void addDeviceInfo(GenericItem item) {
        deviceInfos.put(item.getName(), item.getDetails());
    }
    public String getDeviceInfo(String key) { return deviceInfos.get(key); }
    public void sendDeviceUpdateIntent(Context context) { /* no-op on Linux */ }
    public void setUpdateState(State state, Context context) {
        setState(state);
        sendDeviceUpdateIntent(context);
    }
    public void setBatteryLevel(int level) { /* store if needed */ }
    public void setFirmwareVersion(String version) { /* store if needed */ }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.model.GenericItem`
```java
package nodomain.freeyourgadget.gadgetbridge.model;

public class GenericItem {
    private final String name;
    private final String details;

    public GenericItem(String name, String details) {
        this.name = name;
        this.details = details;
    }
    public String getName() { return name; }
    public String getDetails() { return details; }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec`
```java
package nodomain.freeyourgadget.gadgetbridge.model;
import java.util.concurrent.atomic.AtomicInteger;

public class NotificationSpec {
    private static final AtomicInteger counter = new AtomicInteger((int)(System.currentTimeMillis()/1000));
    private final int id;
    public String body;
    public int dndSuppressed;

    public NotificationSpec() { this.id = counter.incrementAndGet(); }
    public NotificationSpec(int id) { this.id = (id != -1) ? id : counter.incrementAndGet(); }
    public int getId() { return id; }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport`
```java
package nodomain.freeyourgadget.gadgetbridge.service;

// Empty interface. Dead import in FileCloseAndPutRequest.
public interface DeviceSupport {}
```

#### `nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder`
```java
package nodomain.freeyourgadget.gadgetbridge.service.btle;
import android.bluetooth.BluetoothGattCharacteristic;
import java.util.*;
import qhybrid.linux.BleTransport;

public class TransactionBuilder {
    private final BleTransport transport;
    private final List<Runnable> ops = new ArrayList<>();

    public TransactionBuilder(String name, BleTransport transport) {
        this.transport = transport;
    }

    public TransactionBuilder write(BluetoothGattCharacteristic characteristic, byte... data) {
        UUID uuid = characteristic.getUuid();
        ops.add(() -> transport.writeCharacteristic(uuid, data));
        return this;
    }

    public TransactionBuilder write(UUID uuid, byte... data) {
        ops.add(() -> transport.writeCharacteristic(uuid, data));
        return this;
    }

    public TransactionBuilder requestMtu(int mtu) {
        ops.add(() -> transport.requestMtu(mtu));
        return this;
    }

    public void setProgress(int resId, boolean ongoing, int percent) { /* no-op */ }

    public void queue() {
        for (Runnable op : ops) op.run();
    }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport`
```java
package nodomain.freeyourgadget.gadgetbridge.service.btle;

public class AbstractBTLEDeviceSupport {
    // Used by FilePutRawRequest via: import static ...AbstractBTLEDeviceSupport.calcMaxWriteChunk;
    public static int calcMaxWriteChunk(int mtu) {
        int safeMtu = Math.max(23, mtu);
        return Math.min(512, safeMtu - 3);
    }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport`
```java
package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import qhybrid.linux.BleTransport;
import java.util.UUID;

public class QHybridSupport {
    // Constants used by ConfigurationGetRequest
    public static final String ITEM_STEP_COUNT = "STEP_COUNT: ";
    public static final String ITEM_TIMEZONE_OFFSET = "TIMEZONE_OFFSET_COUNT: ";
    public static final String ITEM_VIBRATION_STRENGTH = "VIBRATION_STRENGTH: ";
    public static final String ITEM_STEP_GOAL = "STEP_GOAL: ";

    private BleTransport transport;
    private GBDevice device;

    public QHybridSupport(BleTransport transport, GBDevice device) {
        this.transport = transport;
        this.device = device;
    }

    public TransactionBuilder createTransactionBuilder(String name) {
        return new TransactionBuilder(name, transport);
    }

    public BluetoothGattCharacteristic getCharacteristic(UUID uuid) {
        return new BluetoothGattCharacteristic(uuid);
    }

    public boolean isConnected() { return transport.isConnected(); }
    public GBDevice getDevice() { return device; }
    public Context getContext() { return null; }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.WatchAdapter`
```java
package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter;

import android.content.Context;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport;

public abstract class WatchAdapter {
    private final QHybridSupport deviceSupport;

    public WatchAdapter(QHybridSupport deviceSupport) {
        this.deviceSupport = deviceSupport;
    }

    public QHybridSupport getDeviceSupport() { return deviceSupport; }
    public Context getContext() { return getDeviceSupport().getContext(); }

    // Used by Request.java logging
    public static String arrayToString(byte[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("0x%02X", array[i]));
        }
        return sb.append("]").toString();
    }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.fossil.FossilWatchAdapter`
```java
package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.fossil;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.WatchAdapter;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.file.FileHandle;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.fossil.FossilRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class FossilWatchAdapter extends WatchAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(FossilWatchAdapter.class);

    // Delegate for queueWrite — set by FossilQAdapter
    private BiConsumer<FossilRequest, Boolean> queueWriteDelegate;
    private int mtu = 23;
    private final Map<Short, Short> fileVersions = new HashMap<>();

    public FossilWatchAdapter(QHybridSupport support) {
        super(support);
    }

    // --- Called by vendored request classes ---
    public void queueWrite(FossilRequest request, boolean prioritise) {
        if (queueWriteDelegate != null) {
            queueWriteDelegate.accept(request, prioritise);
        } else {
            LOG.warn("queueWrite called but no delegate set: {}", request.getClass().getSimpleName());
        }
    }

    public int getMTU() { return mtu; }

    public short getSupportedFileVersion(FileHandle handle) {
        return fileVersions.getOrDefault(handle.getMajorHandle(), (short) 0);
    }

    public void log(String message) { LOG.debug(message); }

    // --- Called by FossilQAdapter to configure ---
    public void setQueueWriteDelegate(BiConsumer<FossilRequest, Boolean> delegate) {
        this.queueWriteDelegate = delegate;
    }
    public void setMTU(int mtu) { this.mtu = mtu; }
    public void setSupportedFileVersion(short handle, short version) {
        fileVersions.put(handle, version);
    }
}
```

#### `nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.fossil_hr.FossilHRWatchAdapter`
```java
package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.fossil_hr;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.adapter.fossil.FossilWatchAdapter;
import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.QHybridSupport;

// Empty stub. Only exists because FileGetRawRequest has a dead import.
public class FossilHRWatchAdapter extends FossilWatchAdapter {
    public FossilHRWatchAdapter(QHybridSupport support) { super(support); }
}
```

---

## Phase 2: BlueZ Transport

### Interface

```java
package qhybrid.linux;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface BleTransport {
    boolean connect(String macAddress);
    void disconnect();
    boolean isConnected();
    void writeCharacteristic(UUID uuid, byte[] data);
    byte[] readCharacteristic(UUID uuid);
    void enableNotifications(UUID uuid);
    void requestMtu(int mtu);
    int getMtu();

    // Callbacks
    void setNotificationCallback(BiConsumer<UUID, byte[]> callback);
    void setConnectionCallback(Consumer<Boolean> callback);
    void setMtuCallback(Consumer<Integer> callback);
}
```

### Implementation: `busctl` subprocess + `bluetoothctl`

BlueZ exposes everything via D-Bus. No additional JNI/native dependencies.

```java
// Connect
exec("bluetoothctl", "connect", macAddress);

// Write characteristic
exec("busctl", "call", "org.bluez", charPath,
     "org.bluez.GattCharacteristic1", "WriteValue", "aya{sv}",
     byteCount, ...bytes, 0);

// Start notifications
exec("busctl", "call", "org.bluez", charPath,
     "org.bluez.GattCharacteristic1", "StartNotify");

// Listen for notifications (background thread)
Process monitor = new ProcessBuilder("dbus-monitor",
    "--system", "type='signal',interface='org.freedesktop.DBus.Properties',"
    + "path_namespace='/org/bluez'").start();
// Parse stdout for Value property changes on characteristic paths
```

### Fossil Q Hybrid BLE characteristics (coin-cell models)

| UUID | Purpose | Notify? | Used by |
|------|---------|---------|--------|
| `3dda0002-957f-7d4a-34a6-74696673696d` | Basic request/response | Yes | Misfit protocol (all requests) |
| `3dda0003-957f-7d4a-34a6-74696673696d` | Fossil request/response (control) | Yes | Fossil protocol (request queue, file control) |
| `3dda0004-957f-7d4a-34a6-74696673696d` | File transfer data | Yes | Fossil file get/put (bulk data) |
| `3dda0005-957f-7d4a-34a6-74696673696d` | Call vibration control | Yes | FossilWatchAdapter (call vibrate only — write-only) |
| `3dda0006-957f-7d4a-34a6-74696673696d` | Background events (button press, heartbeat) | Yes | Both adapters (button dispatch) |
| `3dda0007-957f-7d4a-34a6-74696673696d` | Microapp/file upload data | Yes | Misfit file upload |
| `00002a19-0000-1000-8000-00805f9b34fb` | Battery level (standard BLE) | Read | Init (battery %) |
| `00002a26-0000-1000-8000-00805f9b34fb` | Firmware revision (standard BLE) | Read | Init (adapter selection) |
| `00002a24-0000-1000-8000-00805f9b34fb` | Model number (standard BLE) | Read | Init (HW.0.0 / HL.0.0) |

Additional vendor UUIDs that GB enables notifications on (purpose unclear, possibly vendor diagnostics):
- `010541ae-efe8-11c0-91c0-105d1a1155f0`
- `fef9589f-9c21-4d19-9fc0-105d1a1155f0`  
- `842d2791-0d20-4ce4-1ada-105d1a1155f0`

Service UUID used for BLE scan/discovery: `3dda0001-957f-7d4a-34a6-74696673696d`

---

## Phase 3: FossilQAdapter

Our single adapter reimplements the logic from both `MisfitWatchAdapter` (450 lines) and `FossilWatchAdapter` (911 lines), tailored for CLI use. It detects which protocol to use based on firmware version (same logic as `WatchAdapterFactory`).

**Key design**: The vendored request classes talk back to the adapter via `getDeviceSupport()` — our `QHybridSupport` shim class satisfies this interface and delegates to `BleTransport`.

```java
public class FossilQAdapter {
    private BleTransport transport;
    private FossilWatchAdapter shimAdapter;  // The shim that vendored code calls into
    private QHybridSupport shimSupport;      // The shim returned by getDeviceSupport()
    private GBDevice device;
    private ConfigStore config;
    private boolean useFossilProtocol; // true for firmware 2.x

    // Fossil protocol state
    private FossilRequest currentFossilRequest;
    private ArrayList<Request> requestQueue = new ArrayList<>();
    private int mtu = 23;
    private ScheduledExecutorService timeoutExecutor;

    public FossilQAdapter(BleTransport transport) {
        this.transport = transport;
        this.device = new GBDevice();
        this.shimSupport = new QHybridSupport(transport, device);
        this.shimAdapter = new FossilWatchAdapter(shimSupport);
        // Wire up the queueWrite delegate so vendored requests can queue follow-ups
        this.shimAdapter.setQueueWriteDelegate(this::queueWrite);
    }

    // --- Called by CLI commands ---
    public void initialize();
    public void syncTime();
    public void playNotification(VibrationType vibration, int hourDeg, int minDeg);
    public void setAlarms(List<AlarmConfig> alarms);
    public void fetchActivity(Path outputFile);
    public void setStepGoal(int steps);
    public void setVibrationStrength(int strength);
    public void setTimezoneOffset(short minutes);
    public void overwriteButtons(ConfigPayload[] payloads);
    public void setHands(int hourDeg, int minDeg, int subDeg);
    public void findDevice();
    public void calibrate();
    public DeviceInfo getDeviceInfo();

    // --- BLE callbacks (called by BluezTransport) ---
    public void onCharacteristicChanged(UUID uuid, byte[] value);
    public void onMtuChanged(int mtu);
    public void onConnectionStateChange(boolean connected);

    // --- Request queue (Fossil protocol) ---
    // This is the delegate target for shimAdapter.queueWrite()
    private void queueWrite(FossilRequest request, boolean prioritise);
    private void queueNextRequest();
}
```

### Protocol detection

Firmware version string format: `XX0.0.M.mm` where `XX` = model code, `M` = major, `mm` = minor.
Examples: `HW0.0.2.13` (Q Commuter, Fossil protocol), `HL0.0.1.05` (Q Activist, Misfit protocol).

```java
// Same logic as WatchAdapterFactory
public void detectProtocol(String firmwareVersion) {
    char hardwareVersion = firmwareVersion.charAt(2); // '0' or '1'
    char major = firmwareVersion.charAt(6);           // '0', '1', or '2'
    if (hardwareVersion == '1') {
        throw new UnsupportedOperationException("Hybrid HR not supported");
    }
    this.useFossilProtocol = (major == '2');
    // major 0 or 1 = Misfit protocol, major 2 = Fossil protocol
}
```

### Detailed initialization sequence

**Step 1: BLE setup** (done before adapter)
```
1. Connect via BlueZ
2. Enumerate GATT characteristics, build UUID → D-Bus path map
3. Enable notifications on 3dda0002 through 3dda0007
4. Read 00002a19 (battery), 00002a26 (firmware), 00002a24 (model)
5. Parse firmware string → select protocol
```

**Step 2a: Misfit protocol init**
```
1. Queue: GetStepGoalRequest
2. Queue: GetVibrationStrengthRequest
3. Queue: ActivityPointGetRequest
4. Queue: SetTimeRequest (current time + timezone)
5. Queue: AnimationRequest (pairing animation)
6. Queue: SetCurrentStepCountRequest
7. Send: GetCurrentStepCountRequest (triggers queue drain)
```

**Step 2b: Fossil protocol init**
```
1. Send: AnimationRequest (pairing animation)
2. Send: RequestMtuRequest(512) → triggers BlueZ MTU negotiation
3. Send: GetDeviceInfoRequest → parses SupportedFileVersionsInfo
4. Send: ConfigurationPutRequest (step goal + vibration + timezone + time)
5. Send: NotificationFilterPutRequest (notification settings as file)
6. Send: FilePutRequest (button config file)
7. Set state: INITIALIZED
```

### How the shim adapter bridges vendored code to our adapter

```
CLI command
  → FossilQAdapter.setStepGoal(n)
    → creates ConfigurationPutRequest(item, shimAdapter)   // shimAdapter = our FossilWatchAdapter shim
    → queueWrite(request, false)                           // queues it
    → queueNextRequest()                                   // starts processing
      → writes request bytes to BLE via shimSupport.createTransactionBuilder().write().queue()
      → watch responds on 3dda0003
      → FossilQAdapter.onCharacteristicChanged()
        → request.handleResponse(characteristic, value)
          → if request needs follow-up write (e.g. FilePutRawRequest):
            → adapter.getDeviceSupport().createTransactionBuilder("file upload")
              → creates new TransactionBuilder backed by our BleTransport
            → .write(characteristic, packet).queue()
              → writes directly to BLE
          → if request queues follow-up request (e.g. FileLookupAndGetRequest):
            → adapter.queueWrite(new FileGetRawRequest(...), true)
              → shimAdapter.queueWriteDelegate → FossilQAdapter.queueWrite()
```

---

## Phase 4: CLI

**Library**: [picocli](https://picocli.info/) — modern, annotation-based, auto-generates help.

```java
@Command(name = "fossil-q", description = "Fossil Q Hybrid CLI (coin-cell models)",
         subcommands = { ConnectCmd.class, InfoCmd.class, TimeCmd.class,
                         NotifyCmd.class, AlarmCmd.class, ActivityCmd.class,
                         ConfigCmd.class, FindCmd.class, HandsCmd.class,
                         VibrateCmd.class, ButtonsCmd.class, MonitorCmd.class })
public class Main implements Runnable {
    @Option(names = {"-d", "--device"}, description = "MAC address")
    String macAddress;

    public void run() { CommandLine.usage(this, System.out); }
}
```

### Commands

```
fossil-q connect <mac>                Connect to watch
fossil-q info                          Device info (model, firmware, battery)
fossil-q time                          Sync time to watch
fossil-q notify <vibration> [h] [m]   Send notification (vibration + hand movement)
fossil-q vibrate <type>               Vibrate (SINGLE_SHORT, DOUBLE_SHORT, etc.)
fossil-q alarm list                    List alarms (firmware 2.x only)
fossil-q alarm set <HH