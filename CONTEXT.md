# Fresh Context Guide

Read this first when starting a new session on this project.

## What This Is

A Linux CLI tool that talks to Fossil Q Hybrid coin-cell watches (Q Commuter HW.0.0, Q Activist HL.0.0) over BLE. Reuses GadgetBridge's protocol layer with zero patches - shim classes satisfy Android imports at compile time.

## Quick Start

```bash
# Build
./gradlew shadowJar

# Run (press watch button to wake before first connect)
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A info
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A time
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A notify SINGLE_SHORT
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A notify SINGLE_SHORT --vibe call   # triple vibe
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A notify SINGLE_SHORT --vibe call --position phone  # triple vibe + hands at 2:00
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A notify SINGLE_SHORT --position 9:00               # hands at 9:00
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A notify SINGLE_SHORT -v text -p 120/240            # hour=4, min=8
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A notify-test                       # test all patterns
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A position-test                     # test hand positions 1:00-12:00
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A position-test --positions "60,180,270"  # custom positions
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A buttons stopwatch music forward_to_phone
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A buttons mode_toggle second_timezone forward_to_phone  # A→B→C (needs alarm set)
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A buttons "second_timezone+date+alarm_toggle+step_goal+last_notification" second_timezone forward_to_phone  # 5-entry toggle
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A second-timezone 330   # IST (UTC+5:30)
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A second-timezone off   # disable
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A goal-config 8         # set goal target to 8
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A goal-config 8 3       # target=8, current=3
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A read-config            # read all config from watch
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A read-config --raw      # with hex bytes
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A alarm list             # read alarms from watch
java -jar build/libs/fossil-q.jar -d D9:20:71:11:74:2A monitor  # Ctrl+C to stop
```

## Project Layout

```
src/main/java/
  qhybrid/linux/
    Main.java              # picocli CLI, 14 subcommands, --subprocess flag
    FossilQAdapter.java    # Protocol adapter (request queue, init, dispatch)
    ActivityParser.java    # Activity file parser (version 22, no-HR, multi-segment)
    DbusTransport.java     # BLE via dbus-java + bluez-dbus (default, direct D-Bus)
    BluezTransport.java    # BLE via busctl + persistent bluetoothctl + gdbus monitor (--subprocess fallback)
    BleTransport.java      # Interface
  android/...              # Android API shims (8 files)
  androidx/...             # Annotation shims (3 files)
  nodomain/.../            # GB model/service shims (9 files)

gadgetbridge/              # Vendored GB code (124 files, zero patches, copied by sync.sh)
```

## Key Architecture Decisions

1. **Persistent bluetoothctl** for notifications - busctl one-shot D-Bus connections can't hold StartNotify alive
2. **gdbus monitor** for receiving BLE notifications - catches PropertiesChanged Value updates (handles both `[byte 0x...]` and `b'...'` formats)
3. **Write type from flags** - `command` for notify chars, `request` for indicate chars
4. **UTC epoch + TimeConfigItem offset** for time - watch uses 0x000C's offset field for primary display; 0x0011 is second timezone only
5. **Shim classes** instead of patching vendor code - `sync.sh` is pure copy
6. **Auth on separate thread** - `fossil-auth` thread runs auth handshake to avoid deadlocking notification delivery thread
7. **BleTransport interface** - `DbusTransport` (dbus-java, default) and `BluezTransport` (subprocess, `--subprocess` flag) share the same interface. Adapter/protocol code is transport-agnostic.
8. **dbus-java transport** - `bluez-dbus` 0.3.2 + `dbus-java` 5.x for direct D-Bus calls. Single persistent connection replaces 3 subprocess processes (busctl + bluetoothctl + gdbus). PropertiesChanged signal handler replaces gdbus monitor + regex parsing.

## Test Hardware

| Field | Value |
|-------|-------|
| Watch | Fossil Fenmore (BQT1107) / Q Commuter HW.0.0 |
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

GATT retry is a BlueZ/firmware issue (not transport-specific) - adds ~6.5s when triggered.

## Key Docs

| File | Purpose |
|------|---------|
| DBUS-JAVA-MIGRATION.md | Plan for dbus-java transport (implemented, kept for reference) |
| AUTHENTICATION-PLAN.md | Full auth protocol decode + plan to crack notification system |
| FINDINGS.md | 29 technical discoveries from real-hardware testing |
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

**On reconnect:** Auth persists - step 2 is skipped (instant init).

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
  (`parseGdbusByteLiteral`) - the `[byte 0x...]` format is only used for larger arrays

## Known Gotchas

- **BlueZ agent required** - without it, pairing fails and GATT operations error silently
- **`getRawOffset()` vs `getOffset(millis)`** - must use `getOffset()` for DST-aware offset (Europe/London: raw=0, actual=60 in summer)
- **Config 0x0011 is SECOND timezone, not primary** - the official Fossil app sends 0x0011 ONLY when setting a second timezone. Primary TZ comes from TimeConfigItem (0x000C) offset field. **Fixed:** 0x0011 removed from `syncConfiguration()` and `setTimezoneOffset()`. New `setSecondTimezone()` method and `second-timezone` CLI command added. See FINDINGS.md #21a.
- **Alarm file version must be 2** - version 0/1/3 return VERIFICATION_FAIL
- **Weekday bitmask** bit3=Wed, bit4=Thu (hardware-verified). GB Alarm.java has them backwards.
- **Watch does directed advertising** after pairing - won't appear in general scan, connect by MAC
- **32 alarms max** - firmware limit, power-of-two. GB limits to 5, official app to 12 - both artificial. 33+ causes silent timeout (no error code). Alarm read-back works via `FileLookupAndGetRequest` (type 0x02); direct `FileGetRequest` (type 0x01) returns INVALID_OPERATION_DATA. See FINDINGS.md #25.
- **Watch only advertises ~30s after button press** - must wake watch before scanning
- **GATT services fail on first connect** after `bluetoothctl remove` - auto-retry (up to 3 attempts) handles this. BlueZ caches service layout across retries.
- **Stale "In Progress" connections** - a previous failed connect can block new ones. Disconnect before connecting to clear.
- **BLE pairing works after Fossil auth** - `pair` CLI command or auto-pair after auth. Raw Agent1 for Just Works.
- **Auth tied to BLE bond rejection** - watch clears auth when it attempts auto-reconnect to a bonded partner that deleted its link key. No Fossil de-auth command exists. Just removing bond from laptop doesn't clear auth (watch must actively try to reconnect and get rejected).
- **gdbus byte literal format** - GLib 2.84+ uses `b'\003\007'` for small byte arrays, `[byte 0x03, 0x07]` for larger ones. Both must be parsed.
- **Auth indication deadlock** - auth must run on a separate thread from `bluez-monitor` (which delivers the indications via CompletableFuture)
- **2-byte auth status** - watch sends `03 07` (2 bytes) for status=0x00, not `03 07 00`. Handle missing trailing null.
- **dbus-java Properties.Get() unwraps Variant** - returns `UInt16` directly, not `Variant<UInt16>`. Must handle both forms when reading MTU.
- **StartNotify throws NotPermitted on Fossil chars** - expected without BLE bonding. Notifications still delivered via PropertiesChanged signal handler.
- **Toggle entries skip when no data** - ALARM (no alarm set), LAST_NOTIFICATION (no cached notif), STEP_GOAL_COMPLETION (0% steps) are silently skipped in multi-entry toggles. Not an entry count limit. 5-entry toggle confirmed working.
- **Toggle max 6 entries** - 6 entries confirmed (TZ+DATE+ALARM+STEP_GOAL+LAST_NOTIF+24HR). 7 entries with any duplicate causes infinite loop (firmware wraps instead of terminating). Practical ceiling = 6 distinct display-only micro apps. See FINDINGS.md #27.
- **GOAL_TRACKING breaks toggle** - appId 0x04 produces error vibration and aborts remaining toggle entries. Incompatible payload structure (no `B0 XX 00` display-mode blocks). Works only as standalone button (blind counter).
- **"STEP_GOAL_PROGRESS" was ALARM** - appId 0x1a is ALARM (MicroAppId 6657/6658), not step goal progress. PROGRESS is appId 0x1c. See FINDINGS.md #22.
- **Sub-eye indicators: A=TZ, B=DATE, C=ALARM** — all three confirmed on Q Commuter (3-position dial). Other models (e.g. Q Activist) have 5-position dials: Time 2, Date, Alarm, Alert, 24HR. ALERT = LAST_NOTIFICATION (same appId 0x18). Sub-eye default = passive step progress (steps/goal). Celebration vibration when goal met.
- **TWENTY_FOUR_HOUR (appId 0x1E)** — firmware accepts it (no error), counts as toggle step, but produces no visible output on Q Commuter (no 24HR sub-eye label). Likely functional on 5-position dial watches. See FINDINGS.md #28.
- **PlayCrazyShitRequest / hand action files** — dead code in GadgetBridge (never called). Uploads to handle 0x0600 succeed but produce no effect on Q Commuter. The MicroAppCommand choreography system (vibrate, animate hands, loops) is non-functional on coin-cell watches. See FINDINGS.md #29.
- **Config 0x17/0x18 (task tracking goal/value)** — no visual effect on watch. Blind counter only. See FINDINGS.md #22.
- **Notification vibe patterns 0-9 all tested** — 0/9=silent, 1=triple, 2=double, 3/4=single, 5=strong single, 6=strong double, 7=strong triple, 8=long. 12s gap required between notifications (hands must return). See FINDINGS.md #23.
- **Notification hand positions fully configurable** — hour and minute hands move independently to specified degrees (0-359). Both hands return to time after ~10s. Presets: phone/sms=60° (2:00), whatsapp=90° (3:00), email=120° (4:00), calendar=300° (10:00). See FINDINGS.md #24.

## BLE Captures

| File | Content | Notes |
|------|---------|-------|
| `tmp/bugreport/` | First capture - reconnect, auth `01 07` | Full ATT data |
| `tmp/bugreport3/` | Third capture - reconnect, auth `02 06`, full init + notification | Full ATT data, 571 packets |
| `tmp/bugreport2/` | Second capture | Truncated (btsnooz circular buffer) |
| `tmp/bugreport5/` | Fifth capture - full Fossil app session: 2nd TZ, mode toggle, alarms, notif filters | 496 Fossil ATT ops, 780s |
| `tmp/bugreport6/` | Sixth capture - buttons: goal_tracking, last_notification, mode_toggle | Button payloads |
| `tmp/bugreport7/` | Seventh capture - goal tracking add events + sync | No config 0x17/0x18 sent |
| `tmp/bugreport8/` | Eighth capture - button presses on goal_tracking + last_notification | No BLE events (firmware-only) |

## Decompiled Official App

- Location: `tmp/FossilOfficialApp-deobf/` (jadx with `--deobf`)
- Key auth files: `device/logic/request/authentication/`, `device/logic/phase/AuthenticatePhase.java`
- Key notification files: `device/data/notification/NotificationFilter.java`, `NotificationVibePattern.java`
- jadx binary: `/home/wighawag/dev/github/skylot/jadx/build/jadx/bin/jadx`
