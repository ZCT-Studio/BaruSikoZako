// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.mixin;

import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.neoforged.neoforge.common.IOUtilities", remap = false)
public interface NeoForgeSaveQueue {
    @Accessor("saveDataTasks")
    static CompletableFuture<Void> barusikozako$pending() {
        throw new AssertionError("NeoForge's SavedData queue accessor was not transformed");
    }
}
