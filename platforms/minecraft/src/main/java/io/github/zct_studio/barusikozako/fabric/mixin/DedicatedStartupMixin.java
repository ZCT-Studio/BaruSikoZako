// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric.mixin;

import io.github.zct_studio.barusikozako.fabric.WorldStartupGate;
import java.io.IOException;
import net.minecraft.server.Main;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.ContentValidationException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Main.class)
abstract class DedicatedStartupMixin {
    @Redirect(method = "main", at = @At(value = "INVOKE", target = """
        Lnet/minecraft/world/level/storage/LevelStorageSource;validateAndCreateAccess(Ljava/lang/String;)Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"""),
        require = 1, allow = 1)
    private static LevelStorageSource.LevelStorageAccess barusikozako$guardStartup(LevelStorageSource storage,
                                                                                   String world)
        throws IOException, ContentValidationException {
//? if mc == 1.20.1
//?~         WorldStartupGate.beforeWorldAccess(storage.getBaseDir().resolve(world));
//? else
        WorldStartupGate.beforeWorldAccess(storage.getLevelPath(world));
//? end
        return storage.validateAndCreateAccess(world);
    }
}
