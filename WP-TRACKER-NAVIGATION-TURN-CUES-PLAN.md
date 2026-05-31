# WP-NAV — turn-by-turn cues on the watch (buzz + hand direction)

Status: **PLANNED / not started.** Idea: while a navigation app is guiding you (especially walking
in a city), the watch buzzes when a turn is coming up and **points its hands** in the turn direction
(left / right / straight / U-turn), so you know when/where to turn without looking at the phone.

This document captures the **feasibility verdict** + the implementation plan. It builds on the
WP-TRACKER package and the existing hand-control primitives.

---

## Verdict: FEASIBLE — the watch side is already solved; the nav-data side has a clean path

### Watch side — ✅ already done, golden-tested, zero new wire bytes
`FossilController` already exposes everything needed:
- `buzz(vibePattern, hourDeg, minDeg)` — **vibrate NOW + move both hands to absolute degrees**
  (0–359). This is exactly "buzz + point a direction". Golden-tested
  (`Wp6NotificationCompilerTest` HAND_MOVEMENT 0xC2; `AdapterFilePutTest`). Reuses the
  NOTIFICATION_FILTER + NOTIFICATION_PLAY path — **no new wire bytes**.
- `setHands(hourDeg, minDeg, subDeg)` — move hands directly (MoveHandsRequest).

So "point left" = move the minute hand to 270° (9 o'clock), "right" = 90° (3 o'clock),
"straight" = 0° (12), "U-turn" = 180° (6), "slight left/right" = 315°/45°, etc. A short buzz
accompanies it. The hands auto-return to the time afterward (the watch resumes timekeeping), or we
issue a follow-up `setHands` to the current time if needed.

### Nav-data side — the real question. Two dependency-free options, one clearly better.

| Source | How | Google dep? | Data quality | Verdict |
|---|---|---|---|---|
| **OsmAnd AIDL** `registerForNavigationUpdates` | bind OsmAnd's AIDL service; callback delivers **distance to next turn + turn type** as structured data | ❌ none | structured, reliable, documented | **PRIMARY** |
| **Notification parsing** (Google Maps etc.) | read the persistent nav notification via the existing WP11 NotificationListener; parse turn text/icon | ❌ none | fragile, undocumented, breaks on app updates, locale-dependent | **FALLBACK / best-effort** |
| Google Navigation SDK `TurnByTurnManager` | official structured feed | ✅ **needs paid API key + GMS + ToS** | best | **REJECTED** (defeats the zero-Google goal) |

**Decision: integrate OsmAnd via its AIDL API as the primary, first-class path.** It's:
- zero Google dependency (OsmAnd is open-source, the AIDL is LGPL-friendly, ~2 MB companion app),
- **structured** — `registerForNavigationUpdates` is documented to "notify the user about distance
  to the next turn and its type" (see `IOsmAndAidlInterface.aidl` + `osmand-api-demo`),
- great for the stated use case (walking in a city — OsmAnd is a top walking-nav app),
- robust (a real API contract, not scraped UI).

Optionally add **Google Maps notification parsing as a clearly-labelled best-effort fallback** for
users who insist on Maps — but document it as fragile (it relies on `RemoteViews`/notification
internals; ref the StackOverflow + `3v1n0/GMapsParser` findings). Recommend shipping OsmAnd first
and treating Maps parsing as a separate, optional, "may break anytime" follow-up.

---

## Architecture (mirror the WP-TRACKER two-layer discipline)

New package `qhybrid.android.navcue`, same PURE-core + injected-seam + thin-shell style as
`qhybrid.android.tracker`:

### 1. PURE core — `TurnCueMapper` (fully unit-testable, no Android)
- Input: a normalized `TurnEvent { maneuver: Maneuver, distanceMeters: Int }` where `Maneuver` is a
  small enum we define: `STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
  U_TURN, ARRIVE, UNKNOWN` (a superset that both OsmAnd turn types and Maps maneuver strings map
  onto).
- `decide(turn, config) -> TurnCue?` producing:
  - `handHourDeg` / `handMinuteDeg` — the absolute degrees to point (table below),
  - `buzzPattern` — e.g. a short double for "turn soon", a long for "turn NOW" / "arrive",
  - de-dup / debounce policy: only emit a cue when crossing a distance threshold (e.g. fire once at
    ~50 m before the turn for walking; configurable), and never re-fire the same maneuver+step.
- **Direction → degrees table** (pure, tested):
  - STRAIGHT → 0° (12 o'clock), SLIGHT_RIGHT → 45°, RIGHT → 90° (3), SHARP_RIGHT → 135°,
    U_TURN → 180° (6), SHARP_LEFT → 225°, LEFT → 270° (9), SLIGHT_LEFT → 315°, ARRIVE → some
    distinct pose (e.g. both hands to 12 + long buzz). Point the MINUTE hand (more visible); decide
    whether to also move the hour hand for clarity.
- **Maneuver normalization** (pure, tested): `OsmAndTurnType -> Maneuver` and (if added)
  `MapsManeuverString -> Maneuver`. Keep these as separate pure mappers so each source is testable
  in isolation with its exact emitted values.

### 2. PURE glue — `NavCueDispatcher`
- Holds the last-emitted state (last maneuver, last distance bucket) for debounce; on each
  `TurnEvent` runs `TurnCueMapper.decide` and forwards a non-null `TurnCue` to an injected
  `NavCueEffects` seam (`buzzAndPoint(hourDeg, minDeg, pattern)`).
- Fully unit-tested with a fake seam (mirror `TrackerDispatcherTest`): assert the debounce, the
  threshold firing, and the direction→degrees mapping end-to-end.

### 3. Seam — `NavCueEffects`
- `fun buzzAndPoint(hourDeg: Int, minDeg: Int, pattern: Int)` — production impl calls
  `WatchConnectionService` → `FossilController.buzz(pattern, hourDeg, minDeg)` (the existing
  primitive). A `NAVCUE_WIRED` flag, default false until on-device-verified. Noop default for tests.

### 4. On-device shell(s) — the source adapters (on-device-pending)
- **`OsmAndNavSource`** (primary): binds the OsmAnd AIDL service
  (`net.osmand.aidlapi.IOsmAndAidlInterface`), calls `registerForNavigationUpdates(...)` with an
  `IOsmAndAidlCallback`, converts each callback into a `TurnEvent`, and feeds `NavCueDispatcher`.
  Lifecycle: register on "start nav-cue" (a toggle / a button action), unregister on stop. Requires
  OsmAnd installed; gracefully no-op + inform the user if not.
  - Copy `OsmAndAidlHelper.java` + the `.aidl` files from `osmandapp/osmand-api-demo` into the app
    (the documented integration path). No remote dependency.
- **`MapsNotificationNavSource`** (optional fallback): reuse the WP11
  `FossilNotificationListenerService` — add a branch that, when the active notification is the Google
  Maps navigation notification, parses the turn text/icon (best-effort) into a `TurnEvent`. Clearly
  labelled fragile; behind its own toggle. Reference `3v1n0/GMapsParser` for the parsing approach.

---

## How it plugs into the existing app

- **Activation:** a global "navigation turn cues" toggle (mirror the WP-TRACKER `multiFunctionRole`
  pref pattern in `SettingsVocabulary`/`SettingsPrefs` + a Settings card). Optionally a Path-2 button
  action `TOGGLE_NAV_CUES` (same byte-identical-to-RING_PHONE trick as `LOG_WAYPOINT` /
  `SWITCH_MULTI_FUNCTION_MODE`) to start/stop cues from the watch.
- **No conflict with timekeeping:** cues are transient — buzz + point for a moment; the watch
  resumes the time. Consider a setting for how long to hold the direction before returning.
- **No new wire bytes** anywhere — `buzz(pattern, hourDeg, minDeg)` is the existing golden path.
- **No GMS, no Google API key** on the OsmAnd path.

---

## What's testable vs on-device-pending

- **Unit-tested (JVM/Robolectric):** `TurnCueMapper` (maneuver→degrees + buzz, ARRIVE, UNKNOWN
  no-op), the OsmAnd-turn-type→`Maneuver` mapper, the (optional) Maps-string→`Maneuver` mapper, and
  `NavCueDispatcher` debounce/threshold/dedup with a fake `NavCueEffects`. This is the bulk of the
  logic and can be fully green.
- **On-device-pending:** the actual OsmAnd AIDL bind + callback wiring (`OsmAndNavSource`), the live
  `FossilController.buzz(pattern,h,m)` hand movement on a real watch, and (if built) the Maps
  notification parsing. None of these is JVM-testable.

---

## Risks / caveats

- **OsmAnd must be installed** for the primary path — acceptable (it's the recommended walking-nav
  app and aligns with the user's self-hosting bent). Detect + guide if missing.
- **Google Maps parsing is genuinely fragile** — it reads undocumented notification internals
  (`RemoteViews`), is locale-dependent, and Google can break it any release. Do NOT make it the
  headline path; ship it (if at all) as an explicit best-effort extra.
- **Battery / BLE chatter:** a cue per turn is fine (turns are infrequent when walking); make sure
  the dispatcher debounces so we don't buzz every GPS tick. The threshold firing in
  `NavCueDispatcher` handles this.
- **Hand-return behavior** needs on-device tuning (does the watch snap back to time on its own after
  a notification hand-move, or do we re-issue `setHands(now)`?). Verify during the on-device session.

---

## Estimated scope

- PURE `TurnCueMapper` + `NavCueDispatcher` + mappers + tests: ~1 focused session, fully green.
- `NavCueEffects` seam + `WatchConnectionService` wiring to `buzz(pattern,h,m)`: small.
- `OsmAndNavSource` (AIDL bind + helper/.aidl import + register/unregister): the main on-device
  effort; copy from `osmand-api-demo`.
- Settings toggle (+ optional Path-2 button action): small, mirrors WP-TRACKER.
- Optional Maps notification fallback: separate, optional, "may break anytime".

No `build.gradle` dependency (OsmAnd AIDL is copied .aidl/helper, not a remote lib). No protocol /
wire change. No Google Play Services.

## References
- OsmAnd AIDL `registerForNavigationUpdates` + demo:
  https://github.com/osmandapp/osmand-api-demo (copy `OsmAndAidlHelper.java` + `.aidl`)
- OsmAnd AIDL interface: `osmandapp/OsmAnd/OsmAnd-api/.../IOsmAndAidlInterface.aidl`
- Google Maps notification parsing (fragile, fallback only): `3v1n0/GMapsParser`,
  StackOverflow "Get Google Maps Navigation Instructions"
- Google Navigation SDK turn-by-turn (REJECTED — needs key + GMS):
  https://developers.google.com/maps/documentation/navigation/android-sdk/tbt-feed
- Existing watch primitives: `FossilController.buzz(int,int,int)` / `setHands(...)` (golden-tested,
  no new wire bytes).
