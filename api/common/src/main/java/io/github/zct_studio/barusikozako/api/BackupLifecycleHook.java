// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

/**
 * 已注册的生命周期回调在游戏线程和备份工作线程之外执行
 * 实施必须及时返回，不得等待备份任务，并且必须安排任何
 * 游戏线程自己工作。回调之前失败或超时会阻止该操作
 */
public interface BackupLifecycleHook {
    enum Operation { AUTOMATIC_BACKUP, MANUAL_BACKUP, OFFLINE_RESTORE }

    record Context(Operation operation, String repositoryId, java.util.Optional<SnapshotRef> snapshot) {}

    default void before(Context context) {}

    default void after(Context context, TaskResult outcome) {}
}
