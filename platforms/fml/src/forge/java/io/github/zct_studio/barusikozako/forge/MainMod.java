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
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;
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
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
        LoaderBridge.install(new NativeEntrypoints(FMLPaths.CONFIGDIR.get(), context.getContainer().getModInfo().getVersion().toString(),
            () -> ModList.isLoaded("barusikozako_api"), () ->
            ModList.applyForEachModContainer(container -> new NativeEntrypoints.Integration(
                container.getModId(),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::service).map(PublicApi::integration).orElse(null),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::lifecycle).map(PublicApi::lifecycle).orElse(null)))
                .toList()));
        var runtime = new BaruSikoZakoServer();
        RegisterCommandsEvent.BUS.addListener(event -> runtime.registerCommands(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> runtime.onServerStarted(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event -> runtime.onServerStopping());
        ServerStoppedEvent.BUS.addListener(event -> runtime.onServerStopped());
        ServerTickEvent.Pre.BUS.addListener(event -> runtime.tickStart(event.server()));
        ServerTickEvent.Post.BUS.addListener(event -> runtime.tickEnd(event.server()));
    }
}
