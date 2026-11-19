// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.fabric.mixin.SavedDataQueue;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class SavedDataBarriers {
    private SavedDataBarriers() {}

    static void add(MinecraftServer server, List<ServerLevel> levels, List<CompletableFuture<?>> barriers) {
        // In 1.21.11 the overworld and the other levels own the stores; there is no server-level store.
        for (var level : levels) {
            barriers.add(((SavedDataQueue) level.getDataStorage()).barusikozako$pending());
        }
    }
}
