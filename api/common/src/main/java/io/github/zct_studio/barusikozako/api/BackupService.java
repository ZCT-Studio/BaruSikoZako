// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** 主机发布的用于授权集成的服务。所有入口点均返回，无需等待 I/O */
public interface BackupService {
    Submission submit(BackupRequest request);

    Cancellation cancel(TaskId id);

    Optional<TaskState> task(TaskId id);

    CompletionStage<Page<RepositoryInfo>> repositories(PageRequest page);

    CompletionStage<Page<SnapshotInfo>> snapshots(String repositoryId, PageRequest page);
    /**
     * 查询已提交快照当前的绝对目录路径。快照不存在或无本地目录时返回空值。
     * 查询使用仓库读取权限。迁移或删除可使此前返回的路径失效。
     */
    default CompletionStage<Optional<Path>> snapshotDirectory(SnapshotRef snapshot) {
        Objects.requireNonNull(snapshot);
        return java.util.concurrent.CompletableFuture.completedStage(Optional.empty());
    }

    /** 查询已提交快照当前的实际目录名，使用与目录路径查询相同的权限 */
    default CompletionStage<Optional<String>> snapshotFolderName(SnapshotRef snapshot) {
        return snapshotDirectory(snapshot).thenApply(directory -> directory.map(Path::getFileName).map(Path::toString));
    }


    CompletionStage<List<CapabilityState>> capabilities();

    /** 注册世界排除条目，来源为当前集成标识，关闭句柄后影响下一次备份 */
    default AutoCloseable registerWorldExclusion(String pattern) {
        throw new UnsupportedOperationException("World exclusion registration unavailable");
    }

    default CompletionStage<java.util.Map<String, String>> configuration() {
        return java.util.concurrent.CompletableFuture.failedStage(new UnsupportedOperationException("Configuration unavailable"));
    }

    default CompletionStage<Void> updateConfiguration(String key, String value) {
        return java.util.concurrent.CompletableFuture.failedStage(new UnsupportedOperationException("Configuration unavailable"));
    }

    /**
     * 在与注册暂停的订阅相同的边界处采用授权基线
     * 稍后的事件将被缓冲，直到调用者应用基线并开始传递
     */
    CompletionStage<TaskSubscription> subscribe(Optional<String> repositoryId, Consumer<TaskEvent> listener);

    sealed interface Submission {
        record Accepted(TaskId id, CompletionStage<TaskResult> completion) implements Submission {
            public Accepted {
                Objects.requireNonNull(id);
                Objects.requireNonNull(completion);
            }
        }

        record Rejected(Rejection reason) implements Submission {
            public Rejected {
                Objects.requireNonNull(reason);
            }
        }
    }

    enum Rejection { UNAVAILABLE, NOT_AUTHORIZED, BUSY, UNSUPPORTED, INVALID_REQUEST }

    /** REQUESTED 记录意图；最终取消由任务结果报告 */
    enum Cancellation { REQUESTED, TOO_LATE, NOT_AVAILABLE }

    record PageRequest(int limit, Optional<String> continuation) {
        public PageRequest {
            if (limit < 1) throw new IllegalArgumentException("page limit must be positive");
            Objects.requireNonNull(continuation);
        }
    }

    record Page<T>(List<T> items, Optional<String> next) {
        public Page {
            items = List.copyOf(items);
            Objects.requireNonNull(next);
        }
    }

    record RepositoryInfo(String id, String displayName) {}

    record SnapshotInfo(SnapshotRef snapshot, java.time.Instant createdAt, String sourceId,
                        Optional<BackupRequest.Trigger> trigger) {
        public SnapshotInfo {
            Objects.requireNonNull(trigger);
        }

        public SnapshotInfo(SnapshotRef snapshot, java.time.Instant createdAt, String sourceId) {
            this(snapshot, createdAt, sourceId, Optional.empty());
        }
    }

    record CapabilityState(BackupRequest.Kind operation, Availability availability) {}

    enum Availability { AVAILABLE, TEMPORARILY_UNAVAILABLE, UNSUPPORTED }
}
