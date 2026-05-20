# Session Prompt

Read `CONTEXT.md` first for project overview, then `FINDINGS.md` for protocol details, then `TODO.md` for feature status.

## Goal

Implement a **`monitor` command** — a long-running mode that stays connected to the watch and listens for button press events, heartbeat signals, and other watch-initiated messages.

## Why This Matters

Currently every CLI command does a full connect→init→command→disconnect cycle (~6s). The `monitor` command keeps the connection alive and:

1. **Listens for button press events** on `3dda0006` — the watch sends JSON messages and binary events when buttons are pressed (type 0x08 = single press, 0x05 = multi-button)
2. **Prints events to stdout** — enables piping to other tools (`jq`, scripts, Home Assistant webhooks)
3. **Serves as foundation for daemon mode** — once we can stay connected and receive events, we can add a command socket for instant commands without reconnecting

## What We Know

From FINDINGS.md #9 and the BLE capture analysis:
- The watch sends JSON messages on `3dda0006`: `{"req":{"id":43,"buddyChallengeApp":{"type":"sync_pkg"}}}`
- Button press events are also on `3dda0006` (type 0x08 for button press, 0x05 for multi-button actions)
- The watch sends periodic heartbeat events
- `DbusTransport` already has a `PropertiesChanged` signal handler that receives all characteristic value changes

From the decompiled official Fossil app (`tmp/FossilOfficialApp-deobf/`):
- Button events are parsed by `ButtonEventRequest` / `ButtonActionManager`
- Event types include SINGLE_PRESS, DOUBLE_PRESS, LONG_PRESS for TOP, MIDDLE, BOTTOM buttons

## Implementation Plan

1. **Add `monitor` command to `Main.java`** — long-running, Ctrl+C to exit
2. **Add event callback to `FossilQAdapter`** — register a listener for `3dda0006` notifications that aren't file-transfer related
3. **Parse button events** — decode the binary format from `3dda0006` (look at `ButtonEventRequest.java` in vendored code or decompiled app)
4. **Parse JSON messages** — already partially understood from Finding #9
5. **Output format** — one JSON line per event for easy parsing:
   ```
   {"type":"button","button":"TOP","action":"SINGLE_PRESS","timestamp":"2026-05-20T12:34:56Z"}
   {"type":"heartbeat","timestamp":"2026-05-20T12:35:00Z"}
   {"type":"json","data":{"req":{"id":43,"buddyChallengeApp":{"type":"sync_pkg"}}}}
   ```
6. **Graceful shutdown** — Ctrl+C handler that releases resources and disconnects cleanly

## Constraints

- Watch: Fossil Q Commuter (HW.0.0), MAC: D9:20:71:11:74:2A
- Zero patches to vendored GadgetBridge code
- Use `DbusTransport` (default) — it already handles persistent connections and signal-based notifications
- Java 21, BlueZ 5.82, Debian
- Build: `./gradlew shadowJar` → `build/libs/fossil-q.jar`

## Starting Points

- `src/main/java/qhybrid/linux/DbusTransport.java` — PropertiesChanged handler already receives all char notifications
- `src/main/java/qhybrid/linux/FossilQAdapter.java` — `handleBackgroundEvent()` or add new handler for 3dda0006
- `gadgetbridge/` — look for `ButtonEventRequest`, `ButtonActionManager`, event parsing code
- `tmp/FossilOfficialApp-deobf/` — decompiled official app, look for button event handling
