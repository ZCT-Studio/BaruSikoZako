// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.WorldWrites;
import net.minecraft.server.PlayerAdvancements;
//? if mc >= 1.21.11
import net.minecraft.stats.ServerStatsCounter;
//? end
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if mc >= 1.21.11
@Mixin({ServerStatsCounter.class, PlayerAdvancements.class})
//? else
//?~ @Mixin(PlayerAdvancements.class)
//? end
abstract class AuxiliaryPlayerObservation {
    @Inject(method = "save", at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void barusikozako$failed(CallbackInfo info) {
//? if mc >= 1.21.11
        WorldWrites.failed("player statistics or advancements");
//? else
//?~         WorldWrites.failed("player advancements");
//? end
    }
}
