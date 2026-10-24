// This file is part of fossil-q-hybrid, licensed AGPLv3.
package qhybrid.protocol.golden;

import org.junit.jupiter.api.Test;
import qhybrid.protocol.buttonconfig.ConfigPayload;
import qhybrid.protocol.buttonconfig.HidKeyPayload;
import qhybrid.protocol.buttonconfig.HidKeyPayloadTestAccess;

import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Golden tests for the HID-key (SELFIE) button entry builder. FINDINGS §26b/c. */
public class HidKeyPayloadTest {

    @Test
    void templateReproducesCapturedVolumeUpAndVolumeDownExactly() {
        // Same builder, official declIds + usages -> must equal the captured official-app entries.
        // This locks the template, the usage offsets AND the CRC algorithm against real captures.
        assertArrayEquals(ConfigPayload.VOLUME_UP.getData(), HidKeyPayloadTestAccess.data(0x1204, 0xE9));
        assertArrayEquals(ConfigPayload.VOLUME_DOWN.getData(), HidKeyPayloadTestAccess.data(0x1205, 0xEA));
    }

    @Test
    void takePictureDiffersFromVolumeUpOnlyInDeclIdAndCrc() {
        byte[] vup = ConfigPayload.VOLUME_UP.getData();
        byte[] pic = ConfigPayload.HID_VOLUME_UP.getData();
        assertEquals(vup.length, pic.length);
        for (int i = 0; i < vup.length - 4; i++) {
            if (i == 3 || i == 4) continue;
            assertEquals(vup[i], pic[i], "byte " + i);
        }
        assertEquals(0x01, pic[3]);
        assertEquals(0x10, pic[4]);
        assertArrayEquals(new byte[]{0x01, 0x01, 0x10, 0x00}, ConfigPayload.HID_VOLUME_UP.getHeader());
    }

    @Test
    void everyHidKeyCarriesItsUsageAndAValidCrc() {
        for (HidKeyPayload.HidKey key : HidKeyPayload.HidKey.values()) {
            byte[] d = HidKeyPayload.data(key);
            assertEquals(94, d.length);
            assertEquals(d.length, d[5] & 0xFF, "length byte");
            assertEquals(key.usage(), d[53] & 0xFF);
            assertEquals(key.usage(), d[66] & 0xFF);
            CRC32 crc = new CRC32();
            crc.update(d, 0, d.length - 4);
            long got = (d[d.length - 4] & 0xFFL) | (d[d.length - 3] & 0xFFL) << 8
                    | (d[d.length - 2] & 0xFFL) << 16 | (d[d.length - 1] & 0xFFL) << 24;
            assertEquals(crc.getValue(), got, key + " CRC");
        }
        assertNotEquals(ConfigPayload.HID_VOLUME_DOWN.getData()[53], ConfigPayload.HID_VOLUME_UP.getData()[53]);
    }
}
