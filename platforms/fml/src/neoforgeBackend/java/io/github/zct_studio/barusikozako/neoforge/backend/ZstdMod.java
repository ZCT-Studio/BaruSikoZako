// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.neoforge.backend;

import io.github.zct_studio.barusikozako.NativeEntrypoints;
import io.github.zct_studio.barusikozako.platform.PlatformVersions;
import io.github.zct_studio.barusikozako.zstd.ZstdProvider;
import net.neoforged.fml.common.Mod;

@Mod(value = "barusikozako_backendzstd")
public final class ZstdMod {
    public ZstdMod() {
        NativeEntrypoints.registerBackend(new ZstdProvider(), PlatformVersions.ZSTD_VERSION);
    }
}
