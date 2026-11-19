// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.fabric.mixin.LegacyChunkStorageAccess;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;

final class ChunkBarrier {
    private ChunkBarrier() {}

    static CompletableFuture<Void> pending(ServerLevel level) {
        return ((LegacyChunkStorageAccess) level.getChunkSource().chunkMap).barusikozako$worker().synchronize(false);
    }
}
