// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

/**
 * 已注册的生命周期回调在游戏线程和备份工作线程之外执行
 * 实施必须及时返回，不得等待备份任务，并且必须安排任何
 * 游戏线程自己工作。回调之前失败或超时会阻止该操作
 */
public interface BackupLifecycleHook {
    enum Operation {
        /** 定时或脚本触发的备份 */
        AUTOMATIC_BACKUP,
        /** 玩家或 API 手动的备份 */
        MANUAL_BACKUP,
        /** 快照完整性校验 */
        VERIFY,
        /** 快照删除 */
        DELETE,
        /** 在线回滚请求；世界实际切换时还会触发 {@link #OFFLINE_RESTORE} */
        RESTORE,
        /** 启动阶段的离线恢复 */
        OFFLINE_RESTORE
    }

    /** snapshot 为校验、删除或回滚的目标；备份与不针对单个快照的操作留空 */
    record Context(Operation operation, String repositoryId, java.util.Optional<SnapshotRef> snapshot) {}

    default void before(Context context) {}

    default void after(Context context, TaskResult outcome) {}
}
