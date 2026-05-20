# BluezTransport → DbusTransport Migration Plan

## Goal

Add a new `DbusTransport` class implementing `BleTransport` that uses
[dbus-java](https://github.com/hypfvieh/dbus-java) to talk to BlueZ directly
over the D-Bus socket. Keep the existing `BluezTransport` (subprocess-based)
as a fallback, selectable via CLI flag.

## Why

The current `BluezTransport` spawns subprocesses for every BLE operation:

| Operation | Current (subprocess) | dbus-java (in-process) |
|-----------|---------------------|----------------------|
| Write characteristic | ~50-100ms (busctl fork+exec) | ~1ms (D-Bus method call) |
| Read characteristic | ~50-100ms | ~1ms |
| Discover 22 chars | ~44 busctl calls (~3s) | 1 GetManagedObjects call (~50ms) |
| Enable notifications | 6 × bluetoothctl commands (~2.4s) | 6 × StartNotify calls (~50ms) |
| Receive notification | gdbus monitor → stdout parse → regex | D-Bus signal handler (direct callback) |

**Expected total improvement:** connect+init from ~8s to ~3-5s.

Also eliminates:
- Fragile stdout/regex parsing (gdbus `b'...'` vs `[byte ...]` format)
- Persistent bluetoothctl process hack for StartNotify
- gdbus monitor process for receiving notifications
- "In Progress" stale connection issues from orphaned subprocesses

## Architecture

```
BleTransport (interface)
├── BluezTransport     (existing — subprocess-based, keep as fallback)
└── DbusTransport      (new — dbus-java, direct D-Bus calls)
```

### Selection in Main.java

```java
// In connectAndInit():
BleTransport transport;
if (useSubprocess) {
    transport = new BluezTransport();
} else {
    transport = new DbusTransport();  // default
}
```

Add `--subprocess` flag to Main.java to select the old implementation.

## Dependencies

Add to `build.gradle`:

```groovy
dependencies {
    // ... existing deps ...
    implementation 'com.github.hypfvieh:dbus-java-core:5.1.0'
    implementation 'com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.0'
}
```

dbus-java 5.x requires Java 17+ (we already target 17).

## Implementation Plan

### Step 1: DbusTransport skeleton + connect/disconnect

Create `src/main/java/qhybrid/linux/DbusTransport.java` implementing `BleTransport`.

Key D-Bus setup:
```java
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;

// Single persistent connection to system bus
DBusConnection dbus = DBusConnectionBuilder.forSystemBus().build();

// BlueZ interfaces needed:
// - org.bluez.Adapter1        (scan on/off)
// - org.bluez.Device1         (connect/disconnect/trust, properties)
// - org.bluez.GattCharacteristic1 (read/write/StartNotify)
// - org.freedesktop.DBus.ObjectManager (GetManagedObjects for discovery)
// - org.freedesktop.DBus.Properties (PropertiesChanged signals)
```

#### connect(macAddress):
```
1. Get Adapter1 proxy for /org/bluez/hci0
2. Check if device already Connected (get Device1 property)
3. If not connected:
   a. Call Adapter1.SetDiscoveryFilter({Transport: "le"})
   b. Call Adapter1.StartDiscovery()
   c. Subscribe to InterfacesAdded signal, wait for device path to appear
   d. Call Adapter1.StopDiscovery()
   e. Call Device1.Trust() (set Trusted property)
   f. Call Device1.Connect()
4. Wait for ServicesResolved=true via PropertiesChanged signal
5. If ServicesResolved times out: Disconnect, retry (same logic as BluezTransport)
```

#### disconnect():
```
1. Call Device1.Disconnect()
2. Close signal subscriptions
```

### Step 2: Characteristic discovery + read/write

#### discoverCharacteristics():
```java
// One call replaces 44 subprocess calls:
ObjectManager om = dbus.getRemoteObject("org.bluez", "/", ObjectManager.class);
Map<DBusPath, Map<String, Map<String, Variant<?>>>> objects = om.GetManagedObjects();

// Filter for GattCharacteristic1 interfaces under our device path
for (entry : objects) {
    if (entry.path.startsWith(devicePath) && entry.interfaces.containsKey("org.bluez.GattCharacteristic1")) {
        String uuid = entry.interfaces.get("org.bluez.GattCharacteristic1").get("UUID");
        List<String> flags = entry.interfaces.get("org.bluez.GattCharacteristic1").get("Flags");
        charPaths.put(UUID.fromString(uuid), entry.path);
        charFlags.put(UUID.fromString(uuid), flags);
    }
}
```

#### writeCharacteristic(uuid, data):
```java
GattCharacteristic1 char = dbus.getRemoteObject("org.bluez", charPath, GattCharacteristic1.class);
Map<String, Variant<?>> options = Map.of("type", new Variant<>("command")); // or "request"
char.WriteValue(data, options);
```

#### readCharacteristic(uuid, data):
```java
byte[] value = char.ReadValue(Map.of());
```

### Step 3: Notifications via PropertiesChanged signal

This is the key advantage — no gdbus process, no stdout parsing, no regex:

```java
// Subscribe to PropertiesChanged on each characteristic path
dbus.addSigHandler(Properties.PropertiesChanged.class,
    "org.bluez", charPath,
    signal -> {
        Map<String, Variant<?>> changed = signal.getPropertiesChanged();
        if (changed.containsKey("Value")) {
            byte[] value = (byte[]) changed.get("Value").getValue();
            notificationCallback.accept(uuid, value);
        }
    }
);

// Enable notifications — single D-Bus call, stays alive as long as our connection lives
GattCharacteristic1 char = dbus.getRemoteObject("org.bluez", charPath, GattCharacteristic1.class);
char.StartNotify();
// No need for persistent bluetoothctl! Our DBusConnection IS the persistent connection.
```

### Step 4: Scanning

```java
Adapter1 adapter = dbus.getRemoteObject("org.bluez", "/org/bluez/hci0", Adapter1.class);

// Set discovery filter for BLE only
adapter.SetDiscoveryFilter(Map.of("Transport", new Variant<>("le")));

// Subscribe to InterfacesAdded to detect new devices
dbus.addSigHandler(ObjectManager.InterfacesAdded.class, signal -> {
    if (signal.getPath().contains(macAddress.replace(":", "_"))) {
        // Device found
    }
});

adapter.StartDiscovery();
// ... wait for device ...
adapter.StopDiscovery();
```

### Step 5: Wire into Main.java

```java
@Option(names = {"--subprocess"}, description = "Use subprocess-based BLE transport (bluetoothctl/busctl)")
boolean useSubprocess;

// In connectAndInit():
BleTransport transport;
if (useSubprocess) {
    transport = new BluezTransport();
} else {
    transport = new DbusTransport();
}
```

### Step 6: MTU + connection monitoring

MTU: read from Device1 or GattCharacteristic1 `MTU` property directly.

Connection monitoring: subscribe to PropertiesChanged on Device1 path,
watch for `Connected` property changes. Replaces the gdbus monitor
Connected detection.

## BlueZ D-Bus Interface Definitions

dbus-java needs interface definitions. Either:

**Option A:** Use dbus-java's `bluez` module (if available):
```groovy
implementation 'com.github.hypfvieh:bluez-dbus:0.3.0'
```
This provides pre-built Java interfaces for BlueZ (Adapter1, Device1, GattCharacteristic1, etc.).

**Option B:** Define minimal interfaces manually:
```java
@DBusInterfaceName("org.bluez.Device1")
public interface Device1 extends DBusInterface {
    void Connect();
    void Disconnect();
    // Properties accessed via org.freedesktop.DBus.Properties
}

@DBusInterfaceName("org.bluez.GattCharacteristic1")
public interface GattCharacteristic1 extends DBusInterface {
    byte[] ReadValue(Map<String, Variant<?>> options);
    void WriteValue(byte[] value, Map<String, Variant<?>> options);
    void StartNotify();
    void StopNotify();
}
```

**Option A is preferred** — `bluez-dbus` is maintained and handles all the
interface quirks. If it pulls too many dependencies, use Option B with just
the 4-5 interfaces we need.

## Testing Strategy

1. Implement DbusTransport with connect + read battery/firmware
2. Test `info` command with `--subprocess` vs default (dbus-java)
3. Add write + notification support
4. Test `notify` command (auth handshake + file transfer)
5. Compare timing of full init sequence
6. Keep `BluezTransport` tests passing (regression check)

## Risk / Fallback

- If dbus-java has issues on the target Linux distro, `--subprocess` falls back
  to the proven BluezTransport
- dbus-java 5.x is well-maintained (last release 2024) and widely used
- The `BleTransport` interface is clean — no leaky abstractions to worry about
