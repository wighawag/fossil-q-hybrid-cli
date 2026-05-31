# WP-TRACKER — GPS wiring plan (zero Google Play Services)

Status: **IMPLEMENTED (code) / on-device verification pending.** §1–§3 are wired:
`SystemLocationSource` (platform `LocationManager`, zero GMS) is the production `LocationSource`,
`LOCATION_WIRED = true`, `ServiceTrackerDispatch` defaults to it, the manifest uncaps
`ACCESS_COARSE_LOCATION`, and `MainActivity` (Setup) drives the two-step foreground+background
location runtime flow via `TrackerLocationAccess` (with the Play disclosure copy). §4 (loud phone
ring) is also done: `SystemPhoneRinger` + the `PhoneRinger` seam + the pure `RingPolicy`. Pure
helpers are unit-tested (`SystemLocationSourceTest`, `TrackerLocationAccessTest`, `RingPolicyTest`);
the actual `LocationManager` / `MediaPlayer` calls need the on-device checklist below.

The waypoint feature originally shipped with the GPS behind a seam (`LocationSource`,
`LOCATION_WIRED = false`); it now uses the real fix via the **platform `LocationManager`** — **no
`play-services-location`, no GMS dependency**, so it works on de-Googled phones (GrapheneOS / e/OS /
non-GMS ROMs).

Everything here is on-device work: a real location call cannot be meaningfully unit-tested on the
JVM/Robolectric, which is exactly why it was seam-isolated. The pure routing/decision logic
(`TrackerController` / `TrackerDispatcher` / `ButtonActionRouter` / `EventRouter`) is already green
and does NOT change.

---

## Decision: platform `LocationManager`, NOT FusedLocationProviderClient

| Option | Dependency | Works on non-GMS | Fusion quality | Verdict |
|---|---|---|---|---|
| Google `FusedLocationProviderClient` | `play-services-location` (+ GMS) | ❌ no | best (continuous) | rejected |
| Platform `LocationManager` | none (AOSP) | ✅ yes | fine for one-shot fixes | **chosen** |

Rationale: this is a coin-cell-watch companion for a self-hosting user; the feature is a single
discrete fix on a button press, not turn-by-turn navigation. The OS-level provider is plenty, and
adding GMS would be a real footprint + supply-chain + non-GMS-incompatibility cost for no benefit
here.

**Provider selection (best → fallback):**
- API 31+ (`Build.VERSION_CODES.S`): prefer `LocationManager.FUSED_PROVIDER` — this is **Android's
  own** fused provider, dependency-free, NOT Google's.
- Older / when FUSED unavailable: `LocationManager.GPS_PROVIDER` (and optionally
  `NETWORK_PROVIDER` as a coarse fallback when GPS has no fix yet).

---

## Required changes

### 1. `SystemLocationSource` — the production `LocationSource` impl

File: `android/src/main/java/qhybrid/android/tracker/ServiceTrackerDispatch.kt`
(referenced as `ServiceTrackerDispatch.SystemLocationSource` in the seam KDoc), or a standalone
`SystemLocationSource.kt` in the same package.

Implements `LocationSource.currentFix(): LocationSource.Fix?`. Strategy — "last-known + a single
high-accuracy update with a timeout", all dependency-free:

1. Resolve `LocationManager` (`context.getSystemService(LOCATION_SERVICE)`).
2. **Permission guard:** if `ACCESS_FINE_LOCATION` is not granted →
   `Log.w` + return `null` (never throw, never request from here — the request is a UI concern,
   see §3). `currentFix()` is called on the IO worker, so it must be self-contained.
3. **Fast path:** read `getLastKnownLocation(provider)` for each candidate provider; if it's recent
   enough (e.g. `< STALE_FIX_MS`, suggest 60_000) and accurate enough, use it immediately.
4. **One-shot fresh fix with timeout:** otherwise request a single update and block the worker up to
   `FIX_TIMEOUT_MS` (suggest 10_000):
   - API 30+: `getCurrentLocation(provider, cancellationSignal, executor, consumer)` — the modern
     one-shot API; wrap in a `CountDownLatch` / `runBlocking` with `withTimeout` since the seam is a
     blocking call on the IO scope.
   - API < 30: `requestSingleUpdate(provider, listener, looper)` (deprecated but works), guarded by
     a timeout; remove the listener on completion/timeout.
5. Map the resulting `android.location.Location` → `LocationSource.Fix(lat, lon, accuracyM,
   timestamp)` using `location.latitude/longitude/accuracy/time`. `accuracy` → `accuracyM` (null if
   `!hasAccuracy()`).
6. On no provider / no fix / timeout → return `null` (the dispatcher already handles null = "no fix
   logged", graceful).

Threading note: `currentFix()` is already called from `recordWaypointAsync` on the injected IO
scope (`ServiceTrackerDispatch.io`), so blocking-with-timeout is acceptable. Do NOT call it on the
main/ble-gatt thread.

Wire it in: change the `ServiceTrackerDispatch` constructor default from
`location: LocationSource = NoopLocationSource` to default to `SystemLocationSource(context)` (keep
the param injectable so tests still pass a fake). Flip `LocationSource.LOCATION_WIRED = true`.

### 2. Manifest — one small correctness fix

File: `android/src/main/AndroidManifest.xml`. The location *declarations* are already present
(`ACCESS_FINE_LOCATION` uncapped, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, FGS
type `connectedDevice|location`). **One gap remains:**

- **Uncap `ACCESS_COARSE_LOCATION`** (currently `android:maxSdkVersion="30"`). On API 31+ a runtime
  `ACCESS_FINE_LOCATION` request should be paired with `ACCESS_COARSE_LOCATION` so the system can
  show the Precise/Approximate toggle correctly; without COARSE declared on 31+, the fine request
  can behave oddly. Change to an uncapped `<uses-permission android:name="…ACCESS_COARSE_LOCATION"/>`.

(No new manifest entries beyond this — `LocationManager` needs no extra permission, and definitely
no GMS metadata.)

### 3. Runtime permission flow (the genuinely on-device part)

The existing `MainActivity.requiredPermissions()` only requests BLUETOOTH_* (31+) / FINE (≤30) for
BLE — it does **not** cover the tracker's needs on 31+. Two grants are needed, in the correct order:

- **Foreground location** (`ACCESS_FINE_LOCATION` [+ `ACCESS_COARSE_LOCATION` on 31+]): request via
  the normal runtime-permission API (mirror the existing `requiredPermissions()` / `hasPermissions()`
  flow in `MainActivity`). Add a tracker-permission gate, ideally surfaced from the Settings →
  Multi-function role card or the Waypoints screen ("Tracker needs location permission").
- **Background location** (`ACCESS_BACKGROUND_LOCATION`): on API 30+ this is a **separate, second**
  request that can ONLY be asked *after* foreground location is already granted (the OS enforces the
  two-step flow; you cannot bundle them). On API 30+ it also typically routes the user to the app's
  settings page to pick "Allow all the time". Needed because logging happens while the phone is
  pocketed under the foreground service.
  - **Play-sensitive:** `ACCESS_BACKGROUND_LOCATION` requires a prominent in-app disclosure + a
    privacy-policy justification at Play submission ("log GPS waypoints on a watch button press
    while the app runs in the background as a foreground service"). Write that copy as part of this.
  - If you choose to NOT ship background location: gate it so waypoint logging only works while the
    app is foregrounded, and drop the `ACCESS_BACKGROUND_LOCATION` + the background half of the FGS
    location justification. (Simpler Play review; weaker feature — pocketed logging won't fire.)

### 4. Loud phone ring — IMPLEMENTED

`ServiceTrackerDispatch.ringPhone()` now drives a real loud ring via a new injectable `PhoneRinger`
seam:
- `SystemPhoneRinger` (modelled on Gadgetbridge `FindPhoneActivity`): a looping default ringtone on
  `STREAM_ALARM` at **max volume** (restored on stop) + a looping waveform vibration, both with
  `AudioAttributes.USAGE_ALARM` so they ride the alarm path (DND-bypass where the OS allows alarms).
  Auto-stops after a **user-configurable** duration (`AppSettings.ringDurationSeconds`, default
  **60s / 1 min**, range 5s–300s, persisted in `SharedPreferencesSettingsPrefs`, editable from the
  Settings → "Find-my-phone ring" stepper) so a pocketed phone can't ring forever. Zero GMS (AOSP
  `MediaPlayer`/`AudioManager`/`Vibrator` only).
- `RingPolicy.autoStopMillis(seconds)` (pure: clamp + seconds→millis) + max-volume + looping
  waveform are unit-tested (`RingPolicyTest`, `SettingsVocabularyTest`); the dispatch ring path is
  exercised via the fake-`PhoneRinger` seam.
- Manifest: added `VIBRATE` (phone-side vibration; the watch buzz-back over BLE needs no permission).
- `TrackerEffects.TRACKER_EFFECTS_WIRED` flipped to `true`.

No new wire bytes; buzz-back already works. The ring is a **toggle**: a repeated trigger (a second
LONG gesture / RING_PHONE press) STOPS it (`PhoneRinger.toggle`, unit-tested in
`PhoneRingerToggleTest`); it also still auto-stops after 30s.

---

## What stays unchanged (already tested, do NOT touch)

- `TrackerController` / `TrackerDispatcher` (Part A 0x05 gestures + buzz-back)
- `ButtonPressParser` / `ButtonActionRouter` (Part B 0x08 single-press routing)
- `EventRouter` (role-based 0x05 routing)
- `WaypointEntity` / `WaypointDao` / repo methods / DB v4 migration
- `GpxWriter` + `WaypointsScreen` / `WaypointsViewModel` export
- `LocationSource` interface + `NoopLocationSource` (only the production impl + the constructor
  default + the `LOCATION_WIRED` flag change)

---

## Test plan

- **Unchanged JVM tests** stay green (the seam means no pure logic changes). Confirm
  `:android:testDebugUnitTest` still passes after flipping the default.
- **Optional JVM test:** a small `SystemLocationSource` fix-mapping helper (Location → Fix) could be
  factored pure and unit-tested; the actual `LocationManager` call cannot.
- **On-device (the real verification), no GMS phone ideally):**
  1. Grant foreground location; press a TRACKER-role short gesture → a MINOR waypoint with real
     lat/lon appears in the Waypoints screen + buzz-back 5.
  2. Double = MAJOR + buzz 6; long = ring + buzz 8.
  3. `LOG_WAYPOINT` button (0x08) single press → MINOR + buzz.
  4. Pocket the phone (background) → grant background location → confirm a gesture still logs.
  5. Export GPX → opens in a map app with the right points.
  6. Verify on a **non-GMS** phone that fixes still arrive (proves the zero-Google-dep goal).

---

## Estimated scope

- `SystemLocationSource` (one-shot + timeout + provider selection): ~60–90 lines.
- Manifest one-liner (uncap COARSE).
- Runtime permission flow + disclosure copy: the bulk of the UX work; on-device-iterative.
- Loud ring: separate small item.

No `build.gradle` change. No new dependency. No protocol/wire change.
