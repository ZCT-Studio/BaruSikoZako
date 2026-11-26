// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.backend.BackendProvider;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class BackendRegistry {
    static final String ZSTD = "barusikozako_backendzstd";

    private BackendRegistry() {}

    static Map<String, BackendProvider> registered() {
        return Registered.BACKENDS;
    }

    /** 已注册后端的版本；未提供版本的显示 unknown */
    static Map<String, String> versions() {
        return Registered.VERSIONS;
    }

    static BackendProvider.Settings configure(String keyword, boolean create) throws IOException {
        var provider = registered().get(keyword);
        if (provider == null) throw new IOException("Backend is not installed: " + keyword);
        var folder = LoaderBridge.configDirectory().resolve("barusikozako");
        return provider.configure(YamlSettings.load(folder.resolve(keyword + ".yml"), provider.defaults(), create, Map::of));
    }

    private static final class Registered {
        private static final Loaded LOADED = load();
        private static final Map<String, BackendProvider> BACKENDS = LOADED.backends();
        private static final Map<String, String> VERSIONS = LOADED.versions();

        private record Loaded(Map<String, BackendProvider> backends, Map<String, String> versions) {}

        private static Loaded load() {
            var backends = new LinkedHashMap<String, BackendProvider>();
            var versions = new LinkedHashMap<String, String>();
            for (var entry : LoaderBridge.entries("barusikozako:backend", BackendProvider.class)) {
                var provider = entry.value().get();
                var keyword = provider.keyword();
                if (keyword.equals("server") || !keyword.matches("[a-z][a-z0-9_-]{1,63}") ||
                    backends.putIfAbsent(keyword, provider) != null)
                    throw new IllegalStateException("Invalid or duplicate backend keyword: " + keyword);
                var version = entry.version();
                versions.put(keyword, version == null || version.isBlank() ? "unknown" : version);
                var names = new java.util.HashSet<String>();
                for (var command : provider.commands()) {
                    if (!command.keyword().matches("[a-z][a-z0-9_-]{0,31}") || !names.add(command.keyword()) ||
                        java.util.Set.of("status", "config", "backup", "list", "verify", "delete", "rollback", "task", "cancel")
                            .contains(command.keyword()))
                        throw new IllegalStateException("Invalid or reserved backend command: " + command.keyword());
                }
            }
            return new Loaded(Map.copyOf(backends), Map.copyOf(versions));
        }
    }
}
