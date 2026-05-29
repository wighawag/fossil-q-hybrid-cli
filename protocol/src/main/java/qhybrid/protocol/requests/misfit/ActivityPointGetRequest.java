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
package qhybrid.protocol.requests.misfit;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import qhybrid.protocol.requests.Request;

public class ActivityPointGetRequest extends Request {
    public int activityPoint;

    @Override
    public byte[] getStartSequence() {
        return new byte[]{1, 6};
    }

    @Override
    public void handleResponse(java.util.UUID uuid, byte[] value) {
        super.handleResponse(uuid, value);

        if (value.length != 6) return;

        ByteBuffer wrap = ByteBuffer.wrap(value);
        wrap.order(ByteOrder.LITTLE_ENDIAN);
        activityPoint = wrap.getInt(2) >> 8;
    }
}
