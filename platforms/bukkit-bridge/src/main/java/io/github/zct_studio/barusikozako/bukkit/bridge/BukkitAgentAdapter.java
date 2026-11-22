// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit.bridge;

import io.github.zct_studio.barusikozako.api.BackupLifecycleHook;
import io.github.zct_studio.barusikozako.backend.BackendProvider;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit 服务，用于硬依赖于 `barusikozako` 的插件
 * 在onEnable期间注册。将 SPI 和桥接工件保留为仅编译依赖项
 * 当拥有的插件被禁用时，桥会关闭返回的租约
 */
public interface BukkitAgentAdapter {
    String status();

    AutoCloseable registerBackend(Plugin owner, BackendProvider provider);

    AutoCloseable registerExtension(Plugin owner, BackupLifecycleHook extension);
}
