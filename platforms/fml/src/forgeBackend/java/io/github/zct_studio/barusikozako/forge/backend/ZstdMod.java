// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.forge.backend;

import io.github.zct_studio.barusikozako.NativeEntrypoints;
import io.github.zct_studio.barusikozako.zstd.ZstdProvider;
import net.minecraftforge.fml.common.Mod;

@Mod("barusikozako_backendzstd")
public final class ZstdMod {
    public ZstdMod() {
        NativeEntrypoints.registerBackend(new ZstdProvider(),
            net.minecraftforge.fml.ModList.get().getModContainerById("barusikozako_backendzstd")
                .map(container -> container.getModInfo().getVersion().toString()).orElse(null));
    }
}
