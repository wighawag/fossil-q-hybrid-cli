# WP: Capture the Official-App Event ACK on 3dda0006

**Status:** ANALYSIS + CAPTURE PLAN ONLY. No production code changes in this task.
Implementation (wiring the ack) is a separate follow-up, gated on a real capture confirming
the ack byte layout.

## Why

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
repeats to one effect but only absorbs the symptom; the watch still re-sends, and the buzz/play
storm wedges the watch's NOTIFICATION_PLAY handle (0x0900), after which buzz PUTs are rejected with
status `0x86` and only a full re-provision clears it.

## Decisive unknown to resolve

After a `3dda0006` event notification, what does the OFFICIAL app WRITE back in the next tens of ms?

1. Is there a write (to `3dda0006` or another char) that echoes the event's `eventType` and/or
   `sequence` (an explicit ack)?
2. Do the official app's own event frames carry `opCode 0x02` (NOTIFY) instead of our observed
   `0x01` (REQUEST), implying the re-send is provoked by something we do/omit?
3. What is the exact byte layout, target characteristic UUID, and timing of any such ack?

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

## 3. "What to look for" checklist (tied to the three unknowns)

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

## 6. Follow-ups (do NOT do in this task)

- [ ] Perform the capture (section 1-3). Record the confirmed ack frame format + provenance
      (capture file name, frame numbers, timing) in **FINDINGS.md** as a new section. Do NOT invent
      bytes - only write FINDINGS after a real capture confirms them.
- [ ] Implement the wiring (section 5) in a separate task, with the tests above.
- [ ] Re-evaluate whether the de-dup window can be relaxed once the ack stops the re-send at source.

## Provenance / source pointers

- Event frame format + opcode/eventType table: FINDINGS §18; method comment above
  `handleButtonEvent` in FossilQAdapter.java.
- GATT handle <-> UUID map: FINDINGS §20 (hardware-verified handles).
- Write-type-per-characteristic (command vs request): FINDINGS §2.
- De-dup mechanism + the multi-buzz root cause: FossilQAdapter.java lines 113-126, 2008-2105;
  AdapterAsyncEventDedupTest.java; TODO.md "async-event re-send".
- Prior btsnoop-from-bugreport precedent: BLE-CAPTURE-IMPL.md / FINDINGS §21 (bugreport5-8).
