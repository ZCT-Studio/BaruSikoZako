// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import io.github.zct_studio.barusikozako.fabric.mixin.SavedDataQueue;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class SavedDataBarriers {
    private SavedDataBarriers() {}

    static void add(MinecraftServer server, List<ServerLevel> levels, List<CompletableFuture<?>> barriers) {
        // 在 1.21.11 中，主世界和其他关卡拥有商店；没有服务器级商店
        for (var level : levels) {
            barriers.add(((SavedDataQueue) level.getDataStorage()).barusikozako$pending());
        }
    }
}
