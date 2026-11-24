// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.WorldWrites;
import java.nio.file.Path;
//? if mc < 1.21.11
//?~ import net.minecraft.Util;
//? else
import net.minecraft.util.Util;
//? end
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Util.class)
abstract class ReplacementObservation {
//? if mc == 1.20.1
//?~     @Inject(method = "runWithRetries", at = @At(value = "INVOKE",
//?~         target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;)V"), require = 1)
//?~     private static void barusikozako$failed(CallbackInfoReturnable<Boolean> info) {
//?~         WorldWrites.failed("file replacement");
//?~     }
//? else
    @Inject(method = "safeReplaceOrMoveFile", at = @At("RETURN"))
    private static void barusikozako$failed(Path original, Path replacement, Path backup, boolean preserve,
                                          CallbackInfoReturnable<Boolean> info) {
        Path file = original.getFileName();
        if (!info.getReturnValue() && file != null && file.toString().endsWith(".dat"))
            WorldWrites.failed("world or player file replacement");
    }
//? end
}
