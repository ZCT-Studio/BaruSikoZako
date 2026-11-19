// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.neoforge.backend;

import barusikozako.fabric.NativeEntrypoints;
import barusikozako.zstd.ZstdProvider;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;

@Mod(value = "zakobak_zstd", dist = Dist.DEDICATED_SERVER)
public final class ZstdMod {
    public ZstdMod() {
        NativeEntrypoints.registerBackend(new ZstdProvider());
    }
}
