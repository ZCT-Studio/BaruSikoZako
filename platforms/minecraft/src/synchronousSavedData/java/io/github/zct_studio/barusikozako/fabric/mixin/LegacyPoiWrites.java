// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SectionStorage.class)
public interface LegacyPoiWrites {
    @Invoker("tick")
    void barusikozako$submitDirtyColumns(BooleanSupplier hasTime);
}
