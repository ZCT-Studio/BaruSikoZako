// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.BaruSikoZakoServer;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class EmptyServerCaptureMixin {
    // 26.3's empty branch performs its entry autosave, services connections and console input, then returns.
    @Inject(method = "tickServer", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V"), require = 1, allow = 1)
    private void barusikozako$servicePausedCapture(BooleanSupplier haveTime, CallbackInfo info) {
        BaruSikoZakoServer.captureWhileEmptyPaused((MinecraftServer) (Object) this);
    }
}
