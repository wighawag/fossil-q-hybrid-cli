# Fossil Q Hybrid — Roadmap

## Phase 1: Linux CLI ← **current**

Working prototype on real hardware. See TODO.md for feature details.

**Goal:** Complete feature coverage of the watch, validate protocol understanding.

### Milestones
- [x] Connect, read info, time sync, calibration
- [ ] All watch features working (alarms, notifications, activity, buttons)
- [ ] Interactive calibration
- [ ] Monitor mode (button press listener)
- [ ] Config persistence
- [ ] Polish (auto-reconnect, error handling, daemon mode)

---

## Phase 2: Shared Protocol Library

**Goal:** Extract the protocol logic into a reusable library that both the CLI and the Android app can share.

### Key Insight

The vendored GadgetBridge request classes + our shim layer ARE the protocol library. The transport layer (BLE read/write/notify) is the only platform-specific part.

### Architecture

```
┌─────────────────────────────────────────────┐
│           Protocol Library (Java)            │
│                                              │
│  Request classes (vendored from GB)          │
│  FossilQAdapter (request queue, init, etc.)  │
│  Shim classes (Android API stubs)            │
│  Config items, file handles, alarm format    │
│                                              │
│  Interface: BleTransport                     │
│    writeCharacteristic(UUID, byte[])         │
│    readCharacteristic(UUID) → byte[]         │
│    enableNotifications(UUID)                 │
│    setNotificationCallback(BiConsumer)       │
│    ...                                       │
└─────────────┬───────────────┬───────────────┘
              │               │
    ┌─────────┴─────┐ ┌──────┴──────────┐
    │  BluezTransport│ │ AndroidTransport │
    │  (Linux CLI)   │ │ (Android app)    │
    │  busctl/gdbus  │ │ BluetoothGatt    │
    │  bluetoothctl  │ │ native BLE API   │
    └───────────────┘ └─────────────────┘
```

### Steps
- [ ] Extract protocol code into a `protocol/` module (Gradle subproject)
- [ ] CLI becomes a thin wrapper: `cli/` module depends on `protocol/`
- [ ] `BleTransport` interface is the clean boundary
- [ ] Shim classes only needed for CLI (Android has real classes)
- [ ] On Android, the vendored GB request classes could use REAL Android BLE — no shims needed

### Shim Strategy for Android

On Android we have two options:
1. **Keep shims** — the vendored code compiles against our stubs, `AndroidTransport` implements `BleTransport`. Simple but indirect.
2. **Drop shims, use real Android classes** — the vendored code already imports `BluetoothGattCharacteristic` etc. On Android these are real. Only need shims for GB-specific classes (`GBDevice`, `TransactionBuilder`, etc.).

Option 2 is cleaner for Android. The `TransactionBuilder` shim still needed since the real one is deeply coupled to GB's `BtLEQueue`.

---

## Phase 3: Android App

**Goal:** A focused, lightweight Android app exclusively for Fossil Q Hybrid coin-cell watches (Q Commuter, Q Activist).

### Why Not Just Use GadgetBridge?

GadgetBridge is excellent but:
- Supports 100+ devices → complex UI, many options irrelevant to Q Hybrid
- Fossil Q specific features buried in generic settings
- No dedicated calibration UX
- Alarm limit hardcoded to 5 (watch supports 12+)
- Time/timezone handling has known issues

A dedicated app can offer:
- **Focused UX** — every screen is relevant to YOUR watch
- **Better calibration** — interactive hand alignment
- **Full alarm support** — up to hardware limit, not artificial 5
- **Simpler setup** — no device type selection, coordinator logic, etc.

### Feature Set

#### Core
- [ ] BLE scan + pair (with proper agent handling)
- [ ] Time sync (automatic on connect)
- [ ] Battery display
- [ ] Hand calibration (interactive, per-hand)

#### Watch Face
- [ ] Current time display (analog clock matching watch)
- [ ] Battery indicator
- [ ] Connection status
- [ ] Step count / activity progress

#### Alarms
- [ ] Alarm list (add, edit, delete)
- [ ] Up to hardware limit (12+, not GB's 5)
- [ ] Repeating alarms with day picker
- [ ] Labels (if firmware supports version 3 format)

#### Notifications
- [ ] App notification filter (which apps vibrate the watch)
- [ ] Vibration type per app
- [ ] Hand movement per notification
- [ ] DND schedule

#### Activity
- [ ] Step count display
- [ ] Daily/weekly step history
- [ ] Step goal setting
- [ ] Activity data fetch + simple chart
- [ ] Export (CSV/JSON)

#### Buttons
- [ ] Button action configuration (per-button)
- [ ] Available actions: find phone, music control, forward to phone
- [ ] Button press log / event viewer

#### Settings
- [ ] Vibration strength
- [ ] Timezone (auto from phone)
- [ ] Step goal
- [ ] Inactivity warning
- [ ] Units (metric/imperial)

#### Advanced
- [ ] Hand animation designer (custom choreography)
- [ ] Raw file upload/download (for debugging)
- [ ] Protocol log viewer
- [ ] Watch info dump (all device info, file versions)

### Tech Stack (Android)

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Language | Kotlin | Modern Android standard |
| UI | Jetpack Compose | Declarative, less boilerplate |
| BLE | Native Android BLE API | Direct, no wrapper library needed |
| Protocol | Shared Java library (Phase 2) | Reuse vendored GB request classes |
| Architecture | MVVM + StateFlow | Standard Compose pattern |
| Persistence | Room (alarms, activity) + DataStore (prefs) | Standard Android |
| Min SDK | 26 (Android 8.0) | BLE companion mode, notification access |

### UI Screens (rough)

```
Main Screen
├── Watch face (analog clock, battery, steps)
├── Quick actions (sync time, find watch)
└── Navigation
    ├── Alarms
    │   ├── Alarm list
    │   └── Alarm editor (time picker, day selector, label)
    ├── Notifications
    │   ├── App filter list
    │   └── Per-app settings (vibration type, hand movement)
    ├── Buttons
    │   └── Button action picker (top, middle, bottom)
    ├── Activity
    │   ├── Today's steps
    │   └── History chart
    ├── Calibration
    │   └── Interactive hand alignment (per-hand)
    └── Settings
        ├── Vibration strength slider
        ├── Step goal
        ├── Timezone
        └── About / Debug
```

### What We Reuse from CLI Work

| Component | Reuse | Notes |
|-----------|-------|-------|
| Vendored GB request classes | 100% | Same Java files, real Android classes available |
| `FossilQAdapter` | ~90% | Same request queue logic, different transport |
| `BleTransport` interface | 100% | Clean boundary |
| Protocol knowledge (FINDINGS.md) | 100% | File formats, timing, quirks |
| Shim classes | Partially | Only need GB-specific shims, not Android API shims |
| `BluezTransport` | 0% | Linux-only, replaced by `AndroidTransport` |
| picocli CLI | 0% | Replaced by Compose UI |

### Non-Goals

- Support for Hybrid HR, Collider, Gen 6 (different protocol, encrypted)
- GadgetBridge compatibility/integration
- iOS version (future possibility with KMP)
- Cloud sync / accounts

---

## Phase 4: Future Ideas

- **KMP (Kotlin Multiplatform)** — share protocol code between Android + iOS
- **Desktop GUI** — JavaFX or Compose Desktop wrapper around the protocol library
- **Home Assistant integration** — button press → HA action
- **Tasker/Automate plugin** — Android automation integration
- **Web BLE** — browser-based watch configurator (WebBluetooth API)
- **Watch community features** — share hand animations, alarm presets
