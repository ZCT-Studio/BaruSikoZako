// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.mixin.EntityManagerAccess;
import io.github.zct_studio.barusikozako.mixin.EntityStorageAccess;
import io.github.zct_studio.barusikozako.mixin.LevelEntityAccess;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.storage.EntityStorage;

/** 协调单独的普通 POI 和实体存储队列 */
final class CaptureDomains {
    private CaptureDomains() {}

    static void flushPoi(MinecraftServer server) {
        for (var level : server.getAllLevels()) PoiWrites.submit(level.getPoiManager());
    }

    static void addEntityBarriers(MinecraftServer server, List<CompletableFuture<?>> pending) {
        for (var level : server.getAllLevels()) {
            var manager = ((LevelEntityAccess) level).barusikozako$Manager();
            if (!(((EntityManagerAccess) manager).barusikozako$PermanentStorage() instanceof EntityStorage storage))
                throw new IllegalStateException("unknown entity storage implementation");
            pending.add(((EntityStorageAccess) storage).barusikozako$Storage().synchronize(false));
        }
    }
}
