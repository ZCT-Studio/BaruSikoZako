// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

//? if mc < 1.21.11
//?~ import io.github.zct_studio.barusikozako.fabric.mixin.LegacyChunkStorageAccess;
//? end
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;

final class ChunkBarrier {
    private ChunkBarrier() {}

    static CompletableFuture<Void> pending(ServerLevel level) {
//? if mc < 1.21.11
//?~         return ((LegacyChunkStorageAccess) level.getChunkSource().chunkMap).barusikozako$worker().synchronize(false);
//? else
        return level.getChunkSource().chunkMap.synchronize(false);
//? end
    }
}
