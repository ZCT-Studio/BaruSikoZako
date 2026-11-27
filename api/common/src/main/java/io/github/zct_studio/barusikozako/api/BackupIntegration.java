// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

/**
 * 宿主可在生命周期内授予访问权限的集成入口。
 *
 * <p>实现类可以通过以下任一方式被宿主发现，三种方式可以并存，且都以 mod 容器 ID 作为集成身份：
 * <ul>
 *   <li>Fabric/Quilt：在 {@code fabric.mod.json} 的 {@code barusikozako:backup_api} entrypoint 中声明类名；</li>
 *   <li>NeoForge/Forge：在自己的 {@code ModContainer} 上注册 {@code ZakoBakIntegration} 扩展点；</li>
 *   <li>所有加载器：在 JAR 内放置
 *       {@code META-INF/services/io.github.zct_studio.barusikozako.api.BackupIntegration}
 *       并写入实现类名，无需引用任何加载器类型。</li>
 * </ul>
 *
 * <p>服务文件中的实现类必须能被宿主类加载器加载，并具备 public 无参构造。
 * 同一 mod 同时使用多种方式时，entrypoint 或扩展点优先，服务文件作为回退。
 */
@FunctionalInterface
public interface BackupIntegration {
    /** 在游戏和任务线程之外的主机集成线程上接收可撤销服务 */
    void onBackupService(BackupService service);
}
