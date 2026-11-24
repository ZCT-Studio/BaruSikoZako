// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.forge;

import io.github.zct_studio.barusikozako.api.forge.ZakoBakIntegration;
import io.github.zct_studio.barusikozako.BaruSikoZakoServer;
import io.github.zct_studio.barusikozako.LoaderBridge;
import io.github.zct_studio.barusikozako.NativeEntrypoints;
import io.github.zct_studio.barusikozako.PublicApi;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("barusikozako")
public final class MainMod {
    public MainMod() {
        var modList = ModList.get();
        LoaderBridge.install(new NativeEntrypoints(FMLPaths.CONFIGDIR.get(),
            modList.getModContainerById("barusikozako").orElseThrow().getModInfo().getVersion().toString(),
            () -> modList.isLoaded("barusikozako_api"), () ->
            modList.applyForEachModContainer(container -> new NativeEntrypoints.Integration(
                container.getModId(),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::service).map(PublicApi::integration).orElse(null),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::lifecycle).map(PublicApi::lifecycle).orElse(null)))
                .toList()));
        ForgeEvents.bind(new BaruSikoZakoServer());
    }
}
