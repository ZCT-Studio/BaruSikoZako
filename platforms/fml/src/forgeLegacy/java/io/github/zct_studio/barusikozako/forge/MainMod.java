// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.forge;

import io.github.zct_studio.barusikozako.api.forge.ZakoBakIntegration;
import io.github.zct_studio.barusikozako.fabric.BaruSikoZakoServer;
import io.github.zct_studio.barusikozako.fabric.LoaderBridge;
import io.github.zct_studio.barusikozako.fabric.NativeEntrypoints;
import io.github.zct_studio.barusikozako.fabric.PublicApi;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("barusikozako")
public final class MainMod {
    public MainMod(FMLJavaModLoadingContext context) {
        LoaderBridge.install(new NativeEntrypoints(FMLPaths.CONFIGDIR.get(),
            context.getContainer().getModInfo().getVersion().toString(),
            () -> ModList.get().isLoaded("barusikozako_api"), () ->
            ModList.get().applyForEachModContainer(container -> new NativeEntrypoints.Integration(
                container.getModId(),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::service).map(PublicApi::integration).orElse(null),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::lifecycle).map(PublicApi::lifecycle).orElse(null)))
                .toList()));
        ForgeEvents.bind(new BaruSikoZakoServer());
    }
}
