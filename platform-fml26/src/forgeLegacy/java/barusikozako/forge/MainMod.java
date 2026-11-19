// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.forge;

import barusikozako.api.forge.ZakoBakIntegration;
import barusikozako.fabric.BaruSikoZakoServer;
import barusikozako.fabric.LoaderBridge;
import barusikozako.fabric.NativeEntrypoints;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod("barusikozako")
public final class MainMod {
    public MainMod(FMLJavaModLoadingContext context) {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) return;
        LoaderBridge.install(new NativeEntrypoints(FMLPaths.CONFIGDIR.get(),
            context.getContainer().getModInfo().getVersion().toString(), () ->
            ModList.get().applyForEachModContainer(container -> new NativeEntrypoints.Integration(
                container.getModId(),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::service).orElse(null),
                () -> container.getCustomExtension(ZakoBakIntegration.class).map(ZakoBakIntegration::lifecycle).orElse(null)))
                .toList()));
        ForgeEvents.bind(new BaruSikoZakoServer());
    }
}
