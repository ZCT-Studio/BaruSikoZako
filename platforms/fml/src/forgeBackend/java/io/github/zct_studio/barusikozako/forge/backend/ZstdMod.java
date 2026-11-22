// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.forge.backend;

import io.github.zct_studio.barusikozako.fabric.NativeEntrypoints;
import io.github.zct_studio.barusikozako.zstd.ZstdProvider;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

@Mod("barusikozako_backendzstd")
public final class ZstdMod {
    public ZstdMod() {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
        NativeEntrypoints.registerBackend(new ZstdProvider());
    }
}
