# Lyrion (LMS) Music Control — Plan

> Goal: let the watch's music gestures control a **Lyrion Music Server** (formerly
> Logitech Media Server) player over the network, as an **alternative backend** to the
> existing local Android media-session control. Pick a server + one target player in
> Settings; then a watch gesture (play/pause/next/prev/vol) talks to the server and
> starts/controls music on that device.

Reference implementation studied: [kaaholst/android-squeezer](https://github.com/kaaholst/android-squeezer)
— it connects to a server (host:port), discovers players, lets the user pick the
*active* player, and controls it via the LMS protocol (CometD over HTTP, JSON-RPC, or
the raw CLI on port 9090).

---

## 1. Background — how LMS control works

LMS exposes the **same command vocabulary** through three transports:

| Transport | Port | Shape | Notes |
|-----------|------|-------|-------|
| **JSON-RPC over HTTP** | 9000 (HTTP) | `POST /jsonrpc.js` body `{"id":1,"method":"slim.request","params":["<playerid>",[<cmd>...]]}` | **Simplest for us.** Stateless, no persistent socket, easy with `HttpURLConnection`/OkHttp. |
| Raw CLI (telnet) | 9090 | line-based, percent-escaped | What Squeezer historically used; needs a socket. |
| CometD long-poll | 9000 | subscriptions/streaming | What Squeezer 2.x uses for live updates. Overkill for us. |

`<playerid>` is normally the player's **MAC address** (e.g. `00:04:20:ab:cd:ef`).

### Commands we need (all via `slim.request`)
| Watch action | LMS params |
|--------------|-----------|
| PLAY / resume | `["play"]` |
| PAUSE | `["pause","1"]` (or `["pause"]` to toggle) |
| TOGGLE_PLAY_PAUSE | `["pause"]` (no arg = toggle) — or read `mode` first |
| NEXT | `["playlist","index","+1"]` (or `["button","jump_fwd"]`) |
| PREVIOUS | `["playlist","index","-1"]` (or `["button","jump_rew"]`) |
| VOLUME_UP | `["mixer","volume","+5"]` |
| VOLUME_DOWN | `["mixer","volume","-5"]` |
| (power on so it can play) | `["power","1"]` |

### Discovery / status (for the Settings picker)
- **List players:** `{"id":1,"method":"slim.request","params":["",["players","0","999"]]}`
  → returns `playerid`, `name`, `model`, `connected`, `power`, `isplaying` per player.
- **Player status (for "now playing" / verify reachable):**
  `["<playerid>",["status","-","1","tags:al"]]`.
- **Server reachability:** `["",["version","?"]]`.
- **Auto-discovery (optional, like Squeezer):** UDP broadcast on port 3483, or just
  let the user type `host:port`. Start with manual entry; add discovery later.

---

## 2. Current architecture (what we plug into)

The music path is already cleanly seamed (WP12):

```
Watch gesture (0x05 MUSIC_EVENT)
  → FossilQAdapter.handleMusicEvent  → JSON {"type":"music","action":"NEXT",...}
  → FossilController.onEventJson
  → WatchConnectionService.routeEventJson  (role: MUSIC vs TRACKER)
      → musicDispatch.onEventJson(json)              ← ServiceMusicDispatch
          → MusicController.parse(json) → MusicAction (pure)
          → MusicController.decide(...) → Decision   (pure)
          → MusicSessionDispatcher.dispatch(action)  ← SEAM (interface)
              prod = SystemMusicSessionDispatcher (MediaController + AudioManager)
```

Key files:
- `android/.../music/MusicController.kt` — pure parse + decide (reusable as-is).
- `android/.../music/MusicDispatcher.kt` — glue + `MusicSessionDispatcher` **interface seam**.
- `android/.../music/ServiceMusicDispatch.kt` — production Android-media-stack impl.
- `android/.../settings/SettingsPrefs.kt` / `SettingsVocabulary.kt` — app prefs.
- `android/.../settings/SettingsScreen.kt` / `SettingsViewModel.kt` — settings UI.
- `android/.../WatchConnectionService.kt` — wires `musicDispatch`.

**The `MusicSessionDispatcher` interface is exactly the extension point.** We add a
second implementation that talks to LMS instead of the local media stack, and a
"backend" preference that selects which one is used.

---

## 2b. Two orthogonal concepts — BACKEND vs MULTI-FUNCTION MODE

Lyrion control touches **two different axes** that must not be conflated:

1. **Music BACKEND** (`LOCAL` vs `LYRION`) — *where* a music command is sent: the phone's
   own media session, or an LMS player on the network. This is a sub-setting of the
   MUSIC role: when a gesture is interpreted as "music", the backend decides the target.

2. **Multi-function MODE** (currently `MUSIC` ⇄ `TRACKER`) — *what the gesture means*:
   a media command, or a GPS-tracker action. This is the GLOBAL role that the
   `SWITCH_MULTI_FUNCTION_MODE` button cycles, because the 0x05 gesture stream is
   button-blind (carries no button id), so the active meaning must be one global value.

There are **two valid ways** to expose "control my Lyrion speaker" — pick one (see §2c):

- **(A) Lyrion as a BACKEND of the MUSIC mode** *(original plan)*: the rotation stays
  `MUSIC ⇄ TRACKER`; whether MUSIC hits the phone or the speaker is a separate pref.
  Simpler, but you can't switch phone↔speaker with the button — only in Settings.
- **(B) Lyrion as a first-class MODE in a configurable rotation** *(your suggestion)*:
  the rotation becomes a user-defined, ordered list, e.g.
  `[MUSIC_PHONE, MUSIC_LYRION, TRACKER]`, and the button **iterates** through it. One
  press jumps you from controlling the phone to controlling the kitchen speaker. The
  first entry is the default when the button is first assigned. More powerful, and it
  generalises the hardcoded 2-way flip.

**Recommendation: do (B).** It directly answers your ask ("define what it toggles from
and what the default is", "first entry decides what is first, then switch iterates"),
and it makes Lyrion reachable from the watch, not just Settings. (A)'s backend pref
still exists underneath — `MUSIC_PHONE` and `MUSIC_LYRION` are just two modes that both
use the music pipeline with a different `MusicSessionDispatcher`.

---

## 2c. Configurable multi-function rotation (Mode B)

Replace the fixed `multiFunctionRole` (single value, 2-way flip) with a **configurable
ordered rotation** plus a pointer to the active entry.

### Modes (the vocabulary the rotation draws from)
| Mode id | Meaning | Pipeline |
|---------|---------|----------|
| `MUSIC_PHONE` | Media control on the phone | music → `SystemMusicSessionDispatcher` |
| `MUSIC_LYRION` | Media control on the configured LMS player | music → `LyrionMusicSessionDispatcher` |
| `TRACKER` | GPS waypoint / ring-phone gestures | tracker dispatch |

(Legacy value `MUSIC` normalises to `MUSIC_PHONE` for back-compat.)

### New prefs (replace/extend `multiFunctionRole`)
- `multiFunctionRotation: List<String>` — ordered, de-duplicated list of enabled modes
  (e.g. `["MUSIC_PHONE", "MUSIC_LYRION", "TRACKER"]`). **First entry = default/active**
  when the rotation is (re)configured or the button is first set. Stored as a
  delimited string (CSV) in SharedPreferences; normalised to known modes, blanks/unknowns
  dropped, empty → `[MUSIC_PHONE]`.
- `multiFunctionActiveIndex: Int` — index into the rotation of the currently active mode
  (clamped to range; resets to 0 when the rotation changes).

Derived helpers in `SettingsVocabulary` (pure, unit-tested):
- `normalizeRotation(csv): List<String>` — known modes only, de-dup, preserve order,
  non-empty fallback `[MUSIC_PHONE]`.
- `activeMode(rotation, index): String` — safe lookup (clamped).
- `nextIndex(rotation, index): Int` — `(index+1) % size` (the iterate step).
- `modeLabel(mode): String` — human label for Settings + buzz feedback.

### Button switch behaviour
`SWITCH_MULTI_FUNCTION_MODE` (in `ServiceTrackerDispatch`) currently calls `flipRole()`.
Replace with `advanceRotation()`:
```
fun advanceRotation(): String {              // returns the NEW active mode
  val rot  = normalizeRotation(prefs.multiFunctionRotation)
  val next = nextIndex(rot, prefs.multiFunctionActiveIndex)
  prefs.setMultiFunctionActiveIndex(next)
  return rot[next]
}
```
Buzz feedback: instead of the binary now-MUSIC=5 / now-TRACKER=6, buzz a count derived
from the new active **index** (e.g. index+1 short pulses, capped) OR a distinct pattern
per mode, so the user feels *which* of N modes they landed on. Keep it simple: N short
pulses where N = (index+1), clamped to the reserved-pattern range.

### Routing change
`EventRouter.route` and `WatchConnectionService.routeEventJson` currently branch on the
binary role. Change to branch on the **active mode**:
- `MUSIC_PHONE`  → `Route.Music` (phone backend)
- `MUSIC_LYRION` → `Route.Music` (Lyrion backend) — same route, the *dispatcher* differs
- `TRACKER`      → `Route.Tracker`

Since both music modes share `Route.Music`, the active mode also selects which
`MusicSessionDispatcher` `ServiceMusicDispatch` uses (L5). The `0x08` `type:"button"`
path (`Route.ButtonPath2`) is unchanged.

### Settings UI (rotation editor)
A section to: (1) toggle which modes are in the rotation, (2) reorder them (the first
is the default), (3) see/clear the active index. Mirrors the existing role selector but
as a multi-select + ordered list. The Lyrion server/player config (§3) appears when
`MUSIC_LYRION` is in the rotation.

### Migration
On read, if only the legacy `multiFunctionRole` pref exists: map
`MUSIC→[MUSIC_PHONE]`, `TRACKER→[TRACKER]` (or default `[MUSIC_PHONE]`), index 0. New
writes use the rotation prefs; the legacy key can be left untouched for rollback safety.

---

## 3. Design — add an LMS backend behind the existing seam

### 3.1 Lyrion config prefs
With Mode B (§2c), the phone-vs-Lyrion choice is the **active mode** (`MUSIC_PHONE` vs
`MUSIC_LYRION`) — there is no separate `musicBackend` pref. The Lyrion *connection*
config is still its own set of app prefs. Add to `AppSettings` / `SettingsVocabulary` /
`SettingsPrefs`:
- `lyrionServerHost` : String (e.g. `192.168.1.10`).
- `lyrionServerPort` : Int (default `9000`, the HTTP/JSON-RPC port).
- `lyrionPlayerId`   : String (MAC of chosen player, `""` = unset).
- `lyrionPlayerName` : String (cached display name for the UI).
- `lyrionEmptyQueueFallback` : `FAVORITE` (default) | `RANDOM` | `NONE` — what to start
  when a PLAY/TOGGLE gesture hits an **empty playlist** (see §3.4 + §5.3).
- `lyrionFavoriteId` : String — the favourite to start when fallback == `FAVORITE`
  (`""` = unset; picked from the server's favourites list in Settings).

All app-level prefs (never sent to the watch), same pattern as `preferredMusicApp`.
Add `normalize*` + defaults in `SettingsVocabulary`, getters/setters in `SettingsPrefs`,
and round-trip tests mirroring the existing `preferredMusicApp` tests.

### 3.2 Pure LMS request builder (testable, no I/O)
New `android/.../music/lyrion/LyrionCommands.kt` (pure, JVM-unit-testable):
- `fun request(playerId: String, params: List<String>): String` → JSON-RPC body.
- `fun forAction(action: MusicAction): List<String>` → maps MusicAction → LMS params
  (the table in §1). Volume step configurable (default ±5).
- `fun playersQuery(): String`, `fun statusQuery(playerId): String`.
- `fun parsePlayers(responseJson): List<LyrionPlayer>` (id, name, model, connected, power).
This is the bulk of the new logic and is **fully unit-tested** with no network.

### 3.3 LMS transport (thin I/O seam)
New `LyrionClient` interface + `HttpLyrionClient` impl:
- `interface LyrionClient { fun post(host, port, body): String? ; }` — one method,
  blocking HTTP POST to `/jsonrpc.js`. Swallows/returns null on failure.
- `HttpLyrionClient` uses `HttpURLConnection` (no new dependency) on a worker thread.
- Tests inject a fake `LyrionClient` so command building + dispatch decisions are
  verified without a real server.

### 3.4 New dispatcher implementation
New `android/.../music/lyrion/LyrionMusicSessionDispatcher.kt` implementing the
existing `MusicSessionDispatcher` interface:
- `dispatch(action)` → build body via `LyrionCommands.forAction`, POST to the
  configured server for the configured `lyrionPlayerId`. For PLAY/TOGGLE it also sends
  `power 1` first (so a powered-off player wakes and plays — this is the "start music
  on my device by talking to the server" requirement).
- **Empty-queue fallback.** For PLAY/TOGGLE, before issuing `play`, check the queue via
  the `status` query (`playlist_tracks`). If the queue is empty, start music per the
  configured `lyrionEmptyQueueFallback`:
  - `FAVORITE` → play the configured `lyrionFavoriteId` (e.g.
    `["favorites","playlist","play","item_id:<id>"]`); if no favourite is configured,
    fall back to `RANDOM`.
  - `RANDOM` → `["randomplay","tracks"]`.
  - `NONE` → just `play` (no-op on an empty queue — current passive behaviour).
  This is the "configurable fallback (default favourite, can be random)" requirement.
  Keep the empty-queue check + fallback choice in the **pure** `LyrionCommands` layer so
  it is unit-tested; the dispatcher only wires the status read + the chosen command.
- `launchThenDispatch(pkg, action)` → for LMS there is no "app to launch"; treat as a
  plain `dispatch(action)` (power + play with the same empty-queue fallback). The
  local-only launch-fallback concept doesn't apply.

### 3.5 Mode/backend selection in the service
In `ServiceMusicDispatch`, choose which `MusicSessionDispatcher` to build based on the
current **active mode** (§2c):
- `MUSIC_PHONE`  → existing `SystemMusicSessionDispatcher` (unchanged).
- `MUSIC_LYRION` → `LyrionMusicSessionDispatcher` (host/port/playerId read fresh from prefs).

Read the active mode fresh per event (cheap pref read), matching how the role is read
today. The `MusicController.decide` step stays for `MUSIC_PHONE`; for `MUSIC_LYRION`,
"hasActiveSession" is Android-media-specific and not meaningful, so route straight to
the LMS dispatcher (a trivial "always dispatch" decision).

### 3.6 Settings UI
In `SettingsScreen.kt` / `SettingsViewModel.kt`:
1. **Multi-function rotation editor** (§2c): multi-select which modes are active +
   reorder (first = default); shows the active-index indicator.
2. When `MUSIC_LYRION` is in the rotation, show the **Lyrion server section**:
   - Server host + port fields.
   - "Test connection / load players" button → calls `players` query on a worker
     thread, populates a dropdown of discovered players (name + model).
   - Player dropdown → stores `lyrionPlayerId` + `lyrionPlayerName`.
   - Empty-queue fallback selector (FAVORITE/RANDOM/NONE) + favourite picker.
   - (Optional) "Now playing" readout via `status`.
Mirror the existing `preferredMusicApp` dropdown pattern for the player/favourite pickers.

---

## 4. Work breakdown (incremental, each independently testable)

| WP | Scope | Deliverable | Tests |
|----|-------|-------------|-------|
| **L0** | Configurable rotation | Replace `multiFunctionRole` 2-way flip with `multiFunctionRotation` (ordered modes) + `multiFunctionActiveIndex`; modes `MUSIC_PHONE`/`MUSIC_LYRION`/`TRACKER`; `normalizeRotation`/`activeMode`/`nextIndex`/`modeLabel`; `advanceRotation()` in `ServiceTrackerDispatch`; `EventRouter`/`routeEventJson` branch on active mode; legacy `MUSIC`→`MUSIC_PHONE` migration | pure rotation/route unit tests + migration test |
| **L1** | Lyrion prefs | `lyrionServerHost/Port`, `lyrionPlayerId/Name`, `lyrionEmptyQueueFallback`, `lyrionFavoriteId` in `SettingsVocabulary` + `AppSettings` + `SettingsPrefs` | round-trip + normalization unit tests |
| **L2** | Pure commands | `LyrionCommands` (request builder, action map, players/status queries, player + favourites parser, empty-queue fallback selection) | full unit coverage, no I/O |
| **L3** | Transport seam | `LyrionClient` interface + `HttpLyrionClient` | fake client in tests; manual on-network smoke |
| **L4** | Dispatcher | `LyrionMusicSessionDispatcher implements MusicSessionDispatcher` (power+play, next/prev, volume) | dispatch decisions via fake `LyrionClient` |
| **L5** | Mode selection | `ServiceMusicDispatch` picks `SystemMusicSessionDispatcher` vs `LyrionMusicSessionDispatcher` from the active mode | selector unit test |
| **L6** | Settings UI | rotation editor (multi-select + active-mode picker) + Lyrion server section (server fields, player id, fallback selector, favourite id) | ViewModel state tests |
| **L7** | Discovery (optional) | UDP 3483 auto-discovery of servers (like Squeezer) | deferred / nice-to-have |

Suggested order: **L0** → L1 → L2 → L4 (+L3) → L5 → L6, then L7 if wanted.
(L0 is independent of Lyrion and can land first as a pure refactor; `MUSIC_LYRION`
becomes routable once L4/L5 exist.)

**STATUS (implemented):** L0–L7 are done and unit-tested.
- L6 ships the rotation editor (checkbox multi-select + active-mode picker) and a Lyrion
  config card shown when the Lyrion mode is in the rotation.
- **Load players / favourites (done):** a `LyrionDiscovery` seam (`SystemLyrionDiscovery`)
  calls `playersQuery`/`favoritesQuery` via `LyrionClient` off the main thread; the VM
  exposes the results (`loadLyrionPlayers` / `loadLyrionFavorites`) and the Settings card
  has "Load players"/"Load favourites" buttons + dropdowns. Manual id entry still works.
- **L7 UDP discovery (done):** `LyrionDiscoveryCodec` (pure TLV build/parse) + the socket
  in `SystemLyrionDiscovery.discoverServers()` broadcast on port 3483; the VM exposes
  `discoverLyrionServers` and the card has a "Discover servers on network" button +
  dropdown that fills host/port. Manifest gains `INTERNET` / `ACCESS_NETWORK_STATE` /
  `CHANGE_WIFI_MULTICAST_STATE` (+ a brief multicast lock during discovery).

**Remaining follow-up:** password/auth for protected servers (deferred — LMS `login` /
HTTP basic auth).

---

## 5. Decisions / open questions

1. **Transport = JSON-RPC over HTTP (port 9000).** Stateless, no persistent socket,
   no new dependencies (plain `HttpURLConnection`). Squeezer uses CometD for *live UI
   updates*; we only issue fire-and-forget commands + occasional status polls, so
   JSON-RPC is the right fit. (Raw CLI 9090 is an easy alternative if HTTP is disabled.)
2. **Player identity = MAC/playerid** returned by the `players` query (stable, the
   canonical LMS identifier).
3. **"Start music on my device" + empty-queue fallback** *(decided)*: PLAY/TOGGLE
   sends `power 1` first so a sleeping player wakes. If the queue is empty, the
   dispatcher starts music per the configurable `lyrionEmptyQueueFallback`:
   **`FAVORITE` (default)** plays the configured favourite; **`RANDOM`** does
   `randomplay tracks`; **`NONE`** leaves it passive. `FAVORITE` with no favourite set
   degrades gracefully to `RANDOM`. See §3.4.
4. **Multi-function rotation** *(decided — Mode B, §2c)*: the button cycles a
   user-defined ordered list of modes (`MUSIC_PHONE`, `MUSIC_LYRION`, `TRACKER`); the
   first entry is the default/active when (re)configured; the switch iterates with
   wrap-around. Lyrion is therefore a **first-class mode**, reachable from the watch,
   not a hidden Settings-only backend.
5. **Auth/password-protected servers** *(deferred — later)*: LMS supports a `login`
   command / HTTP basic auth. Out of scope for v1 (assume open server on LAN); add in a
   later WP when needed.
6. **Backend scope**: global app pref (one server, one target player), matching the
   request ("specific maybe one device as player in the settings"). Per-watch is not
   needed.

### 5.3 Empty-queue fallback — detail
When a PLAY/TOGGLE gesture targets a player whose playlist is empty, plain `play` does
nothing. The configured `lyrionEmptyQueueFallback` decides what to start:

| Value | Behaviour | LMS command |
|-------|-----------|-------------|
| `FAVORITE` *(default)* | Play the configured favourite. If none set → degrade to `RANDOM`. | `["favorites","playlist","play","item_id:<lyrionFavoriteId>"]` |
| `RANDOM` | Play a random mix of tracks. | `["randomplay","tracks"]` |
| `NONE` | Do nothing extra (passive `play`). | `["play"]` |

Decision rule (lives in pure `LyrionCommands`, unit-tested):
```
fun resolvePlay(queueEmpty, fallback, favoriteId): List<String> =
  if (!queueEmpty) ["play"]
  else when (fallback) {
    NONE     -> ["play"]
    RANDOM   -> ["randomplay","tracks"]
    FAVORITE -> if (favoriteId.isNotBlank()) ["favorites","playlist","play","item_id:$favoriteId"]
                else ["randomplay","tracks"]   // graceful degrade
  }
```
Favourites for the Settings picker come from `["favorites","items","0","999"]` (id +
name per item).
6. **No watch-protocol changes.** This is purely phone-side dispatch routing — the
   watch's existing `{"type":"music",...}` event contract is reused unchanged, exactly
   like the local backend.

---

## 6. Why this fits cleanly

- Reuses the existing `MusicSessionDispatcher` **interface seam** — the LMS backend is
  just a second implementation; the pure parse/route layers (`MusicController`,
  `EventRouter`, `WatchConnectionService.routeEventJson`) are untouched.
- Same pref pattern as `preferredMusicApp` (app-level, never synced to watch).
- Pure command builder is fully unit-testable; only the thin HTTP POST touches the
  network (and is itself behind a seam).
- No new watch wire bytes; no changes to the CLI tool — Android-only feature.
