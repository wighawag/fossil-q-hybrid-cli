# Fossil Q Hybrid CLI for Linux

A Linux desktop CLI tool for communicating with **coin-cell battery** Fossil Q Hybrid watches over BLE.

**Supported watches:**
- Fossil Q Commuter (HW.0.0)
- Fossil Q Activist (HL.0.0)

**Not supported:** Hybrid HR (IV.0.0), Collider (DN.1.0), Gen 6 Hybrid — these use encrypted protocols.

## How it works

This project reuses [GadgetBridge](https://codeberg.org/Gadgetbridge/Gadgetbridge)'s Fossil Q Hybrid protocol layer (requests, file transfer, configuration encoding) **without patching any upstream code**. Instead, we provide shim/stub classes that satisfy Android API imports at compile time, and a `BluezTransport` that bridges the protocol to Linux's BlueZ D-Bus stack.

```
┌─────────────────────────────────────────────┐
│  CLI (picocli)                              │
│  └── FossilQAdapter (our adapter)           │
│       ├── Vendored GB request classes       │
│       │   (compiled against shim classes)   │
│       └── BluezTransport                    │
│            └── busctl / bluetoothctl / dbus  │
│                 └── BlueZ → BLE → Watch     │
└─────────────────────────────────────────────┘
```

## Prerequisites

- Java 17+
- BlueZ 5.50+ (standard on modern Linux)
- `bluetoothctl`, `busctl`, `dbus-monitor` (usually pre-installed)
- Your user must be in the `bluetooth` group (or run as root)
- Watch must be **unpaired from phone** first (single-bond limitation)

## Build

```bash
# If updating vendored code from upstream GadgetBridge:
# git clone https://codeberg.org/Gadgetbridge/Gadgetbridge.git tmp/Gadgetbridge
# bash sync.sh

# Build fat JAR
./gradlew shadowJar

# Run
java -jar build/libs/fossil-q.jar --help
```

## Connecting the Watch

The Fossil Q Hybrid uses BLE (Bluetooth Low Energy). It can only be connected to **one device at a time** — if it's paired to your phone, you must unpair it first.

### First-time setup

1. **Unpair from phone** (if previously paired): Open the Fossil app → Settings → Remove watch, or forget the device in Android/iOS Bluetooth settings.

2. **Wake the watch**: Press the watch button. The watch only advertises (is discoverable) for a short window after waking.

3. **Connect from Linux**: The CLI will auto-scan if the device isn't known to BlueZ:
   ```bash
   java -jar build/libs/fossil-q.jar -d AA:BB:CC:DD:EE:FF info
   # If the watch isn't found, it will scan for 30s.
   # Press the watch button during the scan.
   ```

4. **Manual connection** (if auto-scan fails):
   ```bash
   # In one terminal, start scanning:
   bluetoothctl scan on
   # Press the watch button — wait for "Device AA:BB:CC:DD:EE:FF Fossil" to appear
   # Then connect:
   bluetoothctl connect AA:BB:CC:DD:EE:FF
   ```
   Note: `bluetoothctl pair` will fail with `AuthenticationFailed` — this is normal. The Fossil Q uses its own authentication via BLE characteristics, not standard BLE pairing. Just use `connect`.

### Reconnecting

Once connected the first time, BlueZ remembers the device. Subsequent runs of the CLI will auto-connect without scanning. If the connection is lost:
- **Press the watch button** to wake it (it stops advertising after ~30s of inactivity)
- If BlueZ has forgotten the device (e.g. after `bluetoothctl remove`), repeat the first-time setup

### Troubleshooting

| Problem | Solution |
|---------|----------|
| `Device not found during scan` | Press the watch button to wake it. The advertising window is short (~30s). |
| `Connection failed` | Make sure the watch isn't paired to another device (phone). Only one connection at a time. |
| `GATT services not resolved` | Disconnect and reconnect: `bluetoothctl disconnect AA:BB:...` then retry. |
| `AuthenticationFailed` on pair | Normal — don't use `pair`, just `connect`. |
| Watch not advertising at all | Try a long press (10+ seconds) on the button, or pull the crown out and push it back in. |

### Finding your watch's MAC address

```bash
bluetoothctl scan on
# Look for a device named "Fossil" or "Q Commuter"
# The MAC address looks like: D9:20:71:11:74:2A
bluetoothctl scan off
```

## Usage

```bash
# Shorthand: create an alias
alias fossil-q='java -jar build/libs/fossil-q.jar'

# Show device info
fossil-q -d AA:BB:CC:DD:EE:FF info

# Sync time
fossil-q -d AA:BB:CC:DD:EE:FF time

# Send notification with vibration
fossil-q -d AA:BB:CC:DD:EE:FF notify SINGLE_SHORT

# Notification with custom vibration pattern + hand position
fossil-q -d AA:BB:CC:DD:EE:FF notify SINGLE_SHORT --vibe call --position phone    # triple vibe, hands at 2:00
fossil-q -d AA:BB:CC:DD:EE:FF notify SINGLE_SHORT --vibe text --position 9:00     # double vibe, hands at 9:00
fossil-q -d AA:BB:CC:DD:EE:FF notify SINGLE_SHORT -v long -p 120/240              # hour=4, min=8

# Test all hand positions around the clock
fossil-q -d AA:BB:CC:DD:EE:FF position-test

# Find my watch (vibrate)
fossil-q -d AA:BB:CC:DD:EE:FF find

# Move hands
fossil-q -d AA:BB:CC:DD:EE:FF hands 180 90 0 --release

# Set step goal
fossil-q -d AA:BB:CC:DD:EE:FF step-goal 8000

# Set alarm (Fossil protocol only)
fossil-q -d AA:BB:CC:DD:EE:FF alarm 07:30 --days 31 --label "Weekday"

# Fetch activity data
fossil-q -d AA:BB:CC:DD:EE:FF activity -o steps.bin
```

### Vibration types
`SINGLE_SHORT`, `DOUBLE_SHORT`, `TRIPLE_SHORT`, `SINGLE_NORMAL`, `DOUBLE_NORMAL`, `TRIPLE_NORMAL`, `SINGLE_LONG`, `NO_VIBE`

## Protocol details

The watch uses two protocol variants depending on firmware:
- **Misfit protocol** (firmware 0.x / 1.x) — simpler request/response on characteristic `3dda0002`
- **Fossil protocol** (firmware 2.x) — file-based with control (`3dda0003`) and data (`3dda0004`) channels

Protocol is auto-detected from the firmware version string.

## Architecture

| Directory | Purpose |
|-----------|---------|
| `gadgetbridge/` | Vendored GadgetBridge protocol code (verbatim, zero patches) |
| `src/main/java/android/` | Android API shims (UUID holder, constants) |
| `src/main/java/androidx/` | Annotation stubs |
| `src/main/java/nodomain/.../` | GadgetBridge model/service shims |
| `src/main/java/qhybrid/linux/` | Our code: CLI, adapter, BLE transport |

See [fossil-qhybrid-linux.md](fossil-qhybrid-linux.md) for the detailed design document.

## License

AGPL-3.0 (matching GadgetBridge)

## Credits

Protocol implementation from [GadgetBridge](https://codeberg.org/Gadgetbridge/Gadgetbridge) by the GadgetBridge contributors.
