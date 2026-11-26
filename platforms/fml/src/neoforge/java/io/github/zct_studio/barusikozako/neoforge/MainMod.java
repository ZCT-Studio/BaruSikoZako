// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.neoforge;

import io.github.zct_studio.barusikozako.api.neoforge.ZakoBakIntegration;
import io.github.zct_studio.barusikozako.BaruSikoZakoServer;
import io.github.zct_studio.barusikozako.LoaderBridge;
import io.github.zct_studio.barusikozako.NativeEntrypoints;
import io.github.zct_studio.barusikozako.PublicApi;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(value = "barusikozako")
public final class MainMod {
    public MainMod(ModContainer context) {
        LoaderBridge.install(new NativeEntrypoints(FMLPaths.CONFIGDIR.get(), context.getModInfo().getVersion().toString(),
            () -> ModList.get().isLoaded("barusikozako_api"),
            () -> ModList.get().getModContainerById("barusikozako_api")
                .map(container -> container.getModInfo().getVersion().toString()).orElse(null), () ->
            ModList.get().applyForEachModContainer(container -> new NativeEntrypoints.Integration(
                container.getModId(),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::service).map(PublicApi::integration).orElse(null),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::lifecycle).map(PublicApi::lifecycle).orElse(null)))
                .toList()));
        BaruSikoZakoServer.bootstrapConfig();
        var runtime = new BaruSikoZakoServer();
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((RegisterCommandsEvent event) -> runtime.registerCommands(event.getDispatcher()));
        bus.addListener((ServerStartedEvent event) -> runtime.onServerStarted(event.getServer()));
        bus.addListener((ServerStoppingEvent event) -> runtime.onServerStopping());
        bus.addListener((ServerStoppedEvent event) -> runtime.onServerStopped());
        bus.addListener((ServerTickEvent.Pre event) -> runtime.tickStart(event.getServer()));
        bus.addListener((ServerTickEvent.Post event) -> runtime.tickEnd(event.getServer()));
    }
}
