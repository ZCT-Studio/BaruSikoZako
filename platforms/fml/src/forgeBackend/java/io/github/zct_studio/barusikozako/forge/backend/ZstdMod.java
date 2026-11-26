// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.forge.backend;

import io.github.zct_studio.barusikozako.NativeEntrypoints;
import io.github.zct_studio.barusikozako.platform.PlatformVersions;
import io.github.zct_studio.barusikozako.zstd.ZstdProvider;
import net.minecraftforge.fml.common.Mod;

@Mod("barusikozako_backendzstd")
public final class ZstdMod {
    public ZstdMod() {
        NativeEntrypoints.registerBackend(new ZstdProvider(), PlatformVersions.ZSTD_VERSION);
    }
}
