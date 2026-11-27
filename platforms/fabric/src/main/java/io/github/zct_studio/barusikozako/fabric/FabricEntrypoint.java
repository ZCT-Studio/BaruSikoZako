// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import io.github.zct_studio.barusikozako.BaruSikoZakoServer;
import io.github.zct_studio.barusikozako.LoaderBridge;
import io.github.zct_studio.barusikozako.PublicApi;
import io.github.zct_studio.barusikozako.ServiceIntegrations;

public final class FabricEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        var loader = FabricLoader.getInstance();
        LoaderBridge.install(new LoaderBridge.Binding() {
            @Override public java.nio.file.Path configDirectory() { return loader.getConfigDir(); }
            @Override public boolean apiAvailable() { return loader.isModLoaded("barusikozako_api"); }

            @Override public String apiVersion() {
                return loader.getModContainer("barusikozako_api")
                    .map(container -> {
                        var version = container.getMetadata().getVersion().getFriendlyString();
                        return version == null || version.isBlank() ? "unknown" : version;
                    }).orElse(null);
            }

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
                    .map(entry -> new LoaderBridge.Entry<T>(entry.getProvider().getMetadata().getId(),
                        entry::getEntrypoint, entry.getProvider().getMetadata().getVersion().getFriendlyString()))
                    .toList();
            }
        });
        BaruSikoZakoServer.bootstrapConfig();
        var runtime = new BaruSikoZakoServer();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> runtime.registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(runtime::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime.onServerStopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> runtime.onServerStopped());
        ServerTickEvents.START_SERVER_TICK.register(runtime::tickStart);
        ServerTickEvents.END_SERVER_TICK.register(runtime::tickEnd);
    }

    /**
     * 集成发现：{@code barusikozako:backup_api} / {@code barusikozako:lifecycle} entrypoint
     * 仍是兼容通道并优先；未声明 entrypoint 的 mod 再读取自己的 {@code META-INF/services}。
     * 同一 mod 只产出一条，身份为 mod 元数据 ID。
     */
    private static final class ApiEntries {
        static <T> java.util.List<LoaderBridge.Entry<T>> entries(FabricLoader loader, String channel, Class<T> contract) {
            if (contract == io.github.zct_studio.barusikozako.runtime.BackupIntegration.class) {
                return backupEntries(loader, channel, contract);
            }
            return lifecycleEntries(loader, channel, contract);
        }

        private static <T> java.util.List<LoaderBridge.Entry<T>> backupEntries(FabricLoader loader, String channel,
                                                                               Class<T> contract) {
            var entries = new java.util.ArrayList<LoaderBridge.Entry<T>>();
            var owners = new java.util.HashSet<String>();
            for (var entry : loader.getEntrypointContainers(channel,
                io.github.zct_studio.barusikozako.api.BackupIntegration.class)) {
                var id = entry.getProvider().getMetadata().getId();
                if (!owners.add(id)) continue;
                entries.add(new LoaderBridge.Entry<>(id,
                    () -> contract.cast(PublicApi.integration(entry.getEntrypoint()))));
            }
            for (var container : loader.getAllMods()) {
                var id = container.getMetadata().getId();
                if (owners.contains(id)) continue;
                var source = ServiceIntegrations.source(id,
                    path -> container.findPath(path).flatMap(ApiEntries::readAll));
                if (!ServiceIntegrations.declares(source,
                    io.github.zct_studio.barusikozako.api.BackupIntegration.class)) continue;
                owners.add(id);
                var discovered = ServiceIntegrations.service(id, source,
                    io.github.zct_studio.barusikozako.api.BackupIntegration.class);
                entries.add(new LoaderBridge.Entry<>(id, () -> {
                    var value = discovered.get();
                    return value == null ? null : contract.cast(PublicApi.integration(value));
                }));
            }
            return entries;
        }

        private static <T> java.util.List<LoaderBridge.Entry<T>> lifecycleEntries(FabricLoader loader, String channel,
                                                                                  Class<T> contract) {
            var entries = new java.util.ArrayList<LoaderBridge.Entry<T>>();
            var owners = new java.util.HashSet<String>();
            for (var entry : loader.getEntrypointContainers(channel,
                io.github.zct_studio.barusikozako.api.BackupLifecycleHook.class)) {
                var id = entry.getProvider().getMetadata().getId();
                if (!owners.add(id)) continue;
                entries.add(new LoaderBridge.Entry<>(id,
                    () -> contract.cast(PublicApi.lifecycle(entry.getEntrypoint()))));
            }
            for (var container : loader.getAllMods()) {
                var id = container.getMetadata().getId();
                if (owners.contains(id)) continue;
                var source = ServiceIntegrations.source(id,
                    path -> container.findPath(path).flatMap(ApiEntries::readAll));
                if (!ServiceIntegrations.declares(source,
                    io.github.zct_studio.barusikozako.api.BackupLifecycleHook.class)) continue;
                owners.add(id);
                var discovered = ServiceIntegrations.service(id, source,
                    io.github.zct_studio.barusikozako.api.BackupLifecycleHook.class);
                entries.add(new LoaderBridge.Entry<>(id, () -> {
                    var value = discovered.get();
                    return value == null ? null : contract.cast(PublicApi.lifecycle(value));
                }));
            }
            return entries;
        }

        private static java.util.Optional<byte[]> readAll(java.nio.file.Path path) {
            try {
                return java.util.Optional.of(java.nio.file.Files.readAllBytes(path));
            } catch (java.io.IOException unreadable) {
                return java.util.Optional.empty();
            }
        }
    }
}
