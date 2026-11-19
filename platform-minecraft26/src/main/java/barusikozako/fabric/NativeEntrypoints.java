// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.backend.BackendProvider;
import barusikozako.api.BackupIntegration;
import barusikozako.api.BackupLifecycleHook;
import java.util.function.Supplier;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicit native-loader backend registration. Native integrations can supply loader-owned bindings. */
public final class NativeEntrypoints implements LoaderBridge.Binding {
    private static final List<BackendProvider> BACKENDS = new CopyOnWriteArrayList<>();
    private final Path configDirectory;
    private final String productVersion;
    private final Supplier<List<Integration>> integrations;

    public record Integration(String owner, Supplier<BackupIntegration> service,
                              Supplier<BackupLifecycleHook> lifecycle) {}

    public NativeEntrypoints(Path configDirectory, String productVersion, Supplier<List<Integration>> integrations) {
        this.configDirectory = configDirectory;
        this.productVersion = productVersion;
        this.integrations = integrations;
    }

    public static void registerBackend(BackendProvider provider) {
        BACKENDS.add(java.util.Objects.requireNonNull(provider));
    }

    @Override public Path configDirectory() { return configDirectory; }

    @Override public String productVersion() { return productVersion; }

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
