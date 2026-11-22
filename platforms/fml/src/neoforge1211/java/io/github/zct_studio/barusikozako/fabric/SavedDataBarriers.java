// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import io.github.zct_studio.barusikozako.fabric.mixin.NeoForgeSaveQueue;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class SavedDataBarriers {
    private SavedDataBarriers() {}

    static void add(MinecraftServer server, List<ServerLevel> levels, List<CompletableFuture<?>> barriers) {
        // 库存 NeoForge 队列链写入；提交后获取服务器线程上的当前尾部
        barriers.add(NeoForgeSaveQueue.barusikozako$pending());
    }
}
