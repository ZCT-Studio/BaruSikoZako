// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

/** 宿主可在生命周期内授予访问权限的集成入口 */
@FunctionalInterface
public interface BackupIntegration {
    /** 在游戏和任务线程之外的主机集成线程上接收可撤销服务 */
    void onBackupService(BackupService service);
}
