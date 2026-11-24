// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.backend.BackendProvider;
import io.github.zct_studio.barusikozako.runtime.BackupIntegration;
import io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 显式本机加载程序后端注册。本机集成可以提供加载程序拥有的绑定 */
public final class NativeEntrypoints implements LoaderBridge.Binding {
    private static final List<BackendProvider> BACKENDS = new CopyOnWriteArrayList<>();
    private final Path configDirectory;
    private final String productVersion;
    private final BooleanSupplier apiInstalled;
    private final Supplier<List<Integration>> integrations;

    public record Integration(String owner, Supplier<BackupIntegration> service,
                              Supplier<BackupLifecycleHook> lifecycle) {}

    public NativeEntrypoints(Path configDirectory, String productVersion, BooleanSupplier apiInstalled,
                             Supplier<List<Integration>> integrations) {
        this.configDirectory = configDirectory;
        this.productVersion = productVersion;
        this.apiInstalled = apiInstalled;
        this.integrations = integrations;
    }

    public static void registerBackend(BackendProvider provider) {
        BACKENDS.add(java.util.Objects.requireNonNull(provider));
    }

    public static AutoCloseable registerBackupExclusion(String scope, String source, String pattern) {
        return io.github.zct_studio.barusikozako.core.BackupExclusions.register(scope, source, pattern);
    }

    @Override public Path configDirectory() { return configDirectory; }

    @Override public String productVersion() { return productVersion; }
    @Override public boolean apiAvailable() { return apiInstalled.getAsBoolean(); }

    @Override public <T> List<LoaderBridge.Entry<T>> entries(String channel, Class<T> contract) {
        if (channel.equals("barusikozako:backend") && contract == BackendProvider.class)
            return BACKENDS.stream().map(provider ->
                new LoaderBridge.Entry<T>(provider.keyword(), () -> contract.cast(provider))).toList();
        if (channel.equals("barusikozako:backup_api") && contract == BackupIntegration.class)
            return integrations.get().stream().map(value ->
                new LoaderBridge.Entry<T>(value.owner(), () -> contract.cast(value.service().get()))).toList();
        if (channel.equals("barusikozako:lifecycle") && contract == BackupLifecycleHook.class)
            return integrations.get().stream().map(value ->
                new LoaderBridge.Entry<T>(value.owner(), () -> contract.cast(value.lifecycle().get()))).toList();
        return List.of();
    }
}
