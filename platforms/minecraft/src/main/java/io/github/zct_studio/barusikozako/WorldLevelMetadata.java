// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

//? if mc == 1.20.1
//?~ import java.io.DataInputStream;
//?~ import java.io.IOException;
//?~ import java.io.InputStream;
//?~ import java.util.zip.GZIPInputStream;
//?~ import net.minecraft.nbt.NbtAccounter;
//?~ import net.minecraft.nbt.NbtIo;
//? else
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
//? end

final class WorldLevelMetadata {
    private WorldLevelMetadata() {}

    static void check(InputStream input) throws IOException {
//? if mc == 1.20.1
//?~         try (var decoded = new DataInputStream(new GZIPInputStream(input))) {
//?~             var root = NbtIo.read(decoded, new NbtAccounter(16L << 20));
//?~             if (root.getCompound("Data").isEmpty()) throw new IOException("Snapshot level.dat has no world data");
//?~         }
//? else
        var root = NbtIo.readCompressed(input, NbtAccounter.create(16L << 20));
        if (root.getCompound("Data").isEmpty()) throw new IOException("Snapshot level.dat has no world data");
//? end
    }
}
