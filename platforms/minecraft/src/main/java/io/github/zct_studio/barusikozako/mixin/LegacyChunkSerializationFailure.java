// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

//? if mc == 1.20.1
//?~ package io.github.zct_studio.barusikozako.mixin;
//?~
//?~ import io.github.zct_studio.barusikozako.WorldWrites;
//?~ import net.minecraft.server.level.ChunkMap;
//?~ import org.spongepowered.asm.mixin.Mixin;
//?~ import org.spongepowered.asm.mixin.injection.At;
//?~ import org.spongepowered.asm.mixin.injection.Inject;
//?~ import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?~
//?~ @Mixin(ChunkMap.class)
//?~ abstract class LegacyChunkSerializationFailure {
//?~     @Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE",
//?~         target = "Lorg/slf4j/Logger;error(Ljava/lang/String;[Ljava/lang/Object;)V"), require = 1)
//?~     private void barusikozako$failed(CallbackInfoReturnable<Boolean> info) {
//?~         WorldWrites.failed("chunk serialization");
//?~     }
//?~ }
//? end
