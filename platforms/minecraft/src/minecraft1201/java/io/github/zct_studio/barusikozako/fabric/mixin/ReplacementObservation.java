// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.WorldWrites;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Util.class)
abstract class ReplacementObservation {
    @Inject(method = "runWithRetries", at = @At(value = "INVOKE",
        target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V"), require = 1)
    private static void barusikozako$failed(CallbackInfoReturnable<Boolean> info) {
        WorldWrites.failed("file replacement");
    }
}
