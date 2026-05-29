# Fossil Q Hybrid Android App — Work Breakdown (Isolated, Testable Parts)

This document breaks **[ANDROID-PLAN.md](ANDROID-PLAN.md)** into discrete work packages (WPs). Each WP is designed to be:

- **Self-contained**: handed to a fresh context with only this section + named reference files.
- **Independently testable**: has explicit verification that does NOT require later WPs.
- **Decoupled**: depends only on earlier WPs through narrow, named interfaces (mostly `BleTransport`, the Room DAOs, and the protocol "compile bytes" helpers).

> **Testing philosophy**: We separate **pure logic** (byte compilation, parsing, time/alarm math, calendar→alarm mapping) from **platform glue** (BLE, services, Compose). Pure logic is unit-tested on the JVM with NO device and NO emulator. Platform glue is tested with instrumented tests or manual on-device checklists. This lets ~70% of the work be verified without hardware.

---

## Dependency Graph

```
 WP0  Build split (:protocol / :cli / :android)
   │
   ├─► WP1  Protocol "headless façade" (FossilController + FakeBleTransport)   [JVM unit-testable]
   │     │
   │     ├─► WP2  AndroidBleTransport (real BluetoothGatt)        [on-device]
   │     │     │
   │     │     └─► WP3  Foreground service + CompanionDeviceManager reconnect  [on-device]
   │     │
   │     ├─► WP4  Room DB + multi-watch + clone/transfer          [JVM/Robolectric unit-testable]
   │     │
   │     ├─► WP5  Alarm domain logic (16/16 split, byte compile)  [JVM unit-testable]
   │     ├─► WP6  Notification rule compile + play file           [JVM unit-testable]
   │     ├─► WP7  Button mapping compile (multi-entry, dial modes) [JVM unit-testable]
   │     ├─► WP8  Activity/Sleep/Calorie parsing surface          [JVM unit-testable]
   │     └─► WP9  Calendar→alarm mapping (pure)                    [JVM unit-testable]
   │
   ├─► WP10 Permissions/setup carousel (Compose)                  [on-device / UI test]
   ├─► WP11 Notification listener glue → WP6                      [on-device]
   ├─► WP12 Button event glue (gesture → media/find-phone) → WP7  [on-device]
   ├─► WP13 Calendar provider read + ContentObserver → WP9        [Robolectric/on-device]
   ├─► WP14 WorkManager periodic + sync-on-connect orchestration  [instrumented]
   ├─► WP15 In-app log viewer (level filter, export)              [JVM + UI]
   └─► WP16 Compose UI screens (dashboard, alarms, notif, buttons, calibration, sleep, settings)  [on-device]
```

**Key idea:** WP5–WP9 are *pure byte/logic packages* that only depend on the vendored protocol classes and produce `byte[]` / domain objects. They can each be built and unit-tested in complete isolation against the documented wire formats in `FINDINGS.md`, with zero Android and zero hardware. They are the safest, highest-leverage work to parallelize.

---

> **Companion document:** This breakdown is meant to be read **alongside [ANDROID-PLAN.md](ANDROID-PLAN.md)**. The plan describes *what* the app does and *why* (architecture, schema, feature behavior); this document describes *how to slice the work* into isolated, testable pieces. Each WP names the exact `ANDROID-PLAN.md` sections it implements. Hand a fresh context **both files** + the WP's named reference files.

> **Vendored code is never modified.** Every WP that "extracts pure helpers" only touches OUR code (`src/main/java/qhybrid/linux/**`, e.g. `FossilQAdapter`). The vendored `gadgetbridge/app/src/main/java/**` tree stays byte-for-byte identical and `sync.sh`-managed. The helper functions being extracted (`buildNotificationFilterData`, `buildOfficialNotificationFile`, button-compile logic) already live in our `FossilQAdapter`, NOT in the vendored tree.

> **What we reuse from Gadgetbridge (important):** We reuse GB's **protocol layer** (the Fossil Q request classes — real, vendored, untouched) at ~100%. We do **NOT** reuse GB's real Android BLE engine (`BtLEQueue`, `DeviceCommunicationService`, `ControlCenterv2`), because it was never vendored into this repo — it was deliberately replaced by tiny stubs (`TransactionBuilder`, `AbstractBTLEDeviceSupport`, `GBDevice`, `BluetoothGattCharacteristic`). `AndroidBleTransport` is therefore NEW code implementing the same `BleTransport` seam the CLI uses ("Option A" — thin transport). See ANDROID-PLAN.md §1.

### Architecture decision: thin transport (Option A) vs. vendor GB's real BLE stack (Option B)

**Option A (recommended; the repo is already shaped for it):** Reuse GB's protocol classes (vendored, untouched); write a NEW `AndroidBleTransport` implementing the `BleTransport` seam; write our own lightweight foreground service + `CompanionDeviceManager` reconnect + Compose permission carousel. Keeps the app lightweight and Compose-native (the whole reason for not just using GB).

**Option B:** Vendor GB's REAL Android BLE engine (`BtLEQueue`, `DeviceCommunicationService`, `ControlCenterv2`, permission activities, coordinators). Maximizes reuse of GB's battle-tested service/permission code but pulls in dozens of deeply-coupled classes (`GBApplication`, greenDAO, prefs) that this repo deliberately stubbed out — large, messy, and fights the "lightweight" goal. Would also bloat the `BleTransport` seam the CLI depends on.

**This breakdown assumes Option A throughout.** If Option B is chosen, WP2 / WP3 / WP10 change substantially and a separate "re-vendor GB BLE stack" WP must be inserted before WP2. (Hybrid option: stay on Option A but vendor only GB's permission *wizard screens* in isolation, without its BLE engine.)

### Reference trees (read-only — NEVER compiled into our build)

Two full reference trees exist locally and should be consulted by the relevant WPs. **Neither is added to any `srcDirs` or dependency** — they are read-only references we copy *logic and byte formats* from, re-expressed in our own (Compose / `BleTransport`-seam) code:

- **`tmp/Gadgetbridge/`** — the FULL upstream Gadgetbridge source (not the partial slice in `gadgetbridge/`). Useful patterns:
  - `service/btle/BtLEQueue.java` (~1098 lines) — GATT callback handling, write-with/without-response selection, MTU negotiation, CCCD descriptor writes, reconnect/backoff. **Borrow the logic behind `BleTransport`; do NOT adopt the class** (it hard-depends on `GBApplication`, `GBExceptionHandler`, `R`, `DeviceSupport`, coordinators, greenDAO).
  - `service/GBCompanionDeviceService.java`, `util/BondingUtil.java` — `CompanionDeviceManager` association + bonding state machine.
  - `util/PermissionsUtils.java`, `activities/welcome/WelcomeFragmentPermissions.java`, `activities/PermissionsActivity.java` — permission list/check logic + rationale flow (GB uses XML Views/RecyclerView; we re-express in Compose).
  - `deviceevents/GBDeviceEventFindPhone.java` — CDM-based find-phone.
- **`tmp/FossilOfficialApp-deobf/`** — the deobfuscated official Fossil app. The ground-truth **byte-format reference**: cross-check our reverse-engineered encoders (notification filter, button config, alarm modes, play file) against the official app's actual encoders. Upgrades WP5/WP6/WP7 golden bytes from "hardware-observed" to "hardware-observed AND confirmed against the source encoder."

**Per-WP reference mapping:** WP2 → `BtLEQueue`; WP3 → `GBCompanionDeviceService` + `BondingUtil`; WP10 → `PermissionsUtils` + welcome flow; WP12 → `GBDeviceEventFindPhone`; WP5/WP6/WP7/WP9 → `FossilOfficialApp-deobf` encoders for byte-format confirmation.

---

## WP0 — Gradle Multi-Module Split

**Goal:** `:protocol` (pure Java) + `:cli` (Java app) + `:android` (Android app) in one tree. CLI stays 100% working.

**Scope:**
- `settings.gradle`: include `:protocol`, `:cli`, `:android`.
- Move vendored `gadgetbridge/` + our shared classes (`FossilQAdapter`, `BleTransport`, `ButtonConfigBuilder`, `ActivityParser`, `ButtonGestureDetector`, the `android/`/`androidx/`/`nodomain/` shims) into `:protocol`.
- `:cli` depends on `:protocol`; keeps `Main.java`, `DbusTransport`, `BluezTransport`, JLine, JSON config.
- `:android` skeleton only (empty `Activity`, `srcDirs` reuse pattern from the plan), depends on `:protocol`.
- Keep `sync.sh` working.

**Reference files:** `build.gradle`, `settings.gradle`, `sync.sh`, `ROADMAP.md` (Phase 2), `ANDROID-PLAN.md` §1.

**Isolated test (no hardware):**
- `./gradlew :protocol:compileJava` succeeds.
- `./gradlew :cli:shadowJar` produces the same `fossil-q` jar; `fossil-q --help` lists all existing commands.
- `./gradlew :android:assembleDebug` builds the empty skeleton APK.
- Smoke: run one CLI command that doesn't need hardware (e.g. an `--help`/dry-run path) to confirm classpath wiring.

**Done when:** all three modules compile; CLI jar behaves identically to before the split.

---

## WP0.5 — Walking Skeleton (Installable "Hello-Watch" APK)

**Goal:** Prove the *entire toolchain* end-to-end with the smallest possible runnable APK, BEFORE building any features. This de-risks the build/permission/BLE setup early so we know the foundation is sound.

**Scope (deliberately minimal):**
- `:android` produces an installable debug APK with a single Compose screen.
- One "Request permissions" button (Bluetooth/Scan only — minimal subset).
- One "Connect" button that, given the known MAC, uses `:protocol` (`FossilController` + `AndroidBleTransport` stub-or-minimal) to attempt a BLE connect and print device info (battery, firmware) to the screen + logcat.
- It is OK if `AndroidBleTransport` is only a first-pass here; WP2 hardens it. The point is to prove `:protocol` links and runs inside an APK on a real phone.

**Depends on:** WP0. (Pairs with an early/partial WP2 and a minimal slice of WP10.)

**Reference files:** `ANDROID-PLAN.md` §1 (build), §2 (permissions), §4.A; `BleTransport.java`.

**Isolated test (on-device):**
- `./gradlew :android:installDebug` installs on a phone.
- App launches, requests BT permission, and on "Connect" shows the watch's battery % + firmware string (or a clean error if out of range).

**Done when:** a real phone runs the APK and reads at least one piece of live data from the watch. This is the "the setup works" milestone you asked for.

---

## WP1 — Protocol Headless Façade + FakeBleTransport

**Goal:** A clean, platform-agnostic entry point into the protocol that both Android and tests drive, plus an in-memory fake transport for unit testing the request/response queue without any BLE.

**Scope:**
- Define/confirm a small façade (e.g. `FossilController`) over `FossilQAdapter` exposing: `connect/init`, `syncTime`, `setAlarms(byte[])`, `uploadNotificationFilter(byte[])`, `playNotification(...)`, `setButtons(byte[])`, `setInactivityNudge(...)`, `setVibrationStrength(...)`, `requestActivity()`, callbacks for `onInitialized`, `onActivityData`, `onEventJson`.
- Implement `FakeBleTransport implements BleTransport`: records writes, lets a test inject notification frames (e.g. canned auth `03 07 01`, file-put acceptance type 3, CRC confirm type 8, close type 4).
- No new protocol behavior — just expose + make testable what `FossilQAdapter` already does.

**Reference files:** `src/main/java/qhybrid/linux/FossilQAdapter.java`, `BleTransport.java`, `FINDINGS.md` (#8 file transfer flow, #14 auth, #5 MTU).

**Isolated test (no hardware):**
- Drive `FakeBleTransport` through a full init sequence and assert the exact write byte sequences (animation → MTU handled locally → device info → config → buttons → INITIALIZED).
- Auth handshake: inject `03 07 00` then `03 06 00 01`, assert `02 06 ...` confirm was written.
- File-put: assert chunking to MTU, CRC confirmation handling, and `onFilePut(true)`.

**Done when:** the protocol can be exercised end-to-end on the JVM with deterministic, asserted byte traffic.

---

## WP2 — AndroidBleTransport (real BluetoothGatt)

**Goal:** Implement `BleTransport` on top of Android's native `BluetoothGatt`.

**Scope:**
- GATT connect/discover/services, characteristic write (command vs request per `FINDINGS.md` #2), `enableNotifications` (CCCD), MTU request, notification callback fan-out.
- Bonding via `device.createBond()` after auth (plan §4.A).
- Map Android threading to the adapter's callback model.

**Depends on:** WP0, WP1 (interface + façade).

**Reference files:** `BleTransport.java`, `FINDINGS.md` #2 (write types), #7 (pairing/agent), #15 (connection timing), `ANDROID-PLAN.md` §4.A.

**Isolated test (on-device, minimal):**
- Standalone test harness Activity: connect to the known MAC, run init via `FossilController`, log to logcat: battery %, firmware, "INITIALIZED".
- Verify the auth button-press flow once (fresh bond) and once (already bonded → `03 07 01`).
- No UI, no DB — just transport + façade.

**Done when:** the test harness reliably connects, authenticates, and reports device info on real hardware.

---

## WP3 — Foreground Service + Companion Reconnect

**Goal:** Keep the link alive in the background and auto-reconnect when the watch reappears.

**Scope:**
- Foreground service hosting the `FossilController`/transport; persistent notification.
- `CompanionDeviceManager.associate()` flow; handle `onDeviceAppeared` → connect.
- Battery-optimization exemption prompt; boot-completed restart.
- Exposes a connection-state `StateFlow` and a "sync now" entry point for other WPs.

**Depends on:** WP2.

**Reference files:** `ANDROID-PLAN.md` §2 (Companion), §4.A, §5.

**Isolated test (on-device checklist):**
- Associate watch; walk out of range → service shows "disconnected"; walk back → auto-reconnects without opening the app.
- Reboot phone → service restarts and reconnects.
- Confirm no continuous scanning (battery): verify reconnect is event-driven.

**Done when:** disconnect/reconnect and reboot survival work hands-free.

---

## WP4 — Room Database + Multi-Watch + Clone/Transfer

**Goal:** Persistence layer with the 4 entities and the settings-transfer operation.

**Scope:**
- `WatchEntity`, `WatchAlarmEntity`, `NotificationRuleEntity`, `ButtonMappingEntity` + DAOs (plan §3).
- Active-watch selection; per-watch queries.
- `transferSettings(fromMac, toMac)` bulk copy with `REPLACE`.

**Depends on:** WP0 (module exists). No BLE.

**Reference files:** `ANDROID-PLAN.md` §3.

**Isolated test (JVM/Robolectric, in-memory DB):**
- CRUD round-trips for each entity.
- Foreign-key cascade delete (deleting a watch removes its alarms/rules/buttons).
- `transferSettings`: seed Watch A with N alarms/rules/buttons → transfer → assert Watch B has identical rows under its MAC, Watch A untouched.

**Done when:** all DAO tests pass headlessly; transfer is verified by assertions.

---

## WP5 — Alarm Domain Logic (16/16 split + byte compile)

**Goal:** Convert a list of alarm domain objects into the watch's alarm file bytes, honoring the 16/16 slot split and all three wire modes.

**Scope:**
- Compile standard alarms (slots 0–15): repeating `[0x80|days][minute|0x80][hour]`, one-shot `[0xFF][minute][hour]`.
- Reserve slots 16–31 for calendar (accept an injected list; compile non-repeating weekday `[0x80|days][minute][hour]`).
- Corrected weekday bitmask (bit3=Wed, bit4=Thu — `FINDINGS.md` #12). Enforce 32-slot max.

**Depends on:** WP1 (uses the existing alarm request/`FilePutRequest` path).

**Reference files:** `FINDINGS.md` #12 (alarm formats, bitmask, max count), `AlarmsSetRequest.java`, `Alarm.java`, `ANDROID-PLAN.md` §4.B.

**Isolated test (no hardware):**
- Golden-byte tests: each mode produces exactly the documented bytes (reuse the hardware-verified table in `FINDINGS.md` #12, e.g. non-repeat Thu 11:14 → `90 0E 0B`).
- Weekday mask: Mon–Fri shortcut → correct mask using corrected bits.
- 33 alarms → rejected before upload.

**Done when:** byte output matches the FINDINGS table for every case.

---

## WP6 — Notification Rule Compile + Play File

**Goal:** Pure functions to (a) compile all per-app rules into the multi-entry filter file, and (b) build the play file for a given package.

**Scope:**
- `compileFilter(rules) → byte[]` (32 bytes/entry) using package CRC = `crc("qhybrid.linux.<name>")`.
- `buildPlayFile(packageName) → byte[]` (official 12-length-buffer format).
- Vibe pattern 0–9, hour/min degrees 0–359.

**Depends on:** WP1 (reuses `buildNotificationFilterData` / `buildOfficialNotificationFile` logic from `FossilQAdapter`, extracted to a pure helper).

**Reference files:** `FossilQAdapter.java` (`buildNotificationFilterData`, `buildOfficialNotificationFile`, `computeNullTerminatedCrc`), `NotificationConfig.java`, `FINDINGS.md` #14, #17, `ANDROID-PLAN.md` §4.C.

**Isolated test (no hardware):**
- Filter golden bytes: compare against the official-app 7-entry capture in `FINDINGS.md` #17 (CRCs + positions + vibe).
- CRC determinism per package name.
- Multi-entry concatenation length = N×32.

**Done when:** compiled filter reproduces the documented CRC/position/vibe layout.

---

## WP7 — Button Mapping Compile (multi-entry + dial modes)

**Goal:** Pure compilation of button configuration files, including multi-entry mode toggles and the supported dial modes.

**Scope:**
- Single- and multi-entry button files via `ButtonConfigBuilder` (top/middle/bottom).
- Dial modes: Alert, Timezone 2, Alarm, Date, 24-Hour (the only physical modes); music = phone-side action, NOT a dial mode.
- Model-aware availability hook (which modes a given watch face supports).

**Depends on:** WP1.

**Reference files:** `ButtonConfigBuilder.java`, `ConfigPayload.java`, `ConfigFileBuilder.java`, `FINDINGS.md` #19/#21b/#22, `ANDROID-PLAN.md` §4.E.

**Isolated test (no hardware):**
- Golden bytes vs captured payloads embedded in `ButtonConfigBuilder` (ALARM_SEQUENCED, DATE_TOGGLE, 24H, GOAL_TRACKING).
- Multi-entry CRC32 trailer correctness.
- Map a 3-mode toggle → assert structure (counts, headers, payload order).

**Done when:** generated files match captured byte patterns.

---

## WP8 — Activity / Sleep / Calorie Parsing Surface

**Goal:** Expose `ActivityParser` outputs (steps, calories, sleep periods) as clean domain objects for the UI/DB. The parser already exists — this WP is the API + tests around it.

**Scope:**
- Thin domain mapping: `ActivityData` → per-day step/calorie totals; `detectSleep(...)` → sleep periods + quality.
- No new parsing math; verify against fixtures.

**Depends on:** WP1 (parser lives in `:protocol`).

**Reference files:** `ActivityParser.java`, `activity.bin` / `activity-test.bin` (fixtures), `ANDROID-PLAN.md` §4.H.

**Isolated test (no hardware):**
- Parse `activity-test.bin`; assert total steps, calorie sum, and that sleep detection returns the expected period count/duration.
- Edge: empty file, single segment, multi-segment ordering.

**Done when:** fixtures parse to stable, asserted values.

---

## WP9 — Calendar → Alarm Mapping (pure)

**Goal:** Pure function turning a list of `(title, startEpochMillis)` events into ≤16 non-repeating weekday alarm domain objects for slots 16–31.

**Scope:**
- Filter to next 7 days, sort, take nearest 16.
- Map each event's local weekday/hour/minute → alarm domain object (non-repeating weekday).
- De-dup/clamp; deterministic ordering.

**Depends on:** WP5 (produces alarm domain objects WP5 compiles). Does **not** depend on Android calendar APIs.

**Reference files:** `FINDINGS.md` #12 (non-repeat weekday format), `ANDROID-PLAN.md` §4.D / §4.B.

**Isolated test (no hardware):**
- Given fixed "now" and a fixed event list, assert exactly which events become alarms and their slot order.
- Event >7 days out is excluded; 20 events → only 16 nearest kept.
- A Friday 10:15 event → alarm domain object with Friday bit + 10:15 (then verify via WP5 it compiles to the right bytes).

**Done when:** mapping is deterministic and verified against fixtures.

---

## WP10 — Permissions / Setup Carousel (Compose)

**Goal:** First-run wizard that requests each permission with rationale (plan §2).

**Scope:** Welcome → Bluetooth/Scan → Notification Access → Calendar → Battery Optimization. Detect already-granted; deep-link to system settings where needed.

**Depends on:** WP0. (Can be built before WP2–WP9; it only gates them at runtime.)

**Reference files:** `ANDROID-PLAN.md` §2.

**Isolated test (on-device / UI test):**
- Fresh install → each step requests the right permission; re-entry skips granted ones; "open settings" intents resolve.

**Done when:** all permissions reachable and state correctly reflected.

---

## WP11 — Notification Listener Glue → WP6

**Goal:** `NotificationListenerService` that maps an intercepted notification's package → rule → triggers play file via the service.

**Depends on:** WP3 (service/connection), WP6 (compile/play), WP4 (rule lookup).

**Reference files:** `ANDROID-PLAN.md` §4.C.

**Isolated test (on-device):**
- With a rule for a test app, post a notification → watch vibrates with the configured pattern + hands move to configured degrees.
- No rule → no watch action. Verify dedupe/rate-limit.

**Done when:** real notifications drive the configured watch behavior.

---

## WP12 — Button Event Glue (gesture → media / find-phone)

**Goal:** Consume button events from `3dda0006`, run `ButtonGestureDetector`, dispatch to MediaController / preferred-app launcher / find-phone ring.

**Depends on:** WP3, WP7 (to have configured a `FORWARD_TO_PHONE` button), WP1 (`ButtonGestureDetector`).

**Reference files:** `FossilQAdapter` `ButtonGestureDetector`, `ANDROID-PLAN.md` §4.E.2/§4.E.3/§4.J.

**Isolated test (on-device):**
- Single/double/long press → correct media action; with music dead + preferred app set (e.g. Bandcamp) → launches and plays.
- Double-press find-phone → loud ring bypassing DND; stop in app.

**Done when:** gestures map to actions reliably across single/double/long.

---

## WP13 — Calendar Provider Read + ContentObserver → WP9

**Goal:** Read system calendar events and observe changes; feed WP9.

**Depends on:** WP9 (mapping), WP4 (cache), WP10 (permission).

**Reference files:** `ANDROID-PLAN.md` §4.D.1, §4.D.3.

**Isolated test (Robolectric / on-device):**
- Insert a test event via provider → `ContentObserver` fires → cache updates with mapped alarms.
- Cloud-sync simulation: external write → observer fires.

**Done when:** provider reads + change detection produce the WP9 alarm set.

---

## WP14 — Sync Orchestration (WorkManager + Sync-on-Connect)

**Goal:** Tie triggers together: periodic safety job, ContentObserver push, and sync-on-connect; compile (WP5/WP6/WP7) and upload via service (WP3).

**Depends on:** WP3, WP4, WP5, WP6, WP7, WP9, WP13.

**Reference files:** `ANDROID-PLAN.md` §4.D.2/§4.D.5.

**Isolated test (instrumented):**
- Disconnected: change calendar → on reconnect, slots 16–31 upload.
- Periodic worker runs and reconciles; queued uploads flush on connect.

**Done when:** all three triggers converge to a single correct upload pipeline.

---

## WP15 — In-App Log Viewer (level filter + export)

**Goal:** Capture INFO/DEBUG logs into a ring buffer; Compose console with level filter + export.

**Scope:**
- An SLF4J appender (or bridge) feeding an in-memory ring buffer with level + timestamp.
- The protocol already logs via SLF4J — route those through too.
- INFO = friendly operational lines (plan §4.I examples); DEBUG = raw hex/GATT/DB.

**Depends on:** WP0; richer once WP1–WP3 emit logs.

**Reference files:** `ANDROID-PLAN.md` §4.I.

**Isolated test (JVM + UI):**
- Unit: push N log records of mixed levels → filter returns expected subset; export format stable.
- UI: filter toggles update the list; copy/export produces a text blob.

**Done when:** log routing + filter + export verified (buffer unit-tested headlessly).

---

## WP16 — Compose UI Screens

**Goal:** The user-facing screens, each backed by a ViewModel reading WP4 + WP8 and writing via the service.

**Sub-parts (each independently buildable with fake data/preview):**
- **16a Dashboard**: connection status, battery, steps/goal, active-watch selector, Find Watch.
- **16b Alarms**: list/add/edit/delete (slots 0–15), day picker, weekday/weekend shortcuts.
- **16c Notifications**: app list + search, per-app vibe + hand degrees editor.
- **16d Buttons**: per-button mapping + dial-mode toggles (model-aware).
- **16e Calibration**: interactive hand alignment (WP F flow).
- **16f Sleep/Activity**: charts from WP8.
- **16g Settings**: nudge, vibration strength, timezone, preferred music app, settings transfer (WP4), log viewer (WP15).

**Depends on:** WP4, WP8, plus service (WP3) for live actions. Each screen testable with Compose previews + fakes before wiring real data.

**Reference files:** `ANDROID-PLAN.md` §3–§5, ROADMAP "UI Screens".

**Isolated test (UI / preview):**
- Compose preview for each screen with fake state; basic UI tests for interactions (add alarm, toggle rule, drag calibration hand → emits correct intent to a fake controller).

**Done when:** each screen renders + emits correct domain actions against a fake controller/DAO.

---

## Suggested Execution Waves (for parallelization)

| Wave | Parts | Can run in parallel? | Hardware? |
|------|-------|----------------------|-----------|
| 0 | WP0 | no (foundation) | no |
| 1 | WP1, WP4 | yes | no |
| 2 | **WP5, WP6, WP7, WP8, WP9** (all pure logic) | **yes — 5 independent fresh contexts** | no |
| 3 | WP2, WP10, WP15 | yes | WP2 yes; others no |
| 4 | WP3, WP13 | yes | WP3 yes; WP13 partial |
| 5 | WP11, WP12, WP14 | yes | yes |
| 6 | WP16a–g | yes (per screen) | partial |

**Highest-leverage parallelization:** Wave 2 (WP5–WP9) — five pure-logic packages, each verifiable against `FINDINGS.md` golden bytes and `activity.bin` fixtures with zero hardware and zero Android emulator. These de-risk the entire protocol surface before any UI or BLE glue exists.

---

## Cross-Cutting Test Assets (build once, reuse everywhere)

- `FakeBleTransport` (WP1) — drives any protocol path headlessly.
- Golden-byte fixtures extracted from `FINDINGS.md` (#12 alarms, #17 notification filter) and `ButtonConfigBuilder` captures.
- `activity.bin` / `activity-test.bin` — activity/sleep parsing fixtures (already in repo).
- A `FakeFossilController` for UI WPs so screens can be tested without BLE/DB.
