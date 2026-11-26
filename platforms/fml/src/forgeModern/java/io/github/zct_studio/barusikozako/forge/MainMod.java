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
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

@Mod("barusikozako")
public final class MainMod {
    public MainMod(FMLJavaModLoadingContext context) {
        var self = context.getContainer().getModInfo();
        //? if mc >= 26
        var api = ModList.getModContainerById("barusikozako_api");
        //? else
        var api = ModList.get().getModContainerById("barusikozako_api");
        //? end
        if (api.isPresent()) {
            var installed = api.get().getModInfo().getVersion().toString();
            var rejection = ApiVersionGuard.rejection(PlatformVersions.MINIMUM_API_VERSION, installed);
            if (rejection != null) throw new ModLoadingException(self, ModLoadingStage.CONSTRUCT, rejection, null);
        }
        LoaderBridge.install(new NativeEntrypoints(FMLPaths.CONFIGDIR.get(), self.getVersion().toString(),
            () -> ModList.isLoaded("barusikozako_api"),
            //? if mc >= 26
            () -> ModList.getModContainerById("barusikozako_api")
            //? else
            () -> ModList.get().getModContainerById("barusikozako_api")
            //? end
                .map(container -> container.getModInfo().getVersion().toString()).orElse(null), () ->
            ModList.applyForEachModContainer(container -> new NativeEntrypoints.Integration(
                container.getModId(),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::service).map(PublicApi::integration).orElse(null),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::lifecycle).map(PublicApi::lifecycle).orElse(null)))
                .toList()));
        BaruSikoZakoServer.bootstrapConfig();
        var runtime = new BaruSikoZakoServer();
        RegisterCommandsEvent.BUS.addListener(event -> runtime.registerCommands(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> runtime.onServerStarted(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event -> runtime.onServerStopping());
        ServerStoppedEvent.BUS.addListener(event -> runtime.onServerStopped());
        ServerTickEvent.Pre.BUS.addListener(event -> runtime.tickStart(event.server()));
        ServerTickEvent.Post.BUS.addListener(event -> runtime.tickEnd(event.server()));
    }
}
