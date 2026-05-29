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

import qhybrid.protocol.FossilWatchAdapter;
import qhybrid.protocol.file.FileHandle;

public abstract class FileLookupAndGetRequest extends FileLookupRequest {
    public FileLookupAndGetRequest(FileHandle fileHandle, FossilWatchAdapter adapter) {
        super(fileHandle, adapter);
    }

    @Override
    public void handleFileLookup(short fileHandle){
        getAdapter().queueWrite(new FileGetRawRequest(getHandle(), getAdapter()) {
            @Override
            public void handleFileRawData(byte[] fileData) {
                FileLookupAndGetRequest.this.handleFileData(fileData);
            }
        }, true);
    }

    abstract public void handleFileData(byte[] fileData);
}
