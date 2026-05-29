# WP-REOWN — Re-own the Protocol Layer (Phase 2, done properly)

> **Status:** ✅ DONE & HARDWARE-VERIFIED. The protocol layer is now owned,
> platform-neutral Java in `qhybrid.protocol.*` (deps: slf4j only). The vendored
> GadgetBridge tree, the `android/`/`androidx/`/`nodomain/` shims, `sync.sh`, and
> the `androidStrippedJar`/`androidApi` Gradle machinery are all removed. Both
> `:cli` and `:android` use a plain `project(':protocol')`. WP1
> (`FossilController` façade + `FakeBleTransport` + golden tests) delivered as a
> byproduct. Disk config moved to `:cli`; settings are passed in via
> `qhybrid.protocol.model.{SyncSettings, NotificationFilterEntry}`. Provenance:
> `PROTOCOL-PROVENANCE.md` + `NOTICE` (GadgetBridge GPLv3 @ `f5b5416`). All 4
> acceptance gates pass; CLI verified on a Q Commuter across all command clusters.
> This superseded the "vendored GadgetBridge, never modified + `sync.sh`" approach.

## Why (the decision)

The current `:protocol` module keeps GadgetBridge's qhybrid request classes
**byte-for-byte vendored and untouched**, compiled against a hand-written shim
layer that fakes Android APIs (`android/*`, `androidx/*`) plus GB-specific
classes (`nodomain/*`: `GBDevice`, `TransactionBuilder`, `QHybridSupport`, `GB`,
`DeviceSupport`, `WatchAdapter`, `FossilWatchAdapter`). This made the CLI work,
but introduced a whole apparatus that fights us the moment Android needs the
**real** Android APIs:

- We maintain two stub trees + a "shim-stripped jar" variant so `:android` sees
  real SDK classes while `:cli` sees stubs.
- We still hit API-shape mismatches between our stubs and real Android (e.g.
  `new BluetoothGattCharacteristic(uuid)` exists on our stub but NOT on Android,
  which has only the 3-arg constructor — this crashed WP0.5 at runtime).

The "free upstream updates via `sync.sh`" benefit is **largely theoretical** for
this protocol: the Fossil Q Hybrid (coin-cell) protocol is mature/frozen, and our
`FossilQAdapter` has **already deliberately diverged** from GB (corrected weekday
bitmask, null-terminated CRC, 32-alarm limit, official-app notification-filter
format). We are not actually tracking upstream for these classes.

**Decision:** re-own the protocol code as our own clean, platform-neutral Java
that depends ONLY on `BleTransport` + `byte[]` + `UUID` (no Android, no Linux).
Delete everything we don't use. Preserve provenance for attribution and so we can
backport improvements to GadgetBridge if we ever want to.

## Goals

1. `:protocol` becomes **pure platform-neutral Java**: no `android.*` imports, no
   `androidx.*` imports, no stub trees, no `sync.sh`, no shim-stripped jar.
2. The ONLY platform seam remains `BleTransport` (implemented by `BluezTransport`/
   `DbusTransport` on CLI and `AndroidBleTransport` on Android).
3. The protocol surface is driven through a small façade (`FossilController` —
   this also delivers **WP1**) so both CLI and Android call the same entry point.
4. **CLI behaves byte-for-byte identically** to today (regression gate).
5. The Android app links against `:protocol` directly with NO special Gradle
   config (drop the `androidApi` / `androidStrippedJar` machinery).

## Non-goals

- Do NOT rewrite the BLE transports (CLI BlueZ, Android GATT) — only the protocol.
- Do NOT change wire formats. This is a refactor to OWN the code, not to change
  watch behavior. Any byte change is a bug.
- Do NOT add features (alarms/notif/buttons logic stays as-is in behavior).

## License (must settle first, but already favorable)

- This repo is **AGPLv3**. GadgetBridge is **GPLv3**. GPLv3 code may be combined
  into an AGPLv3 work; the combined result is governed by AGPLv3. Re-owned files
  remain copyleft (AGPLv3) — re-owning is NOT relicensing.
- **Each re-owned file gets a provenance + license header**, e.g.:
  ```
  // Derived from GadgetBridge:
  //   app/src/main/java/.../qhybrid/requests/fossil/file/FilePutRawRequest.java
  //   @ commit 4e1b5eb (GPLv3). Adapted and re-owned for fossil-q-hybrid.
  // This file is part of fossil-q-hybrid, licensed AGPLv3.
  ```
- Keep `tmp/Gadgetbridge/` as a **read-only reference** (already gitignored) and
  record the source commit in a `PROTOCOL-PROVENANCE.md` (path → GB path → commit).
- Action item: add a short NOTICE/attribution section to the repo crediting
  GadgetBridge for the protocol reverse-engineering.

## Scope of code to re-own (what we actually use)

Re-own ONLY the classes the CLI exercises today. From audit, the live set is the
Fossil 2.x path: device-info reads, config get/put, file lookup/put/get/close/
verify/delete (`3dda0003/04`), alarms, buttons, notifications, activity download,
auth handshake (`3dda0005`), async events (`3dda0006`).

**Delete entirely (unused):**
- All `fossil_hr/**` (HR-watch only — already excluded from the build).
- `FirmwareFilePutRequest` (OTA — excluded), encrypted file requests, misfit
  legacy requests not used by the CLI, OTA/erase requests.
- The entire shim trees once nothing imports them:
  `src/main/java/android/**`, `src/main/java/androidx/**`, and the `nodomain/*`
  shims (`GBDevice`, `TransactionBuilder`, `QHybridSupport`, `GB`, `DeviceSupport`,
  `WatchAdapter`, `FossilWatchAdapter`, `GenericItem`, `NotificationSpec`,
  `StringUtils`) — folded into clean owned equivalents or removed.

**Keep (already pure Java, just relocate/own):** `CRC32C`, `Version`,
`NotificationConfiguration` data class, `ConfigPayload`/`ConfigFileBuilder`/
`RLEEncoder` (buttonconfig), `FileHandle`, `ResultCode`.

## Target shape

```
:protocol (pure Java, deps: slf4j-api only)
  qhybrid.protocol/
    BleTransport                 (the seam — unchanged)
    FossilController             (façade — also satisfies WP1)
    FossilQAdapter               (request queue/init/auth — re-owned, no Android types)
    requests/…                   (owned request classes: only what we use)
    file/  buttonconfig/  encoder/   (owned, relocated)
    util/ (CRC32C, Version)
    model/ (small owned data classes replacing GenericItem/NotificationSpec)
    NotificationConfiguration, FileHandle, ResultCode
  (NO android/  NO androidx/  NO nodomain/  NO sync.sh)
```

`TransactionBuilder`'s only real job was "accumulate writes, flush to transport"
— fold that into the adapter or a tiny owned `WriteBatch` that talks to
`BleTransport`. `BluetoothGattCharacteristic` usage collapses to just a `UUID`.

## Execution plan (incremental, behind the seam, CLI as continuous regression)

1. **Provenance + license headers** scaffolding; `PROTOCOL-PROVENANCE.md`; NOTICE.
2. **Lock behavior with golden tests FIRST** (do this before touching code):
   - Build `FakeBleTransport` (this is also WP1) and golden-byte tests from
     `FINDINGS.md` (#12 alarms, #17 notif filter) + `activity.bin`/`activity-test.bin`
     parsing assertions. These capture current correct output.
3. **Collapse Android types**: replace `BluetoothGattCharacteristic` with `UUID`
   in owned code; remove `TransactionBuilder` → owned write path. Re-own request
   classes one cluster at a time (file → config → alarms → buttons → notif →
   activity → auth/events), keeping each green against golden tests + CLI.
4. **Delete shim trees** (`android/`, `androidx/`, `nodomain/`) once unreferenced.
5. **Drop Gradle machinery**: remove `androidStrippedJar`/`androidApi` config;
   `:android` and `:cli` both `implementation project(':protocol')`.
6. **Remove `sync.sh`** (or repurpose to a one-shot "diff against upstream" helper).
7. **Relocate** to a `qhybrid.protocol` package (optional but cleaner).

## Acceptance tests

- **No Android/Linux deps in `:protocol`:** grep shows zero `import android.`,
  zero `import androidx.`, zero `nodomain.` references.
- **Golden bytes unchanged:** every alarm/notif/button/activity golden test passes
  identically before and after.
- **CLI identical:** `./fossil-q --help` + a hardware run of `info`, `alarm set`,
  `notify`, `activity` behave exactly as today (manual on-watch regression).
- **Android:** `:android:assembleDebug` with a PLAIN `project(':protocol')` dep
  (no stripped jar); on-device connect/auth/read still works.
- **Build:** `:protocol:compileJava`, `:cli:shadowJar`, `:android:assembleDebug`,
  `:protocol:test` all green.

## Risks & mitigations

- **Protocol regression** (vendored code is hardware-proven): mitigate with the
  golden-byte tests built in step 2 BEFORE any rewrite, plus CLI-on-watch checks.
- **Hidden coupling** surfacing (like the `Main.VIBE_PATTERN_NAMES` and the
  config-file loading we already found): do it incrementally, compile often.
- **Scope creep**: this is a refactor, NOT a behavior change. Reject any "while
  we're here" feature work.

## Relationship to other WPs

- **Delivers WP1** (FossilController façade + FakeBleTransport) as a byproduct.
- **Unblocks** clean WP2 (AndroidBleTransport hardening) — no more shim/strip games.
- Should happen **before** heavy Android feature WPs (WP4+) so they build on the
  clean protocol surface.
```
