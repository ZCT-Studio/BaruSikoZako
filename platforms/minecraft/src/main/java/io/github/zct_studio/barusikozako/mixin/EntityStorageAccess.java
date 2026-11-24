// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
//? if mc == 1.20.1
//?~ import net.minecraft.world.level.chunk.storage.IOWorker;
//? else
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
//? end
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityStorage.class)
public interface EntityStorageAccess {
//? if mc == 1.20.1
//?~     @Accessor("worker") IOWorker barusikozako$Storage();
//? else
    @Accessor("simpleRegionStorage") SimpleRegionStorage barusikozako$Storage();
//? end
}
