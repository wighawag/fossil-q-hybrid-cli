# Fresh Context Guide

Read this first when starting a new session on this project.

## What This Is

A Linux CLI tool that talks to Fossil Q Hybrid coin-cell watches (Q Commuter HW.0.0, Q Activist HL.0.0) over BLE. Reuses GadgetBridge's protocol layer with zero patches — shim classes satisfy Android imports at compile time.

## Quick Start

```bash
# Build
./gradlew shadowJar

# Run (watch must be paired + connected via bluetoothctl first)
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A info
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A time
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A find -t 3
```

## Project Layout

```
src/main/java/
  qhybrid/linux/
    Main.java              # picocli CLI, 11 subcommands
    FossilQAdapter.java    # Protocol adapter (request queue, init, dispatch)
    BluezTransport.java    # BLE via busctl + persistent bluetoothctl + gdbus monitor
    BleTransport.java      # Interface
  android/...              # Android API shims (8 files)
  androidx/...             # Annotation shims (3 files)
  nodomain/.../            # GB model/service shims (9 files)

gadgetbridge/              # Vendored GB code (124 files, zero patches, copied by sync.sh)
```

## Key Architecture Decisions

1. **Persistent bluetoothctl** for notifications — busctl one-shot D-Bus connections can't hold StartNotify alive
2. **gdbus monitor** for receiving BLE notifications — catches PropertiesChanged Value updates
3. **Write type from flags** — `command` for notify chars, `request` for indicate chars
4. **UTC epoch + TimezoneOffsetConfigItem** for time — watch uses the offset to shift display
5. **Shim classes** instead of patching vendor code — `sync.sh` is pure copy

## Test Hardware

| Field | Value |
|-------|-------|
| Watch | Fossil Q Commuter |
| Model | HW.0.0 |
| Firmware | HW0.0.2.9r.v3 |
| Protocol | Fossil (2.x) |
| BLE Address | D9:20:71:11:74:2A |
| MTU | 185 |

## Key Docs

| File | Purpose |
|------|---------|
| FINDINGS.md | 13 technical discoveries from real-hardware testing |
| TODO.md | Feature checklist with priorities |
| CALIBRATION-PLAN.md | Interactive calibration UX design |
| ROADMAP.md | CLI → shared library → Android app |
| fossil-qhybrid-linux.md | Original architecture plan (shim inventory, protocol analysis) |
| README.md | User-facing docs |

## Known Gotchas

- **BlueZ agent required** — without it, pairing fails and GATT operations error silently
- **`getRawOffset()` vs `getOffset(millis)`** — must use `getOffset()` for DST-aware offset (Europe/London: raw=0, actual=60 in summer)
- **Alarm file version must be 2** — version 0/1/3 return VERIFICATION_FAIL
- **Thursday/Wednesday swapped** in alarm day bitmask (bit3=Thu, bit4=Wed)
- **Watch does directed advertising** after pairing — won't appear in general scan, connect by MAC
- **12+ alarms work** despite GadgetBridge limiting to 5
