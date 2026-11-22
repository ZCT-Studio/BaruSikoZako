// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.WorldWrites;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SavedData.class)
abstract class SavedDataObservation {
    @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"), require = 1)
    private void barusikozako$failed(CallbackInfo info) {
        WorldWrites.failed("SavedData");
    }
}
