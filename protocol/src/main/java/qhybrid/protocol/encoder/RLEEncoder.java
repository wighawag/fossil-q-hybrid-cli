/*
 * Derived from GadgetBridge (Codeberg: Gadgetbridge/Gadgetbridge) @ commit
 * f5b5416ca84de63a65e527e1e5e0a5202f3e3f4f (GPLv3). Adapted and re-owned for
 * fossil-q-hybrid: platform-neutral, package qhybrid.protocol.
 * This file is part of fossil-q-hybrid, licensed AGPLv3. See PROTOCOL-PROVENANCE.md.
 */
/*  Copyright (C) 2020-2024 Daniel Dakhno

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
package qhybrid.protocol.encoder;

import java.io.ByteArrayOutputStream;

public class RLEEncoder {
    public static byte[] RLEEncode(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 2);

        int lastByte = data[0];
        int count = 1;
        byte currentByte = -1;

        for (int i = 1; i < data.length; i++) {
            currentByte = data[i];

            if (currentByte != lastByte || count >= 255) {
                bos.write(count);
                bos.write(data[i - 1]);

                count = 1;
                lastByte = data[i];
            } else {
                count++;
            }
        }

        bos.write(count);
        bos.write(currentByte);

        byte[] result = bos.toByteArray();

        return result;
    }
}
