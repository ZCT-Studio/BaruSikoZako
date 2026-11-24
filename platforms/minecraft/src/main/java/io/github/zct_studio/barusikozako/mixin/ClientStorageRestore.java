// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.mixin;

import io.github.zct_studio.barusikozako.WorldStartupGate;
import java.io.IOException;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelStorageSource.class)
abstract class ClientStorageRestore {
    @Unique private static final StackWalker barusikozako$worldOpenCaller = StackWalker.getInstance();

    @Inject(method = {"validateAndCreateAccess", "createAccess"}, at = @At("HEAD"), require = 2)
    private void barusikozako$recoverBeforeOpen(String name,
            CallbackInfoReturnable<LevelStorageSource.LevelStorageAccess> callback) throws IOException {
        // 世界列表里的编辑和删除也会取得存档访问对象
        boolean opening = barusikozako$worldOpenCaller.walk(frames -> frames.limit(8).anyMatch(frame ->
            frame.getClassName().equals("net.minecraft.client.gui.screens.worldselection.WorldOpenFlows")
                || frame.getClassName().equals("net.minecraft.class_7196")));
        if (!opening) return;
        WorldStartupGate.beforeClientWorldAccess(((LevelStorageSource) (Object) this).getBaseDir(), name);
    }
}
