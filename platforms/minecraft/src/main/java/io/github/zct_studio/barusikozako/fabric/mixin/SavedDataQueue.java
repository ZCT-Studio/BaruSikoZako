// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

//? if mc > 1.21.1
package io.github.zct_studio.barusikozako.fabric.mixin;

import java.util.concurrent.CompletableFuture;
//? if mc >= 26
import net.minecraft.world.level.storage.SavedDataStorage;
//? else
//?~ import net.minecraft.world.level.storage.DimensionDataStorage;
//? end
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//? if mc >= 26
@Mixin(SavedDataStorage.class)
//? else
//?~ @Mixin(DimensionDataStorage.class)
//? end
public interface SavedDataQueue {
    @Accessor("pendingWriteFuture")
    CompletableFuture<?> barusikozako$pending();
}
//? end
