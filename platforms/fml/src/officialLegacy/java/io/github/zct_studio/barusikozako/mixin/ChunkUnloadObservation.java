// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.mixin;

import io.github.zct_studio.barusikozako.WorldWrites;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
abstract class ChunkUnloadObservation {
    @Inject(method = "lambda$scheduleUnload$13", remap = false, at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private static void barusikozako$failed(ChunkHolder holder, Void result, Throwable failure, CallbackInfo info) {
        WorldWrites.failed("unloading chunk " + holder.getPos());
    }
}
