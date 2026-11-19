// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

final class WorldLevelMetadata {
    private WorldLevelMetadata() {}

    static void check(InputStream input) throws IOException {
        var root = NbtIo.readCompressed(input, NbtAccounter.create(16L << 20));
        if (root.getCompound("Data").isEmpty()) throw new IOException("Snapshot level.dat has no world data");
    }
}
