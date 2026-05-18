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

## Usage

```bash
# First: pair the watch (one-time)
bluetoothctl scan le              # Find your watch
bluetoothctl pair AA:BB:CC:DD:EE:FF
bluetoothctl connect AA:BB:CC:DD:EE:FF

# Show device info
fossil-q -d AA:BB:CC:DD:EE:FF info

# Sync time
fossil-q -d AA:BB:CC:DD:EE:FF time

# Send notification with vibration
fossil-q -d AA:BB:CC:DD:EE:FF notify SINGLE_SHORT
fossil-q -d AA:BB:CC:DD:EE:FF notify DOUBLE_NORMAL -H 90 -M 180

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
