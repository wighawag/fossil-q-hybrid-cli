/*
 * Derived from GadgetBridge (Codeberg: Gadgetbridge/Gadgetbridge) @ commit
 * f5b5416ca84de63a65e527e1e5e0a5202f3e3f4f (GPLv3). Adapted and re-owned for
 * fossil-q-hybrid: platform-neutral (no Android types), package qhybrid.protocol.
 * This file is part of fossil-q-hybrid, licensed AGPLv3. See PROTOCOL-PROVENANCE.md.
 */
/*  Copyright (C) 2019-2024 Daniel Dakhno

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package qhybrid.protocol.requests.fossil.file;

public enum ResultCode {
    SUCCESS(0, true),
    INVALID_OPERATION_DATA(1, false),
    OPERATION_IN_PROGRESS(2, false),
    MISS_PACKET(3, false),
    SOCKET_BUSY(4, false),
    VERIFICATION_FAIL(5, false),
    OVERFLOW(6, false),
    SIZE_OVER_LIMIT(7, false),
    FIRMWARE_INTERNAL_ERROR(128, false),
    FIRMWARE_INTERNAL_ERROR_NOT_OPEN(129, false),
    FIRMWARE_INTERNAL_ERROR_ACCESS_ERROR(130, false),
    FIRMWARE_INTERNAL_ERROR_NOT_FOUND(131, false),
    FIRMWARE_INTERNAL_ERROR_NOT_VALID(132, false),
    FIRMWARE_INTERNAL_ERROR_ALREADY_CREATE(133, false),
    FIRMWARE_INTERNAL_ERROR_NOT_ENOUGH_MEMORY(134, false),
    FIRMWARE_INTERNAL_ERROR_NOT_IMPLEMENTED(135, false),
    FIRMWARE_INTERNAL_ERROR_NOT_SUPPORT(136, false),
    FIRMWARE_INTERNAL_ERROR_SOCKET_BUSY(137, false),
    FIRMWARE_INTERNAL_ERROR_SOCKET_ALREADY_OPEN(138, false),
    FIRMWARE_INTERNAL_ERROR_INPUT_DATA_INVALID(139, false),
    FIRMWARE_INTERNAL_NOT_AUTHENTICATE(140, false),
    FIRMWARE_INTERNAL_SIZE_OVER_LIMIT(141, false),
    UNKNOWN(-1, false);

    // NOTE: the firmware-internal codes are 128..141 (0x80..0x8D). The wire status byte is a single
    // unsigned byte, but Java `byte` is SIGNED, so e.g. 0x83 read as a byte is -125. fromCode()
    // below masks to unsigned (& 0xFF) so 0x83 resolves to NOT_FOUND (131), NOT a bogus negative.
    // (A former UNKNOWN_1(-125) entry existed only to "catch" that sign-extended 0x83 — removed,
    // since masking fixes it at the source. See FINDINGS "ROOT CAUSE PROVEN".)

    final boolean success;
    final int code;

    ResultCode(int code, boolean success) {
        this.code = code;
        this.success = success;
    }

    public boolean inidicatesSuccess() {
        return this.success;
    }

    public static ResultCode fromCode(int code) {
        // Mask to an unsigned byte so a sign-extended status (e.g. 0x83 read as a Java byte = -125)
        // still resolves to its real code (0x83 = 131 = NOT_FOUND) instead of falling through to
        // UNKNOWN. All real status values are a single byte (0..255), so this is always safe.
        int unsigned = code & 0xFF;
        for (ResultCode resultCode : ResultCode.values()) {
            if (resultCode.code == unsigned) {
                return resultCode;
            }
        }
        return UNKNOWN;
    }
}
