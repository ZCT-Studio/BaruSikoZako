// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

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

    static BackendProvider.Settings configure(String keyword, boolean create) throws IOException {
        var provider = registered().get(keyword);
        if (provider == null) throw new IOException("Backend is not installed: " + keyword);
        var folder = LoaderBridge.configDirectory().resolve("barusikozako");
        return provider.configure(YamlSettings.load(folder.resolve(keyword + ".yml"), provider.defaults(), create, Map::of));
    }

    private static final class Registered {
        private static final Map<String, BackendProvider> BACKENDS = load();

        private static Map<String, BackendProvider> load() {
            var result = new LinkedHashMap<String, BackendProvider>();
            for (var provider : LoaderBridge.entries("barusikozako:backend", BackendProvider.class).stream().map(entry -> entry.value().get()).toList()) {
                var keyword = provider.keyword();
                if (keyword.equals("server") || !keyword.matches("[a-z][a-z0-9_-]{1,63}") ||
                    result.putIfAbsent(keyword, provider) != null)
                    throw new IllegalStateException("Invalid or duplicate backend keyword: " + keyword);
                var names = new java.util.HashSet<String>();
                for (var command : provider.commands()) {
                    if (!command.keyword().matches("[a-z][a-z0-9_-]{0,31}") || !names.add(command.keyword()) ||
                        java.util.Set.of("status", "config", "backup", "list", "verify", "delete", "restore", "task", "cancel")
                            .contains(command.keyword()))
                        throw new IllegalStateException("Invalid or reserved backend command: " + command.keyword());
                }
            }
            return Map.copyOf(result);
        }
    }
}
