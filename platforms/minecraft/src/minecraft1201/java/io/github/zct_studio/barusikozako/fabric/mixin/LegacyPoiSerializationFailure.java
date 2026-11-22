// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.WorldWrites;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionStorage.class)
abstract class LegacyPoiSerializationFailure {
    @Inject(method = "writeColumn(Lnet/minecraft/world/level/ChunkPos;)V", at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V"), require = 1)
    private void barusikozako$encodingFailed(CallbackInfo info) {
        WorldWrites.failed("POI serialization");
    }
}
