// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric.mixin;

import barusikozako.fabric.WorldWrites;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SavedDataStorage.class)
abstract class SavedDataObservation {
    @Inject(method = "tryWrite", at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void barusikozako$failed(CallbackInfo info) {
        WorldWrites.failed("SavedData");
    }
}
