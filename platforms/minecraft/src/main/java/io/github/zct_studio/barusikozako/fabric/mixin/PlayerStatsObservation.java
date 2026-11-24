// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

//? if mc < 1.21.11
//?~ package io.github.zct_studio.barusikozako.fabric.mixin;
//?~
//?~ import io.github.zct_studio.barusikozako.fabric.WorldWrites;
//?~ import net.minecraft.stats.ServerStatsCounter;
//?~ import org.spongepowered.asm.mixin.Mixin;
//?~ import org.spongepowered.asm.mixin.injection.At;
//?~ import org.spongepowered.asm.mixin.injection.Inject;
//?~ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?~
//?~ @Mixin(ServerStatsCounter.class)
//?~ abstract class PlayerStatsObservation {
//?~     @Inject(method = "save", at = @At(value = "INVOKE",
//?~         target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"))
//?~     private void barusikozako$failed(CallbackInfo info) {
//?~         WorldWrites.failed("player statistics");
//?~     }
//?~ }
//? end
