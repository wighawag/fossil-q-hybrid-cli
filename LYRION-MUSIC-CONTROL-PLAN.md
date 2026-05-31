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

## 3. Design — add an LMS backend behind the existing seam

### 3.1 New preference: music control backend
Add to `AppSettings` / `SettingsVocabulary` / `SettingsPrefs`:
- `musicBackend`: `LOCAL` (default, current behaviour) | `LYRION`.
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

### 3.5 Backend selection in the service
In `ServiceMusicDispatch` (or a small new selector), choose which
`MusicSessionDispatcher` to build based on `musicBackend`:
- `LOCAL` → existing `SystemMusicSessionDispatcher` (unchanged).
- `LYRION` → `LyrionMusicSessionDispatcher` (host/port/playerId read fresh from prefs).

The `MusicController.decide` step can stay, but for LMS "hasActiveSession" is less
meaningful — simplest is: when backend == LYRION, route straight to the LMS dispatcher
(skip the active-session/launch-fallback decision, which is Android-media-specific).
Cleanest implementation: pick the dispatcher up front; keep the pure decide for LOCAL,
and for LYRION use a trivial "always dispatch" decision.

### 3.6 Settings UI
In `SettingsScreen.kt` / `SettingsViewModel.kt`, add a **"Music control" section**:
1. Backend selector (radio/segmented): *Phone media* vs *Lyrion server*.
2. When *Lyrion server* selected, show:
   - Server host + port fields.
   - "Test connection / load players" button → calls `players` query on a worker
     thread, populates a dropdown of discovered players (name + model).
   - Player dropdown → stores `lyrionPlayerId` + `lyrionPlayerName`.
   - (Optional) "Now playing" readout via `status`.
Mirror the existing `preferredMusicApp` dropdown pattern for the player picker.

---

## 4. Work breakdown (incremental, each independently testable)

| WP | Scope | Deliverable | Tests |
|----|-------|-------------|-------|
| **L1** | Prefs | `musicBackend`, `lyrionServerHost/Port`, `lyrionPlayerId/Name` in `SettingsVocabulary` + `AppSettings` + `SettingsPrefs` | round-trip + normalization unit tests |
| **L2** | Pure commands | `LyrionCommands` (request builder, action map, players/status queries, player + favourites parser, empty-queue fallback selection) | full unit coverage, no I/O |
| **L3** | Transport seam | `LyrionClient` interface + `HttpLyrionClient` | fake client in tests; manual on-network smoke |
| **L4** | Dispatcher | `LyrionMusicSessionDispatcher implements MusicSessionDispatcher` (power+play, next/prev, volume) | dispatch decisions via fake `LyrionClient` |
| **L5** | Backend selection | `ServiceMusicDispatch` chooses LOCAL vs LYRION from prefs | selector unit test |
| **L6** | Settings UI | backend toggle + server fields + player picker + empty-queue fallback selector (FAVORITE/RANDOM/NONE) + favourite picker (load favourites) | ViewModel state tests |
| **L7** | Discovery (optional) | UDP 3483 auto-discovery of servers (like Squeezer) | deferred / nice-to-have |

Suggested order: L1 → L2 → L4 (+L3) → L5 → L6, then L7 if wanted.

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
4. **Auth/password-protected servers** *(deferred — later)*: LMS supports a `login`
   command / HTTP basic auth. Out of scope for v1 (assume open server on LAN); add in a
   later WP when needed.
5. **Backend scope**: global app pref (one server, one target player), matching the
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
