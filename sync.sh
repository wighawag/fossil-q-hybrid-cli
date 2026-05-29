#!/bin/bash
# sync.sh — pure copy from upstream GadgetBridge, no patches needed
set -euo pipefail

GB_REPO="${1:-tmp/Gadgetbridge}"
BASE="app/src/main/java/nodomain/freeyourgadget/gadgetbridge"
# Vendored tree now lives in the :protocol module.
DST_ROOT="protocol/gadgetbridge"
SRC_SVC="$GB_REPO/$BASE/service/devices/qhybrid"
DST_SVC="$DST_ROOT/$BASE/service/devices/qhybrid"

# Protocol layer (requests, file handles, encoder, button config)
# NOTE: parser/ is NOT copied — heavy GB entity deps, we parse activity data ourselves
# NOTE: adapter/ is NOT copied — we provide shim classes instead
# NOTE: QHybridSupport.java / QHybridBaseSupport.java are NOT copied — we provide shims
mkdir -p "$DST_SVC"
for dir in requests file encoder buttonconfig; do
    rm -rf "$DST_SVC/$dir"
    cp -r "$SRC_SVC/$dir" "$DST_SVC/$dir"
done

# NotificationConfiguration (data class, pure Java + Serializable)
mkdir -p "$DST_ROOT/$BASE/devices/qhybrid"
cp "$GB_REPO/$BASE/devices/qhybrid/NotificationConfiguration.java" \
   "$DST_ROOT/$BASE/devices/qhybrid/"

# Utility classes (pure Java, no android deps — vendored verbatim)
mkdir -p "$DST_ROOT/$BASE/util"
cp "$GB_REPO/$BASE/util/CRC32C.java" "$DST_ROOT/$BASE/util/"
cp "$GB_REPO/$BASE/util/Version.java" "$DST_ROOT/$BASE/util/"
# StringUtils is NOT vendored — real class imports commons-lang3. We provide a minimal shim.

echo "Synced from $(cd "$GB_REPO" && git rev-parse --short HEAD). No patches needed."
