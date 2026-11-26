// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.forge;

import io.github.zct_studio.barusikozako.api.forge.ZakoBakIntegration;
import io.github.zct_studio.barusikozako.BaruSikoZakoServer;
import io.github.zct_studio.barusikozako.LoaderBridge;
import io.github.zct_studio.barusikozako.NativeEntrypoints;
import io.github.zct_studio.barusikozako.PublicApi;
import io.github.zct_studio.barusikozako.platform.ApiVersionGuard;
import io.github.zct_studio.barusikozako.platform.PlatformVersions;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingException;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("barusikozako")
public final class MainMod {
    public MainMod() {
        var modList = ModList.get();
        var self = modList.getModContainerById("barusikozako").orElseThrow().getModInfo();
        var api = modList.getModContainerById("barusikozako_api");
        if (api.isPresent()) {
            var installed = api.get().getModInfo().getVersion().toString();
            var rejection = ApiVersionGuard.rejection(PlatformVersions.MINIMUM_API_VERSION, installed);
            if (rejection != null) throw new ModLoadingException(self, ModLoadingStage.CONSTRUCT, rejection, null);
        }
        LoaderBridge.install(new NativeEntrypoints(FMLPaths.CONFIGDIR.get(), self.getVersion().toString(),
            () -> modList.isLoaded("barusikozako_api"), () ->
                modList.getModContainerById("barusikozako_api")
                    .map(container -> container.getModInfo().getVersion().toString()).orElse(null),
            () ->
            modList.applyForEachModContainer(container -> new NativeEntrypoints.Integration(
                container.getModId(),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::service).map(PublicApi::integration).orElse(null),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::lifecycle).map(PublicApi::lifecycle).orElse(null)))
                .toList()));
        BaruSikoZakoServer.bootstrapConfig();
        ForgeEvents.bind(new BaruSikoZakoServer());
    }
}
