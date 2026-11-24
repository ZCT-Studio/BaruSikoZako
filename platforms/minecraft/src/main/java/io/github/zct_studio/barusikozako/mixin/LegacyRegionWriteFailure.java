// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

//? if mc == 1.20.1
//?~ package io.github.zct_studio.barusikozako.mixin;
//?~
//?~ import io.github.zct_studio.barusikozako.WorldWrites;
//?~ import net.minecraft.world.level.chunk.storage.IOWorker;
//?~ import org.spongepowered.asm.mixin.Mixin;
//?~ import org.spongepowered.asm.mixin.injection.At;
//?~ import org.spongepowered.asm.mixin.injection.Inject;
//?~ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?~
//?~ @Mixin(IOWorker.class)
//?~ abstract class LegacyRegionWriteFailure {
//?~     @Inject(method = "runStore", at = @At(value = "INVOKE",
//?~         target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"), require = 1)
//?~     private void barusikozako$failed(CallbackInfo info) {
//?~         WorldWrites.failed("region data");
//?~     }
//?~ }
//? end
