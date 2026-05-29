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

**Key idea:** WP5–WP9 are *pure byte/logic packages* that only depend on the owned protocol classes (`qhybrid.protocol.*`) and produce `byte[]` / domain objects. They can each be built and unit-tested in complete isolation against the documented wire formats in `FINDINGS.md`, with zero Android and zero hardware. They are the safest, highest-leverage work to parallelize.

> **⚠️ POST-RE-OWN STATUS (read this first).** The PROTOCOL-REOWN-WP is **DONE**
> (see `PROTOCOL-REOWN-WP.md`, `PROTOCOL-PROVENANCE.md`). Several assumptions below
> were written *before* the re-own and are now **OBSOLETE** — ignore them:
> - There is **no vendored GadgetBridge tree, no shims, no `sync.sh`, no
>   `androidStrippedJar`/`androidApi`** anymore. The protocol is **owned**, pure,
>   platform-neutral Java in package **`qhybrid.protocol.*`** (deps: slf4j only).
> - The request classes are **ours to edit** — the old "vendored code is never
>   modified" rule no longer applies.
> - The canonical entry point is the **`qhybrid.protocol.FossilController`** façade
>   with settings **passed in** via `qhybrid.protocol.model.{SyncSettings,
>   NotificationFilterEntry}`. Do NOT `new FossilQAdapter(...)` + disk-load.
> - Class locations now: `qhybrid.protocol.FossilQAdapter`,
>   `qhybrid.protocol.BleTransport`, `qhybrid.protocol.ButtonConfigBuilder`,
>   `qhybrid.protocol.ActivityParser`; `ButtonGestureDetector` is nested in the
>   adapter. Disk config (`DeviceConfig`/`GlobalConfig`/`NotificationConfig`) moved
>   to **`:cli`**. `:cli` and `:android` both use plain `project(':protocol')`.
> - Byte builders for WP5–WP9 are owned and partly static:
>   `FossilQAdapter.buildNotificationFilterFile(...)` (WP6),
>   `AlarmsSetRequest.createFileFromAlarms(...)` + `Alarm` (WP5),
>   `ButtonConfigBuilder`/`buttonconfig.ConfigFileBuilder` (WP7),
>   `ActivityParser` (WP8). Unit-test against the golden tests in
>   `protocol/src/test` (`FakeBleTransport` + golden bytes already exist).
> - Runtime gotcha: the Fossil Q is single-link; CLI `le-connection-abort-by-local`
>   just means the phone is still connected — disconnect it first (transport issue,
>   not protocol).

---

> **Companion document:** This breakdown is meant to be read **alongside [ANDROID-PLAN.md](ANDROID-PLAN.md)**. The plan describes *what* the app does and *why* (architecture, schema, feature behavior); this document describes *how to slice the work* into isolated, testable pieces. Each WP names the exact `ANDROID-PLAN.md` sections it implements. Hand a fresh context **both files** + the WP's named reference files.

> **~~Vendored code is never modified.~~ (OBSOLETE after the re-own.)** The protocol
> is now **owned** under `qhybrid.protocol.*` and is ours to edit. The former
> vendored `gadgetbridge/` tree, the `android/`/`androidx/`/`nodomain/` shims, and
> `sync.sh` have all been **deleted**. Provenance is recorded in
> `PROTOCOL-PROVENANCE.md` + `NOTICE`. The helper builders (notification filter,
> button config, etc.) live in `qhybrid.protocol.*` (some as static methods).

> **What we derive from Gadgetbridge:** the **protocol layer** (Fossil Q request
> classes, file transfer, encoders, auth) was derived from GB and re-owned as clean
> platform-neutral Java. We do **NOT** use GB's Android BLE engine (`BtLEQueue`,
> `DeviceCommunicationService`, `ControlCenterv2`). `AndroidBleTransport` is NEW
> code implementing the same `qhybrid.protocol.BleTransport` seam the CLI uses
> ("Option A" — thin transport). See ANDROID-PLAN.md §1.

### Architecture decision: thin transport (Option A) vs. vendor GB's real BLE stack (Option B)

**Option A (chosen and now fully realised):** Use our owned `qhybrid.protocol.*` classes; write a NEW `AndroidBleTransport` implementing the `BleTransport` seam; write our own lightweight foreground service + `CompanionDeviceManager` reconnect + Compose permission carousel. Keeps the app lightweight and Compose-native. (The protocol re-own removed the last reason this was ever in tension with the build.)

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

**Status:** ✅ DONE. Multi-module split (`:protocol` / `:cli` / `:android`) complete; CLI behaves identically (run via `./fossil-q`). *(Historical note: the original split used a vendored `protocol/gadgetbridge/` tree + `sync.sh` + shim-stripped jar. The PROTOCOL-REOWN-WP later **removed all of that** — protocol is now owned `qhybrid.protocol.*`. `DeviceConfig`/`GlobalConfig`/`NotificationConfig` now live in `:cli`.)*

**Goal:** `:protocol` (pure Java) + `:cli` (Java app) + `:android` (Android app) in one tree. CLI stays 100% working.

**Scope:**
- `settings.gradle`: include `:protocol`, `:cli`, `:android`.
- ~~Move vendored `gadgetbridge/` + shims into `:protocol`.~~ *(Superseded: the protocol was later re-owned as `qhybrid.protocol.*`; the vendored tree + shims were deleted.)*
- `:cli` depends on `:protocol`; keeps `Main.java`, `DbusTransport`, `BluezTransport`, JLine, JSON config.
- `:android` skeleton only (empty `Activity`, `srcDirs` reuse pattern from the plan), depends on `:protocol`.
- ~~Keep `sync.sh` working.~~ *(`sync.sh` has since been removed.)*

**Reference files:** `build.gradle`, `settings.gradle`, `ROADMAP.md` (Phase 2), `ANDROID-PLAN.md` §1.

**Isolated test (no hardware):**
- `./gradlew :protocol:compileJava` succeeds.
- `./gradlew :cli:shadowJar` produces the same `fossil-q` jar; `fossil-q --help` lists all existing commands.
- `./gradlew :android:assembleDebug` builds the empty skeleton APK.
- Smoke: run one CLI command that doesn't need hardware (e.g. an `--help`/dry-run path) to confirm classpath wiring.

**Done when:** all three modules compile; CLI jar behaves identically to before the split.

---

## WP0.5 — Walking Skeleton (Installable "Hello-Watch" APK)

**Status:** ✅ DONE — FULLY VERIFIED ON HARDWARE. On a real phone the APK: connects → downloads supported file versions (full file-transfer over 3dda0003/0004) → runs the complete Fossil auth handshake (status `03 07 00` → request → watch VIBRATES → user presses TOP button → `03 06 00 01` ACCEPTED) → Android creates the BLE bond → reads live device info (Model `HW.0.0`, Firmware `HW0.0.2.9r.v3`, Battery `22%`, Protocol `Fossil 2.x`). This exercises far more of the protocol than the WP0.5 minimum.

Implemented: real `AndroidBleTransport` (Kotlin) bridging async `BluetoothGatt` to the blocking `BleTransport` contract; a Compose screen (editable MAC field prefilled `D9:20:71:11:74:2A`, permission button, Connect button) driving `FossilQAdapter` off the main thread; slf4j→logcat logging (`slf4j-android`). *(Historical: WP0.5 used a SHIM-STRIPPED `:protocol` artifact (`androidApi`/`androidStrippedJar`) so the real Android SDK resolved. The PROTOCOL-REOWN-WP **removed** that machinery — `:android` now uses a plain `project(':protocol')` because the protocol has no Android stubs at all.)*

**Critical bugs found & fixed during on-device bring-up (the shim-strip exposed real Android-API mismatches):**
1. `new BluetoothGattCharacteristic(uuid)` (1-arg) existed on our JVM stub but NOT on real Android → `NoSuchMethodError`/crash. Fixed: use the real 3-arg constructor `(UUID, properties, permissions)` in `FossilQAdapter` + `QHybridSupport`, and add that constructor to the stub so both targets compile.
2. `AndroidBleTransport` wrote `ENABLE_NOTIFICATION_VALUE` to ALL characteristics, but Fossil uses **INDICATE** on `3dda0003`/`3dda0005` (write+indicate) and NOTIFY on the rest (FINDINGS #2). With the wrong CCCD value the watch never sent the file header (3dda0003) or the auth response (3dda0005) → NPE in `FileGetRawRequest` + a ~60s auth stall with no vibrate. Fixed: pick `ENABLE_INDICATION_VALUE` vs `ENABLE_NOTIFICATION_VALUE` from the characteristic's properties.
3. `AndroidBleTransport.connect()` must enable notifications on the six `3dda000x` characteristics before init (the protocol assumes the transport did this, like `BluezTransport`).

NOTE: this skeleton calls `FossilQAdapter` directly — the `FossilController` façade remains WP1's deliverable. The on-screen "if the watch vibrates" message is a hedge because the adapter doesn't yet surface live auth state to the UI; a proper auth-state callback belongs to WP1/the re-own WP.

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

**Status:** ✅ DONE (delivered by the PROTOCOL-REOWN-WP). `qhybrid.protocol.FossilController`
is the façade; `qhybrid.protocol.FakeBleTransport` (+ `FileTransferResponder` /
`FileGetResponder`) is the test fake. 22 JVM tests in `protocol/src/test` lock the
wire bytes (golden alarms/config/notif-filter/activity + full file-put + auth
handshake). Settings are passed in via `qhybrid.protocol.model.{SyncSettings,
NotificationFilterEntry}` (no disk loading in :protocol). Callbacks: `onActivityData`,
`onEventJson`, `onAuthRequired`, `onConfigSynced`.

**Goal:** A clean, platform-agnostic entry point into the protocol that both Android and tests drive, plus an in-memory fake transport for unit testing the request/response queue without any BLE.

**Scope:**
- Define/confirm a small façade (e.g. `FossilController`) over `FossilQAdapter` exposing: `connect/init`, `syncTime`, `setAlarms(byte[])`, `uploadNotificationFilter(byte[])`, `playNotification(...)`, `setButtons(byte[])`, `setInactivityNudge(...)`, `setVibrationStrength(...)`, `requestActivity()`, callbacks for `onInitialized`, `onActivityData`, `onEventJson`.
- Implement `FakeBleTransport implements BleTransport`: records writes, lets a test inject notification frames (e.g. canned auth `03 07 01`, file-put acceptance type 3, CRC confirm type 8, close type 4).
- No new protocol behavior — just expose + make testable what `FossilQAdapter` already does.

**Reference files:** `protocol/src/main/java/qhybrid/protocol/FossilQAdapter.java`, `qhybrid/protocol/FossilController.java`, `qhybrid/protocol/BleTransport.java`, `FINDINGS.md` (#8 file transfer flow, #14 auth, #5 MTU). **STATUS: DONE** — `FossilController` façade + `FakeBleTransport` + golden tests delivered by the PROTOCOL-REOWN-WP; settings are passed in via `SyncSettings`.

**Isolated test (no hardware):**
- Drive `FakeBleTransport` through a full init sequence and assert the exact write byte sequences (animation → MTU handled locally → device info → config → buttons → INITIALIZED).
- Auth handshake: inject `03 07 00` then `03 06 00 01`, assert `02 06 ...` confirm was written.
- File-put: assert chunking to MTU, CRC confirmation handling, and `onFilePut(true)`.

**Done when:** the protocol can be exercised end-to-end on the JVM with deterministic, asserted byte traffic.

---

## WP2 — AndroidBleTransport (real BluetoothGatt)

**Status:** ✅ DONE — HARDWARE-VERIFIED. `android/.../AndroidBleTransport.kt` is now a
robust `BleTransport` over `BluetoothGatt`, and `MainActivity` drives the protocol through
the `FossilController` façade (not the raw `FossilQAdapter`). Verified on a real phone:
fresh-bond `connect → watch vibrates → TOP button → 03 06 00 01 ACCEPTED → createBond →
INITIALIZED` in ~4.5s (all real bond-negotiation time, no dead wait); already-bonded
fast-path `03 07 01 → INITIALIZED` with no button; clean Disconnect; reads battery 22% /
firmware HW0.0.2.9r.v3 / model HW.0.0. `:protocol:test` (22) stays green; `./fossil-q --help`
unchanged.

**Hardening delivered over the WP0.5 first pass (all behind the `BleTransport` seam — NO
protocol/wire-byte changes):**
1. **Per-characteristic write-type** (FINDINGS #2): `WRITE_TYPE_NO_RESPONSE` (command) for the
   write+notify / write-without-response chars (3dda0002/0004/0006/0007); `WRITE_TYPE_DEFAULT`
   (request) only for the INDICATE chars (3dda0003/0005). Mirrors `BluezTransport.getWriteType()`.
   *(WP0.5 wrongly used request for everything.)*
2. **Connect → `STATE_CONNECTED` latch → discover with retry/backoff** (3 attempts, 250ms·n),
   verifying the six Fossil chars resolved; clean timeout → disconnect+false.
3. **`refreshGattCache()`** via reflective `BluetoothGatt.refresh()` — **option (b): fallback
   only**, between failed discovery attempts, to clear a stale service cache.
4. **Proactive `requestMtu(512)`** in `connect()` wired to `mtuCallback` (FINDINGS #5 — the
   adapter never issues an ATT-layer MTU request itself); negotiated to 185 on this watch.
5. **`pair()` polls `device.bondState`** (the authoritative system state) instead of the
   `ACTION_BOND_STATE_CHANGED` broadcast. *The broadcast-receiver approach missed the BONDED
   transition and cost a full 30s timeout AFTER the bond had already succeeded — the polling
   version returns ~within 200ms of the real bond completing.* Short-circuits if already bonded;
   exits early on return-to-NONE after BONDING (genuine failure).
6. **Stale-callback guards**: each read/write op records its expected characteristic UUID; late
   callbacks from a prior op are ignored so they can't complete the next op's latch.
   Submission-failure (write/read/descriptor/MTU returning false) is handled, not hung.
7. **Clean teardown**: best-effort disable notifications → `disconnect()` + `close()` → release
   all in-flight latches → single connection-state callback (unexpected drops fire it too, so
   out-of-range/return is visible; intentional disconnect doesn't double-fire).
8. **`MainActivity` via `FossilController`**: auth prompt shown only when the watch actively
   requests auth; live `Link: Connected/Disconnected` from the connection callback; Disconnect
   button; GATT teardown on `onDestroy`.

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

**Done when:** the test harness reliably connects, authenticates, and reports device info on real hardware. ✅ Met — see Status above.

---

## WP3 — Foreground Service + Companion Reconnect

**Status:** ✅ DONE — HARDWARE-VERIFIED. A foreground `WatchConnectionService`
(`START_STICKY`, `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`, persistent notification) now
OWNS the `FossilController` + `AndroidBleTransport` for the session (ownership moved out of
`MainActivity`), driving all blocking transport work on a single `ble-worker` thread.
Reconnect is event-driven via `CompanionDeviceManager` — NO continuous scanning. Verified
on a real phone (API 31+):
- **Associate** (CDM chooser) → persist MAC → arm `startObservingDevicePresence` →
  auto-connect → fresh bond (TOP button → `03 06 00 01` → `createBond`) → INITIALIZED.
- **Close app** → service keeps the link; persistent notification stays (Connected · NN%).
- **Out of range** (battery pulled) → GATT timeout (status 8) → DISCONNECTED, presence
  re-armed; **back in range** → system CDM `onDeviceAppeared` wakes the service →
  bonded fast-path `03 07 01` → INITIALIZED in ~1s, **without opening the app**.
- **Reboot** → `BootReceiver` (BOOT_COMPLETED allow-list) re-arms presence + starts the
  service → CDM wake → reconnected hands-free (confirmed via `dumpsys`: FGS foreground,
  LE-encrypted bonded link, `WatchPresenceService` bound by system uid 1000).
- No `startScan` anywhere in the logs; no ANRs; clean teardown on every drop.

`:android:assembleDebug` + `:android:lintDebug` clean; `:protocol:test` (22) stays green;
`./fossil-q --help` unchanged; `AndroidBleTransport.kt` / `FossilController` / `BleTransport`
/ wire bytes untouched.

**New files** (all `qhybrid.android`): `WatchState.kt` (process-wide `StateFlow<WatchStatus>`),
`WatchConnectionService.kt` (FGS, owns controller/transport, static `connectNow/syncNow/
disconnect/onDeviceAppeared/stop`), `WatchPresenceService.kt` (`CompanionDeviceService`,
API 31+ presence callbacks), `CompanionManager.kt` (associate / observe / MAC persistence in
SharedPreferences — isolated so WP4 can swap to Room / battery-opt prompt), `BootReceiver.kt`,
`ReconnectFallback.kt` (minimal one-shot MAC-filtered scan for API 26–30; no-op on 31+).
`MainActivity.kt` rewritten as a thin client (observe StateFlow + Associate/Connect/Sync/
Disconnect/Battery-exempt buttons; reuses WP2 auth-prompt-only-when-needed via AUTH_REQUIRED).
The `runOnConnectSync` hook is a deliberate no-op placeholder for WP5/6/9; WP14 calls `syncNow`.

**Manifest deltas:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
`RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` (31+), `POST_NOTIFICATIONS` (33+),
`ACCESS_COARSE_LOCATION` (≤30 lint fix); the service, the CDM presence service
(`BIND_COMPANION_DEVICE_SERVICE` + `android.companion.CompanionDeviceService` intent-filter),
and the boot receiver; `companion_device_setup` feature.

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

**Status:** ✅ DONE & VERIFIED (JVM, no hardware). Pure helper added to `:protocol`:
`qhybrid.protocol.requests.fossil.alarm.AlarmSlot` (platform-neutral domain object
mirroring WP4's `WatchAlarmEntity`) + `AlarmCompiler` (16/16 split + 3 wire modes +
32-slot guard). 14 new golden-byte tests (`Wp5AlarmCompilerTest`) green; `:protocol:test`
now 36 total (22 prior + 14), 0 failures. `./fossil-q --help` unchanged;
`:android:assembleDebug` succeeds. No protocol wire bytes / `Alarm.java` /
`AlarmsSetRequest.java` / `BleTransport` / `AndroidBleTransport.kt` / WP3 service touched.

- **Placement:** pure helper in `:protocol` (reuses the golden-locked `Alarm.getData()`
  for standard modes; the undocumented calendar non-repeat-weekday `[0x80|days][min][hour]`
  is built directly in `AlarmCompiler` since `Alarm` can't emit it).
- **daysMask ↔ wire bitmask:** **1:1 identity, NO translation.** `WatchAlarmEntity.daysMask`
  (bit0=Sun..bit6=Sat) is exactly the hardware-corrected wire `days` byte (bit3=Wed,
  bit4=Thu per FINDINGS #12). The mask is passed straight through. (The `WEEKDAY_*`
  constants on `Alarm.java` are mislabeled for Wed/Thu but are unused by the byte path.)
- **Doc note found:** the `--days 30 = Mon-Fri` CLI example (FINDINGS line 357) is
  mislabeled — 0x1E (30) is Mon-Thu; true Mon-Fri is 0x3E (62), matching the `0xBE`
  hardware capture at FINDINGS line 1038. Both values are locked by tests.

> **CLI FOLLOW-UP (not part of this WP — do later):** wire `AlarmSlot`/`AlarmCompiler`
> (and the eventual WP9 calendar→alarm mapping) into the `:cli` `alarm` command so the
> CLI can drive the 16/16 split + calendar slots too, reusing this same pure helper.

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

**STATUS: DONE & VERIFIED.** Pure helper `qhybrid.protocol.requests.fossil.notification.NotificationCompiler`
(`compileFilter`, `compileEntry`, `compileEntryWithCrc`, `buildPlayFile`, `computeNullTerminatedCrc`).
The existing `FossilQAdapter` filter + play builders now **delegate** to it (single source of truth;
live wire bytes unchanged). The impure play path injects `System.currentTimeMillis()` (timestamp +
messageId) into the pure `buildPlayFile`. Façade `FossilController.buildPlayFile(...)` added next to
`buildNotificationFilterFile(...)`. Golden tests: `protocol/src/test/.../golden/Wp6NotificationCompilerTest.java`
(19 tests) — known-package CRCs (`com.whatsapp`=0x40C7ED7C, `com.google.android.calendar`=0xBA3DC156),
per-entry layout, the full FINDINGS #17 7-entry/224-byte capture via the CRC-injected builder, N×32
length, input-order, and the deterministic play file with time-dependent fields asserted at the right
offsets. `:protocol:test` 68 green. **Variable-length / SENDER_NAME (0x02) form (FINDINGS #21d) is NOT
implemented — possible future work.**

> **TODO (CLI wiring, later):** the `:cli` `notify` / `notify-config` commands should be routed
> through this same pure helper (via `FossilController.buildNotificationFilterFile` /
> `FossilController.buildPlayFile`) so there is exactly one notification-byte implementation.

---

## WP7 — Button Mapping Compile (multi-entry + dial modes)

**Status:** ✅ DONE & VERIFIED (JVM, no hardware). Pure helper added to `:protocol`:
`qhybrid.protocol.requests.fossil.button.ButtonCompiler` (slf4j-free; `java.nio` +
`java.util.zip.CRC32` only). It is now the single source of truth for both real wire
formats and both legacy paths delegate to it 1:1:
- `ButtonConfigBuilder.build(...)` → `ButtonCompiler.compileMultiEntry(...)`
  (per-entry `0x00`, non-dedup payloads, customization section, CRC32 LE trailer).
- `buttonconfig.ConfigFileBuilder.build(appendChecksum)` →
  `ButtonCompiler.compileSingleEntryPerButton(ConfigPayload[], boolean)`
  (dedup payloads, customization count `0x00`, optional CRC32 trailer).
Façade entry points added: `FossilController.compileButtons(top,mid,bot)` and
`compileButtonsSingleEntry(payloads, appendChecksum)` (mirrors WP6 `buildPlayFile`).
Model-aware availability hook (pure lookup, no byte mutation):
`ButtonCompiler.DialMode` {ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR} (music is
NOT a dial mode), `DialModel` {THREE_POSITION, FIVE_POSITION}, `availableModes(model)`,
`isModeAvailable(model, mode)`. 15 new golden tests
(`Wp7ButtonCompilerTest`) green; `:protocol:test` now 83 total (68 prior + 15), 0
failures. `./fossil-q --help` unchanged; `:android:assembleDebug` succeeds. No wire
bytes / `BleTransport` / `AndroidBleTransport.kt` / WP3 service /
`AlarmCompiler` / `CalendarAlarmMapper` / `NotificationCompiler` output touched.
Golden vectors locked: embedded captured payloads (ALARM_SEQUENCED, DATE_TOGGLE,
24H/24H_SEQ, GOAL_TRACKING), a full single-entry assignment file, a 3-mode toggle
(structure + order + CRC32 trailer recomputed independently), and old-path == compiler
equality. CLI `buttons` command already routes through these builders (so it produces
compiler bytes); a TODO note marks routing it directly through `ButtonCompiler` later
(mirrors WP6).

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

**Status:** ✅ DONE & VERIFIED (JVM, no hardware). Pure surface added to `:protocol`:
`qhybrid.protocol.activity.ActivitySummarizer` (slf4j-free; `java.time` only). It adds
**ZERO parsing math** — it only wraps/aggregates the EXISTING `ActivityParser` output into
clean platform-neutral domain objects for the UI/DB, and the sleep path **delegates 1:1** to
`ActivityParser.detectSleep(...)`:
- **(a) per-day:** `DayActivity` {date, steps, calories, activeMinutes, recordCount} +
  `summarizeByDay(ActivityData, ZoneId)` (zone injected — no system clock) + `totalSteps`/
  `totalCalories` convenience.
- **(b) sleep:** `SleepSession` {start/endEpochSeconds, durationMinutes, restlessMinutes,
  avgVariability, quality} + `detectSleepSessions(ActivityData)` (+ thresholds overload) which
  calls `ActivityParser.detectSleep` and maps each `SleepPeriod` 1:1. `ActivityParser` is
  untouched and remains the single source of truth for byte decoding + sleep detection.

Fçade entry points added: `FossilController.summarizeActivityByDay(data, zone)` and
`FossilController.detectSleepSessions(data)` (mirrors WP6/WP7 static pure helpers).

14 new fixture/golden tests (`protocol/src/test/.../golden/Wp8ActivitySummarizerTest.java`)
green; `:protocol:test` now **94 total (83 prior + 11)**, 0 failures. `./fossil-q --help`
unchanged; `:android:assembleDebug` succeeds. No `ActivityParser` math/output / `BleTransport`
/ `AndroidBleTransport.kt` / WP3 service / WP5–WP9 compiler output touched.

**Golden values locked (from the repo fixtures via the existing parser):**
- `activity-test.bin`: 1 segment, 18 records → **totalSteps=6, calorieSum=0, sleepSessions=0**
  (18 records < the 30-minute sleep minimum → empty list).
- `activity.bin`: 2 segments, 857 records → **totalSteps=2, calorieSum=0, sleepSessions=1**,
  that session **duration=855m, restless=6, quality="good"**.
- Per-day equivalence: Σ day steps == `ActivityData.totalSteps()`; Σ day calories == raw
  record calorie sum; day buckets sorted ascending; Σ recordCount == `records.size()`.
- Sleep equivalence: `detectSleepSessions` returns exactly what `ActivityParser.detectSleep`
  returns (count + every field, incl. avgVariability and quality).
- Edge cases: empty `ActivityData` (no records) → both surfaces empty; single segment
  (`activity-test.bin`); multi-segment ordering (`activity.bin`).

> **CLI FOLLOW-UP (not part of this WP — do later):** `:cli` `Main.java` (the `activity`
> command, ~lines 2366–2385) already surfaces activity/sleep/calorie via direct
> `ActivityParser` calls + `formatSummary`/`formatSleepSummary`/`formatNdjson*`. Route its
> per-day/sleep output through this same `ActivitySummarizer` / `FossilController` surface so
> there is exactly one parsing-surface implementation (mirrors the WP6/WP7 CLI follow-ups).

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

**Status:** ✅ DONE & VERIFIED (JVM, no hardware). Pure helper added to `:protocol`:
`qhybrid.protocol.requests.fossil.alarm.CalendarAlarmMapper` (alongside WP5's
`AlarmSlot`/`AlarmCompiler`). 13 new tests (`Wp9CalendarAlarmMapperTest`) green;
`:protocol:test` now 49 total (36 prior + 13), 0 failures. `./fossil-q --help`
unchanged; `:android:assembleDebug` succeeds. No protocol wire bytes / `AlarmSlot` /
`AlarmCompiler` output / `BleTransport` / `AndroidBleTransport.kt` / WP3 service touched.

- **Placement:** pure helper in `:protocol` (only `java.time` + slf4j-free). WP9
  PRODUCES `AlarmSlot` objects that WP5's `AlarmCompiler` compiles 1:1. No Android
  calendar APIs (that is WP13, which will feed `(title, DTSTART)` pairs in).
- **Entry point (time injected, no system clock):**
  `CalendarAlarmMapper.mapEventsToAlarmSlots(List<CalendarEvent>, long nowEpochMillis, ZoneId zone)`.
  `CalendarEvent` = `(String title, long startEpochMillis)`. Local weekday/hour/minute
  computed via `Instant.atZone(zone)`, so DST/offset is handled by `java.time` and
  tests pass a fixed `now` + `ZoneId` for determinism.
- **Pipeline:** filter to `[now, now + 7 days)` (half-open) → de-dup on wire identity
  `(daysMask, hour, minute)` (earliest-start wins, tie-break title then original index)
  → sort by start → take nearest ≤16 → assign slots 16, 17, … → emit non-repeating,
  enabled `AlarmSlot`s.
- **Weekday → daysMask:** 1:1 wire bits (bit0=Sun..bit6=Sat; bit3=Wed, bit4=Thu per
  FINDINGS #12), NO translation — same convention as WP5. Friday → bit5 (`0x20`), which
  `AlarmCompiler.encode` turns into `[0xA0][min][hour]` (acceptance: Fri 10:15 → `A0 0F 0A`).

> **CLI FOLLOW-UP (not part of this WP — do later):** wire `CalendarAlarmMapper`
> into the `:cli` `alarm` command so the CLI can drive calendar→alarm slots 16–31
> too, reusing this same pure helper (pair with the WP5 CLI follow-up).

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

> **WP14 STATUS:** ✅ DONE & VERIFIED (provable orchestration core JVM/Robolectric-tested; the
> service wiring builds + lint-passes; the BLE upload effect flagged on-device-pending). Follows
> the proven WP16 two-layer pattern exactly: a pure, injectable decision/compile core + a thin
> Android layer (the WP3 service + WorkManager) behind it. **WP14 flips ALL the remaining WP16
> `*_WIRED` / `SETTINGS_WIRED` flags true.** Implemented in 5 committed sub-parts (`wp14: …`).
>
> **(1) Sync orchestrator core (`qhybrid.android.sync`, pure + Robolectric/in-memory Room):**
> - `SyncOrchestrator` (pure object) — given a `SyncInput` (the active watch's WP4 rows: alarms
>   0–15, calendar 16–31, notification rules, button mappings + app/watch settings) it computes
>   the compiled payloads via the **golden-tested WP5/6/7 compilers + WP16g vocabulary** and calls
>   an injectable `Uploader` seam in a **defined, guarded order**: alarms → notification filter →
>   buttons → vibration → nudge → second timezone. **Invents NO wire bytes** — reuses
>   `AlarmCompiler` (WP5), `NotificationFilterEntry` (WP6), `ButtonConfigBuilder.build` (= WP7
>   `ButtonCompiler`) exclusively. Row→protocol mapping is pure (daysMask passed straight through;
>   `CUSTOM_TOGGLE` → dial-mode SEQUENCED/TOGGLE entries; other modes → `ConfigPayload` actions;
>   unknown ids dropped).
> - **Guards / tolerance:** no-active-watch → empty `SyncResult`, never throws; empty section →
>   skipped (never push an empty file); all-disabled alarms → skipped (compiles to 0 bytes);
>   **32-slot guard** delegated to `AlarmCompiler` (throws on 33+ → recorded as a `SyncError`,
>   the remaining sections still run); null settings not applied. `SyncResult` records
>   performed/skipped/errors for logging.
> - `SyncInput`/`SyncSettings` (immutable snapshot) + `SyncDataLoader` (the suspend DB-reading
>   bridge: 16/16 slot split, per-watch vibration from the WP4 row, app-pref nudge/second-timezone
>   from the WP16g `SettingsPrefs`; nudge only included when enabled). Keeping the loader separate
>   leaves the orchestrator a pure function of `SyncInput`.
> - **Tests:** `SyncOrchestratorTest` (13, Robolectric) drive a `FakeUploader` and assert the
>   compiled payloads **cross-checked against the same golden compilers** + the upload order + the
>   guards (no-watch / empty / all-disabled / unknown-action / 32-slot / null-settings /
>   performed-vs-skipped); `SyncDataLoaderTest` (6, in-memory Room) asserts the DB→`SyncInput`
>   assembly.
>
> **(2) Service upload actions + `runOnConnectSync` (`qhybrid.android.WatchConnectionService`):**
> - A private `ServiceUploader` implements `Uploader` against the WP3-owned `FossilController`,
>   reusing the golden-tested façade upload + settings methods (`setAlarms` /
>   `uploadNotificationFilter` / `setButtons` + `setVibrationStrength` / `setInactivityNudge` /
>   `setSecondTimezone`). The alarm upload waits (bounded 30s) on the adapter's `CompletableFuture`
>   to sequence the pass; the rest fire-and-forward like init.
> - `runOnConnectSync(controller)` (the WP3 placeholder) now loads the active watch's config via
>   `SyncDataLoader` and runs `SyncOrchestrator` against `ServiceUploader`, logging the result. It
>   runs on the **ble-worker** thread (the caller is already on it), so the suspend DB read uses
>   `runBlocking` off the main thread. `submitSync()` (the existing `syncNow` entry point) already
>   routes through `runOnConnectSync`, so **`syncNow` now drives the orchestrator** too — and so
>   does every WP16 "Save to watch" seam (they poke `syncNow`). No regression to the ble-gatt
>   HandlerThread / transport / wire bytes.
>
> **(3) Flipped `*_WIRED` flags (sub-parts 3 & 4) — all true, each with a production-flag test:**
> - `ServiceAlarmSync.ALARM_UPLOAD_WIRED = true` (WP5 `AlarmCompiler` → alarm file).
> - `ServiceNotificationSync.FILTER_UPLOAD_WIRED = true` (WP6 `NotificationCompiler`, 32 B/entry).
> - `ServiceButtonSync.BUTTON_UPLOAD_WIRED = true` (WP7 `ButtonCompiler` → SETTINGS_BUTTONS 0x0600).
> - `ServiceSettingsSync.SETTINGS_WIRED = true` (vibration/nudge/second-timezone via
>   `ConfigurationPutRequest` items 0x0A/0x09/0x11 — **persist-then-sync**: the VM persists the
>   value to the WP4 row / `SettingsPrefs`, then `syncNow` reloads + applies it live). The
>   `*Sync.saveToWatch()` impls are unchanged in behaviour (persist via the ViewModel intents,
>   then `syncNow`); only the flag flips. The fake-backed ViewModel tests keep using `wired=false`
>   fakes; a new `production…IsWired()` test in each ViewModel test asserts the production constant.
>
> **(4) Periodic safety job + sync-on-connect glue (sub-part 5):**
> - **DEP CHOICE (stated):** WorkManager was NOT on the classpath → added first-party
>   `androidx.work:work-runtime-ktx:2.9.1` (named in ANDROID-PLAN §5/§6) rather than hand-rolling
>   AlarmManager.
> - `SyncScheduleDecider` (**pure, unit-tested**) — the conservative "safety net" decision: **NO
>   continuous scanning, NO forced connect** (CDM owns reconnection). Rules: no associated watch →
>   skip; associated but link down → skip (the CDM reconnect + sync-on-connect path owns it);
>   associated + link up → `syncNow` to reconcile drift. Clamps the period to WorkManager's 15-min
>   floor.
> - `SyncSafetyWorker` (`CoroutineWorker`) applies the decider to `WatchState` + the CDM
>   association and pokes `syncNow` only when the link is up (always `Result.success` — a skip is
>   normal). `SyncScheduler` enqueues UNIQUE periodic work (KEEP, default 6 h) with NOT_REQUIRED
>   network + battery-not-low constraints (low-priority reconciler; defers under Doze). Armed
>   (idempotent) on `BootReceiver` re-arm + on service `ACTION_CONNECT`; cancelled on full stop.
>   Sync-on-connect itself is sub-part 2.
> - **Tests:** `SyncScheduleDeciderTest` (5).
>
> **Reused vs newly wired:** REUSED unchanged — every protocol compiler/façade (WP5/6/7 +
> settings), the WP3 service entry points + ble-worker threading + ble-gatt HandlerThread, the
> WP4 repository surface, the WP16g `SettingsPrefs`/`SettingsVocabulary` + the WP16d button
> vocabulary, the WP16 `*Sync` seams' `saveToWatch()` behaviour. NEWLY WIRED — the
> `qhybrid.android.sync` package (orchestrator core + loader + uploader seam + scheduler/worker),
> `runOnConnectSync` filled in, the 4 `*_WIRED` flags, WorkManager scheduling.
>
> **Did NOT touch** protocol wire bytes / `FossilController`/`FossilQAdapter` / `BleTransport` /
> `AndroidBleTransport.kt` (ble-gatt HandlerThread intact) / `ActivityParser`/`ActivitySummarizer`
> output / existing WP4 repository semantics (additive only — none needed) / WP15 Debug Menu
> gating. **NO Room entity/field added** (WP14 doesn't own one; calendar slots 16–31 stay WP9/WP13).
>
> **Acceptance met:** `:protocol:test` **108 green** (unchanged — all orchestration logic is in
> `:android` since it maps Room rows + drives the Android service; the compilers it reuses are
> already golden-tested in `:protocol`); `:android:testDebugUnitTest` **176 green** (139 at WP16g
> baseline → +13 vocab already present = 148, then +28 new across sub-parts 1/3/4/5: 19 sync core,
> 3 alarm/notif/button production-flag, 1 settings production-flag, 5 scheduler), 0 failures;
> `:android:assembleDebug` + `:android:lintDebug` succeed; `./fossil-q --help` unchanged.
>
> **On-device verification pending:** the actual BLE writes (alarm/filter/button file transfer +
> the live `ConfigurationPutRequest` settings) and their hardware effect; sync-on-connect firing
> end-to-end on a real watch; the WorkManager periodic schedule + Doze deferral. The headless half
> — the compile order / guards / DB→payload mapping / settings + the periodic decision logic — is
> unit-tested. Model-agnostic scope is on-device-pending: a watch that lacks a setting/button/dial
> mode ignores it on-device.
>
> **Remaining non-UI milestones (unchanged by WP14):** the **activity-file fetch WP** (BLE read →
> WP8 parse → `ServiceActivitySource`, flips `ACTIVITY_WIRED` + feeds `DashboardUiState.steps`);
> **WP13** calendar provider read + ContentObserver → WP9 calendar→alarm mapping for slots 16–31
> (the orchestrator already accepts `calendarAlarms` and compiles slots 16–31, so WP13 just needs
> to populate those rows); **WP9** calendar slots 16–31 are wired through the compiler but unfed
> until WP13; the `NotificationListenerService` interception + `MediaSessionManager` music control;
> the calibration live commands (WP F, `CALIBRATION_WIRED`); and the protocol/CLI follow-ups
> (routing the CLI `alarm`/`notify`/`button` commands through the shared WP5/6/7 helpers).

**Goal:** Tie triggers together: periodic safety job, ContentObserver push, and sync-on-connect; compile (WP5/WP6/WP7) and upload via service (WP3).

**Depends on:** WP3, WP4, WP5, WP6, WP7, WP9, WP13.

**Reference files:** `ANDROID-PLAN.md` §4.D.2/§4.D.5.

**Isolated test (instrumented):**
- Disconnected: change calendar → on reconnect, slots 16–31 upload.
- Periodic worker runs and reconciles; queued uploads flush on connect.

**Done when:** all three triggers converge to a single correct upload pipeline.

---

## WP15 — In-App Log Viewer + Debug Menu (level filter + export)

**Status:** ✅ DONE & VERIFIED (provable core JVM-tested; UI builds; on-device behaviour
flagged pending). Follows the proven two-layer pattern: a pure JVM-tested core + an
Android UI layer that compiles and lint-passes.

**(1) Provable core (pure Java in `:protocol`, zero Android/hardware):**
- `qhybrid.protocol.log.LogRecord` — immutable `(timestampMillis, Level, tag, message)`.
  `Level` {TRACE,DEBUG,INFO,WARN,ERROR} carries a stable numeric `severity()` (not enum
  ordinal) so the level filter survives any reordering. INFO+ = friendly operational
  lines; DEBUG/TRACE = raw hex/GATT/DB.
- `qhybrid.protocol.log.LogRingBuffer` — bounded, thread-safe FIFO ring (DEFAULT_CAPACITY
  2000, oldest evicted at capacity). `filter(minLevel)` returns the "≥ level" subset
  (null = all); `export([minLevel])` produces a stable copy/share blob
  (`"<UTC time> <LEVEL> <tag>: <message>\n"`, level padded to 5 for alignment);
  `formatLine` renders one line; `addChangeListener` (plain `Runnable`, no coroutines)
  lets the UI refresh; process-wide `shared()` singleton (mirrors WP3 `WatchState`).
- **Placement justified:** the pure buffer lives in `:protocol` (not `:android/src/test`)
  because (a) `:protocol` is the SLF4J producer both `:cli` and `:android` share, (b) it
  has the mature JUnit5 golden-test harness the WP5–WP9 cores use, and (c) it needs zero
  Android — keeping it out of the Robolectric/JUnit4 `:android` test set. The Android-only
  pieces (SLF4J bridge, Compose, Debug Menu) live in `:android`.
- **Tests:** `protocol/src/test/.../golden/Wp15LogRingBufferTest.java` (14 tests) — mixed-level
  filtering returns the expected subset, severity ordering, ring eviction at/over capacity,
  capacity guard, clear, a byte-stable export blob (fixed timestamps), per-level export,
  formatLine, listener fire/remove/throw-safety, null-field normalization, singleton.
  `:protocol:test` now **108 total (94 prior + 14)**, 0 failures.

**(2) SLF4J bridge (`:android`, tees — does NOT replace logcat):**
- `qhybrid.android.log.BufferTeeServiceProvider` is the app's single SLF4J binding
  (`META-INF/services/org.slf4j.spi.SLF4JServiceProvider`). It instantiates slf4j-android's
  own `ServiceProvider` internally and wraps every returned logger in `LogBufferLogger`,
  which forwards each call UNCHANGED to slf4j-android (→ logcat) **and** appends to
  `LogRingBuffer.shared()`. So logcat routing is preserved (the ring sits alongside it).
  Ring capture is intentionally NOT gated by the delegate's per-level enablement — the
  in-app console always shows DEBUG (raw hex/GATT/DB) even when logcat suppresses it;
  arguments are substituted via SLF4J's own `MessageFormatter`. `BufferOnlyLogger`
  (extends `LegacyAbstractLogger`) is a graceful fallback if slf4j-android can't load.
- The APK ends up with BOTH service entries (ours + slf4j-android's), so
  `FossilQApplication` (new `android:name`) sets `System.setProperty("slf4j.provider",
  "…BufferTeeServiceProvider")` in a static initializer (before any logger is created) to
  pin OUR provider deterministically (SLF4J 2.0.9 honours `slf4j.provider`).
- **Tests:** `android/src/test/.../log/Slf4jBridgeTest.kt` (3, Robolectric) drive the bridge
  directly (app + `:protocol` loggers): records land in the buffer at the right level,
  `{}` args are formatted, throwable stacks are captured, and the level filter returns the
  expected subset. (Driven directly rather than via the global binding because AGP's
  unit-test classpath doesn't reliably select our `META-INF/services` entry; the packaged
  APK + `slf4j.provider` pin make it live on-device.)

**(3) Compose log console + Debug Menu (`:android`, build + lint green):**
- `qhybrid.android.log.LogConsole` — terminal-style `LazyColumn` (monospace, per-level
  colour, auto-scroll to newest), level-filter chips (ALL / DEBUG / INFO / WARN / ERROR
  via `LogRingBuffer.filter`), **Copy** (clipboard), **Export** (writes
  `cache/logs/fossilq-log-*.txt`, shares via `FileProvider` `ACTION_SEND`), and **Clear**.
  `rememberLogRecords` bridges the buffer's `Runnable` change-listener into Compose state.
- `qhybrid.android.debug.DebugMenuScreen` + `DebugTools` — the Debug Menu, **release-gated**
  by `DebugMenu.isEnabled() == BuildConfig.DEBUG`. Reached from a **top-right gear**
  (`Icons.Filled.Build`) in `MainActivity`'s new `Scaffold` TopAppBar, shown ONLY in debug
  builds. Hosts the log console plus:
    - **DB tools (WP4):** Dump DB, Seed sample, List watches, Clone from→to
      (`WatchRepository.transferSettings`, auto-registers the target), Activate, Wipe
      (CASCADE) — all via `WatchRepository`, each logging progress through SLF4J (so it
      lands in the console).
    - **BLE/protocol tools (WP3):** Connect/Sync/Disconnect via the existing
      `WatchConnectionService` static entry points; shows Link state + **negotiated MTU**
      (newly surfaced read-only on `WatchState.WatchStatus.mtu`, published from the
      transport's existing `getMtu()` on INITIALIZED — NO wire change).
    - **Misc:** Build/version (`BuildConfig`), permission state, CDM associations.
- **Tests:** `android/src/test/.../debug/DebugToolsTest.kt` (5, Robolectric + in-memory
  Room via the WP4 `DbTestBase`): seed/list, clone/transfer, wipe-CASCADE, set-active, and
  DB-dump all invoke the right `WatchRepository` calls and (when the tee binding is active)
  log to the buffer. `:android:testDebugUnitTest` **17 total (9 prior DB + 3 bridge + 5
  debug-tools)**, 0 failures.

**Manifest/build deltas:** `buildConfig true` (for `BuildConfig.DEBUG` gate);
`material-icons-core` (gear icon); `FileProvider` + `res/xml/file_paths.xml` (export-to-file
share); `android:name=".FossilQApplication"`; `src/main/resources/META-INF/services/
org.slf4j.spi.SLF4JServiceProvider`.

**Acceptance met:** `:protocol:test` 108 green (94 + 14); `:android:testDebugUnitTest` 17
green; `:android:assembleDebug` + `:android:lintDebug` succeed; `./fossil-q --help`
unchanged (zero `:cli`/protocol-wire changes). NO change to ActivityParser /
AlarmCompiler / NotificationCompiler / ButtonCompiler / CalendarAlarmMapper /
ActivitySummarizer / BleTransport / AndroidBleTransport.kt / WP3 service wire behaviour
(the only service edit is read-only MTU surfacing on INITIALIZED).

**On-device verification pending:** the Compose console rendering/scroll, the copy/export
share-sheet, the top-right gear → Debug Menu navigation, and the live effect of the BLE
Debug actions can only be confirmed on a device (the data path — buffer→filter→export and
the DB actions — is unit-tested headlessly).

**Future follow-ups:** richer Debug Menu actions (replay a canned byte sequence; hand-
animation diagnostics §4.J); wire WP14 sync-now once it lands so "Sync now" drives a real
upload; optional persistent log file / log level config UI; route `:cli` logs through the
same `LogRingBuffer` for a CLI `logs` command.

---

### Original WP15 brief (for reference)

**Goal:** Capture INFO/DEBUG logs into a ring buffer; Compose console with level filter + export. Also host the **Debug Menu** — a developer-tools surface reached from a top-right overflow/gear in the app, gated so it never ships enabled in release (e.g. `BuildConfig.DEBUG`).

**Scope:**
- An SLF4J appender (or bridge) feeding an in-memory ring buffer with level + timestamp.
- The protocol already logs via SLF4J — route those through too.
- INFO = friendly operational lines (plan §4.I examples); DEBUG = raw hex/GATT/DB.
- **Debug Menu** (plan §4.I.4): top-right entry → a screen of dev/testing actions, with the log console living here too. First actions:
  - **DB tools (WP4):** dump Room DB to the console, seed sample data, **clone/transfer between two MACs** (`WatchRepository.transferSettings`), wipe a watch (exercises CASCADE), list/switch active watch.
  - **BLE/protocol tools (WP3):** connect/disconnect/sync-now, show link state + negotiated MTU.
  - **Misc:** build/version info, permission state, CDM associations.
  - *Note:* this replaces the throwaway WP4 in-`MainActivity` debug panel (dump-to-logcat tag `FossilQ-DB`) that verified multi-watch + clone on two real watches and was then removed.

**Depends on:** WP0; richer once WP1–WP4 emit logs / expose the repository.

**Reference files:** `ANDROID-PLAN.md` §4.I (incl. §4.I.4 Debug Menu).

**Isolated test (JVM + UI):**
- Unit: push N log records of mixed levels → filter returns expected subset; export format stable.
- UI: filter toggles update the list; copy/export produces a text blob; Debug Menu actions invoke the right repo/service calls against a fake.

**Done when:** log routing + filter + export verified (buffer unit-tested headlessly); Debug Menu reachable and release-gated.

---

## WP16 — Compose UI Screens

> **WP16a STATUS:** ✅ DONE & VERIFIED (provable core JVM/Robolectric-tested; UI builds +
> lint-passes; live rendering + Find Watch choreography flagged on-device-pending). Follows
> the proven WP15 two-layer pattern: a unit-tested state holder + an Android UI layer that
> compiles and lint-passes.
>
> **(1) Provable core (`qhybrid.android.dashboard`, Robolectric + in-memory Room):**
> - `DashboardViewModel` + `DashboardUiState` — `combine`s WP3 `WatchState.status`
>   (live link/battery/firmware/model/mtu/message) with the WP4 `WatchRepository`
>   active-watch row (`observeActiveWatch`) + registry (`observeWatches`) into one
>   immutable `DashboardUiState` via `stateIn(WhileSubscribed)`. Battery/firmware/model
>   prefer the LIVE link value and fall back to the active watch's last-known row.
>   Derived UI helpers: `isConnected`, `isBusy` (CONNECTING/INITIALIZING/AUTH_REQUIRED),
>   `selectedMac`. **Steps are STUBBED** (`steps = null`, `stepGoal` from the WP4 row) —
>   live activity data is WP16f.
> - Intents: `setActiveWatch` (→ `WatchRepository.setActiveWatch`, WP4),
>   `connect`/`disconnect`/`sync` (→ the injectable `WatchActions` seam), and `findWatch`
>   (phone→watch). Connect with no arg resolves the active watch's mac from the current
>   `uiState` (no extra DB roundtrip); the service still falls back to the CDM-associated mac.
> - `WatchActions` interface + `ServiceWatchActions` production impl forwarding 1:1 to the
>   existing WP3 `WatchConnectionService` static entry points (`connectNow`/`disconnect`/
>   `syncNow`). **No new BLE/protocol behavior:** `findWatch()` is a documented no-op stub
>   (real hand-choreography / repeating-vibe trigger per ANDROID-PLAN §4.J is on-device-pending).
> - **Tests:** `android/src/test/.../dashboard/DashboardViewModelTest.kt` (7, Robolectric +
>   `DbTestBase` in-memory Room). State combination (live link + active watch → expected
>   `DashboardUiState`, incl. battery/firmware/model + `mtu` + watches list + null steps);
>   battery/firmware fall-back to the active watch row when the link has none; switching the
>   active watch updates both the DB and the combined state; `connect()` uses the active
>   watch's mac when unspecified and honours an explicit mac; disconnect/sync/find hit the
>   fake; busy/connected state across CONNECTING→AUTH_REQUIRED→INITIALIZED. (The VM is given
>   a REAL `CoroutineScope` and the combined `StateFlow` is polled with a bounded `awaitState`
>   because Room's reactive Flows run on Room's own executor — virtual-time would not observe
>   their re-emissions.) `:android:testDebugUnitTest` now **24 total (17 prior + 7)**, 0 failures.
>
> **(2) UI layer (`qhybrid.android.dashboard.DashboardScreen`, build + lint green):**
> - `DashboardScreen()` hosts the production VM via `viewModel(factory = …)` and renders a
>   stateless `DashboardContent(state, intents…)` (pure function of `DashboardUiState` + intent
>   lambdas, so it is preview-/UI-testable with fake state and no VM/Room/BLE). Cards:
>   **Connection** (link label + status message + battery/model/firmware/MTU), **Steps**
>   (clearly-marked WP16f placeholder: `— / goal` with a 0% bar + "not wired yet" note),
>   **Active watch** selector (`ExposedDropdownMenuBox` from `observeWatches`, emits
>   `setActiveWatch`), and **action buttons** Connect/Disconnect/Sync + **Find Watch**
>   (enable-gated by `isConnected`/`isBusy`; a label flags Find Watch's on-device-pending
>   choreography).
> - Made the app's **home content** in `MainActivity` (replacing the thin WP3 `HomeScreen`).
>   The WP3 setup flow (permissions / CDM associate / battery exemption) moved behind a new
>   top-right **gear (Settings)** so first-run pairing stays reachable; the **WP15 Debug gear
>   (Build icon) is intact and still release-gated** by `DebugMenu.isEnabled()`.
>
> **Build/deps deltas:** added `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7` (for
> `viewModel()` + factory). No manifest changes.
>
> **Acceptance met:** `:protocol:test` 108 green (unchanged); `:android:testDebugUnitTest`
> 24 green (17 + 7); `:android:assembleDebug` + `:android:lintDebug` succeed; `./fossil-q
> --help` unchanged. NO change to protocol wire bytes / `BleTransport` / `AndroidBleTransport.kt`
> / WP3 `WatchConnectionService` wire behavior (only READS `WatchState` + calls existing static
> entry points) / WP4 repository behavior / WP15 Debug Menu gating.
>
> **On-device verification pending:** the Compose Dashboard rendering/scroll, the active-watch
> dropdown interaction, the Connect/Disconnect/Sync/Find-Watch live effects, and the gear→Setup
> / gear→Debug navigation can only be confirmed on a device. The headless half (state
> combination + intents → right repo/fake calls) is unit-tested.
>
> **Follow-ups:** WP16f wires live steps/activity (WP8 parsing → DB) into `DashboardUiState.steps`;
> real **Find Watch** choreography (phone→watch repeating notification / hand animation,
> ANDROID-PLAN §4.J) is a later WP that adds the actual protocol trigger behind `WatchActions.findWatch`;
> remaining screens **WP16b** (alarms), **WP16c** (notifications), **WP16d** (buttons),
> **WP16e** (calibration), **WP16f** (sleep/activity charts), **WP16g** (settings) follow the
> same ViewModel+StateFlow / fake-backed-test pattern.

> **WP16b STATUS:** ✅ DONE & VERIFIED (provable core JVM/Robolectric-tested; UI builds +
> lint-passes; list/picker rendering + the real alarm-byte upload flagged on-device-pending /
> deferred to WP14). Mirrors the proven WP16a two-layer pattern: a unit-tested state holder +
> an Android UI layer that compiles and lint-passes. **Scope = user slots 0–15 only** (calendar
> slots 16–31 are WP9/WP13, out of scope).
>
> **(1) Provable core (`qhybrid.android.alarms`, Robolectric + in-memory Room):**
> - `AlarmsViewModel` + `AlarmsUiState` — observes the WP4 `WatchRepository.observeActiveWatch()`
>   and (per active watch) `observeAlarms(mac)`, **filtered to slots 0–15 and sorted by slotId**,
>   into one immutable `AlarmsUiState` via `flatMapLatest` + `stateIn(WhileSubscribed)`. Derived
>   helpers: `activeMac`, `hasActiveWatch`, `isFull` (≥16), `nextFreeSlot` (lowest free in 0..15).
> - Intents: `addAlarm` (picks the **lowest free slot**; **16-slot user cap** — no-op when full or
>   no active watch), `updateAlarm`/`setDays`/`toggleDay` (→ `upsertAlarm`), `deleteAlarm`
>   (→ `deleteAlarmSlot`), `toggleEnabled`, and `setWeekdays`/`setWeekend`/`setEveryday` shortcuts.
> - `AlarmDays` — centralized day-bit constants shared by VM + tests + UI so they cannot drift:
>   `SUN..SAT` (bit0=Sun … **bit3=Wed, bit4=Thu** … bit6=Sat), `WEEKDAY=0x3E`, `WEEKEND=0x41`,
>   `EVERYDAY=0x7F`, plus `toggle`/`summary` helpers. **This IS the WP5 wire convention 1:1** —
>   `daysMask` is read/written straight through to `WatchAlarmEntity` and on to `AlarmCompiler`;
>   NO bit-order translation is invented.
> - `AlarmSync` interface + `ServiceAlarmSync` production impl: "Save to watch" forwards to the
>   existing WP3 `WatchConnectionService.syncNow` (the rows are already persisted to Room by the
>   intents). **No new BLE/protocol behavior.** `saveToWatch()` returns `ALARM_UPLOAD_WIRED=false`
>   to surface that the **actual alarm-byte upload pipeline (WP5 compile → BLE write) is DEFERRED
>   to WP14**; the UI shows an "on-device-pending" note until then.
> - **Tests:** `android/src/test/.../alarms/AlarmsViewModelTest.kt` (13, Robolectric + `DbTestBase`
>   in-memory Room): day constants match the wire convention; only slots 0–15 combine, sorted (a
>   slot-16 row is excluded); empty/no-active-watch; add picks the lowest free slot; 16-slot cap;
>   add no-op without an active watch; weekday/weekend/everyday shortcuts produce 0x3E/0x41/0x7F
>   (and force repeating); `toggleDay` flips a single bit; toggleEnabled/delete/update/setDays hit
>   the right repo rows; save delegates to the fake and reports the WP14-pending flag. (Same
>   REAL-`CoroutineScope` + bounded `awaitState` polling as WP16a, since Room Flows re-emit on
>   Room's executor.) `:android:testDebugUnitTest` now **37 total (24 prior + 13)**, 0 failures.
>
> **(2) UI layer (`qhybrid.android.alarms.AlarmsScreen`, build + lint green):**
> - `AlarmsScreen()` hosts the production VM via `viewModel(factory = …)` and renders a stateless
>   `AlarmsContent(state, intents…)` (pure function of `AlarmsUiState` + lambdas → preview/UI-testable
>   with fake state). A `LazyColumn` of slot-0–15 alarms (time, day summary, enabled `Switch`,
>   delete), an **Add alarm** FAB (hidden at the cap), and a **Save to watch** button (with the
>   WP14-pending note). The add/edit `AlertDialog` has a `TimePicker`, day-of-week `FilterChip`s,
>   the **Weekdays/Weekend/Every day** shortcuts, a **one-shot vs repeating** switch, and an
>   optional label field.
> - Reachable via a new **bottom `NavigationBar`** in `MainActivity` (Dashboard ⇄ Alarms). The
>   nav only shows on the home surface; the **top-right Setup gear** and the **WP15 Debug gear
>   (still release-gated** by `DebugMenu.isEnabled()`) overlay on top, and the WP16a Dashboard tab
>   is unchanged. (Alarms tab icon uses `Notifications` from `material-icons-core`.)
>
> **Acceptance met:** `:protocol:test` 108 green (unchanged); `:android:testDebugUnitTest` 37 green
> (24 + 13); `:android:assembleDebug` + `:android:lintDebug` succeed; `./fossil-q --help` unchanged.
> NO change to protocol wire bytes / `BleTransport` / `AndroidBleTransport.kt` / WP3
> `WatchConnectionService` wire behavior (only calls the existing `syncNow` entry point) /
> `AlarmCompiler`/`AlarmSlot` output / WP4 repository semantics / WP15 Debug Menu gating; WP16a
> Dashboard still works.
>
> **On-device verification pending:** the Compose Alarms list/scroll, the time picker + day-chip
> interaction, the shortcuts, the enabled-switch/delete/save effects, and the bottom-nav switching
> can only be confirmed on a device. The headless half (state combination + intents → right
> repo/fake calls + correct daysMask) is unit-tested.
>
> **Deferred / follow-ups:** the **actual alarm-byte upload to the watch is WP14** (compile slots
> 0–15 via WP5 `AlarmCompiler` → BLE alarm-file write); `ServiceAlarmSync.ALARM_UPLOAD_WIRED` flips
> to true then. Calendar slots 16–31 (WP9/WP13) remain out of scope. Remaining screens **WP16c**
> (notifications), **WP16d** (buttons), **WP16e** (calibration), **WP16f** (sleep/activity charts),
> **WP16g** (settings) follow the same ViewModel+StateFlow / fake-backed-test pattern.

> **WP16c STATUS:** ✅ DONE & VERIFIED (provable core JVM/Robolectric-tested; UI builds +
> lint-passes; list/picker rendering + the real filter-byte upload flagged on-device-pending /
> deferred to WP14). Mirrors the proven WP16a/WP16b two-layer pattern exactly: a unit-tested
> state holder + an Android UI layer that compiles and lint-passes. Manages per-app
> `NotificationRuleEntity` rows (vibe pattern + precise hand position) for the active watch.
>
> **(1) Provable core (`qhybrid.android.notifications`, Robolectric + in-memory Room):**
> - `NotificationsViewModel` + `NotificationsUiState` — observes the WP4
>   `WatchRepository.observeActiveWatch()` and (per active watch) `observeRules(mac)`, **sorted
>   by packageName**, into one immutable `NotificationsUiState` via `flatMapLatest` +
>   `stateIn(WhileSubscribed)` (identical structure to WP16b). Derived helpers: `activeMac`,
>   `hasActiveWatch`, `packageNames` (for duplicate-rejection).
> - Intents: `addRule` (**rejects a duplicate packageName** — the composite PK is
>   [watchMac, packageName]; we don't want a silent REPLACE — and no-ops on no-active-watch /
>   blank package; returns a Boolean accepted/rejected), `updateRule` (→ `upsertRule`),
>   `deleteRule(pkg)` (→ new single-row repo/DAO `deleteRule(mac, pkg)`), `setVibePattern`,
>   and `setHandPosition(hour/minute degrees)`. All writes **clamp** vibe to 0–9 and degrees
>   to 0–359 before persisting.
> - `VibePatterns` — centralized vibe-pattern constants + human labels shared by VM + tests +
>   UI so they cannot drift: `AUTO=0, CALL=1, TEXT=2, EMAIL=3, DEFAULT=4 … ONE_LONG=8, NO_VIBE=9`
>   plus `LABELS`, `ALL`, `clamp`/`clampDegrees`/`label`/`handSummary`. **This IS the WP6 wire
>   convention 1:1** (NotificationCompiler 0xC3 VIBRATION byte / FINDINGS #23) — the stored value
>   is the on-wire vibe byte; NO translation. Degree bounds 0–359 match FINDINGS #24.
> - `NotificationSync` interface + `ServiceNotificationSync` production impl: "Save to watch"
>   forwards to the existing WP3 `WatchConnectionService.syncNow` (the rows are already persisted
>   to Room by the intents). **No new BLE/protocol behavior.** `saveToWatch()` returns
>   `FILTER_UPLOAD_WIRED=false` to surface that the **actual filter-byte upload pipeline (WP6
>   `NotificationCompiler.compileFilter` → 32-byte-per-entry BLE filter-file write) is DEFERRED
>   to WP14**; the UI shows an "on-device-pending" note until then.
> - **Additive DB change:** added single-row `NotificationRuleDao.deleteRule(mac, pkg)` (same
>   style as the existing `deleteForWatch`) + `WatchRepository.deleteRule(mac, pkg)`. No change
>   to existing DAO/repository semantics.
> - **Tests:** `android/src/test/.../notifications/NotificationsViewModelTest.kt` (12, Robolectric
>   + `DbTestBase` in-memory Room): vibe constants match the wire convention + clamp bounds; rules
>   combine sorted by packageName; empty/no-active-watch; add inserts the given fields; duplicate
>   packageName rejected (NOT overwritten); add no-op on no-active-watch / blank package; vibe +
>   hand-position updates write the right row; `updateRule` clamps out-of-range values; delete
>   removes the row; save delegates to the fake and reports the WP14-pending flag. Plus 1 DAO test
>   `CrudRoundTripTest.rule_deleteSingleRow` for the new single-row delete. (Same
>   REAL-`CoroutineScope` + bounded `awaitState` polling as WP16a/b, since Room Flows re-emit on
>   Room's executor.) `:android:testDebugUnitTest` now **50 total (37 prior + 13)**, 0 failures.
>
> **(2) UI layer (`qhybrid.android.notifications.NotificationsScreen`, build + lint green):**
> - `NotificationsScreen()` hosts the production VM via `viewModel(factory = …)` and renders a
>   stateless `NotificationsContent(state, installedApps, intents…)` (pure function of
>   `NotificationsUiState` + the installed-app list + lambdas → preview/UI-testable with fake
>   state). A `LazyColumn` of per-app rules (package name, vibe-pattern label, hand-position
>   summary), an **Add app rule** FAB, a delete icon per row, and a **Save to watch** button
>   (with the WP14-pending note). The add/edit `AlertDialog` has a **searchable installed-app
>   picker** (`AppPicker`: a text field that filters the installed-app list **live by display
>   name OR package id** — fixing the earlier static non-filtering dropdown — showing each app's
>   **icon + friendly name** with the package id as a subtitle; already-configured apps are hidden
>   and the field doubles as a free-text fallback), inline duplicate-package validation, a
>   **vibe-pattern dropdown** (`0 — Auto … 9 — Silent`), and hour/minute **hand-degree** number
>   inputs (0–359).
> - **Installed-app list (pulled forward):** `InstalledAppsProvider` seam +
>   `SystemInstalledAppsProvider` production impl enumerate **launchable** apps via
>   `PackageManager.queryIntentActivities(ACTION_MAIN/CATEGORY_LAUNCHER)` — **no special
>   permission, no Play-sensitive `QUERY_ALL_PACKAGES`** (only surfaces apps with a launcher
>   icon, i.e. the user-facing apps that post notifications), de-duped per package, sorted by
>   label, loaded off the main thread (`LaunchedEffect` + `Dispatchers.IO`). The pure
>   search/filter (`InstalledApp.matches`) is unit-tested (`InstalledAppsTest`, 5).
>   - **Manifest (additive):** Android 11+ (API 30+) filters `PackageManager` queries to the
>     caller's visible set, so a `<queries>` element with a `MAIN`/`LAUNCHER` intent was added to
>     `AndroidManifest.xml` (**OPTION A** — no runtime permission, no Play-sensitive
>     `QUERY_ALL_PACKAGES`); without it the picker only saw a handful of self-visible apps.
>     **OPTION B (future, if non-launchable packages are ever needed):** declare the
>     `QUERY_ALL_PACKAGES` permission instead — noted as Play-Store-sensitive (requires a
>     submission justification).
> - Reachable via a **third tab** on the existing bottom `NavigationBar` in `MainActivity`
>   (Dashboard / Alarms / **Notifications**). The Notifications tab uses `Icons.Filled.Email`
>   from `material-icons-core` (the `Notifications` icon is already used by the Alarms tab; the
>   extended/`Alarm` icons are NOT on the classpath). The nav only shows on the home surface; the
>   **top-right Setup gear** and the **WP15 Debug gear (still release-gated** by
>   `DebugMenu.isEnabled()`) overlay on top; WP16a Dashboard + WP16b Alarms tabs are unchanged.
>
> **Acceptance met:** `:protocol:test` 108 green (unchanged); `:android:testDebugUnitTest` 55 green
> (37 + 18: 12 ViewModel + 1 DAO + 5 installed-app search); `:android:assembleDebug` +
> `:android:lintDebug` succeed; `./fossil-q --help` unchanged. NO change to protocol wire bytes /
> `BleTransport` / `AndroidBleTransport.kt` / WP3 `WatchConnectionService` wire behavior (only
> calls the existing `syncNow` entry point) / `NotificationCompiler` output / existing WP4
> repository semantics (only ADDED the single-row `deleteRule`) / WP15 Debug Menu gating; WP16a
> Dashboard + WP16b Alarms still work.
>
> **On-device verification pending:** the Compose Notifications list/scroll, the package field +
> sample dropdown, the vibe-pattern dropdown, the hand-degree inputs, the delete/save effects, and
> the 3-tab bottom-nav switching, and the live installed-app enumeration/icons can only be
> confirmed on a device. The headless half (state combination + intents → right repo/fake calls +
> correct clamping/duplicate-rejection + app search/filter) is unit-tested.
>
> **Deferred / follow-ups:** the **actual filter-byte upload to the watch is WP14** (compile the
> per-app rows via WP6 `NotificationCompiler.compileFilter` → 32-byte-per-entry BLE filter-file
> write); `ServiceNotificationSync.FILTER_UPLOAD_WIRED` flips to true then. The **searchable
> installed-app picker (name + icon) was pulled forward** into WP16c (launcher-intent query, no
> special permission); the remaining `NotificationListenerService` plumbing (actually
> *intercepting* posted notifications to push play files) stays a later WP. Remaining screens
> **WP16d** (buttons), **WP16e**
> (calibration), **WP16f** (sleep/activity charts), **WP16g** (settings) follow the same
> ViewModel+StateFlow / fake-backed-test pattern.

> **WP16d STATUS:** ✅ DONE & VERIFIED (provable core JVM/Robolectric-tested; UI builds +
> lint-passes; mapping-list/editor rendering + the real button-config upload flagged
> on-device-pending / deferred to WP14). Mirrors the proven WP16a/b/c two-layer pattern exactly:
> a unit-tested state holder + an Android UI layer that compiles and lint-passes. Manages
> per-button `ButtonMappingEntity` rows (modeType + dial-mode toggles + action list) for the
> active watch.
>
> **DESIGN DECISION — MODEL-AGNOSTIC / FLEXIBLE:** the screen deliberately does NOT hard-code
> per-model button counts/layouts and does NOT gate any buttonId/mode/action behind a watch-model
> lookup table. The user can add a mapping for ANY `buttonId` (hex `0x10`/`0x20`/`0x30` … or more
> for 5-position dials, or even arbitrary values), pick ANY mode/action, with NO count cap, and
> remove any mapping. The protocol *does* have a model concept
> (`ButtonCompiler.DialModel` + `availableModes`) but WP16d does not enforce it; per-hardware
> validation that a given buttonId actually exists is **out of scope (on-device-pending / WP14)**.
> Only the truly shared, model-independent vocabulary is centralized (modeType strings + labels,
> dial-mode list, action catalog) — NOT a per-model button map.
>
> **(1) Provable core (`qhybrid.android.buttons`, Robolectric + in-memory Room):**
> - `ButtonsViewModel` + `ButtonsUiState` — observes the WP4
>   `WatchRepository.observeActiveWatch()` and (per active watch) `observeButtons(mac)`, **sorted
>   by buttonId (any count)**, into one immutable `ButtonsUiState` via `flatMapLatest` +
>   `stateIn(WhileSubscribed)` (identical structure to WP16b/c). Derived helpers: `activeMac`,
>   `hasActiveWatch`, `buttonIds` (for duplicate-rejection). **No model-specific filtering.**
> - Intents: `addMapping(buttonId, modeType, actionsJson)` (**rejects a duplicate buttonId** —
>   the composite PK is [watchMac, buttonId]; we don't want a silent REPLACE — and no-ops on
>   no-active-watch; returns a Boolean accepted/rejected; buttonId is NOT range-checked → any
>   value allowed), `updateMapping` (→ `upsertButton`), `setMode(buttonId, modeType)`,
>   `setActions(buttonId, actionsJson)` / `setActionList`, and `resetButton(buttonId)`
>   (→ new single-row repo/DAO `deleteButton(mac, buttonId)`). All writes normalize the modeType
>   and round-trip `actionsJson` through the helper so a malformed string can never be persisted.
> - `ButtonModes` / `ButtonDialModes` / `ButtonActions` — centralized, model-agnostic vocabulary
>   shared by VM + tests + UI so they cannot drift (discipline like `VibePatterns`/`AlarmDays`):
>   modeType `SINGLE_ACTION`/`MUSIC_MULTIMODE`/`CUSTOM_TOGGLE` (+ labels, `usesDialModes`);
>   dial modes `ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR` (1:1 with WP7
>   `ButtonCompiler.DialMode`); action catalog 1:1 with WP7 `ConfigPayload` enum names
>   (`FORWARD_TO_PHONE … RING_PHONE`, 11 entries) + human labels.
> - `ButtonActionsJson` — small, robust encode/decode helper for the free-form `actionsJson`
>   array. Encodes the canonical `[{"action":"…"}]` shape; **tolerates empty/blank/malformed JSON
>   → empty list (never throws)**; also accepts a bare-string-array fallback and the WP4 DAO
>   fixture shape. Plus `summary(...)` for the row subtitle.
> - `ButtonSync` (interface) + `ServiceButtonSync` (production) — the injectable "Save to watch"
>   seam (mirrors `AlarmSync`/`NotificationSync`). Production forwards to the existing WP3
>   `WatchConnectionService.syncNow` (no new wire behavior) and returns
>   `BUTTON_UPLOAD_WIRED=false` until WP14.
> - Tests (`ButtonsViewModelTest`, `ButtonVocabularyTest`, + a `button_deleteSingleRow` DAO test
>   in `CrudRoundTripTest`): mappings combine/sort at any count (incl. 5-position `0x40`/`0x50`);
>   add inserts the given fields; **arbitrary buttonId not rejected (model-agnostic)**;
>   duplicate-buttonId rejection (no silent REPLACE); set-mode / set-actions write the right row;
>   malformed-JSON update persists a valid empty array; reset removes the row; save hits the fake
>   + reports the WP14-pending flag; constants sanity (modeType/dial/action 1:1 with protocol);
>   actionsJson encode/decode round-trip + empty/malformed tolerance + bare-string fallback.
>   `:android:testDebugUnitTest` = **77 green** (55 prior + 22 new), 0 failures.
>
> **(2) UI layer (`ButtonsScreen.kt`, builds + lint-passes; behavior on-device-pending):**
> - Stateless `ButtonsContent(state, onAdd, onUpdate, onReset, onSave)` (preview-/UI-testable
>   with fake state, no VM/Room/BLE) + a thin `ButtonsScreen()` that wires the production VM via
>   `ButtonsViewModel.factory(context)`. Renders the active watch's mapping list (buttonId hex
>   label, mode label, action/dial summary), an **"Add button mapping" FAB that accepts any
>   buttonId** (hex `0x10` or decimal), a per-mapping editor dialog with a **mode dropdown**, an
>   **action multi-select** (SINGLE_ACTION/MUSIC_MULTIMODE) OR **dial-mode toggle chips**
>   (CUSTOM_TOGGLE — chosen mode implies the dial UI), a **reset** per mapping, and a **Save to
>   watch** button that surfaces the WP14-pending note when not wired.
> - Reachable via a **4th bottom-nav tab** added to `MainActivity` (Dashboard / Alarms /
>   Notifications / **Buttons**). Buttons tab icon = `Icons.Filled.Star` (verified on the
>   material-icons-core classpath the way WP16c verified `Email`; extended icons are NOT on the
>   classpath). The WP15 Debug gear stays present + release-gated; WP16a/b/c still work.
>
> **Did NOT touch** protocol wire bytes / `ButtonCompiler` / `ButtonConfigBuilder` /
> `ConfigPayload` output / `BleTransport` / `AndroidBleTransport.kt` / WP3 service wire behavior
> (only calls the existing `syncNow`) / existing WP4 repository semantics (only ADDED the
> single-row `deleteButton`/`getButton`) / WP15 Debug Menu gating. `:protocol:test` stays at
> **108 green**; `./fossil-q --help` unchanged; `:android:assembleDebug` + `:android:lintDebug`
> succeed.
>
> **On-device verification pending:** the Compose mapping list/scroll, the add-buttonId field +
> mode dropdown + action multi-select + dial-mode chips, the reset/save effects, and the 4-tab
> bottom-nav switching can only be confirmed on a device. The headless half (state
> combination/sort at any count + intents → right repo/fake calls + duplicate-rejection +
> JSON tolerance + constants) is unit-tested.
>
> **Deferred / follow-ups:** the **actual button-config upload to the watch is WP14** (compile
> the per-button rows via WP7 `ButtonCompiler.compileMultiEntry`/`compileSingleEntryPerButton`
> → `FossilController.compileButtons` → SETTINGS_BUTTONS `0x0600` BLE file write);
> `ServiceButtonSync.BUTTON_UPLOAD_WIRED` flips to true then. **Model-aware hardware validation**
> (which buttonIds/dial modes a given connected watch actually supports, via
> `ButtonCompiler.availableModes`) is intentionally out of scope here and may land with WP14.
> Remaining screens **WP16e** (calibration), **WP16f** (sleep/activity charts), **WP16g**
> (settings) follow the same ViewModel+StateFlow / fake-backed-test pattern.

> **WP16e STATUS:** ✅ DONE & VERIFIED (provable core JVM/Robolectric-tested; UI builds +
> lint-passes; hand-select / nudge / Apply rendering + the real move-hands/save-calibration
> command flagged on-device-pending / deferred to **WP14 / WP F**). Mirrors the proven
> WP16a/b/c/d two-layer pattern's **active-watch half exactly** (`combine` over
> `observeActiveWatch` + `stateIn(WhileSubscribed)`; injectable `*Sync` seam with a
> `*_WIRED=false` deferral flag; stateless `*Content`; production `factory`; Robolectric +
> in-memory Room tests via `DbTestBase` with a real `CoroutineScope` + bounded `awaitState`
> polling). The fifth user-facing screen: interactive hand-position calibration for the active
> watch.
>
> **DESIGN DECISION — EPHEMERAL / NOT PERSISTED (drives the whole design):** calibration is a
> physical "set zero = current hand position" handshake, NOT stored data. The meaningful value
> is a LIVE delta relative to where the hands physically are this instant; a saved offset would
> re-apply a now-wrong correction, and de-calibration cannot be saved. Therefore **NO new Room
> entity / DAO / `WatchRepository` calibration method was added** — the calibration
> offset + hand-selection live ONLY in `CalibrationUiState` (in-memory): zeroed on
> `enterCalibration()`, cleared on `exitCalibration()`. **Re-opening the screen ALWAYS starts a
> fresh, neutral session** (no reloaded offset). The ViewModel observes `observeActiveWatch()`
> PURELY to know which watch is active and to disable the UI when none is connected. **NO test
> asserts any DB persistence — there is none.** (Confirmed: no calibration DB field existed to
> (mis)use; the protocol *does* have the building blocks —
> `FossilQAdapter.requestHandsControl`/`setHands`/`saveCalibration`/`releaseHandsControl` — but
> they are NOT yet exposed via the WP3 `WatchConnectionService` static entry points, so the
> apply path stays deferred behind the `CALIBRATION_WIRED=false` seam; no wire bytes invented.)
>
> **DESIGN DECISION — MODEL-AGNOSTIC / FLEXIBLE:** the screen deliberately does NOT hard-code
> per-model hand counts or sub-eye layouts. `CalibrationHands` is a flat catalog
> (`HOUR`/`MINUTE`/`SUB`, 1:1 with the CLI `calibrate` command) the UI lets the user nudge
> without a model lookup table; a watch that lacks one of these hands simply ignores its move
> (hardware behaviour is on-device-pending / WP14 / WP F). Only the truly shared,
> model-independent vocabulary is centralized (hand ids + labels; the degree/step conventions).
>
> **(1) Provable core (`qhybrid.android.calibration`, Robolectric + in-memory Room):**
> - `CalibrationViewModel` + `CalibrationUiState` — `combine(observeActiveWatch(), session)`
>   into one immutable `CalibrationUiState` via `stateIn(WhileSubscribed)`. The active-watch
>   half (`activeWatch`/`hasActiveWatch`) is observed; the calibration session (`inProgress`,
>   `selectedHand`, `offsets: Map<hand,deg>`) is plain **in-memory** `MutableStateFlow` — no DB.
>   Derived helpers: `activeMac`, `canCalibrate` (= `hasActiveWatch && inProgress`),
>   `offsetOf(hand)` (defaults neutral 0°).
> - Intents: `enterCalibration()` (starts a FRESH neutral session — `inProgress=true`, default
>   hand, offsets zeroed, **never reloads a prior offset**), `exitCalibration()` (clears the
>   session; no persistence), `nudge(hand, deltaDegrees)` (apply + normalize via `HandDegrees`,
>   tolerates wrap past 0/359; no-op unless in session), `setHand(hand, degrees)` (absolute,
>   normalized; tolerates negative/out-of-range), `selectHand(hand)` (unknown id → default), and
>   `apply()` delegating to the injectable `CalibrationSync` seam (fire-and-forget LIVE command;
>   no-op unless in session + active watch; returns the WP14/WP-F-pending flag).
> - `CalibrationHands` / `HandDegrees` — centralized, model-agnostic vocabulary shared by
>   VM + tests + UI (discipline like `VibePatterns`/`AlarmDays`/`ButtonModes`): hand ids
>   `HOUR`/`MINUTE`/`SUB` (+ labels, `normalize`); degree conventions `FULL=360`, `COARSE=6°`
>   (one minute mark), `FINE=1°`, `NEUTRAL=0`, with `normalize(deg)`/`nudge(deg,delta)` that
>   wrap onto the 0–359 ring (matches the CLI `calibrate` `wrap()` helper).
> - `CalibrationSync` (interface) + `ServiceCalibrationSync` (production) — the injectable
>   "Apply" seam (mirrors `AlarmSync`/`NotificationSync`/`ButtonSync`). Production forwards to
>   the existing WP3 `WatchConnectionService.syncNow` (no new wire behavior) and returns
>   `CALIBRATION_WIRED=false` until WP14 / WP F.
> - Tests (`CalibrationViewModelTest`, `CalibrationVocabularyTest`): UiState reflects the active
>   watch + disables when none; enter starts a neutral session; exit clears + re-enter starts
>   neutral with **NO reloaded offset**; nudge applies + normalizes incl. wrap past 0 and past
>   359; nudge/setHand/apply are no-ops outside a session; setHand/selectHand update the
>   in-memory session (incl. negative/out-of-range + unknown-id tolerance); apply hits the fake
>   + reports the WP14/WP-F-pending flag + forwards per-hand offsets; degree normalize/nudge
>   round-trip + idempotence; constants sanity. **No DB-persistence assertions (none exists).**
>   `:android:testDebugUnitTest` = **95 green** (77 prior + 18 new), 0 failures.
>
> **(2) UI layer (`CalibrationScreen.kt`, builds + lint-passes; behavior on-device-pending):**
> - Stateless `CalibrationContent(state, onEnter, onExit, onSelectHand, onNudge, onApply)`
>   (preview-/UI-testable with fake state, no VM/Room/BLE) + a thin `CalibrationScreen()` that
>   wires the production VM via `CalibrationViewModel.factory(context)`. Renders enter/exit
>   calibration, a **hand selector** (flat catalog chips, not gated by model), **+/- nudge**
>   controls (coarse `6°` + fine `1°`), a **live per-hand degree readout** + an all-hands
>   session summary, and an **Apply** button that surfaces the WP14/WP-F-pending note when not
>   wired. Makes the transient nature explicit ("Exit (discard)" + "nothing is saved" hints).
> - Reachable via a **5th bottom-nav tab** added to `MainActivity` (Dashboard / Alarms /
>   Notifications / Buttons / **Calibration**). Calibration tab icon = `Icons.Filled.Refresh`
>   (verified on the material-icons-core classpath the way WP16d verified `Star` — enumerated
>   the jar's `filled/*Kt.class` entries; extended icons are NOT on the classpath). The WP15
>   Debug gear stays present + release-gated; WP16a–d still work.
>
> **Did NOT touch** protocol wire bytes / any protocol calibration output
> (`SaveCalibrationRequest`/`MoveHandsRequest`/`RequestHandControlRequest`) / `BleTransport` /
> `AndroidBleTransport.kt` / WP3 service wire behavior (only calls the existing `syncNow`) /
> existing WP4 repository semantics (**added NO DB entity/DAO/repo method — calibration is
> ephemeral**) / WP15 Debug Menu gating. `:protocol:test` stays at **108 green**;
> `./fossil-q --help` unchanged; `:android:assembleDebug` + `:android:lintDebug` succeed.
>
> **On-device verification pending:** the Compose hand-select chips, the +/- nudge controls, the
> live degree readout, the Apply effect, and the 5-tab bottom-nav switching can only be confirmed
> on a device. The headless half (active-watch observation + in-memory session intents + degree
> normalization/wrap + constants) is unit-tested.
>
> **Deferred / follow-ups:** the **actual move-hands / save-calibration command to the watch is
> WP14 / WP F** (the CLI `calibrate` sequence `requestHandsControl` → `setHands(h,m,s)` →
> `saveCalibration` → `releaseHandsControl` → `syncTime`, wired through the WP3 foreground
> service as a new BLE action); `ServiceCalibrationSync.CALIBRATION_WIRED` flips to true then.
> Calibration is intentionally **NOT persisted** (ephemeral by nature — a live reference set,
> not stored data). Remaining screens **WP16f** (sleep/activity charts) and **WP16g** (settings)
> follow the same ViewModel+StateFlow / fake-backed-test pattern.

> **WP16f STATUS:** ✅ DONE & VERIFIED (provable core JVM/Robolectric-tested; UI builds +
> lint-passes; the actual activity-file fetch + parse pipeline flagged on-device-pending /
> deferred to a later WP). The **sixth** user-facing screen: a **READ-ONLY, model-agnostic**
> Sleep/Activity analytics view for the active watch (daily steps/calories + a sleep timeline +
> a quality summary), derived from WP8 parsing. Lives in `qhybrid.android.sleep.*`. Mirrors the
> proven WP16a–e two-layer pattern's **active-watch half exactly** (`combine` over
> `observeActiveWatch` + `stateIn(WhileSubscribed)`; an injectable seam with a `*_WIRED=false`
> deferral flag; centralized model-agnostic constants; a stateless `*Content`; a production
> `factory`; Robolectric + in-memory Room tests via `DbTestBase` with a real `CoroutineScope` +
> bounded `awaitState` polling).
>
> **DATA-SOURCE DECISION (drives the whole design) — NO PERSISTED STORE; FETCH DEFERRED.** WP8
> (`qhybrid.protocol.activity.ActivitySummarizer` / `ActivityParser`) is a **pure on-demand**
> function over a raw binary activity file (`byte[]`). There is **NO persisted activity/sleep
> store**: WP4's Room schema has no activity entity, and the WP16/WP16f breakdown does NOT say
> WP16f owns one. Therefore **NO new Room entity/DAO/repository method and NO speculative DB
> schema were added.** The screen renders from an injectable `ActivitySource`; the real
> fetch→parse pipeline (BLE read of the watch's activity file → `ActivityParser.parse` →
> `SleepActivityAdapter` → state) is **deferred** behind `ServiceActivitySource.ACTIVITY_WIRED`
> = `false` (no invented wire bytes; production `refresh()` only pokes the existing
> `WatchConnectionService.syncNow`). The parser already exists (WP8) — only the *fetch* (an
> activity-file read action on the WP3 service, plus optional caching) is missing; that is its
> own later WP. Flip `ACTIVITY_WIRED` to true then.
>
> **DESIGN DECISIONS — READ-ONLY + MODEL-AGNOSTIC.** The screen only *displays* parsed data; the
> only "action" is `refresh`, routed through the deferred source seam (no write/upload path,
> unlike alarms/buttons/calibration). No per-model hard-coding — every watch emits the same
> minute-record format WP8 decodes. Charts use **Compose primitives only** (`Canvas` step bars +
> `Box`/`Row` stacked restful/restless bars) — **no new charting dependency** (none on the
> classpath; verified).
>
> **(1) Provable core (`qhybrid.android.sleep`, Robolectric + in-memory Room):**
> - `SleepActivityViewModel` + `SleepActivityUiState` — `combine(observeActiveWatch(),
>   source.data)` into one immutable UiState via `stateIn(WhileSubscribed)`. The active-watch
>   half (`activeWatch`/`hasActiveWatch`/`stepGoal`) is observed PURELY to know which watch is
>   active; when none, the data half is **forced empty** (read-only, no leftover data). Derived
>   helpers: `days`/`sleep`/`sleepSummary`, `totalSteps`/`totalCalories`/`totalActiveMinutes`,
>   `isEmpty`, `canRefresh`. Sole intent `refresh()` (no-op without an active watch) delegates to
>   the `ActivitySource` seam and returns the `ACTIVITY_WIRED` pending flag.
> - `SleepActivityAdapter` + chart-ready immutable view models (`DaySummary`, `SleepSegment`,
>   `SleepSummary`, `ActivityChartData`) — adapts WP8 output for display with **ZERO
>   parsing/sleep math**: reuses `ActivitySummarizer.summarizeByDay` (per-day steps/calories/
>   active-minutes) and `ActivitySummarizer.detectSleepSessions` (which delegates 1:1 to
>   `ActivityParser.detectSleep`) and reshapes them into `java.time`-free / protocol-type-free
>   Kotlin models so they flow straight into Compose and are trivially fakeable. `fromParsed(data,
>   zone)` is the on-demand convenience (zone injected — no system clock).
> - `SleepQuality` / `SleepActivityFormat` — centralized, model-agnostic display vocabulary
>   (discipline like `VibePatterns`/`CalibrationHands`): the quality ids `good`/`fair`/`restless`
>   + `none`, **thresholds mirroring `ActivityParser.SleepPeriod.quality()` 1:1** (<10% restless →
>   good, <25% → fair, else restless), labels, and `durationLabel`/`restlessPercent` (never
>   divides by zero). The protocol layer stays the single source of truth for the math.
> - `ActivitySource` (interface) + `ServiceActivitySource` (production) — the injectable data
>   seam (mirrors `AlarmSync`/`NotificationSync`/`ButtonSync`/`CalibrationSync`). Production
>   emits empty `ActivityChartData` and `refresh()` only pokes `syncNow`, returning
>   `ACTIVITY_WIRED=false` until the fetch WP lands (no new wire behavior, no invented bytes,
>   no DB).
> - Tests (`SleepActivityViewModelTest`, `SleepActivityAdapterTest`): UiState reflects the active
>   watch + **empties when none** (even if the source holds data); a fake source feeds known data
>   → correct Day/Sleep summaries + timeline + aggregate totals; the **WP8 adapter maps a known
>   parser/detectSleep result correctly** (golden-style, reusing the REAL `ActivityParser` on the
>   repo fixtures `activity.bin`/`activity-test.bin` — same files WP8 locks: 1 session, 855m,
>   restless=6, quality `good`; the short file yields no sleep); quality-threshold + constants
>   sanity (mirror the protocol); `refresh` hits the fake + reports the `ACTIVITY_WIRED` pending
>   flag (and is a no-op without an active watch); empty/partial-data tolerance (zero records →
>   no crash, days-but-no-sleep). `:android:testDebugUnitTest` = **128 green** (95 prior + 33
>   new), 0 failures. (Build wiring: the android unit-test task now sets
>   `systemProperty 'fossilq.repoRoot', rootDir.absolutePath` so the adapter golden test can
>   locate the repo-root `activity*.bin` fixtures, mirroring `protocol/build.gradle`.)
>
> **(2) UI layer (`SleepActivityScreen.kt`, builds + lint-passes; behavior on-device-pending):**
> - Stateless `SleepActivityContent(state, onRefresh)` (preview-/UI-testable with fake state, no
>   VM/Room/BLE) + a thin `SleepActivityScreen()` wiring the production VM via
>   `SleepActivityViewModel.factory(context)`. Renders an **Activity** summary card (steps /
>   calories / active minutes + the WP4 step goal), a **per-day steps `Canvas` bar chart** (with a
>   model-agnostic goal line from the WP4 row) + per-day readout, a **Sleep** quality summary card
>   (total / restful / restless + a stacked ratio bar), a **sleep timeline** (one stacked
>   restful/restless bar per session, width ∝ duration), and a **Refresh** button that surfaces
>   the on-device-pending note when not wired. Clearly marked **read-only** ("nothing here is sent
>   to the watch").
> - Reachable via a **6th bottom-nav tab** added to `MainActivity` (Dashboard / Alarms /
>   Notifications / Buttons / Calibration / **Sleep**). Sleep tab icon = `Icons.Filled.DateRange`
>   (verified on the material-icons-core classpath the WP16e way — enumerated the jar's
>   `filled/*Kt.class` entries; extended icons are NOT on the classpath). The WP15 Debug gear
>   stays present + release-gated; WP16a–e still work.
>
> **Did NOT touch** protocol wire bytes / `ActivityParser` output / `ActivitySummarizer` math /
> `BleTransport` / `AndroidBleTransport.kt` / WP3 service wire behavior (only calls the existing
> `syncNow`) / existing WP4 repository semantics (**added NO DB entity/DAO/repo method — there is
> no persisted activity store**) / WP15 Debug Menu gating. `:protocol:test` stays at **108 green**;
> `./fossil-q --help` unchanged; `:android:assembleDebug` + `:android:lintDebug` succeed.
>
> **On-device verification pending:** the Compose charts/timeline rendering, the Refresh effect,
> and the 6-tab bottom-nav switching can only be confirmed on a device. The headless half
> (active-watch observation + the WP8→display adapter + refresh intent + constants) is unit-tested.
>
> **Deferred / follow-ups:** the **actual activity-file fetch + parse pipeline is a later WP**
> (BLE read of the watch's activity file via the WP3 service → `ActivityParser.parse` →
> `SleepActivityAdapter` → push into `ServiceActivitySource`, plus an optional parsed-result
> cache); `ServiceActivitySource.ACTIVITY_WIRED` flips to true then, and this also feeds
> `DashboardUiState.steps` (the WP16a placeholder). WP16f intentionally adds **NO Room schema**
> (WP8 is on-demand). The **last remaining screen is WP16g** (settings: nudge, vibration strength,
> timezone, preferred music app, settings transfer (WP4), log viewer (WP15)) — same
> ViewModel+StateFlow / fake-backed-test pattern.

> **WP16g STATUS:** ✅ DONE & VERIFIED (provable core JVM/Robolectric-tested; UI builds +
> lint-passes; the LIVE settings commands flagged on-device-pending / deferred to WP14). The
> **seventh and LAST** user-facing screen — **WP16g completes the user-facing screen set
> (WP16a–g).** A per-active-watch **Settings** screen lives in `qhybrid.android.settings.*`.
> Mirrors the proven WP16a–f two-layer pattern's active-watch half exactly
> (`combine(observeActiveWatch(), …)` + `stateIn(WhileSubscribed)`; injectable seams with a
> `SETTINGS_WIRED=false` deferral flag for the live commands; centralized model-agnostic
> constants; a stateless `*Content`; a production `factory`; Robolectric + in-memory Room tests
> via `DbTestBase` with a real `CoroutineScope` + bounded `awaitState` polling).
>
> **PER-SETTING DATA-SOURCE DECISIONS (this drove the whole design — stated per setting):**
> - **Vibration strength = (a) PERSISTED per-watch.** It is ALREADY a WP4
>   `WatchEntity.vibrationStrength` field (default 50), so it round-trips through
>   `WatchRepository.upsertWatch` — **NO new Room field/entity/DAO added** (the breakdown does NOT
>   give WP16g a new DB field; we reuse the existing one). Applying it LIVE to the watch is a
>   deferred command (see below).
> - **Inactivity nudge (enabled + 1–255 min) = (b) APP PREF + (c) deferred LIVE command.** No
>   `WatchEntity` field exists and the breakdown does not give WP16g one, so the value is persisted
>   app-side via the injectable `SettingsPrefs` seam (production = a tiny `fossilq_settings`
>   SharedPreferences blob, the same isolated style as WP3 `CompanionManager`, swappable for
>   DataStore later). Applying it live is deferred.
> - **Second timezone (offset minutes, UTC−12..UTC+14) = (b) APP PREF + (c) deferred LIVE
>   command.** Same treatment as nudge (persisted via `SettingsPrefs`; live apply deferred).
> - **Preferred music app = (b) PURE APP PREF (never sent to the watch).** A phone-side pref for
>   the music-control fallback (ANDROID-PLAN §4.E); persisted via `SettingsPrefs`. The picker
>   **REUSES the WP16c `InstalledAppsProvider`** (launcher-intent query, no special permission) —
>   no second app-picker built.
> - **Settings transfer = REUSE the WP4 surface.** Wired straight to
>   `WatchRepository.transferSettings(from, to)` (the same clone op the WP15 Debug Menu uses) — not
>   reinvented; no-op for a blank/identical MAC pair.
> - **Log viewer = REUSE the WP15 viewer.** The Settings "View logs" entry opens the existing
>   `qhybrid.android.log.LogConsole` (the WP15 log viewer) as an overlay surface — **NO second log
>   viewer built**; and it is reachable in release builds too (the Debug Menu itself stays
>   release-gated by `DebugMenu.isEnabled()`).
>
> **DEFERRED — the LIVE settings commands (WP14, no invented bytes).** Vibration strength /
> inactivity nudge / second timezone are ALSO live watch commands. The protocol helpers ALREADY
> exist and are golden-tested (`FossilController.setVibrationStrength` / `setInactivityNudge` /
> `setSecondTimezone` → `ConfigurationPutRequest` items 0x0A / 0x09 / 0x11), but they are NOT yet
> exposed via the WP3 `WatchConnectionService` static entry points. Wiring those through the
> foreground service as new BLE actions is **WP14** (the same WP that wires the alarm/notification/
> button uploads). Until then the injectable `SettingsSync` seam ONLY pokes the existing `syncNow`
> (no new wire bytes, none invented) and reports `ServiceSettingsSync.SETTINGS_WIRED = false`, so
> the UI flags the live apply as on-device-pending. **Persisted values are saved regardless**, so
> flipping `SETTINGS_WIRED` later just applies the already-stored prefs/row.
>
> **(1) Provable core (`qhybrid.android.settings`, Robolectric + in-memory Room):**
> - `SettingsViewModel` + `SettingsUiState` — `combine(observeActiveWatch(), appSettings,
>   installedApps)` into one immutable UiState via `stateIn(WhileSubscribed)`. The active-watch
>   half supplies the **persisted** `vibrationStrength` (from the WP4 row, normalized) +
>   `hasActiveWatch`/`canEditWatchSettings` (per-watch controls disable when none). The app-level
>   prefs (nudge / second timezone / preferred music app) stay editable regardless. Intents:
>   `setVibrationStrength` (persist to the WP4 row **and** deferred live apply),
>   `setNudge`/`setSecondTimezoneOffset` (persist to `SettingsPrefs` **and**, when a watch is
>   active, deferred live apply), `setPreferredMusicApp` (pure app pref), `loadInstalledApps`
>   (reuses WP16c), and `transferSettings` (→ WP4 `transferSettings`). Every value is clamped/
>   normalized via `SettingsVocabulary`.
> - `SettingsVocabulary` — centralized, **model-agnostic** vocabulary/ranges/defaults
>   (discipline like `VibePatterns`/`CalibrationHands`/`SleepQuality`): vibration 0–100 (default
>   50, mirroring the `WatchEntity` field); nudge 1–255 min (1-byte range, 15-min step, off by
>   default); second-timezone offsets UTC−12..UTC+14 at 30-min granularity + `UTC±HH:MM` labels;
>   the music-app `NONE` sentinel. All `normalize*` clamp out-of-range and tolerate null/blank.
> - `SettingsPrefs` (interface) + `SharedPreferencesSettingsPrefs` (production) — the injectable
>   app-pref seam (nudge / second timezone / music app) so the VM is testable with an in-memory
>   fake. NOT per-watch-keyed (these are user-level prefs); per-watch state stays in Room.
> - `SettingsSync` (interface) + `ServiceSettingsSync` (production) — the injectable LIVE-command
>   seam (mirrors `AlarmSync`/`NotificationSync`/`ButtonSync`/`CalibrationSync`/`ActivitySource`).
>   Production pokes `syncNow` and reports `SETTINGS_WIRED=false` (deferred to WP14; no invented
>   bytes). Music app + settings-transfer are intentionally NOT here (phone-side / WP4 DB op).
> - Tests (`SettingsViewModelTest` 11, `SettingsVocabularyTest` 6 = 11 Robolectric + Room + pure
>   JVM constants): UiState reflects the active watch's persisted vibration strength + **disables**
>   when none; vibration strength **round-trips through the WP4 repo** (asserted in the DB) + hits
>   the `SettingsSync` fake reporting `SETTINGS_WIRED` pending; nudge/timezone **round-trip through
>   the prefs fake** + hit the sync fake (and persist-but-don't-sync without an active watch);
>   preferred music app round-trips and **never** fires a live command; `loadInstalledApps` reuses
>   the WP16c provider; **settings-transfer hits the WP4 `transferSettings` surface** (rows copied
>   onto target, source untouched) and is a no-op for a blank/identical pair; clamp out-of-range +
>   empty/partial tolerance (no crash with no watch / empty DB / missing prefs).
>   `:android:testDebugUnitTest` = **139 green** (128 prior + 11 new), 0 failures.
>
> **(2) UI layer (`SettingsScreen.kt`, builds + lint-passes; behavior on-device-pending):**
> - Stateless `SettingsContent(state, intents…)` (preview-/UI-testable with fake state, no
>   VM/Room/BLE) + a thin `SettingsScreen(onOpenLogs)` wiring the production VM via
>   `SettingsViewModel.factory(context)`. Grouped Material3 cards — **Vibration strength**
>   (`Slider`), **Inactivity nudge** (`Switch` + ± steppers), **Second timezone**
>   (`ExposedDropdownMenuBox`), **Preferred music app** (`ExposedDropdownMenuBox` over the WP16c
>   app list), **Settings transfer** (from/to MAC + `Button` → WP4 clone), and a **Logs** card
>   (`View logs` → the WP15 `LogConsole`). Standard Material3 controls only — **no new
>   dependency** (verified). Each per-watch/live setting is clearly marked **on-device-pending
>   (WP14)**; persisted prefs + settings-transfer are real.
> - Reachable via a **7th bottom-nav tab** added to `MainActivity` (Dashboard / Alarms /
>   Notifications / Buttons / Calibration / Sleep / **Settings**). Settings tab icon =
>   `Icons.Filled.Info` (verified on the material-icons-core classpath the WP16e/f way —
>   enumerated the jar's `filled/*Kt.class` entries; extended icons are NOT on the classpath).
>   **GEAR vs TAB decision:** the top-right **Settings gear stays the WP3 Setup flow**
>   (permissions / CDM associate / battery exemption — first-run pairing belongs there, not in the
>   per-watch Settings tab); the new **Settings tab is the per-active-watch settings**. They do
>   not conflict. The WP15 **Debug gear stays present + release-gated**; WP16a–f still work.
>
> **Did NOT touch** protocol wire bytes / `FossilController`/`FossilQAdapter` settings helpers /
> `BleTransport` / `AndroidBleTransport.kt` / WP3 service wire behavior (only calls the existing
> `syncNow`) / existing WP4 repository semantics (**added NO DB entity/DAO/repo method — vibration
> strength reuses the existing `WatchEntity.vibrationStrength`; nudge/timezone/music are app
> prefs**) / WP15 Debug Menu gating (the log viewer is REUSED). `:protocol:test` stays at **108
> green**; `./fossil-q --help` unchanged; `:android:assembleDebug` + `:android:lintDebug` succeed.
>
> **On-device verification pending:** the Compose Settings rendering/controls, the live settings
> apply effect, the music picker, the settings-transfer effect, the log-viewer overlay, and the
> 7-tab bottom-nav switching can only be confirmed on a device. The headless half (active-watch +
> prefs combination, the persist round-trips, the deferred sync intents, the WP4 transfer call,
> the constants/normalization) is unit-tested.
>
> **Deferred / follow-ups + REMAINING MILESTONES.** WP16g completes WP16a–g (all seven
> user-facing screens). The remaining work is **non-UI**: the live settings commands are
> **WP14** (flip `SETTINGS_WIRED` true once the `ConfigurationPutRequest` items are wired through
> the WP3 service — alongside the alarm/notification/button uploads); **WP14** also covers the
> WorkManager/sync-on-connect orchestration; the **activity-file fetch WP** (BLE read → WP8
> parse → `ServiceActivitySource`, also feeds `DashboardUiState.steps`); the
> `NotificationListenerService` interception + `MediaSessionManager` music control; and **WP13**
> (calendar provider read → WP9 calendar-alarm mapping for slots 16–31). The protocol/CLI
> follow-ups (routing the CLI `alarm`/`notify`/`button` commands through the shared WP5/6/7
> helpers) also remain.

> **WP16d PREP — concrete vocabulary (scanned from code, for the Buttons screen).** This is a
> reference for the WP16d implementer so the constants are NOT rediscovered. **Design decision:
> the WP16d UI is MODEL-AGNOSTIC** — it allows ALL buttons/modes/actions and does NOT gate by
> watch model (some watches have 3 dial positions, some 5; we let all be possible). The protocol
> *does* have a model concept (`ButtonCompiler.DialModel` + `availableModes`) but WP16d must NOT
> hard-code or enforce it; WP14 may use it later for hardware validation.
>
> - **`ButtonMappingEntity`** (`qhybrid.android.db`, WP4): composite PK `[watchMac, buttonId]`;
>   fields `buttonId: Int` (**0x10=TOP, 0x20=MIDDLE, 0x30=BOTTOM**, and … 0x40, 0x50 for 5-pos
>   — increments of 0x10; do NOT cap the count), `modeType: String`, `actionsJson: String`.
>   `ButtonMappingDao` has `upsert`/`upsertAll`/`delete`(whole row)/`deleteForWatch`/`getForWatch`/
>   `observeForWatch` — **add a single-row `deleteRule`-style `deleteButton(mac, buttonId)`** (and
>   `WatchRepository.deleteButton`) in the same additive style as WP16c, with a DAO test.
> - **`modeType` values** (the entity doc + DAO test use these strings): `"SINGLE_ACTION"`
>   (button fires one action), `"MUSIC_MULTIMODE"` (multi-function music control), `"CUSTOM_TOGGLE"`
>   (cycles through several dial modes on press). Centralize these + human labels in a shared
>   `ButtonModes` object (discipline like `VibePatterns`/`AlarmDays`). Treat as a flat catalog.
> - **Action catalog** = `qhybrid.protocol.buttonconfig.ConfigPayload` enum (WP7), each with a
>   human `description`. Surface these as the selectable actions (centralize id+label in a shared
>   `ButtonActions` object, OR read `ConfigPayload.values()`/`getDescription()` directly):
>   `FORWARD_TO_PHONE` ("forward to phone"), `FORWARD_TO_PHONE_MULTI` ("forward to phone
>   (multifunction)"), `MUSIC_CONTROL` ("control music (play/pause/prev/next)"), `STOPWATCH`,
>   `DATE` ("show date"), `LAST_NOTIFICATION` ("show last notification"), `SECOND_TIMEZONE`
>   ("show second timezone"), `VOLUME_UP` ("music volume up"), `VOLUME_DOWN` ("music volume down"),
>   `STEP_GOAL_COMPLETION` ("show step goal completion"), `RING_PHONE` ("ring phone").
> - **Dial modes** (the watch-face sub-eye positions a `CUSTOM_TOGGLE` cycles) =
>   `ButtonCompiler.DialMode` enum: `ALERT, TIMEZONE_2, ALARM, DATE, TWENTY_FOUR_HOUR`. (Music is
>   NOT a dial mode — it's a phone-side action.) The UI's dial-mode toggles should offer ALL of
>   these regardless of model.
> - **`actionsJson`** is a free-form JSON array string (e.g. `[{"action":"MUSIC_PLAY"}]` in the
>   DAO test). WP16d needs a small encode/decode helper that tolerates empty/malformed JSON; the
>   exact-byte compile (via WP7 `ButtonCompiler.compileMultiEntry`/`compileSingleEntryPerButton`,
>   `FossilController.compileButtons`) is for WP14 (logging/preview only if trivially safe).
> - **Protocol button compile (do NOT change its output):** `qhybrid.protocol.requests.fossil.button.ButtonCompiler`
>   + `qhybrid.protocol.ButtonConfigBuilder` + `qhybrid.protocol.buttonconfig.{ConfigFileBuilder,ConfigPayload}`;
>   golden-tested in `Wp7ButtonCompilerTest`. SETTINGS_BUTTONS file 0x0600.

**Goal:** The user-facing screens, each backed by a ViewModel reading WP4 + WP8 and writing via the service.

**Sub-parts (each independently buildable with fake data/preview):**
- **16a Dashboard**: connection status, battery, steps/goal, active-watch selector, Find Watch.
- **16b Alarms**: list/add/edit/delete (slots 0–15), day picker, weekday/weekend shortcuts.
- **16c Notifications**: app list + search, per-app vibe + hand degrees editor.
- **16d Buttons**: per-button mapping + dial-mode toggles (**model-agnostic** — allow all buttons/modes; no per-model gating).
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
