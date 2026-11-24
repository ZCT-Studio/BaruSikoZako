// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

//? if mc <= 1.21.1 && loader == neoforge
//?~ import io.github.zct_studio.barusikozako.fabric.mixin.NeoForgeSaveQueue;
//? elif mc > 1.21.1
import io.github.zct_studio.barusikozako.fabric.mixin.SavedDataQueue;
//? end
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

final class SavedDataBarriers {
    private SavedDataBarriers() {}

    static void add(MinecraftServer server, List<ServerLevel> levels, List<CompletableFuture<?>> barriers) {
//? if mc >= 26
        barriers.add(((SavedDataQueue) server.getDataStorage()).barusikozako$pending());
        for (var level : levels) {
            barriers.add(((SavedDataQueue) level.getDataStorage()).barusikozako$pending());
        }
//? elif mc > 1.21.1
//?~         // 1.21.10/1.21.11：主世界和其他关卡各自拥有存储，没有服务器级存储
//?~         for (var level : levels) {
//?~             barriers.add(((SavedDataQueue) level.getDataStorage()).barusikozako$pending());
//?~         }
//? elif loader == neoforge
//?~         switch (NeoForgeSavedDataMixins.mode()) {
//?~             case SYNCHRONOUS -> { }
//?~             case ASYNCHRONOUS -> barriers.add(NeoForgeSaveQueue.barusikozako$pending());
//?~             case UNKNOWN -> throw new IllegalStateException("Unrecognized NeoForge SavedData save path");
//?~         }
//? else
//?~         // DimensionDataStorage.save 同步写入。 SavedDataObservation 记录捕获 I/O 故障
//? end
    }
}