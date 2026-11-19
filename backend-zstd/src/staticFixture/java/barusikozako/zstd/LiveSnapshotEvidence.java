// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.zstd;

import barusikozako.core.BackupEngine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Reads expected contents from a verified snapshot, without using the live fixture world. */
public final class LiveSnapshotEvidence {
    public static void main(String[] args) throws IOException {
        var run = Path.of(args[0]).toRealPath();
        if (!Files.readString(run.resolve(".barusikozako-smoke")).strip().equals("isolated test"))
            throw new IOException("Expected a stopped isolated test world");
        var engine = new BackupEngine();
        var limits = new BackupEngine.Limits(2L << 30, 3L << 30, 100_000);
        try (var repository = ZstdRepository.open(run.resolve("config/barusikozako/repository"))) {
            var stage = engine.prepareRestore(repository, UUID.fromString(args[1]), limits, run);
            try {
                var player = UUID.fromString(args[2]);
                try (var input = new GZIPInputStream(Files.newInputStream(stage.resolve("players/data/" + player + ".dat")))) {
                    if (!new String(input.readNBytes(2 << 20), StandardCharsets.ISO_8859_1).contains("minecraft:diamond"))
                        throw new IOException("Snapshot lacks the pre-backup player inventory marker");
                }
                if (!Files.isRegularFile(stage.resolve("level.dat")))
                    throw new IOException("Snapshot lacks world root metadata");
                if (!args[3].isEmpty()) {
                    var coordinates = args[3].split(":", 2);
                    checkMeetingPoi(stage, Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]));
                    System.out.println("Verified the POI marker in the archived chunk.");
                }
                System.out.println("Verified the archived online player inventory and world metadata.");
            } finally {
                try (var paths = Files.walk(stage)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static void checkMeetingPoi(Path root, int x, int z) throws IOException {
        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        var region = root.resolve("dimensions/minecraft/overworld/poi/r." + Math.floorDiv(chunkX, 32) +
            "." + Math.floorDiv(chunkZ, 32) + ".mca");
        try (var stream = Files.newInputStream(region)) {
            byte[] data = stream.readNBytes((16 << 20) + 1);
            if (data.length < 8192 || data.length > (16 << 20)) throw new IOException("Unexpected fixture POI region size");
            var buffer = ByteBuffer.wrap(data);
            int slot = Math.floorMod(chunkX, 32) + Math.floorMod(chunkZ, 32) * 32;
            long offset = (buffer.getInt(4 * slot) >>> 8) * 4096L;
            if (offset < 8192 || offset + 5 > data.length) throw new IOException("Archived POI slot is absent");
            int length = buffer.getInt((int) offset);
            if (length < 2 || offset + 4 + length > data.length) throw new IOException("Archived POI record is invalid");
            int compression = buffer.get((int) offset + 4);
            var payload = new ByteArrayInputStream(data, (int) offset + 5, length - 1);
            try (var decoded = switch (compression) {
                case 1 -> new GZIPInputStream(payload);
                case 2 -> new InflaterInputStream(payload);
                case 3 -> payload;
                default -> throw new IOException("Unexpected fixture region compression: " + compression);
            }) {
                if (!new String(decoded.readNBytes(2 << 20), StandardCharsets.ISO_8859_1).contains("minecraft:meeting"))
                    throw new IOException("Snapshot lacks the bell meeting POI in the marked chunk");
            }
        }
    }
}
