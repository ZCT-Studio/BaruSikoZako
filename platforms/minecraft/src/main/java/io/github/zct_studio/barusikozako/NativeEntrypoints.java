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
    private static final List<Registered> BACKENDS = new CopyOnWriteArrayList<>();
    private record Registered(BackendProvider provider, String version) {}
    private final Path configDirectory;
    private final String productVersion;
    private final BooleanSupplier apiInstalled;
    private final Supplier<String> apiVersion;
    private final Supplier<List<Integration>> integrations;

    public record Integration(String owner, Supplier<BackupIntegration> service,
                              Supplier<BackupLifecycleHook> lifecycle) {}

    public NativeEntrypoints(Path configDirectory, String productVersion, BooleanSupplier apiInstalled,
                             Supplier<List<Integration>> integrations) {
        this(configDirectory, productVersion, apiInstalled, () -> null, integrations);
    }

    public NativeEntrypoints(Path configDirectory, String productVersion, BooleanSupplier apiInstalled,
                             Supplier<String> apiVersion, Supplier<List<Integration>> integrations) {
        this.configDirectory = configDirectory;
        this.productVersion = productVersion;
        this.apiInstalled = apiInstalled;
        this.apiVersion = apiVersion;
        this.integrations = integrations;
    }

    public static void registerBackend(BackendProvider provider) {
        registerBackend(provider, null);
    }

    /** 注册后端并显式传入其版本；加载器通常使用自己的模组元数据版本 */
    public static void registerBackend(BackendProvider provider, String version) {
        BACKENDS.add(new Registered(java.util.Objects.requireNonNull(provider), version));
    }

    public static AutoCloseable registerBackupExclusion(String scope, String source, String pattern) {
        return io.github.zct_studio.barusikozako.core.BackupExclusions.register(scope, source, pattern);
    }

    /**
     * 合并扩展点与 {@code META-INF/services} 两条通道；扩展点优先，二者都在首次使用时才解析。
     * 集成标识保持为 mod 容器 ID，授权规则不变。
     */
    public static Integration integration(String owner, ServiceIntegrations.Source source,
                                          Supplier<BackupIntegration> extensionService,
                                          Supplier<BackupLifecycleHook> extensionLifecycle) {
        Supplier<BackupIntegration> serviceFromExtension = extensionService == null ? () -> null : extensionService;
        Supplier<BackupLifecycleHook> lifecycleFromExtension =
            extensionLifecycle == null ? () -> null : extensionLifecycle;
        Supplier<BackupIntegration> service = ServiceIntegrations.firstAvailable(owner, List.of(
            serviceFromExtension,
            () -> adaptService(ServiceIntegrations.service(owner, source,
                io.github.zct_studio.barusikozako.api.BackupIntegration.class).get())));
        Supplier<BackupLifecycleHook> lifecycle = ServiceIntegrations.firstAvailable(owner, List.of(
            lifecycleFromExtension,
            () -> adaptLifecycle(ServiceIntegrations.service(owner, source,
                io.github.zct_studio.barusikozako.api.BackupLifecycleHook.class).get())));
        return new Integration(owner, service, lifecycle);
    }

    private static BackupIntegration adaptService(
            io.github.zct_studio.barusikozako.api.BackupIntegration value) {
        return value == null ? null : PublicApi.integration(value);
    }

    private static BackupLifecycleHook adaptLifecycle(
            io.github.zct_studio.barusikozako.api.BackupLifecycleHook value) {
        return value == null ? null : PublicApi.lifecycle(value);
    }

    @Override public Path configDirectory() { return configDirectory; }

    @Override public String productVersion() { return productVersion; }
    @Override public boolean apiAvailable() { return apiInstalled.getAsBoolean(); }

    @Override public String apiVersion() {
        var version = apiVersion.get();
        if (version != null) return version;
        return apiInstalled.getAsBoolean() ? "unknown" : null;
    }

    @Override public <T> List<LoaderBridge.Entry<T>> entries(String channel, Class<T> contract) {
        if (channel.equals("barusikozako:backend") && contract == BackendProvider.class)
            return BACKENDS.stream().map(entry ->
                new LoaderBridge.Entry<T>(entry.provider().keyword(),
                    () -> contract.cast(entry.provider()), entry.version())).toList();
        if (channel.equals("barusikozako:backup_api") && contract == BackupIntegration.class)
            return integrations.get().stream().map(value ->
                new LoaderBridge.Entry<T>(value.owner(), () -> contract.cast(value.service().get()))).toList();
        if (channel.equals("barusikozako:lifecycle") && contract == BackupLifecycleHook.class)
            return integrations.get().stream().map(value ->
                new LoaderBridge.Entry<T>(value.owner(), () -> contract.cast(value.lifecycle().get()))).toList();
        return List.of();
    }
}
