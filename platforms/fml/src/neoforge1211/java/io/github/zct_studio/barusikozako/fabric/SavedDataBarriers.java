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
        switch (NeoForgeSavedDataMixins.mode()) {
            case SYNCHRONOUS -> { }
            case ASYNCHRONOUS -> barriers.add(NeoForgeSaveQueue.barusikozako$pending());
            case UNKNOWN -> throw new IllegalStateException("Unrecognized NeoForge SavedData save path");
        }
    }
}
