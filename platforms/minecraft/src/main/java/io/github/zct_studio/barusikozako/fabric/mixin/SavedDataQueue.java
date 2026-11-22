// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SavedDataStorage.class)
public interface SavedDataQueue {
    @Accessor("pendingWriteFuture")
    CompletableFuture<?> barusikozako$pending();
}
