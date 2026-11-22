// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.neoforge.backend;

import io.github.zct_studio.barusikozako.fabric.NativeEntrypoints;
import io.github.zct_studio.barusikozako.zstd.ZstdProvider;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;

@Mod(value = "barusikozako_backendzstd", dist = Dist.DEDICATED_SERVER)
public final class ZstdMod {
    public ZstdMod() {
        NativeEntrypoints.registerBackend(new ZstdProvider());
    }
}
