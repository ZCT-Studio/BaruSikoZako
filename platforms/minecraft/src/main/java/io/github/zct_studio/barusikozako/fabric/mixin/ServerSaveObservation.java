// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

//? if mc != 1.20.1
package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.WorldWrites;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class ServerSaveObservation {
    @Inject(method = "reportChunkSaveFailure", at = @At("HEAD"))
    private void barusikozako$chunkFailure(CallbackInfo info) {
        WorldWrites.failed("region data");
    }
}
//? end
