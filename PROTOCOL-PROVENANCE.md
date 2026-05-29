# Protocol Layer Provenance

The Fossil Q Hybrid protocol implementation in `:protocol`
(`qhybrid.protocol.*`) is **derived from GadgetBridge** and re-owned for this
project. This file records the origin of each derived source file for attribution
and so improvements can be back-ported upstream if desired.

## Upstream source

- **Project:** GadgetBridge — <https://codeberg.org/Gadgetbridge/Gadgetbridge>
- **Commit:** `f5b5416ca84de63a65e527e1e5e0a5202f3e3f4f`
  (reported source revision; the local `tmp/Gadgetbridge` clone is a different
  working tree and could not be used to verify byte-for-byte — treat the commit as
  the best-effort provenance reference).
- **Upstream license:** GPLv3.

## Licensing

This repository is licensed **AGPLv3**. GPLv3 code may be combined into an AGPLv3
work; the combined result is governed by AGPLv3. Re-owning these files is **not**
relicensing — they remain copyleft. Each derived file carries a provenance + license
header pointing back here.

## What was re-owned (and what changed)

The re-own made the protocol layer **platform-neutral**:

- Removed all `android.*` / `androidx.*` usage. `BluetoothGattCharacteristic` in
  request handlers collapsed to `java.util.UUID` (`handleResponse(UUID, byte[])`);
  `androidx.annotation.*` annotations dropped.
- Replaced GadgetBridge's `TransactionBuilder` with the owned
  `qhybrid.protocol.WriteBatch`; `AbstractBTLEDeviceSupport.calcMaxWriteChunk` moved
  onto `WriteBatch`.
- Replaced the `GBDevice` / `QHybridSupport` / `FossilWatchAdapter` /
  `WatchAdapter` / `DeviceSupport` shims with owned equivalents
  (`qhybrid.protocol.{FossilWatchAdapter, DeviceSupport}`,
  `qhybrid.protocol.model.DeviceState`, `qhybrid.protocol.model.DeviceInfoItem`).
- `ResultCode` relocated from `requests/fossil_hr/file` to `requests/fossil/file`.
- `NotificationConfiguration` relocated to `requests/fossil/notification`.
- Wire formats are **unchanged**; the golden-byte tests in `protocol/src/test` are
  the regression gate.

Unused upstream code (all `fossil_hr/**`, OTA/firmware, encrypted file requests,
misfit legacy not exercised by the CLI) was **not** re-owned.

## File mapping (re-owned path ← original GadgetBridge path)

| Re-owned (`protocol/src/main/java/...`) | Original GadgetBridge path |
|---|---|
| `qhybrid/protocol/buttonconfig/ConfigFileBuilder.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/buttonconfig/ConfigFileBuilder.java` |
| `qhybrid/protocol/buttonconfig/ConfigPayload.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/buttonconfig/ConfigPayload.java` |
| `qhybrid/protocol/encoder/RLEEncoder.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/encoder/RLEEncoder.java` |
| `qhybrid/protocol/file/FileHandle.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/file/FileHandle.java` |
| `qhybrid/protocol/requests/Request.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/Request.java` |
| `qhybrid/protocol/requests/fossil/FossilRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/FossilRequest.java` |
| `qhybrid/protocol/requests/fossil/RequestMtuRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/RequestMtuRequest.java` |
| `qhybrid/protocol/requests/fossil/SetDeviceStateRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/SetDeviceStateRequest.java` |
| `qhybrid/protocol/requests/fossil/alarm/Alarm.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/alarm/Alarm.java` |
| `qhybrid/protocol/requests/fossil/alarm/AlarmsSetRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/alarm/AlarmsSetRequest.java` |
| `qhybrid/protocol/requests/fossil/configuration/ConfigurationPutRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/configuration/ConfigurationPutRequest.java` |
| `qhybrid/protocol/requests/fossil/device_info/DeviceInfo.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/device_info/DeviceInfo.java` |
| `qhybrid/protocol/requests/fossil/device_info/DeviceSecurityVersionInfo.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/device_info/DeviceSecurityVersionInfo.java` |
| `qhybrid/protocol/requests/fossil/device_info/GetDeviceInfoRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/device_info/GetDeviceInfoRequest.java` |
| `qhybrid/protocol/requests/fossil/device_info/SupportedFileVersionsInfo.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/device_info/SupportedFileVersionsInfo.java` |
| `qhybrid/protocol/requests/fossil/file/FileDeleteRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/file/FileDeleteRequest.java` |
| `qhybrid/protocol/requests/fossil/file/FileGetRawRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/file/FileGetRawRequest.java` |
| `qhybrid/protocol/requests/fossil/file/FileGetRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/file/FileGetRequest.java` |
| `qhybrid/protocol/requests/fossil/file/FileLookupAndGetRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/file/FileLookupAndGetRequest.java` |
| `qhybrid/protocol/requests/fossil/file/FileLookupRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/file/FileLookupRequest.java` |
| `qhybrid/protocol/requests/fossil/file/FilePutRawRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/file/FilePutRawRequest.java` |
| `qhybrid/protocol/requests/fossil/file/FilePutRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/file/FilePutRequest.java` |
| `qhybrid/protocol/requests/fossil/file/ResultCode.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil_hr/file/ResultCode.java` |
| `qhybrid/protocol/requests/fossil/notification/NotificationConfiguration.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/qhybrid/NotificationConfiguration.java` |
| `qhybrid/protocol/requests/fossil/notification/NotificationFilterPutRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/fossil/notification/NotificationFilterPutRequest.java` |
| `qhybrid/protocol/requests/misfit/ActivityPointGetRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/ActivityPointGetRequest.java` |
| `qhybrid/protocol/requests/misfit/AnimationRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/AnimationRequest.java` |
| `qhybrid/protocol/requests/misfit/GetCurrentStepCountRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/GetCurrentStepCountRequest.java` |
| `qhybrid/protocol/requests/misfit/GetStepGoalRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/GetStepGoalRequest.java` |
| `qhybrid/protocol/requests/misfit/GetVibrationStrengthRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/GetVibrationStrengthRequest.java` |
| `qhybrid/protocol/requests/misfit/MoveHandsRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/MoveHandsRequest.java` |
| `qhybrid/protocol/requests/misfit/PlayNotificationRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/PlayNotificationRequest.java` |
| `qhybrid/protocol/requests/misfit/ReleaseHandsControlRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/ReleaseHandsControlRequest.java` |
| `qhybrid/protocol/requests/misfit/RequestHandControlRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/RequestHandControlRequest.java` |
| `qhybrid/protocol/requests/misfit/SaveCalibrationRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/SaveCalibrationRequest.java` |
| `qhybrid/protocol/requests/misfit/SetCurrentStepCountRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/SetCurrentStepCountRequest.java` |
| `qhybrid/protocol/requests/misfit/SetStepGoalRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/SetStepGoalRequest.java` |
| `qhybrid/protocol/requests/misfit/SetTimeRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/SetTimeRequest.java` |
| `qhybrid/protocol/requests/misfit/SetVibrationStrengthRequest.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/qhybrid/requests/misfit/SetVibrationStrengthRequest.java` |
| `qhybrid/protocol/util/CRC32C.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/CRC32C.java` |
| `qhybrid/protocol/util/Version.java` | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/Version.java` |

## Reference tree

`tmp/Gadgetbridge/` is kept as a local, gitignored read-only reference. The
former `sync.sh` (which copied the vendored tree verbatim) has been removed — the
protocol is now owned, not vendored.
