// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit.api;

import io.github.zct_studio.barusikozako.api.BackupService;
import org.bukkit.plugin.Plugin;

/** Bukkit 发现 mod 版本使用的相同公共服务接口 */
public interface BukkitBackupApi {
    /** 返回调用者的授权服务，在插件禁用时撤销 */
    BackupService service(Plugin owner);
}
