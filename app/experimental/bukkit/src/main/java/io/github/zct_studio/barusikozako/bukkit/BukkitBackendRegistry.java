// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit;

import io.github.zct_studio.barusikozako.backend.BackendProvider;
import io.github.zct_studio.barusikozako.backend.BackendProvider.Settings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Bukkit backend registry. Loads backend providers via {@link ServiceLoader} and configures them.
 */
final class BukkitBackendRegistry {
    private BukkitBackendRegistry() {}

    static Settings configure(String keyword, BukkitConfig config, boolean create) throws IOException {
        var provider = ServiceLoader.load(BackendProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.keyword().equals(keyword))
                .findFirst()
                .orElseThrow(() -> new IOException("Backend is not installed: " + keyword));

        var folder = Path.of(configDir(config).resolve(keyword + ".yml").toString());
        return provider.configure(YamlSettings.load(folder, provider.defaults(), true, () -> {
            var values = new LinkedHashMap<>(provider.defaults());
            // Bukkit 尚无遗留迁移
            return Map.of();
        }));
    }

    private static Path configDir(BukkitConfig config) {
        // 这是一个解决方法 - 我们需要访问服务器的配置目录
        // 现在，假设相对于服务器根目录的标准位置
        return Path.of("config", "barusikozako");
    }
}