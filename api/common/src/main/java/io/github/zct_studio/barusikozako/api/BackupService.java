// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** 主机发布的用于授权集成的服务。所有入口点均返回，无需等待 I/O */
public interface BackupService {
    Submission submit(BackupRequest request);

    /** 提交任务并附带操作人；旧实现可以忽略操作人 */
    default Submission submit(BackupRequest request, Optional<BackupOperator> operator) {
        Objects.requireNonNull(operator);
        return submit(request);
    }

    Cancellation cancel(TaskId id);

    Optional<TaskState> task(TaskId id);

    /**
     * 与任务列表命令一致：列出调用方可见且仍在保留期内的任务，按提交时间倒序。
     * continuation 为上一页末项的任务 ID；该任务被淘汰后游标失效并返回空页。
     */
    default CompletionStage<Page<TaskInfo>> tasks(PageRequest page) {
        Objects.requireNonNull(page);
        return java.util.concurrent.CompletableFuture.failedStage(
            new UnsupportedOperationException("Task listing unavailable"));
    }

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

    /** 与快照详细信息命令一致：按标识查询单条信息，不存在或超出保留期时为空 */
    default CompletionStage<Optional<SnapshotInfo>> snapshot(SnapshotRef snapshot) {
        Objects.requireNonNull(snapshot);
        return java.util.concurrent.CompletableFuture.completedStage(Optional.empty());
    }

    /** 与服务状态命令一致：捕获可用性、后端、仓库、计划间隔与配置目录 */
    default CompletionStage<ServiceStatus> status() {
        return java.util.concurrent.CompletableFuture.failedStage(
            new UnsupportedOperationException("Service status unavailable"));
    }

    /** 与版本命令一致：本体、API 与已注册后端的版本 */
    default CompletionStage<VersionInfo> version() {
        return java.util.concurrent.CompletableFuture.failedStage(
            new UnsupportedOperationException("Version information unavailable"));
    }

    /**
     * 已注册后端与已激活拓展的真实模组元数据。id 是加载器标识，name 是模组显示名，
     * 便于界面展示；宿主不支持时返回空列表。
     */
    default CompletionStage<List<ModInfo>> mods() {
        return java.util.concurrent.CompletableFuture.completedStage(List.of());
    }

    /** 一个已注册后端或已激活拓展的模组元数据 */
    record ModInfo(String id, String name, String version) {
        public ModInfo {
            Objects.requireNonNull(id);
            name = name == null || name.isBlank() ? id : name;
            version = version == null || version.isBlank() ? "unknown" : version;
        }
    }

    /**
     * 记录一条日志。宿主自动附加集成来源标识（mod 容器 ID），调用方不能伪造来源；
     * 句柄被撤销或宿主不支持时静默忽略。message 由宿主清洗为单行文本。
     */
    default void log(LogLevel level, String message) {}

    /**
     * 向预设受众发送一条聊天消息，返回的阶段给出成功排队投递的在线玩家数。
     *
     * <p>受众只能是 {@link MessageTarget} 的三类预设值，接口不接受玩家名或 UUID，
     * 因此扩展无法自由选择接收人。{@code EXECUTOR} 指最近一次经同一句柄提交任务时
     * 携带的 {@link BackupOperator} 所对应的在线玩家；找不到可解析的执行者时返回 0。
     * 投递是异步的，数值只代表已排队的玩家数，不等待消息显示。
     */
    default CompletionStage<Integer> sendMessage(MessageTarget target, String message) {
        return java.util.concurrent.CompletableFuture.completedStage(0);
    }

    /** 日志级别；与宿主日志体系对应 */
    enum LogLevel { ERROR, WARN, INFO, DEBUG, TRACE }

    /** 玩家消息受众；只允许预设三类，不接受任意玩家标识 */
    enum MessageTarget { ALL, EXECUTOR, ADMINS }

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

    /** 与后端配置命令一致：读取已注册后端的配置，需要配置权限 */
    default CompletionStage<Map<String, String>> backendConfiguration(String backend) {
        Objects.requireNonNull(backend);
        return java.util.concurrent.CompletableFuture.failedStage(new UnsupportedOperationException("Configuration unavailable"));
    }

    /** 与后端配置命令一致：写入已注册后端的配置，需要配置权限 */
    default CompletionStage<Void> updateBackendConfiguration(String backend, String key, String value) {
        Objects.requireNonNull(backend);
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
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

    /** 任务列表条目：提交时间与当前状态 */
    record TaskInfo(java.time.Instant submittedAt, TaskState state) {
        public TaskInfo {
            Objects.requireNonNull(submittedAt);
            Objects.requireNonNull(state);
        }
    }

    /** 服务状态；与服务状态命令展示的信息一致 */
    record ServiceStatus(boolean captureAvailable, Optional<String> backend, Optional<RepositoryInfo> repository,
                         Optional<Path> repositoryDirectory, long scheduleIntervalSeconds,
                         Optional<Path> configurationDirectory) {
        public ServiceStatus {
            Objects.requireNonNull(backend);
            Objects.requireNonNull(repository);
            Objects.requireNonNull(repositoryDirectory);
            Objects.requireNonNull(configurationDirectory);
            if (scheduleIntervalSeconds < 0) throw new IllegalArgumentException("negative schedule interval");
        }
    }

    /** 版本信息；与版本命令展示的信息一致 */
    record VersionInfo(String modVersion, Optional<String> apiVersion, Map<String, String> backends) {
        public VersionInfo {
            Objects.requireNonNull(modVersion);
            Objects.requireNonNull(apiVersion);
            Objects.requireNonNull(backends);
            backends = Map.copyOf(backends);
        }
    }

    record SnapshotInfo(SnapshotRef snapshot, java.time.Instant createdAt, String sourceId,
                        Consistency consistency, Optional<BackupRequest.Trigger> trigger,
                        java.util.OptionalLong archiveBytes, java.util.OptionalLong logicalBytes,
                        Optional<java.time.Duration> elapsed, Optional<SnapshotLayout> layout,
                        Optional<BackupOperator> operator) {
        public SnapshotInfo {
            Objects.requireNonNull(consistency);
            Objects.requireNonNull(trigger);
            Objects.requireNonNull(archiveBytes);
            Objects.requireNonNull(logicalBytes);
            Objects.requireNonNull(elapsed);
            Objects.requireNonNull(layout);
            Objects.requireNonNull(operator);
            if (archiveBytes.isPresent() && archiveBytes.getAsLong() < 0 ||
                logicalBytes.isPresent() && logicalBytes.getAsLong() < 0)
                throw new IllegalArgumentException("negative snapshot size");
            if (elapsed.isPresent() && elapsed.orElseThrow().isNegative())
                throw new IllegalArgumentException("negative snapshot duration");
        }

        public SnapshotInfo(SnapshotRef snapshot, java.time.Instant createdAt, String sourceId,
                            Consistency consistency, Optional<BackupRequest.Trigger> trigger) {
            this(snapshot, createdAt, sourceId, consistency, trigger, java.util.OptionalLong.empty(),
                java.util.OptionalLong.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        public SnapshotInfo(SnapshotRef snapshot, java.time.Instant createdAt, String sourceId,
                            Optional<BackupRequest.Trigger> trigger) {
            this(snapshot, createdAt, sourceId, Consistency.STRICT, trigger);
        }

        public SnapshotInfo(SnapshotRef snapshot, java.time.Instant createdAt, String sourceId) {
            this(snapshot, createdAt, sourceId, Consistency.STRICT, Optional.empty());
        }
    }

    /** 快照的存储布局；旧后端可能不提供 */
    enum SnapshotLayout { FULL, INCREMENTAL, DIFFERENTIAL }

    /** 备份操作人；缺少该记录时界面显示为 Server */
    record BackupOperator(String name, Optional<java.util.UUID> id) {
        public BackupOperator {
            Objects.requireNonNull(name);
            Objects.requireNonNull(id);
            if (name.isBlank()) throw new IllegalArgumentException("missing operator name");
        }
    }

    record CapabilityState(BackupRequest.Kind operation, Availability availability) {}

    /** 快照捕获时的一致性等级；旧快照按 strict 处理 */
    enum Consistency { STRICT, RELAXED }

    enum Availability { AVAILABLE, TEMPORARILY_UNAVAILABLE, UNSUPPORTED }
}
