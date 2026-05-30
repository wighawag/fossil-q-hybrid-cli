# WP-FILEPUT-RELIABLE — Establish a reliable, verifiable file-PUT completion on Android

You are working on **fossil-q-hybrid-cli** at
`/home/wighawag/dev/github/wighawag/fossil-q-hybrid-cli`.

## The real problem (why this WP exists)

The Android app cannot reliably tell whether a BLE **file-PUT committed** on the watch. Everything
downstream (notification filter, buttons, alarms, and the manual "Vibrate" buzz) depends on
file-PUTs, so this is THE foundational bug. We spent many on-device cycles patching the buzz at the
transport layer and kept failing because the underlying file-PUT **completion handshake was
implemented incorrectly** — we were guessing at the protocol instead of matching the firmware.

We have now decoded the **official Fossil app's** file-PUT state machine (deobfuscated sources under
`tmp/FossilOfficialApp-deobf`). This WP re-implements our `:protocol` file-PUT to match it, so a PUT
has a trustworthy success/failure signal on BOTH transports (Android GATT + Linux BlueZ/D-Bus). Once
PUTs are verifiable, the buzz (and a later play-only buzz design) becomes trivial and reliable.

> The **CLI works today** over BlueZ — do NOT regress it. BlueZ paces writes and delivers the
> control responses implicitly; Android's GATT stack does not, which is why the incorrect handshake
> only bit on Android.

## GROUND TRUTH — the official app's file-PUT protocol (from `tmp/FossilOfficialApp-deobf`)

Control channel = **FTC characteristic `3dda0003`** (write-with-response / INDICATE). Data channel =
**`3dda0004`** (write-WITHOUT-response). Operation codes
(`com/fossil/blesdk/device/logic/request/code/FileControlOperationCode.java`):

```
GET_FILE=1  LIST_FILE=2  PUT_FILE=3  VERIFY_FILE=4  GET_SIZE_WRITTEN=5
VERIFY_DATA=6  ERASE_DATA=7  EOF_REACH=8  ABORT_FILE=9  WAITING_REQUEST=10  DELETE_FILE=11
```
A **response** frame = `operationCode | 0x80` (so PUT→`0x83`, VERIFY→`0x84`, EOF_REACH→`0x88`,
WAITING_REQUEST→`0x8A`, ABORT→`0x89`). Control frames are little-endian:
`[opByte][handle:2][status:1][...]`. (`FileControlRequest.java` parses status at offset 3, handle at
offset 1; `WAITING_REQUEST` carries a firmware-proposed timeout as a uint32 at offset 5.)

**The PUT sequence the official app actually runs** (`TransmitDataPhase.java`,
`PutFileRequest`, `TransferDataRequest`, `VerifyFileRequest.java`, `FileControlRequest.java`):

1. **PUT_FILE (op 3)** request on `3dda0003`: declares offset + remaining length + total length.
   Watch replies `0x83` (accept).
2. **Transfer data** on `3dda0004` (write-without-response, paced; chunk = MTU-bounded).
3. The watch reports progress/finish via **EOF_REACH (`0x88`)** / **WAITING_REQUEST (`0x8A`)** frames
   carrying **sizeWritten + a CRC32 of the bytes received**. The app compares that CRC to its OWN
   `Crc32Calculator.CRC32` of the data it sent (`TransmitDataPhase.m11139a`).
   - If **CRC matches AND sizeWritten == totalLength** → go to step 4 (**VERIFY_FILE**).
   - If sizeWritten < total (or CRC mismatch) → **loop back to PUT_FILE(3)** to continue from the
     written offset (retry, bounded: `m11144q` aborts after 3 no-progress retries with
     `DATA_TRANSFER_RETRY_REACH_THRESHOLD`).
4. **VERIFY_FILE (op 4)** request on `3dda0003` (`VerifyFileRequest`, a `FileControlRequest` with
   **retryThreshold = 3**): write `[0x04][handle]`, then **WAIT for the `0x84` response with
   SUCCESS status**. THIS is the real "file committed" confirmation. It is a proper
   request/response WITH RETRIES — **not** a fire-and-forget "close".

**Key corrections vs. our current `FilePutRawRequest` (which we implemented by guessing):**
- We treated op **8** ("type-8 CRC confirm") as completion. It is **EOF_REACH** — a progress/finish
  report carrying the watch's CRC, NOT the final ack. Completion is the **VERIFY_FILE(4) → 0x84
  success** exchange.
- We treated op **4** as a fire-and-forget "close". It is **VERIFY_FILE**, a request we must SEND and
  then WAIT for its `0x84` success response (with retries).
- We never compared the watch's reported CRC (in the 8/10 frame) to our own; the official app does,
  and uses it to decide continue-vs-verify.
- The watch may send **WAITING_REQUEST(0x8A)** telling us how long to wait — we currently ignore it.

This explains every on-device symptom we saw (search the git log for `wp-buzztest`): the watch sent
`0x88` (EOF_REACH) and `0x8A`/`0x84`, we mis-handled them, never sent a proper VERIFY, so the watch
sat in its internal timeout (~10s) and then **ABORT_FILE(0x89 / "type-9")**'d the transfer.

## Files to read FIRST

Official app (ground truth):
- `tmp/FossilOfficialApp-deobf/sources/com/fossil/blesdk/device/logic/request/code/FileControlOperationCode.java`
- `tmp/.../device/logic/request/code/FileControlStatusCode.java`, `ResponseStatusCode.java`
- `tmp/.../device/logic/request/file/FileControlRequest.java` (frame format + WAITING_REQUEST timeout)
- `tmp/.../device/logic/request/file/PutFileRequest.java`, `VerifyFileRequest.java`
- `tmp/.../device/logic/request/TransferDataRequest.java` (data-chunk write path/pacing)
- `tmp/.../device/logic/phase/TransmitDataPhase.java` (THE orchestration: PUT→data→CRC-check→
  loop-or-VERIFY; the retry thresholds; `m11139a` CRC compare; `m11144q` retry)
- `tmp/.../device/logic/phase/TransferFileDataPhase.java`, `PutWatchParameterFilePhase.java`
- `tmp/.../device/core/command/WriteCharacteristicCommand.java` (uses `setValue`+`writeCharacteristic`;
  write type comes from the characteristic; the app does NOT set it per-write)

Our code:
- `protocol/.../requests/fossil/file/FilePutRawRequest.java` — the current (incorrect) state machine.
  Note the recent `wp-buzztest` commits: type-8-completion, foreign-handle guard, fire-and-forget
  close. **This WP supersedes those heuristics with the correct VERIFY-based completion.**
- `protocol/.../requests/fossil/file/FilePutRequest.java`, `FileHandle.java`, `ResultCode.java`
- `protocol/.../FossilQAdapter.java` — `queueWrite`, the serial `requestQueue`/`queueNextRequest`,
  `onCharacteristicChanged` dispatch, `restartTimeout`/`REQUEST_TIMEOUT_SECS` (currently 30s),
  `currentFossilRequest.isFinished()`.
- `protocol/.../WriteBatch.java`, `BleTransport.java` (note `writeCharacteristicNoWait` added for the
  old close hack — re-evaluate; you may not need it once VERIFY is correct).
- `android/.../AndroidBleTransport.kt` — `writeCharacteristic(awaitResponse)`, the no-response
  pacing/retry (`NO_RESPONSE_PACING_MS`, `NO_RESPONSE_MAX_RETRIES`), `OP_TIMEOUT_MS=10s`,
  `onCharacteristicWrite`/`writeLatch`, the one-outstanding-op constraint.
- Tests: `protocol/src/test/java/qhybrid/protocol/AdapterFilePutTest.java`,
  `AdapterButtonPutTest.java`, `ControllerBuzzTest.java`, `FossilControllerInitTest.java`,
  `FakeBleTransport.java`, `FileTransferResponder.java` (the harness to extend for the real flow).
- `FINDINGS.md` (search "file", "PUT", "Notification Filter").

## What to implement

Re-own `FilePutRawRequest` (and the test responder) to the **correct** handshake:

1. **PUT_FILE(3)** open → on `0x83` accept, transmit data chunks on `3dda0004` (keep the existing
   no-response pacing/retry — that part is right).
2. On **EOF_REACH(`0x88`)** / **WAITING_REQUEST(`0x8A`)**: read sizeWritten + the watch's CRC32.
   Compare to our own CRC32 over the bytes sent.
   - bytes complete + CRC match → send **VERIFY_FILE(4)** and await its `0x84` SUCCESS.
   - otherwise → resume PUT_FILE(3) from the written offset (bounded retry, mirror the official
     3-retry / no-progress threshold). (A simple first cut MAY treat partial/mismatch as failure if
     full resume is too big — but document it; the watch generally sends all-or-timeout for our
     small files.)
3. **VERIFY_FILE(4)**: send `[0x04][handle]` on `3dda0003`, **wait for `0x84` SUCCESS** (this is
   `onFilePut(true)`), with a bounded retry (≈3) and honor the WAITING_REQUEST proposed timeout if
   present. Only `0x84`-SUCCESS completes the PUT. ABORT(`0x89`) / non-success status → `onFilePut(false)`.
4. Keep the strictly-serial queue; ensure VERIFY's response advances the queue (so a following PUT —
   e.g. a play file — runs only after the previous PUT truly verified).
5. Re-evaluate the `writeCharacteristicNoWait` hack: with a correct VERIFY exchange you likely send
   VERIFY as a normal write-with-response and WAIT for `0x84`, so the old fire-and-forget close is
   probably removed. Keep the foreign-handle guard if still useful, but prefer correctness over the
   accumulated heuristics. Do not regress the no-response DATA pacing/retry (that fix is correct).

**No new wire bytes beyond matching the official protocol.** You are aligning to the firmware's real
contract, not inventing anything.

## Tests (provable core)

Extend `FileTransferResponder` / `FakeBleTransport` to emulate the REAL watch side:
PUT accept(`0x83`) → (receive chunks) → **EOF_REACH(`0x88`) with correct sizeWritten + CRC32 of the
received payload** → VERIFY response(`0x84`) SUCCESS. Then:
- A PUT completes its future/`onFilePut(true)` ONLY after the `0x84` VERIFY-success (NOT on `0x88`).
- CRC mismatch in the `0x88` frame → resume/abort path (assert it does not falsely succeed).
- ABORT(`0x89`) → `onFilePut(false)`.
- Two sequential PUTs (e.g. filter then play): the second only starts after the first VERIFY-succeeds,
  and a stale/late control frame for the first handle does not corrupt the second (keep/verify the
  handle guard).
- Update `AdapterFilePutTest`, `AdapterButtonPutTest`, `ControllerBuzzTest`, `FossilControllerInitTest`
  to the corrected flow. Lock the golden filter/play/button bytes unchanged (the FILE CONTENTS don't
  change — only the control handshake does).

## Gates (run before EVERY commit; all must pass)

- `./gradlew :protocol:test` green (report count; currently 113).
- `./gradlew :android:testDebugUnitTest` green, 0 failures (trust the XML count, not banners):
  ```
  python3 -c "import glob,xml.etree.ElementTree as ET; t=f=e=0
  [ (t:=t+int(r.get('tests',0)), f:=f+int(r.get('failures',0)), e:=e+int(r.get('errors',0))) for x in glob.glob('android/build/test-results/testDebugUnitTest/*.xml') for r in [ET.parse(x).getroot()]];
  print(t,f,e)"
  ```
  (currently 242; report new count.)
- `./gradlew :android:assembleDebug` + `:android:lintDebug` succeed.
- `./gradlew :cli:shadowJar` succeeds AND `./fossil-q --help` md5 == `7533ceccb6b29f81f6172bd5a71c5b98`
  (rebuild the jar so the CLI re-test runs the new code).

## Workflow

- Per `/home/wighawag/.pi/agent/AGENTS.md`: this WP authorizes the gradle/test/commit commands and
  per-sub-part commits (overriding the general "no auto-commit" rule). Do NOT push.
- Discrete sub-parts, commit after each with gates green:
  (1) decode + lock the op-code/frame/status constants + the corrected `FilePutRawRequest` VERIFY
  completion + extend `FileTransferResponder`; (2) update existing PUT tests to the real flow;
  (3) remove/trim the now-unneeded close heuristics. Message: `wp-fileput: <sub-part>`.
- After each commit: `git log --oneline -1` + `git show --stat HEAD`. An external auto-commit may
  claim staged changes with a different message — if the diff is right, that's fine; don't rewrite
  history, just report it.
- Prefer `read`/`grep`/`find`/`ls` over `bash` for exploration.

## On-device verification (the user runs it — give the recipe)

Watch is **single-bond** (phone OR Linux, not both).
1. **CLI (phone BT off):** `./fossil-q --verbose notify --vibe 5` must STILL buzz; verbose log should
   show PUT → data → EOF_REACH(`0x88`) → VERIFY(`0x84` success) → `FilePut COMPLETE` for both the
   filter (`0x0C00`) and play (`0x0900`) files.
2. **Android:** install `android/build/outputs/apk/debug/android-debug.apk`, `adb logcat > save.log`.
   Settings → Test vibration → Vibrate (single)/(triple). Expect each file-PUT to reach VERIFY
   success (`0x84`) with NO `0x89`/ABORT and NO ~10s stalls → the watch BUZZES (single vs triple).
   Capture `save.log` and report the `FilePut[...]` sequence.

## Acceptance

- `:protocol` file-PUT completes ONLY on a real VERIFY_FILE(`0x84`) success, matching the official
  app; CRC-mismatch/ABORT are honest failures; the serial queue advances correctly so sequential
  PUTs work. Golden FILE bytes unchanged (no new wire bytes). CLI still buzzes; Android file-PUTs
  (filter/buttons/alarms) and the manual buzz work on-device. Gates green at each commit; no push.

## After this WP

With reliable PUTs, revisit the manual buzz: the existing filter+play (two PUTs) should now work
because each PUT truly verifies before the next starts. The **play-only** optimization
(`WP-BUZZ-PLAYONLY-PROMPT.md`) becomes a clean follow-up (upload reserved pattern filters once at
connect; buzz = single verified play PUT), but is no longer required for correctness.

## Do NOT

- Do NOT push to git.
- Do NOT change the FILE byte formats (filter/play/button/alarm/config) — only the control handshake.
- Do NOT regress the no-response DATA-chunk pacing/retry, the single-link-writer model, or the CLI.
- Do NOT implement auto-buzz-on-strength-change (possible future WP-VIBEACK).
```
