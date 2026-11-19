// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric.mixin;

import barusikozako.fabric.WorldWrites;
import java.nio.file.Path;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Util.class)
abstract class ReplacementObservation {
    @Inject(method = "safeReplaceOrMoveFile", at = @At("RETURN"))
    private static void barusikozako$failed(Path original, Path replacement, Path backup, boolean preserve,
                                          CallbackInfoReturnable<Boolean> info) {
        Path file = original.getFileName();
        if (!info.getReturnValue() && file != null && file.toString().endsWith(".dat"))
            WorldWrites.failed("world or player file replacement");
    }
}
