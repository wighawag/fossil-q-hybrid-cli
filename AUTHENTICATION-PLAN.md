# Fossil Q Hybrid Authentication Protocol — Plan of Attack

## Goal

Implement the Fossil authentication handshake so that:
1. **Notification filters take effect** → vibration + hand animation from file-based notifications
2. **Eliminate the `3dda0005` findDevice workaround** for vibration
3. **Full notification customization** — per-app hand positions, vibration patterns

## Background

The Fossil Q Hybrid watch has a "user authorization" mechanism on the `3dda0005` (AUTHENTICATION)
BLE characteristic. Without completing this handshake, the watch:
- Accepts file uploads (no error)
- Silently ignores notification filters
- Does not trigger vibration/hand movement from NOTIFICATION_PLAY files

The official Fossil app performs this handshake during every reconnect. GadgetBridge has the
code for Fossil HR but never uses it for Q Hybrid — which is why GB also falls back to the
call characteristic for vibration.

## What We Know

### Auth Characteristic: `3dda0005-957f-7d4a-34a6-74696673696d`

**Operation format:** `[operation_id(1)][package_type_id(1)][payload...]`

**Operation IDs:**
| ID | Name |
|----|------|
| 0x01 | GET (request/query) |
| 0x02 | SET (write/authenticate) |
| 0x03 | RESPONSE (from watch) |

**Package Type IDs:**
| ID | Name | Direction |
|----|------|-----------|
| 0x01 | SEND_PHONE_RANDOM_NUMBER | SET → watch |
| 0x02 | SEND_BOTH_RANDOM_NUMBER | SET → watch |
| 0x03 | EXCHANGE_PUBLIC_KEY | SET → watch |
| 0x04 | PROCESS_USER_AUTHORIZATION | GET → watch (deprecated) |
| 0x05 | STOP_PROCESS | SET → watch |
| 0x06 | PROCESS_USER_AUTHORIZATION_V2 | SET → watch |
| 0x07 | GET_USER_AUTHORIZATION_STATUS | GET → watch |

### BLE Capture Evidence

**Capture 1 (bugreport1)** — reconnect session:
```
App writes:    01 07                     → GET + GET_USER_AUTHORIZATION_STATUS
Watch responds: 03 07 01                 → RESPONSE + status=0x01 (already authorized)
```
No further auth needed — watch was already paired with this phone.

**Capture 3 (bugreport3)** — reconnect after longer disconnect:
```
App writes:    02 06 30 75 00 00 01      → SET + PROCESS_USER_AUTHORIZATION_V2 + magic + 01
Watch responds: 03 06 00 01              → RESPONSE + status 00 01 (success)
```
Watch needed re-authorization.

### GadgetBridge Code (already vendored in `gadgetbridge/`)

| Class | Bytes | Purpose |
|-------|-------|---------|
| `CheckDeviceNeedsConfirmationRequest` | `01 07` | Check if watch needs auth |
| `ConfirmOnDeviceRequest` | `02 06 30 75 00 00 00` | Authorize (magic bytes hardcoded!) |
| `VerifyPrivateKeyRequest` | `02 01 01 [8 random bytes]` | AES challenge-response (Fossil HR only) |
| `CheckDevicePairingRequest` | `01 16` | Check pairing status |
| `PerformDevicePairingRequest` | `02 16` | Perform pairing |

### Key Insight: The `30 75 00 00 01` Payload Decoded!

These are **NOT magic bytes or a secret key**. From `ConfirmAuthorizationRequest.java`:

```java
ByteBuffer.allocate(5)
    .order(ByteOrder.LITTLE_ENDIAN)
    .putInt((int) timeoutInMs)      // 4 bytes LE
    .put(removeOtherLinkedPhones ? 1 : 0)  // 1 byte
```

- `30 75 00 00` = `0x00007530` LE = **30000 milliseconds** (30 second timeout for user to press watch button)
- `01` = **removeOtherLinkedPhones = true** (unpair other devices)

GadgetBridge's `ConfirmOnDeviceRequest` sends `30 75 00 00 00` (same timeout, but
`removeOtherLinkedPhones = false`).

## The Protocol Flow

### Authorization Flow (Q Hybrid)

The `ConfirmAuthorizationRequest` in the official Fossil app uses:
- `AuthenticationOperationCode.PROCESS_USER_AUTHORIZATION_V2` = SET(0x02) + type 0x06
- Request data: `[timeoutMs(4 LE)][removeOtherPhones(1)]`
- Response data: `[userAction(1)]` where 0=REJECT, 1=ACCEPT

```
1. App → Watch:  01 07                        (GET_USER_AUTHORIZATION_STATUS)
   Watch → App:  03 07 XX                     (status)
                  XX=0x00: needs confirmation (user must press watch button)
                  XX=0x01: already authorized

2. If XX != 0x01 (needs authorization):
   App → Watch:  02 06 30 75 00 00 01         (PROCESS_USER_AUTHORIZATION_V2)
                  30 75 00 00 = timeout 30000ms LE
                  01 = removeOtherLinkedPhones
   [Watch waits up to 30s for user to press button to confirm]
   Watch → App:  03 06 00 XX                  (response)
                  XX=0x00: REJECT (user didn't press / timed out)
                  XX=0x01: ACCEPT (user confirmed)

3. Now notification filters are active!
```

Note: In capture 3 (bugreport3), only step 2 happened — the app skipped step 1 and went
straight to confirmation. In capture 1 (bugreport1), only step 1 happened and the watch
responded with `0x01` (already authorized), so step 2 was skipped.

### AES Challenge-Response (First-Time Pairing, Fossil HR)

The Fossil HR uses AES-128-CBC for mutual authentication during first pairing:
```
1. App → Watch:  02 01 [keyType] [8 random phone bytes]   (SEND_PHONE_RANDOM_NUMBER)
   Watch → App:  03 01 [status] [16 encrypted bytes]      (watch's challenge)

2. App decrypts with shared 16-byte AES key (IV=0) → gets [8 watch random + 8 phone random]
   App verifies its phone random matches bytes [8:16]
   App swaps halves (watch_random + phone_random → phone_random + watch_random)
   App re-encrypts

3. App → Watch:  02 02 [keyType] [16 encrypted bytes]     (SEND_BOTH_RANDOM_NUMBERS)
   Watch → App:  03 02 [status]                           (success/failure)
```

**Key types:**
- PRE_SHARED_KEY (0x00): data passes through unchanged
- SIXTEEN_BYTES_MSB_ECDH (0x01): data passes through unchanged
- SIXTEEN_BYTES_LSB_ECDH (0x02): data is byte-reversed

**AES parameters:** AES-128-CBC, no padding, IV = 16 zero bytes

**The shared 16-byte secret key** is:
- Established during EXCHANGE_PUBLIC_KEYS (0x03) using ECDH key exchange
- Stored on both the phone and watch
- Used for all subsequent AES operations

The Q Hybrid may or may not require this AES path. First test will be whether the simpler
PROCESS_USER_AUTHORIZATION_V2 path works from a clean state (after `bluetoothctl remove`).

## Plan of Attack

### Phase 1: Simple Auth — PROCESS_USER_AUTHORIZATION_V2

**Effort: Small — implement and test in ~30 minutes**

Add to `FossilQAdapter.initFossilProtocol()`, BEFORE `syncNotificationSettings()`:

```java
// Step 1: Check if watch needs authorization
byte[] checkAuth = {0x01, 0x07};
writeToAuthChar(checkAuth);
// Wait for indication: 03 07 XX
// XX=0x01 → already authorized, skip to step 3
// XX=0x00 → needs confirmation, continue to step 2

// Step 2: Request user authorization (user must press watch button within 30s)
byte[] confirmAuth = {0x02, 0x06, 0x30, 0x75, 0x00, 0x00, 0x01};
//                                  ^^^^^^^^^^^^  ^^-- removeOtherPhones=true
//                                  30s timeout LE
writeToAuthChar(confirmAuth);
// Wait for indication: 03 06 00 XX
// XX=0x01 → ACCEPT (user pressed button)
// XX=0x00 → REJECT (user didn't press / timed out)

// Step 3: Upload notification filter (now it will actually be used)
syncNotificationSettings();
```

**Implementation details:**
- `3dda0005` uses **indications** (not notifications) — requires explicit confirmation
- Our `BluezTransport` already enables CCCD for this characteristic
- Need to add: write-to-auth-char + wait-for-indication with timeout
- The auth char is the same one used for `findDevice()` — but `findDevice()` writes
  without waiting for indications, while auth requires reading the response
- Auth indication handler must be separate from the file transfer notification handler

**What to expect when testing:**
- On `01 07`: Watch might briefly flash/animate to indicate auth check
- On `02 06 ...`: Watch will likely show a confirmation animation — user must press
  the crown/button within 30 seconds to authorize
- After authorization: notification filters take effect → vibration + hand animation!

**Test plan:**
1. Implement the two-step auth
2. Run `notify` command with watch freshly unpaired (`bluetoothctl remove`)
3. When prompted, press watch button to authorize
4. If watch vibrates AND moves hands to filter position → auth works!
5. On subsequent runs, check if `01 07` returns `0x01` (already authorized)
6. If so, skip step 2 → faster reconnects

**Fallback:** If `02 06 30 75 00 00 01` fails, try:
- Last byte `00` (don't remove other phones — GB's default)
- Shorter timeout (e.g., `10 27 00 00` = 10000ms)
- The deprecated `01 04` (PROCESS_USER_AUTHORIZATION v1) path

### Phase 2: AES Challenge-Response (If Phase 1 Fails)

**Effort: Medium — AES crypto + key management**

If the Q Hybrid requires AES mutual authentication before accepting auth V2:

1. **Implement ECDH key exchange** (EXCHANGE_PUBLIC_KEY, type 0x03)
   - Generate EC key pair
   - Exchange public keys with watch
   - Derive shared secret → first 16 bytes = AES key

2. **Implement AES challenge-response** (`VerifyPrivateKeyRequest` equivalent)
   - Send 8 random bytes encrypted with shared key
   - Receive watch's challenge (16 encrypted bytes)
   - Decrypt → verify → swap halves → re-encrypt → send back

3. **Key persistence** — store the shared AES key
   - Save to `~/.config/fossil-q/auth-key-<MAC>.bin` (16 bytes)
   - Load on reconnect → skip key exchange, just do AES verify

**Strategy:**
- First test Phase 1 thoroughly (the Q Hybrid might not need AES at all)
- If AES is needed, the code is already in GB's `VerifyPrivateKeyRequest.java`
- A BLE capture of first-time pairing from the official app would confirm

### Phase 3: Full Notification System

**Effort: Small (once auth works)**

With auth working, notification filters are active:

The filter format is **already fully decoded and byte-identical to the official app**:
```
Entry (32 bytes):
  [packetLength=30 (2 LE)]
  [0x04][4][CRC32 of packageName+'\0' (4 LE)]     PKG_CRC
  [0x80][1][0x00]                                   GROUP_ID
  [0xC1][1][0x00]                                   PRIORITY
  [0xC2][10][hour° min° sub° dur_ms sub2°]         HAND_MOVEMENT (all shorts LE)
  [0xC4][1][0x00]                                   DISPLAY_CONFIG
  [0xC3][1][vibePattern]                            VIBRATION
```

**Vibration patterns:** AUTO(0), CALL(1), TEXT(2), EMAIL(3), DEFAULT(4),
ONE_SHORT(5), TWO_SHORT(6), THREE_SHORT(7), ONE_LONG(8), NO_VIBE(9)

**NOTIFICATION_PLAY file format (lbl=12):**
```
[mainBufferLength (2 LE)]
[lengthBufferLength=12 (1)]
[type=2 (1)]              NOTIFICATION type
[flags=2 (1)]             FINISHED flag
[uidLen=4 (1)]
[crcLen=4 (1)]
[titleLen (1)]
[senderLen (1)]
[msgLen (1)]
[sentinelLen=4 (1)]
[timestampLen=4 (1)]
[uid (4 LE)]              notification ID
[crc (4 LE)]              CRC32(packageName + '\0') — must match filter!
[title bytes]
[sender bytes]
[msg bytes]
[0xFF 0xFF 0xFF 0xFF]     sentinel
[timestamp (4 LE)]        Unix epoch seconds
```

## Files Reference

### Source code
- `src/main/java/qhybrid/linux/FossilQAdapter.java` — main adapter, init, notify
- `src/main/java/qhybrid/linux/BluezTransport.java` — BLE transport (scan, connect, read, write)
- `src/main/java/qhybrid/linux/BleTransport.java` — transport interface

### GadgetBridge vendored auth code (in `gadgetbridge/`, NOT in `src/`)
- `gadgetbridge/.../fossil_hr/authentication/AuthenticationRequest.java` — base class
- `gadgetbridge/.../fossil_hr/authentication/CheckDeviceNeedsConfirmationRequest.java` — `01 07`
- `gadgetbridge/.../fossil_hr/authentication/ConfirmOnDeviceRequest.java` — `02 06 30 75 00 00 00`
- `gadgetbridge/.../fossil_hr/authentication/VerifyPrivateKeyRequest.java` — AES challenge-response

### Official Fossil app (decompiled in `tmp/FossilOfficialApp-deobf/`)
- `tmp/.../authentication/AuthenticationRequest.java` — base class
- `tmp/.../code/AuthenticationOperationCode.java` — operation/package type enums
- `tmp/.../code/AuthenticationResponseStatusCode.java` — response codes

### BLE captures
- `tmp/bugreport/FS/data/misc/bluetooth/logs/btsnoop_hci.log` — first capture (reconnect, auth=01 07)
- `tmp/bugreport3/FS/data/misc/bluetooth/logs/btsnoop_hci.log` — third capture (reconnect, full flow)
- Handle mapping: 0x0042=DC, 0x0045=FTC, 0x0048=FTD, 0x004b=AUTH, 0x004e=ASYNC, 0x004c=AUTH CCCD

## Official Fossil App Pairing Flow (User-Observed)

This maps the on-screen UX to the likely BLE protocol steps:

```
1. "Start Pairing" screen → "Continue"
   - App hasn't started scanning yet

2. "Looking for your watch" (animated circles)
   - BLE scan starts. Watch found WITHOUT pressing button (watch may already
     be advertising from a previous button press, or does passive scan match)
   - App finds device by Fossil service UUID in advertisement

3. "1 Device Found" → "This is my watch"
   - User selects device. App initiates BLE connection.

4. "Please wait" popup (1-2 seconds)
   - App connects to GATT, discovers services, reads device info
   - App may do initial handshake on 3dda0005

5. "Accept on My Watch" — watch starts vibrating!
   - App wrote PROCESS_USER_AUTHORIZATION_V2 (02 06 30 75 00 00 01)
   - Watch vibrates to signal "press top button to authorize"
   - Timeout: 30 seconds (0x7530 = 30000ms)
   - Top button = ACCEPT (0x01), Bottom button = REJECT (0x00)
   - Watch responds on 3dda0005: 03 06 00 01 (ACCEPT)

6. Android "Pair with Fossil?" popup
   - This is standard Android BLE bonding (not the Fossil auth)
   - The app calls createBond() after Fossil-level auth succeeds
   - The "contacts and call history" toggle is for Android permissions
   - This creates the BLE bond (LTK exchange) for encrypted connection

7. "Almost there" (progress bar)
   - AES key exchange via ECDH (EXCHANGE_PUBLIC_KEY, type 0x03)
   - OR: AES challenge-response with pre-shared key
   - App uploads: configuration, notification filters, contacts, etc.
   - This is when the notification filters are first written!

8. Dashboard
   - Watch is fully paired and configured
```

**Key takeaway:** Step 5 confirms that PROCESS_USER_AUTHORIZATION_V2 triggers watch
vibration and waits for button press. This is the exact auth step we need to implement.
The watch button press is the "user consent" that authorizes the phone to control
notification behavior.

**Important:** Step 6 (Android bonding) happens AFTER Fossil auth (step 5). This means
the Fossil auth works on an unencrypted connection. We don't need BLE bonding for it.
However, BLE bonding might be needed for the subsequent AES key exchange (step 7).

**For our CLI:** We skip step 6 entirely (we use `bluetoothctl trust`, not `pair`).
The question is whether step 7 (AES key exchange) is required for notification filters
to work, or if step 5 alone is sufficient.

## Quick Test (Before Full Implementation)

You can test the auth immediately with raw BLE writes. While the CLI is connected:

```bash
# In bluetoothctl:
# 1. Check auth status
gatt.write /org/bluez/hci0/dev_D9_20_71_11_74_2A/service0040/char004a 01 07

# 2. Wait for indication on char004a (03 07 XX)

# 3. If needed, send confirmation
gatt.write /org/bluez/hci0/dev_D9_20_71_11_74_2A/service0040/char004a 02 06 30 75 00 00 01

# 4. Wait for indication (03 06 00 01)
```

Or in the Java code, write to the auth UUID directly:
```java
transport.write(UUID.fromString("3dda0005-957f-7d4a-34a6-74696673696d"),
                new byte[]{0x01, 0x07});
// wait for indication...
transport.write(UUID.fromString("3dda0005-957f-7d4a-34a6-74696673696d"),
                new byte[]{0x02, 0x06, 0x30, 0x75, 0x00, 0x00, 0x01});
// wait for indication 03 06 00 01...
```

## Authentication Response Status Codes

From `AuthenticationResponseStatusCode.java` in the official Fossil app:

| Code | Name | Description |
|------|------|-------------|
| 0x00 | SUCCESS | Operation succeeded |
| 0x01 | WRONG_LENGTH | Request data wrong length |
| 0x02 | FAIL_TO_GENERATE_RANDOM_NUMBER | Watch RNG failure |
| 0x03 | FAIL_TO_GET_KEY | Key not found |
| 0x04 | FAIL_TO_ENCRYPT_FRAME | AES encryption failed |
| 0x05 | FAIL_TO_DECRYPT_FRAME | AES decryption failed |
| 0x06 | INVALID_KEY_TYPE | Unknown key type |
| 0x07 | WRONG_CONFIRM_RANDOM_NUMBER | AES challenge failed |
| 0x08 | WRONG_STATE | Auth in wrong state |
| 0x09 | FAIL_TO_STORE_KEY | Key storage failed |
| 0x0A | WRONG_KEY_OPERATION | Invalid operation |
| 0x0B | NOT_SUPPORTED | Feature not supported |
| 0x0C | FAIL_TO_GENERATE_PUBLIC_KEY | ECDH key gen failed |
| 0x0D | FAIL_TO_GENERATE_SECRET_KEY | ECDH secret failed |
| 0x0E | FAIL_BECAUSE_OF_INVALID_INPUT | Bad input data |
| 0x0F | FAIL_BECAUSE_OF_NOT_SET_KEY | No key configured |
| 0x10 | FAIL_TO_SEND_REQUEST_AUTHENTICATE_THROUGH_ASYNC | Internal error |

These appear as byte[2] in the response: `03 XX YY` where XX is the package type
and YY is the status code.

## User Authorization Actions (Response to ConfirmAuthorizationRequest)

| Value | Action |
|-------|--------|
| 0x00 | REJECT (user didn't press button / timed out) |
| 0x01 | ACCEPT (user pressed button to confirm) |

## Implementation Constraint: Auth vs File Transfer on 3dda0005

In `FossilQAdapter.onCharacteristicChanged()`, notifications/indications from `3dda0005`
currently fall through to the Fossil file transfer request handler (`currentFossilRequest`).
This means auth indications would be consumed by the wrong handler.

**Solution options:**
1. Add a dedicated `pendingAuthResponse` field. When auth is in progress, intercept
   `3dda0005` indications before they reach the file transfer handler.
2. Use `CompletableFuture<byte[]>` or similar — auth write sets up the future,
   `onCharacteristicChanged` completes it when response arrives on `3dda0005`.
3. Run auth BEFORE setting up the file transfer request queue (simplest — auth
   happens during init, before any `FilePutRequest` is queued).

Option 3 is simplest since auth happens early in init, before `syncConfiguration()`
or `syncNotificationSettings()` which use the request queue.
