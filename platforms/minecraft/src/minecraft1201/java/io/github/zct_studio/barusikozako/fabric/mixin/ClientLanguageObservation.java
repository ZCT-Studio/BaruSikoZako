// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.ClientLanguageAccess;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ClientLanguageObservation implements ClientLanguageAccess {
    @Unique private volatile String barusikozako$language;

    @Inject(method = "updateOptions", at = @At("TAIL"))
    private void barusikozako$updateLanguage(ServerboundClientInformationPacket packet, CallbackInfo callback) {
        barusikozako$language = packet.language();
    }

    @Override
    public String barusikozako$getLanguage() {
        return barusikozako$language;
    }
}
