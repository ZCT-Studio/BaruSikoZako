// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.mixin;

import io.github.zct_studio.barusikozako.WorldWrites;
//? if mc >= 26
import net.minecraft.world.level.storage.SavedDataStorage;
//? elif mc > 1.21.1
//?~ import net.minecraft.world.level.storage.DimensionDataStorage;
//? else
//?~ import net.minecraft.world.level.saveddata.SavedData;
//? end
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if mc >= 26
@Mixin(SavedDataStorage.class)
//? elif mc > 1.21.1
//?~ @Mixin(DimensionDataStorage.class)
//? else
//?~ @Mixin(SavedData.class)
//? end
abstract class SavedDataObservation {
//? if mc == 1.20.1
//?~     @Inject(method = "save(Ljava/io/File;)V", at = @At(value = "INVOKE",
//?~         target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"), require = 1)
//? elif mc <= 1.21.1
//?~     @Inject(method = "save(Ljava/io/File;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At(value = "INVOKE",
//?~         target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"), require = 1)
//? else
    @Inject(method = "tryWrite", at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
//? end
    private void barusikozako$failed(CallbackInfo info) {
        WorldWrites.failed("SavedData");
    }
}
