// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.requests.fossil.file;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the signed-byte status decode (FINDINGS "ROOT CAUSE PROVEN"): a VERIFY/accept
 * status byte of 0x83 read as a Java {@code byte} sign-extends to -125. {@link ResultCode#fromCode}
 * must mask to unsigned so 0x83 resolves to {@link ResultCode#FIRMWARE_INTERNAL_ERROR_NOT_FOUND}
 * (131), NOT a bogus negative / UNKNOWN. The watch sends 0x83 when a play file's VERIFY is rejected
 * after the area went bad.
 */
public class ResultCodeTest {

    @Test
    void signedStatusByte0x83_resolvesToNotFound() {
        byte raw = (byte) 0x83; // = -125 as a signed Java byte (the value the wire delivers)
        assertEquals(ResultCode.FIRMWARE_INTERNAL_ERROR_NOT_FOUND, ResultCode.fromCode(raw));
        // And the already-unsigned form must resolve identically.
        assertEquals(ResultCode.FIRMWARE_INTERNAL_ERROR_NOT_FOUND, ResultCode.fromCode(0x83));
    }

    @Test
    void knownCodesResolve() {
        assertEquals(ResultCode.SUCCESS, ResultCode.fromCode(0));
        assertEquals(ResultCode.VERIFICATION_FAIL, ResultCode.fromCode(5));
        // 0x86 = 134 = NOT_ENOUGH_MEMORY (the play-area-full status), also via a signed byte.
        assertEquals(ResultCode.FIRMWARE_INTERNAL_ERROR_NOT_ENOUGH_MEMORY, ResultCode.fromCode((byte) 0x86));
    }

    @Test
    void unmappedCodeIsUnknown() {
        assertEquals(ResultCode.UNKNOWN, ResultCode.fromCode(0x7F));
    }
}
