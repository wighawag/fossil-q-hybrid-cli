# Fresh Context Guide

Read this first when starting a new session on this project.

## What This Is

A Linux CLI tool that talks to Fossil Q Hybrid coin-cell watches (Q Commuter HW.0.0, Q Activist HL.0.0) over BLE. Reuses GadgetBridge's protocol layer with zero patches — shim classes satisfy Android imports at compile time.

## Quick Start

```bash
# Build
./gradlew shadowJar

# Run (press watch button to wake before first connect)
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A info
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A time
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A notify SINGLE_SHORT
```

## Project Layout

```
src/main/java/
  qhybrid/linux/
    Main.java              # picocli CLI, 11 subcommands, --subprocess flag
    FossilQAdapter.java    # Protocol adapter (request queue, init, dispatch)
    DbusTransport.java     # BLE via dbus-java + bluez-dbus (default, direct D-Bus)
    BluezTransport.java    # BLE via busctl + persistent bluetoothctl + gdbus monitor (--subprocess fallback)
    BleTransport.java      # Interface
  android/...              # Android API shims (8 files)
  androidx/...             # Annotation shims (3 files)
  nodomain/.../            # GB model/service shims (9 files)

gadgetbridge/              # Vendored GB code (124 files, zero patches, copied by sync.sh)
```

## Key Architecture Decisions

1. **Persistent bluetoothctl** for notifications — busctl one-shot D-Bus connections can't hold StartNotify alive
2. **gdbus monitor** for receiving BLE notifications — catches PropertiesChanged Value updates (handles both `[byte 0x...]` and `b'...'` formats)
3. **Write type from flags** — `command` for notify chars, `request` for indicate chars
4. **UTC epoch + TimezoneOffsetConfigItem** for time — watch uses the offset to shift display
5. **Shim classes** instead of patching vendor code — `sync.sh` is pure copy
6. **Auth on separate thread** — `fossil-auth` thread runs auth handshake to avoid deadlocking notification delivery thread
7. **BleTransport interface** — `DbusTransport` (dbus-java, default) and `BluezTransport` (subprocess, `--subprocess` flag) share the same interface. Adapter/protocol code is transport-agnostic.
8. **dbus-java transport** — `bluez-dbus` 0.3.2 + `dbus-java` 5.x for direct D-Bus calls. Single persistent connection replaces 3 subprocess processes (busctl + bluetoothctl + gdbus). PropertiesChanged signal handler replaces gdbus monitor + regex parsing.

## Test Hardware

| Field | Value |
|-------|-------|
| Watch | Fossil Q Commuter |
| Model | HW.0.0 |
| Firmware | HW0.0.2.9r.v3 |
| Protocol | Fossil (2.x) |
| BLE Address | D9:20:71:11:74:2A |
| MTU | 185 |

## Connection Speed

| Transport | Reconnect | Clean (GATT retry) | Notes |
|-----------|-----------|---------------------|-------|
| dbus-java (default) | **~5-7s** | ~12-18s | Direct D-Bus calls, ~1ms per BLE op |
| subprocess (`--subprocess`) | ~8s | ~13-30s | busctl/bluetoothctl/gdbus |

GATT retry is a BlueZ/firmware issue (not transport-specific) — adds ~6.5s when triggered.

## Key Docs

| File | Purpose |
|------|---------|
| DBUS-JAVA-MIGRATION.md | Plan for dbus-java transport (implemented, kept for reference) |
| AUTHENTICATION-PLAN.md | Full auth protocol decode + plan to crack notification system |
| FINDINGS.md | 15 technical discoveries from real-hardware testing |
| TODO.md | Feature checklist with priorities |
| CALIBRATION-PLAN.md | Interactive calibration UX design |
| ROADMAP.md | CLI → shared library → Android app |
| fossil-qhybrid-linux.md | Original architecture plan (shim inventory, protocol analysis) |
| README.md | User-facing docs |
| TEST-RESULTS.md | All 11 CLI commands tested on real hardware |

## Notification System Status

**Status: WORKING ✅** (as of 2026-05-20)

Authentication handshake implemented and tested. File-based notifications now produce
vibration + hand animation. The `findDevice()` workaround has been removed.

**Auth flow (runs during init, before notification filter upload):**
1. Write `01 07` to `3dda0005` → check authorization status
2. If `03 07 00` (or 2-byte `03 07`): needs auth → write `02 06 30 75 00 00 01`
   (30s timeout, removeOtherPhones=true) → watch vibrates → user presses TOP button
3. If `03 07 01`: already authorized → skip step 2
4. Upload notification filter → send notification play file → vibration + hand movement

**On reconnect:** Auth persists — step 2 is skipped (instant init).

**Post-auth BLE pairing:** After Fossil auth succeeds (`03 06 00 01`), the app
triggers BLE pairing (`device.pair()`) to create a bond. Our `registerAgent()`
provides a "Just Works" agent (raw `Agent1` impl with `DBusPath`/`UInt32` types).
Auth clears when bonded partner deletes its link key AND the watch attempts
auto-reconnect (gets rejected at SMP layer). No Fossil de-auth command exists.
Bond benefits: encrypted link, WakeAllowed, faster reconnect (device stays known).

**Filter format:** Fully decoded, byte-identical to official Fossil app (verified).

**Implementation notes:**
- Auth runs on a dedicated `fossil-auth` thread to avoid deadlocking the `bluez-monitor`
  thread (which delivers the auth indications via gdbus)
- The `b'...'` Python byte literal format from GLib 2.84+ gdbus required a new parser
  (`parseGdbusByteLiteral`) — the `[byte 0x...]` format is only used for larger arrays

## Known Gotchas

- **BlueZ agent required** — without it, pairing fails and GATT operations error silently
- **`getRawOffset()` vs `getOffset(millis)`** — must use `getOffset()` for DST-aware offset (Europe/London: raw=0, actual=60 in summer)
- **Alarm file version must be 2** — version 0/1/3 return VERIFICATION_FAIL
- **Thursday/Wednesday swapped** in alarm day bitmask (bit3=Thu, bit4=Wed)
- **Watch does directed advertising** after pairing — won't appear in general scan, connect by MAC
- **12+ alarms work** despite GadgetBridge limiting to 5
- **Watch only advertises ~30s after button press** — must wake watch before scanning
- **GATT services fail on first connect** after `bluetoothctl remove` — auto-retry (up to 3 attempts) handles this. BlueZ caches service layout across retries.
- **Stale "In Progress" connections** — a previous failed connect can block new ones. Disconnect before connecting to clear.
- **`bluetoothctl pair` always fails** — Fossil uses its own auth, not BLE pairing. Just `trust` + `connect`
- **Auth tied to BLE bond** — watch clears auth when BLE link key is deleted (official app's "remove watch"). Our `trust`+`connect` never creates a bond, so `bluetoothctl remove` doesn't clear auth.
- **gdbus byte literal format** — GLib 2.84+ uses `b'\003\007'` for small byte arrays, `[byte 0x03, 0x07]` for larger ones. Both must be parsed.
- **Auth indication deadlock** — auth must run on a separate thread from `bluez-monitor` (which delivers the indications via CompletableFuture)
- **2-byte auth status** — watch sends `03 07` (2 bytes) for status=0x00, not `03 07 00`. Handle missing trailing null.
- **dbus-java Properties.Get() unwraps Variant** — returns `UInt16` directly, not `Variant<UInt16>`. Must handle both forms when reading MTU.
- **StartNotify throws NotPermitted on Fossil chars** — expected without BLE bonding. Notifications still delivered via PropertiesChanged signal handler.

## BLE Captures

| File | Content | Notes |
|------|---------|-------|
| `tmp/bugreport/` | First capture — reconnect, auth `01 07` | Full ATT data |
| `tmp/bugreport3/` | Third capture — reconnect, auth `02 06`, full init + notification | Full ATT data, 571 packets |
| `tmp/bugreport2/` | Second capture | Truncated (btsnooz circular buffer) |

## Decompiled Official App

- Location: `tmp/FossilOfficialApp-deobf/` (jadx with `--deobf`)
- Key auth files: `device/logic/request/authentication/`, `device/logic/phase/AuthenticatePhase.java`
- Key notification files: `device/data/notification/NotificationFilter.java`, `NotificationVibePattern.java`
- jadx binary: `/home/wighawag/dev/github/skylot/jadx/build/jadx/bin/jadx`
