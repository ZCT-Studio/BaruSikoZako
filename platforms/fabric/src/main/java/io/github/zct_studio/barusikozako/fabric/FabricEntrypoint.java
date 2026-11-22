// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricEntrypoint implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        var loader = FabricLoader.getInstance();
        LoaderBridge.install(new LoaderBridge.Binding() {
            @Override public java.nio.file.Path configDirectory() { return loader.getConfigDir(); }
            @Override public boolean apiAvailable() { return loader.isModLoaded("barusikozako_api"); }

            @Override public String productVersion() {
                return loader.getModContainer("barusikozako").orElseThrow().getMetadata().getVersion().getFriendlyString();
            }

            @Override
            public <T> java.util.List<LoaderBridge.Entry<T>> entries(String channel, Class<T> contract) {
                if (contract == io.github.zct_studio.barusikozako.runtime.BackupIntegration.class ||
                    contract == io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.class) {
                    return ApiEntries.entries(loader, channel, contract);
                }
                return loader.getEntrypointContainers(channel, contract).stream()
                    .map(entry -> new LoaderBridge.Entry<T>(entry.getProvider().getMetadata().getId(), entry::getEntrypoint))
                    .toList();
            }
        });
        var runtime = new BaruSikoZakoServer();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> runtime.registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(runtime::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime.onServerStopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> runtime.onServerStopped());
        ServerTickEvents.START_SERVER_TICK.register(runtime::tickStart);
        ServerTickEvents.END_SERVER_TICK.register(runtime::tickEnd);
    }

    private static final class ApiEntries {
        static <T> java.util.List<LoaderBridge.Entry<T>> entries(FabricLoader loader, String channel, Class<T> contract) {
            if (contract == io.github.zct_studio.barusikozako.runtime.BackupIntegration.class) {
                return loader.getEntrypointContainers(channel, io.github.zct_studio.barusikozako.api.BackupIntegration.class).stream()
                    .map(entry -> new LoaderBridge.Entry<>(entry.getProvider().getMetadata().getId(),
                        () -> contract.cast(PublicApi.integration(entry.getEntrypoint())))).toList();
            }
            return loader.getEntrypointContainers(channel, io.github.zct_studio.barusikozako.api.BackupLifecycleHook.class).stream()
                .map(entry -> new LoaderBridge.Entry<>(entry.getProvider().getMetadata().getId(),
                    () -> contract.cast(PublicApi.lifecycle(entry.getEntrypoint())))).toList();
        }
    }
}
