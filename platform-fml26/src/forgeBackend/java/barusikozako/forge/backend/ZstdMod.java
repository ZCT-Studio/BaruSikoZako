// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.forge.backend;

import barusikozako.fabric.NativeEntrypoints;
import barusikozako.zstd.ZstdProvider;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

@Mod("zakobak_zstd")
public final class ZstdMod {
    public ZstdMod() {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
        NativeEntrypoints.registerBackend(new ZstdProvider());
    }
}
