// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;

final class ChunkBarrier {
    private ChunkBarrier() {}

    static CompletableFuture<Void> pending(ServerLevel level) {
        return level.getChunkSource().chunkMap.synchronize(false);
    }
}
