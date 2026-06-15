# WP: Capture the Official-App Event ACK on 3dda0006

**Status:** ANALYSIS + CAPTURE PLAN ONLY. No production code changes in this task.
Implementation (wiring the ack) is a separate follow-up, gated on confirming the ack byte layout
(first from the official-app disassembly, then a btsnoop capture if needed).

This WP resolves ONE remaining unknown about the OFFICIAL Fossil/Skagen app: **the event ack.**
What does the official app write back after a `3dda0006` button / micro-app / music event so the
watch stops re-sending the same REQUEST-opcode frame ~10-18x?

> **The play-file memory leak (formerly "Part B") is RESOLVED separately, do not re-investigate.**
> The buzz `0x86` = `NOT_ENOUGH_MEMORY` was caused by us PUTting every play file to a FIXED handle
> `0x0900`. The official app's `FileHandleManager.getFileHandleToPut` (`m11016b` in
> `tmp/FossilOfficialApp-deobf`, for `FileType.NOTIFICATION`) ROTATES the handle low byte:
> `(9<<8)|index`, `index` incremented `% 255` => `0x0900, 0x0901, 0x0902, ...`. This was root-caused
> from the disassembly (no btsnoop needed) and FIXED in commit `18553a8`: `FossilQAdapter` now keeps
> a per-connection `notificationPlayIndex`, all three play paths open `nextNotificationPlayHandle()`,
> and a new `FilePutRequest(short explicitHandle, FileHandle, byte[], adapter)` constructor carries
> the rotated handle. Covered by `NotificationPlayHandleRotationTest`. See the FINDINGS section
> "ROOT CAUSE + FIX (2026-06-15...): rotate the NOTIFICATION_PLAY handle low byte" (marked SHIPPED).
> Nothing about the play path is capture-gated any more.

## Why (the event ack)

The watch sends button / music / micro-app events on GATT characteristic
`3dda0006-957f-7d4a-34a6-74696673696d` as frames `[opCode][eventType][sequence][data...]`.

Observed storm frame (BOTTOM-button mode-switch press):

```
01 08 0b 01 01 0c 00 37 01 30 5e 00
^opCode=01(REQUEST)
   ^eventType=08(MICRO_APP)
      ^sequence=0b
```

`opCode 0x01 = REQUEST` means the watch expects an app response; `0x02 = NOTIFY` is
fire-and-forget (decode source: official app `AsyncOperationCode`, FINDINGS §18).

Our `FossilQAdapter.handleButtonEvent` (protocol/src/main/java/qhybrid/protocol/FossilQAdapter.java,
method at ~line 1995) READS opCode/eventType/sequence but NEVER writes anything back to `3dda0006`.

**Hypothesis:** because no ack is sent for a REQUEST-opcode event, the firmware re-requests the same
frame (same sequence) ~10-18x until timeout. The de-dup we shipped (commit a2d1db1) collapses the
repeats to one effect but only absorbs the symptom; the watch still re-sends. (Historically this
re-send storm also queued many play-file PUTs, which is what first surfaced the play-file bug; that
play-file bug is now fixed independently by handle rotation, so the only thing left to fix here is
stopping the re-send at its source with the ack.)

## Decisive unknown to resolve

After a `3dda0006` event notification, what does the OFFICIAL app WRITE back in the next tens of ms?

1. Is there a write (to `3dda0006` or another char) that echoes the event's `eventType` and/or
   `sequence` (an explicit ack)?
2. Do the official app's own event frames carry `opCode 0x02` (NOTIFY) instead of our observed
   `0x01` (REQUEST), implying the re-send is provoked by something we do/omit?
3. What is the exact byte layout, target characteristic UUID, and timing of any such ack?

> Note: the play-file lifecycle is NOT an open question (see the resolved-separately note above);
> this WP is only about the event ack.

---

## 0. Check the disassembly FIRST (preferred over a capture) - DONE 2026-06-15

> **RESULT (2026-06-15): the ack format is RECOVERED from the disassembly. The only remaining
> unknown is the trigger condition (ack-all vs ack-REQUEST-only), which is in a method jadx could
> not decompile.** Details in "0a. What the disassembly showed" below; the capture (sections 1-3)
> is now a small CONFIRMATION step, not discovery.

The play-file fix came entirely from the official-app disassembly with no btsnoop needed; the ack
may too. **Before** setting up any capture, read the deobfuscated official app under
`tmp/FossilOfficialApp-deobf/sources` to see whether it sends an ack/response for REQUEST-opcode
(`0x01`) async events and, if so, the exact bytes and target characteristic.

Start with these classes (paths under `tmp/FossilOfficialApp-deobf/sources`):

- `com/fossil/blesdk/device/logic/request/GetAsyncNotificationRequest.java` - the request that
  reads/handles the watch's async events; look for whether it builds and sends a response frame
  back (and on which characteristic) after receiving a REQUEST-opcode event.
- `com/fossil/blesdk/device/logic/request/code/AsyncOperationCode.java` - the opcode enum
  (REQUEST `0x01` vs NOTIFY `0x02` and any response/ack code); this is the same source that
  defined the opcode meanings in FINDINGS §18.
- `com/fossil/blesdk/device/logic/request/RequestId.java` - request ids; check for an
  async-notification ack / response id.
- `com/fossil/blesdk/device/event/DeviceEventId.java` - the event-id table; cross-check the
  `eventType` values (0x05 MUSIC, 0x08 MICRO_APP, etc.) and whether each is flagged as expecting a
  response.

What to extract from the disassembly:

- Does a REQUEST-opcode (`0x01`) async event get an explicit response written back? If the SDK
  fire-and-forgets it (no write), that itself is an answer (the re-send must be provoked by
  something else).
- The exact response/ack byte layout (opcode byte, whether it echoes `eventType` and/or
  `sequence`, any trailing status), and the target characteristic UUID (expected `3dda0006`).
- The write type the SDK uses (write-with-response vs write-without-response).

If the disassembly yields the exact ack bytes, the btsnoop capture (sections 1-3) becomes a
CONFIRMATION step rather than a discovery step (capture one event + one ack and verify the bytes
match). If the disassembly is inconclusive (e.g. the ack is assembled dynamically and hard to read
statically), fall through to the capture below to read it off the wire.

### 0a. What the disassembly showed (2026-06-15)

The official app has a dedicated `RequestId.SEND_ASYNC_EVENT_ACK`, implemented by
`SendAsyncEventAckRequest` + `SendAsyncEventAckPhase`, that writes an ack back to the watch.

**Ack wire format (recovered, NOT guessed):**

```
02 <eventType> <sequence> [optional payload]
^opcode = 0x02 (NOTIFY) -- ALWAYS 2 for the ack
   ^eventType byte -- echoed from the received event
      ^sequence byte -- echoed from the received event
         ^payload -- default EMPTY; only TimeSync/Heartbeat override it
```

- For our event types (MICRO_APP `0x08`, MUSIC `0x05`, APP_NOTIFICATION `0x04`) the payload is the
  default empty, so the ack is exactly **3 bytes: `02 <eventType> <sequence>`**.
- **Target characteristic: `3dda0006`** (`GattCharacteristic.CharacteristicId.ASYNC`).
- **Write type: write-WITHOUT-response** ("command"): `SendAsyncEventAckRequest` extends
  `SingleCommandWithoutNotificationRequest` and issues
  `WriteCharacteristicCommand(CharacteristicId.ASYNC, ackBytes, ...)`. Matches FINDINGS §2.

**Source provenance (under `tmp/FossilOfficialApp-deobf/sources`):**

- `com/fossil/blesdk/device/asyncevent/AsyncEvent.java` - method `m10799a()` (the metadata names it
  `getAckResponseData`) builds the bytes little-endian: `put((byte)2)`, `put(this.f17932a.f19927a)`
  (eventType), `put(this.f17933b)` (sequence), `put(mo10797b())` (payload). `mo10797b()` defaults to
  `new byte[0]`; only `TimeSyncEvent` / `HeartbeatEvent` override it.
- `com/fossil/blesdk/device/logic/request/SendAsyncEventAckRequest.java` - `mo11158j()` returns
  `WriteCharacteristicCommand(GattCharacteristic.CharacteristicId.ASYNC, asyncEvent.m10799a(), ...)`;
  the ack payload `f19867C = asyncEvent.m10799a()`.
- `com/fossil/blesdk/device/logic/phase/SendAsyncEventAckPhase.java` - the phase that runs that
  request (requires `ResourceType.ASYNC`).
- `com/fossil/blesdk/device/logic/request/RequestId.java` - contains `SEND_ASYNC_EVENT_ACK`.
- `com/fossil/blesdk/device/logic/request/code/AsyncEventType.java` - the eventType id bytes
  (`MUSIC_EVENT=5`, `MICRO_APP_EVENT=8`, `APP_NOTIFICATION_EVENT=4`, etc.) used as the 2nd ack byte.
- `com/fossil/blesdk/device/event/DeviceEventParser.java` - `m11009a()` parses the INCOMING frame as
  `rawData[0]`=opcode (`AsyncOperationCode` REQUEST=1 / NOTIFY=2), `[1]`=eventType, `[2]`=sequence -
  confirming our `[opCode][eventType][sequence][data...]` layout.
- `com/fossil/blesdk/device/DeviceImplementation.java` - `sendAsyncEventAck$blesdk_productionRelease`
  (~line 7460) constructs the `SendAsyncEventAckPhase`; the async-event listener dispatches to it
  (~line 1171).

**The remaining unknown (capture-gated): the TRIGGER condition.** Whether the app acks EVERY async
event or ONLY REQUEST-opcode (`0x01`) events is decided in
`DeviceImplementation$streamingAsyncEventListener$1.C18181.invokeSuspend`, which **jadx could not
decompile** ("Method not decompiled, instruction units count: 978"). So the static source cannot
show the gate. A short btsnoop (sections 1-3) confirms it: press a REQUEST-opcode button and check
whether exactly one `02 <type> <seq>` ack follows; check whether any NOTIFY-opcode (`0x02`) event is
acked too. Our conservative default for the impl is to ack ONLY `opCode == 0x01` (see section 5).

---

## 1. Capture procedure (Android btsnoop HCI log)

Requirements: an Android phone with the official **Fossil Smartwatches** (or **Skagen**) app
installed and paired to the SAME watch, USB cable, `adb` on the workstation. (Pair the watch to the
phone first; if it is currently bonded to the Linux box, the watch can usually hold one classic/BLE
companion bond at a time, so you may need to forget it on Linux and re-pair to the phone for the
capture.)

### 1a. Enable the Bluetooth HCI snoop log

1. Settings > About phone > tap **Build number** 7x to unlock Developer Options.
2. Settings > System > **Developer options**.
3. Enable **Bluetooth HCI snoop log**. On some phones this is a toggle; on others it is a
   3-way list (Disabled / Filtered / Enabled) - choose **Enabled** (full), not Filtered.
4. **Toggle Bluetooth OFF then ON** (or reboot). The snoop log only starts capturing after the
   Bluetooth stack restarts with logging enabled. This step is the one most often missed.

### 1b. Trigger several distinct events (keep them well-separated in time)

Open the official app, confirm it shows the watch connected, then with ~5 s gaps between presses so
each event is easy to isolate later:

1. **Mode switch** (BOTTOM button, the micro-app/mode-switch press = `eventType 0x08`) x3.
2. **Music** (TOP button: single = TOGGLE_PLAY_PAUSE, double = NEXT, long = PREVIOUS;
   `eventType 0x05`) x3.
3. **Ring-phone / forward-to-phone** (MIDDLE button micro-app = `eventType 0x08`) x2.

Say out loud or note the wall-clock time of each press; it speeds up locating frames in Wireshark.
Do at least two presses of the SAME action so you can see whether the official app's frame
`sequence` increments and whether the ack echoes it.

### 1c. Pull the log

The btsnoop log is captured into the bugreport (the same method used for bugreport5-8, FINDINGS §21).

```bash
# Easiest: a full zipped bugreport (contains FS/data/misc/bluetooth/logs/btsnoop_hci.log)
adb bugreport bugreport-eventack.zip
```

If `adb bugreport` produces a `.zip`, the snoop log is inside it; if it produces a flat `.txt`
(older devices), use the direct pull paths below instead.

### 1d. Locate `btsnoop_hci.log` inside the bugreport

```bash
mkdir -p tmp/eventack && cd tmp/eventack
unzip -o ../../bugreport-eventack.zip

# The snoop log is one of these (path varies by Android version/OEM):
find . -iname 'btsnoop_hci*.log' -o -iname '*btsnoop*'
# Common locations inside the zip:
#   FS/data/misc/bluetooth/logs/btsnoop_hci.log
#   FS/data/log/bt/btsnoop_hci.log
#   FS/data/misc/bluetooth/logs/btsnoop_hci.log.last   (previous session)
```

Direct-pull fallback (needs root or a userdebug build; usually NOT available on a stock phone):

```bash
adb root && adb pull /data/misc/bluetooth/logs/btsnoop_hci.log tmp/eventack/
# or, OEM-specific:
adb pull /sdcard/btsnoop_hci.log tmp/eventack/
```

`btsnoop_hci.log` is a standard `btsnoop` capture; open it directly in Wireshark.

> Privacy note: a full bugreport contains a lot of device data. Keep it under `tmp/` (gitignored)
> and delete it after extracting the frames you need.

---

## 2. Wireshark analysis recipe

Open `btsnoop_hci.log` in Wireshark (File > Open; it auto-detects the btsnoop format).

### 2a. Find this watch's connection and map GATT handles to the 3dda000x UUIDs

The btsnoop is HCI-level: ATT operations reference **handles**, not UUIDs. You must map them once.

1. Filter to the GATT discovery for this watch:
   `btatt.opcode == 0x09` (Read By Type Response) - these responses list
   `Handle / UUID` pairs for the characteristic *value* handles during service discovery.
2. Expand a Read-By-Type-Response and find the 128-bit Fossil UUIDs `3dda0002..0007`.
   Record each value handle. Expected mapping (from FINDINGS §20, hardware-verified on the
   Q Commuter; the absolute handle numbers can differ per pairing/firmware, so confirm from
   the capture):

   | Handle | UUID | Name |
   |--------|------|------|
   | 0x0042 | 3dda0002 | CMD (commands, write+notify) |
   | 0x0045 | 3dda0003 | CTL (file control, write+indicate) |
   | 0x0048 | 3dda0004 | DAT (file data, write) |
   | 0x004b | 3dda0005 | AUTH (write+indicate) |
   | **0x004e** | **3dda0006** | **EVT (async events, write+notify)** |
   | 0x0051 | 3dda0007 | CHR7 (notification play data, write) |

   The handle that matters is the **3dda0006 value handle** (call it `H_EVT`, expected `0x004e`).
   The CCCD (descriptor, `H_EVT + 1`, e.g. `0x004f`) carries the `0x0001` notification-enable
   write, which is NOT the ack - do not confuse the two.

### 2b. Isolate the 3dda0006 notifications (events the watch sends UP)

Notifications/indications from the watch arrive as ATT Handle-Value Notification (opcode `0x1b`)
or Indication (`0x1d`) on `H_EVT`:

```
btatt.opcode == 0x1b && btatt.handle == 0x004e
```

(Substitute the real `H_EVT` from 2a.) Each hit is one event frame; the ATT value bytes are the
`[opCode][eventType][sequence][data...]` frame. Confirm you see `eventType` `0x08`/`0x05` matching
your presses, and note the `sequence` byte and whether it increments across repeated presses.

### 2c. Read what the phone WRITES immediately after each event

Writes from the phone go DOWN as ATT Write Request (`0x12`, with response) or Write Command
(`0x52`, without response). To see every phone-side write on the event char:

```
(btatt.opcode == 0x12 || btatt.opcode == 0x52) && btatt.handle == 0x004e
```

To catch an ack that might land on a DIFFERENT characteristic, widen to all writes around the
event time:

```
btatt.opcode == 0x12 || btatt.opcode == 0x52
```

Then, for each `3dda0006` notification you isolated in 2b, look at the **next few frames in time
order** (sort by Time; the relevant write usually lands within tens of ms). Read the
`btatt.value` bytes of that write.

### 2d. Columns/fields to read

Add these as custom columns (Edit > Preferences > Columns, or right-click a field > Apply as
Column):

- **Time** (set to "Seconds Since Beginning of Capture" or UTC; you need sub-ms ordering).
- `btatt.opcode` - distinguishes notification (0x1b) vs write-req (0x12) vs write-cmd (0x52).
- `btatt.handle` - the GATT handle (map via 2a).
- `btatt.value` - the raw ATT payload bytes (this is the event frame or the ack bytes).
- Direction: the **Source/Destination** columns (or `bthci_acl` direction) tell you watch->phone
  vs phone->watch. Notifications are watch->phone; writes are phone->watch.

A clean way to see the request/ack pairing: apply
`btatt.handle == 0x004e` (no opcode filter), sort by Time, and read the interleaving of `0x1b`
(watch event) and `0x12`/`0x52` (phone write) on the same handle.

---

## 3. "What to look for" checklist (the event ack)

For each captured `3dda0006` notification `01 <type> <seq> <data...>`:

- [ ] **Unknown 1 - explicit ack?** Is there a phone->watch write within ~tens of ms whose bytes
      ECHO the event's `eventType` and/or `sequence`? Expected ack shape (hypothesis, to be
      confirmed): a short frame on `3dda0006` of the form `02 <eventType> <seq>` (opCode `0x02` =
      NOTIFY/response, same eventType, same sequence), or `<ackOpcode> <seq>`. Record the EXACT
      bytes, the target handle/UUID, and the write type (0x12 vs 0x52).
- [ ] **Unknown 2 - REQUEST vs NOTIFY?** Look at the OFFICIAL app's behaviour two ways:
      (a) the `opCode` byte of the watch's event frames - if the watch sends the same events to the
      official app with `0x01` (REQUEST) just like to us, then the re-send is keyed on a MISSING
      ACK, not on our opcode; (b) whether, after the official app's ack, the watch STOPS re-sending
      (i.e. the official capture shows ONE event frame per press, not 10-18). If the official
      capture shows ONE frame + one ack, that confirms "ack stops the storm".
- [ ] **Unknown 3 - exact layout/UUID/timing.** Record: target characteristic UUID (map the
      handle), write type (req `0x12` / cmd `0x52`), full ack byte string, and the delay
      (event time -> ack time, in ms).

### Telling an ACK apart from unrelated periodic writes

The official app also does routine traffic; do not mistake it for the ack:

- An **ack** should: (a) appear ONLY after an event notification, not on a fixed timer; (b) be
  SMALL (a few bytes); (c) **correlate** with the event - its bytes contain the same `eventType`
  and/or the same `sequence` value as the notification it follows; (d) be repeated/absent in
  lock-step with repeated/absent events.
- **Not an ack** (ignore these): CCCD writes (`0x0001`/`0x0002` to a descriptor handle = `H_EVT+1`,
  only at connect), MTU exchange, periodic time/heartbeat sync writes (these fire on a clock, not
  in response to a button), and file-protocol traffic on `3dda0003`/`3dda0004`.
- Decisive test: press the SAME action twice. If the candidate write's bytes track the event's
  changing `sequence` byte (e.g. ack carries 0x0b then 0x0c), it is the ack. If its bytes are
  constant or it fires when no button was pressed, it is not.

---

## 4. Fallback: if you cannot capture from the official app

If pairing to the phone is not possible (single-bond watch, no Android device, etc.), you can still
test the REQUEST-vs-NOTIFY hypothesis from our OWN logs, short of confirming the ack bytes:

1. **Confirm the re-send correlates with opCode 0x01.** In our existing event logs / `monitor`
   NDJSON + the transport `NOTIFY 3dda0006 <- ...` trace, count retransmits per press and check the
   leading byte. Prior data already shows `01 05 14 02` (opCode 01) re-sent ~10x and
   `01 08 0b ...` (opCode 01) re-sent for the mode switch. If EVERY storming frame is opCode `0x01`
   and `0x02`-opcode frames (e.g. heartbeats) never storm, that is strong circumstantial support
   for "REQUEST without ack -> re-send".
2. **Safe minimal experiment (later, in a controlled session - NOT this task).** With the watch
   re-provisionable on hand, try writing a minimal candidate ack back to `3dda0006` for one
   REQUEST-opcode event and watch whether the retransmit count drops from ~10-18 to ~1. Candidate
   acks to try, in order of likelihood, are (smallest blast radius first):
   - `02 <eventType> <sequence>` (NOTIFY opcode echo of type+seq)
   - `01 <eventType> <sequence>` (REQUEST opcode echo, mirroring the file-protocol "respond with
     same type" convention)
   Use write type `command` (write-without-response) since `3dda0006` is flagged write+notify
   (FINDINGS §2). Treat this as a reversible probe: if the watch wedges (status `0x86` on the play
   handle), stop and re-provision. Because this guesses bytes, it MUST NOT be wired into production
   until a real capture confirms the format; record only as an experiment result, not as the
   authoritative ack.

The capture (section 1-3) is strongly preferred because it gives the exact bytes; the fallback can
only confirm/deny the mechanism, not the wire format.

---

## 5. Where/how the ack would later be wired in (proposal, not implemented)

**Ack model is now confirmed on the wire (two captures, FINDINGS §18a) and it is PER-EVENT-TYPE:**
- **MUSIC (`0x05`), incl. volume:** ack = `02 05 <action> 00 00 00 00 00` on `3dda0006`,
  **write-WITH-response** (`0x12`), ~tens of ms after the event. The 3rd byte echoes the music
  ACTION (not the sequence). This is the one to replicate for music.
- **MICRO_APP (`0x08`), e.g. mode-switch / RING_PHONE:** the official app sends NO `3dda0006` ack at
  all. It instead writes a `SETTINGS_BUTTONS` (file `0x06`) file back (PUT/VERIFY on
  `3dda0003`/`3dda0004`) echoing the event payload. So do NOT wire a guessed `02 08 <seq>` ack for
  `0x08`; there is no such frame. The `0x08` mitigation is either replicate that buttons-file
  write-back or keep the existing de-dup (see FINDINGS §18a OPEN item - in capture the `0x08` did
  not storm even unacked, so reconcile with the earlier on-device storm before choosing).
- **NOTIFY (`0x02`) events:** not acked.

The section below was written for the generic `02 <type> <seq>` shape; treat it as the MUSIC-path
wiring (correct the ack bytes to `02 05 <action> 00*5`, write-WITH-response). The `0x08` path needs a
separate decision, not this generic ack.

**Method:** `FossilQAdapter.handleButtonEvent(byte[] value)`
(protocol/src/main/java/qhybrid/protocol/FossilQAdapter.java, ~line 1995).

**Current relevant shape (read 2026-06-15):**

```java
private void handleButtonEvent(byte[] value) {
    if (value.length < 3) return;
    byte opCode   = value[0];
    byte eventType = value[1];
    byte sequence  = value[2];

    // <-- ACK MUST BE SENT HERE, BEFORE the de-dup early-return below.
    if (isDedupedActionEvent(eventType) && isDuplicateAsyncEvent(value)) {
        LOG.debug("Dropping duplicate async event ...");
        return;                       // <-- de-dup early-return
    }
    ... // decode + emitEvent
}
```

**Write seam:** `transport.writeCharacteristic(UUID_CHAR_BUTTON, ackBytes)` where
`UUID_CHAR_BUTTON = 3dda0006-...` is already a field on the adapter (line 64). `3dda0006` is
flagged write+notify, so the transport must use write-WITHOUT-response (`command`) for it
(FINDINGS §2); confirm the capture's write type matches.

**Ordering requirement (CONFIRMED by reading the method):** the ack must be sent **before** the
de-dup early-return, so that EACH retransmit of a REQUEST-opcode frame is still acked. Reasoning:
the de-dup intentionally drops repeats so they do not each drive an effect; but the firmware re-sends
*because* it has not seen an ack, so an ack placed after the early-return would never fire on the
2nd..Nth copy - exactly the copies the firmware is waiting to have acked. The intended steady state
is: ack every REQUEST frame -> firmware stops re-sending after the first -> de-dup then rarely has
anything to drop (it becomes a belt-and-braces safety net, not the primary mechanism).

**Guard:** only ack when `opCode == 0x01` (REQUEST). Do NOT ack `0x02` (NOTIFY) frames.
Likely also scope to the action event types (or whatever the capture shows the official app acks);
acking unrelated streaming events could have side effects.

**Tests to add** (de-dup tests live in
protocol/src/test/java/qhybrid/protocol/AdapterAsyncEventDedupTest.java; the BLE write seam is faked
by protocol/src/test/java/qhybrid/protocol/FakeBleTransport.java, where
`UUID_CHAR_BUTTON = 3dda0006` and writes are recorded via `writesTo(UUID)` / `lastWriteTo(UUID)`):

1. `requestOpcodeEvent_writesAckToButtonChar`: inject one `01 <type> <seq> ...` frame; assert
   `t.writesTo(FakeBleTransport.UUID_CHAR_BUTTON)` contains exactly the expected ack bytes
   (echoing type+seq, per the confirmed format).
2. `ackFiresBeforeDedup_everyRetransmitIsAcked`: inject the SAME `01`-opcode frame N times; assert
   the ack was written N times (one per retransmit) even though only ONE event is EMITTED (de-dup
   still collapses the user-visible effect). This locks in the ordering requirement above.
3. `notifyOpcodeEvent_isNotAcked`: inject a `02`-opcode frame; assert NO write to `3dda0006`.
4. Keep all existing de-dup assertions green (emit-count unchanged).

---

## 6. The play-file memory leak (formerly "Part B") - RESOLVED SEPARATELY

The buzz `0x86` = `NOT_ENOUGH_MEMORY` failure was root-caused from the official-app disassembly
(no btsnoop needed) and FIXED in a parallel session (commit `18553a8`). Cause: we PUT every play
file to a FIXED handle `0x0900`. The official app's `FileHandleManager.getFileHandleToPut`
(`m11016b` in `tmp/FossilOfficialApp-deobf`, for `FileType.NOTIFICATION`) ROTATES the handle low
byte: `(9<<8)|index`, `index` incremented `% 255` => `0x0900, 0x0901, 0x0902, ...`. The fix gives
`FossilQAdapter` a per-connection `notificationPlayIndex`; all three play paths open
`nextNotificationPlayHandle()`, carried by a new
`FilePutRequest(short explicitHandle, FileHandle, byte[], adapter)` constructor. Covered by
`NotificationPlayHandleRotationTest`. See the FINDINGS section
"ROOT CAUSE + FIX (2026-06-15...): rotate the NOTIFICATION_PLAY handle low byte" (marked SHIPPED).
Nothing about the play path needs a capture; this WP no longer tracks it.

> Disambiguation worth keeping (two different `0x86`s - do not confuse them): in the file-control
> OP-byte table (FINDINGS "Control channel & operation codes"), `0x86` is the RESPONSE byte for
> `VERIFY_DATA` (op 6 | 0x80). In the PUT-accept frame `83 00 09 86 00`, the `86` is the **status
> byte at offset 3**, which decodes via `ResultCode` to `134 = NOT_ENOUGH_MEMORY` (NOT `136 =
> NOT_SUPPORT`). Same hex, different field. The leak was about the STATUS byte.

---

## 7. Follow-ups (do NOT do in this task)

- [x] Check the disassembly FIRST (section 0). DONE 2026-06-15: ack format recovered
      (`02 <eventType> <sequence>` on `3dda0006`, write-without-response) and recorded in
      **FINDINGS §18a** + section 0a. Only the trigger condition remains.
- [ ] Confirm the TRIGGER (ack-all vs ack-`0x01`-only) with a short btsnoop (sections 1-3) of the
      button/music/ring events. Record the confirmed trigger + on-wire evidence (capture file name,
      frame numbers, timing) in **FINDINGS §18a**. The ack BYTES are already confirmed from the
      disassembly - do NOT re-derive them; do NOT invent the trigger.
- [ ] Implement the ack wiring (section 5) in a separate task, with the tests there.
- [ ] Re-evaluate whether the de-dup window can be relaxed once the ack stops the re-send at source.

## Provenance / source pointers

- Event frame format + opcode/eventType table: FINDINGS §18; method comment above
  `handleButtonEvent` in FossilQAdapter.java.
- GATT handle <-> UUID map: FINDINGS §20 (hardware-verified handles).
- Write-type-per-characteristic (command vs request): FINDINGS §2.
- De-dup mechanism + the multi-buzz root cause: FossilQAdapter.java lines 113-126, 2008-2105;
  AdapterAsyncEventDedupTest.java; TODO.md "async-event re-send".
- Prior btsnoop-from-bugreport precedent: BLE-CAPTURE-IMPL.md / FINDINGS §21 (bugreport5-8).
- Official-app disassembly to inspect for the ack (section 0):
  `tmp/FossilOfficialApp-deobf/sources/com/fossil/blesdk/device/logic/request/GetAsyncNotificationRequest.java`,
  `.../request/code/AsyncOperationCode.java`, `.../request/RequestId.java`,
  `.../device/event/DeviceEventId.java`.
- Play-file leak (resolved separately, section 6): FINDINGS "ROOT CAUSE + FIX (2026-06-15...):
  rotate the NOTIFICATION_PLAY handle low byte" (SHIPPED, commit 18553a8);
  NotificationPlayHandleRotationTest. The `0x86` = 134 NOT_ENOUGH_MEMORY vs 136 NOT_SUPPORT
  distinction lives in ResultCode.java
  (protocol/src/main/java/qhybrid/protocol/requests/fossil/file/ResultCode.java).
