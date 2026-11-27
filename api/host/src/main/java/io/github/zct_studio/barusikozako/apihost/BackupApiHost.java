// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService;
import io.github.zct_studio.barusikozako.api.BackupService.Availability;
import io.github.zct_studio.barusikozako.api.BackupService.CapabilityState;
import io.github.zct_studio.barusikozako.api.BackupService.LogLevel;
import io.github.zct_studio.barusikozako.api.BackupService.MessageTarget;
import io.github.zct_studio.barusikozako.api.BackupService.Page;
import io.github.zct_studio.barusikozako.api.BackupService.PageRequest;
import io.github.zct_studio.barusikozako.api.BackupService.RepositoryInfo;
import io.github.zct_studio.barusikozako.api.BackupService.ServiceStatus;
import io.github.zct_studio.barusikozako.api.BackupService.SnapshotInfo;
import io.github.zct_studio.barusikozako.api.BackupService.TaskInfo;
import io.github.zct_studio.barusikozako.api.BackupService.VersionInfo;
import io.github.zct_studio.barusikozako.api.SnapshotRef;
import io.github.zct_studio.barusikozako.api.TaskEvent;
import io.github.zct_studio.barusikozako.api.TaskId;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.api.TaskState;
import io.github.zct_studio.barusikozako.api.TaskSubscription;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 内部主机组件。第三方集成仅接收公共服务 */
public final class BackupApiHost implements AutoCloseable {
    public record TimedTask(TaskState state, Instant submittedAt) {}

    private final Map<BoundAccess, java.util.Set<RuleRegistration>> worldRules = new java.util.HashMap<>();
    /** 每个集成句柄最近一次提交任务时携带的操作人，用于 EXECUTOR 消息受众 */
    private final Map<String, BackupService.BackupOperator> lastOperators = new java.util.concurrent.ConcurrentHashMap<>();

    private final class RuleRegistration implements AutoCloseable {
        private final BoundAccess owner;
        private final AutoCloseable registration;

        private RuleRegistration(BoundAccess owner, AutoCloseable registration) {
            this.owner = owner;
            this.registration = registration;
        }

        @Override public void close() {
            synchronized (BackupApiHost.this) {
                var rules = worldRules.get(owner);
                if (rules == null || !rules.remove(this)) return;
                if (rules.isEmpty()) worldRules.remove(owner);
                try {
                    registration.close();
                } catch (Exception failure) {
                    System.getLogger(BackupApiHost.class.getName()).log(System.Logger.Level.WARNING,
                        "Unable to unregister a world exclusion", failure);
                }
            }
        }
    }
    private static final int MAX_PAGE = 256;
    private final Operations operations;
    private final TaskRuntime runtime;
    private final ThreadPoolExecutor queries;
    private volatile boolean closed;

    public BackupApiHost(Operations operations) {
        this.operations = Objects.requireNonNull(operations);
        runtime = new TaskRuntime(1, 4, 128, Duration.ofMinutes(20), System::nanoTime);
        queries = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), job -> {
            var worker = new Thread(job, "BaruSikoZako repository queries");
            worker.setDaemon(true);
            return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        queries.allowCoreThreadTimeOut(true);
    }

    public Binding bind(String trustedIntegration, BoundAccess.Grant grant) {
        if (closed) throw new IllegalStateException("Backup API host has stopped");
        return new Binding(new BoundAccess(trustedIntegration, grant));
    }

    /** 让可信平台运行低优先级维护，而不会与捕获或前台突变发生冲突 */
    public boolean maintainIfIdle(Maintenance operation) throws IOException {
        return runtime.maintainIfIdle(Objects.requireNonNull(operation));
    }

    @FunctionalInterface
    public interface Maintenance {
        void run(java.util.function.BooleanSupplier mayContinue) throws IOException;
    }

    public final class Binding implements AutoCloseable {
        private final BoundAccess access;
        private final AccessService service;

        private Binding(BoundAccess access) {
            this.access = access;
            service = new AccessService(access);
        }

        public BackupService service() {
            return service;
        }

        /** 列出命令调用方可见且仍在保留期内的任务 */
        public List<TimedTask> tasks() {
            return runtime.tasks(access);
        }

        /** 平台内部命令的通知入口，回调只能投递消息，不可阻塞 */
        public BackupService.Submission submit(BackupRequest request, Consumer<List<String>> ignoreNotice) {
            return service.submit(request, ignoreNotice);
        }

        /** 平台内部命令入口，附带操作人以便任务列表展示 */
        public BackupService.Submission submit(BackupRequest request, Consumer<List<String>> ignoreNotice,
                                               Optional<BackupService.BackupOperator> operator) {
            return service.submit(request, ignoreNotice, operator);
        }

        @Override
        public void close() {
            runtime.revoke(access);
            lastOperators.remove(access.owner());
            synchronized (BackupApiHost.this) {
                for (var rule : List.copyOf(worldRules.getOrDefault(access, java.util.Set.of()))) rule.close();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        lastOperators.clear();
        synchronized (this) {
            var rules = worldRules.values().stream().flatMap(java.util.Set::stream).toList();
            for (var rule : rules) rule.close();
        }
        runtime.close();
        for (var task : queries.shutdownNow()) {
            ((ReadJob<?>) task).result.completeExceptionally(new RejectedExecutionException("Backup API host has stopped"));
        }
    }

    private final class AccessService implements BackupService {
        private final BoundAccess access;

        private AccessService(BoundAccess access) {
            this.access = access;
        }

        @Override
        public Submission submit(BackupRequest request) {
            return submit(request, ignored -> {}, Optional.empty());
        }

        @Override
        public Submission submit(BackupRequest request, Optional<BackupService.BackupOperator> operator) {
            return submit(request, ignored -> {}, operator);
        }

        private Submission submit(BackupRequest request, Consumer<List<String>> ignoreNotice) {
            return submit(request, ignoreNotice, Optional.empty());
        }

        private Submission submit(BackupRequest request, Consumer<List<String>> ignoreNotice,
                                  Optional<BackupService.BackupOperator> operator) {
            Objects.requireNonNull(request);
            if (closed) return new Submission.Rejected(Rejection.UNAVAILABLE);
            if (!access.maySubmit(request)) return new Submission.Rejected(Rejection.NOT_AUTHORIZED);
            // 版本与操作人是宿主事实：在准入、钩子与落盘之前统一写入，拓展无法伪造或遗漏。
            var enriched = withRuntimeMetadata(request, operator);
            var evaluation = operations.evaluate(enriched);
            if (evaluation.isPresent()) return new Submission.Rejected(evaluation.orElseThrow());
            var before = operations.before(enriched, access.owner());
            var admission = runtime.admit(access, enriched, control -> operations.run(enriched, new TaskControl() {
                @Override public void progress(long bytes, OptionalLong total) { control.progress(bytes, total); }
                @Override public boolean mayContinue() { return control.mayContinue(); }
                @Override public boolean beginCommit() { return control.beginCommit(); }
                @Override public void ignoreRules(List<String> rules) {
                    try {
                        ignoreNotice.accept(rules);
                    } catch (RuntimeException failure) {
                        System.getLogger(BackupApiHost.class.getName()).log(System.Logger.Level.WARNING,
                            "Unable to send world exclusion notice", failure);
                    }
                }
            }), before, operator);
            if (admission instanceof TaskRuntime.Admission.Rejected rejected)
                return new Submission.Rejected(rejected.reason());
            var accepted = (TaskRuntime.Admission.Accepted) admission;
            var result = accepted.receipt().stage();
            operator.ifPresent(value -> lastOperators.put(access.owner(), value));
            operations.after(enriched, access.owner(), result);
            return new Submission.Accepted(accepted.id(), result);
        }

        /**
         * 宿主权威地为备份请求补齐运行时版本与操作人信息。
         * 命令与定时任务此前已自行填写这些字段，宿主用等价的真实值覆盖，结果保持一致；
         * 第三方拓展（例如 WebUI）只提供备注等自定义字段，版本信息由宿主补上。
         * 拓展自定义字段保留，但不得覆盖宿主键，且总量遵守 Trigger 的元数据预算。
         */
        private BackupRequest withRuntimeMetadata(BackupRequest request,
                                                  Optional<BackupService.BackupOperator> operator) {
            if (!(request instanceof BackupRequest.Backup backup)) return request;
            Map<String, String> runtime;
            try {
                runtime = operations.runtimeMetadata();
            } catch (RuntimeException failure) {
                // 元数据是诊断信息，取不到版本绝不能让备份本身失败。
                System.getLogger(BackupApiHost.class.getName()).log(System.Logger.Level.WARNING,
                    "Unable to collect runtime metadata", failure);
                runtime = Map.of();
            }
            if (runtime.isEmpty() && operator.isEmpty()) return request;

            var merged = new java.util.LinkedHashMap<String, String>();
            // 宿主字段先写，保证一定存在且不会被拓展伪造。
            runtime.forEach((key, value) -> putMetadata(merged, key, value));
            operator.ifPresent(value -> {
                putMetadata(merged, "operator", value.name());
                value.id().ifPresent(id -> putMetadata(merged, "operatorUuid", id.toString()));
            });
            // 拓展自带的备注/来源标记等字段在剩余预算内保留，但不能覆盖宿主键。
            backup.trigger().metadata().forEach((key, value) -> {
                if (!merged.containsKey(key)) putMetadata(merged, key, value);
            });
            var trigger = new BackupRequest.Trigger(backup.trigger().source(), backup.trigger().reason(), merged);
            return new BackupRequest.Backup(backup.repositoryId(), backup.sourceId(), trigger,
                backup.mode(), backup.baseline());
        }

        /** 遵守 BackupRequest.Trigger 的约束：最多 16 项，键 ≤64、值 ≤256，且不允许 null 值。 */
        private static void putMetadata(Map<String, String> target, String key, String value) {
            if (key == null || key.isBlank() || key.length() > 64) return;
            if (target.size() >= 16 && !target.containsKey(key)) return;
            var text = value == null ? "unknown" : value;
            target.put(key, text.length() > 256 ? text.substring(0, 256) : text);
        }

        @Override public Cancellation cancel(TaskId id) { return runtime.cancel(access, id); }
        @Override public Optional<TaskState> task(TaskId id) { return runtime.task(access, id); }

        @Override
        public CompletionStage<Page<TaskInfo>> tasks(PageRequest page) {
            return read(() -> {
                requireActive();
                checkPage(page);
                var all = new java.util.ArrayList<>(runtime.tasks(access));
                all.sort(Comparator.comparing((TimedTask task) -> task.submittedAt()).reversed()
                    .thenComparing(task -> task.state().id().value()));
                int start = 0;
                if (page.continuation().isPresent()) {
                    String after = page.continuation().orElseThrow();
                    start = -1;
                    for (int index = 0; index < all.size(); index++) {
                        if (all.get(index).state().id().value().equals(after)) {
                            start = index + 1;
                            break;
                        }
                    }
                    if (start < 0) return new Page<>(List.of(), Optional.empty());
                }
                int end = Math.min(all.size(), start + page.limit());
                Optional<String> next = end < all.size()
                    ? Optional.of(all.get(end - 1).state().id().value()) : Optional.empty();
                return new Page<>(all.subList(start, end).stream()
                    .map(task -> new TaskInfo(task.submittedAt(), task.state())).toList(), next);
            });
        }

        @Override public AutoCloseable registerWorldExclusion(String pattern) {
            synchronized (BackupApiHost.this) {
                if (closed) throw new IllegalStateException("Backup API host has stopped");
                requireActive();
                var rule = new RuleRegistration(access, operations.registerWorldExclusion(access.owner(), pattern));
                worldRules.computeIfAbsent(access, ignored -> new java.util.HashSet<>()).add(rule);
                return rule;
            }
        }

        @Override
        public CompletionStage<Page<RepositoryInfo>> repositories(PageRequest page) {
            return read(() -> {
                requireActive();
                checkPage(page);
                var after = page.continuation().orElse("");
                var matching = operations.repositories().stream()
                    .filter(repository -> access.mayViewRepository(repository.id()) && repository.id().compareTo(after) > 0)
                    .sorted(Comparator.comparing(RepositoryInfo::id)).limit(page.limit() + 1L).toList();
                requireActive();
                if (matching.size() <= page.limit()) return new Page<>(matching, Optional.empty());
                return new Page<>(matching.subList(0, page.limit()), Optional.of(matching.get(page.limit() - 1).id()));
            });
        }

        @Override
        public CompletionStage<Page<SnapshotInfo>> snapshots(String repositoryId, PageRequest page) {
            return read(() -> {
                checkPage(page);
                requireView(repositoryId);
                var snapshots = operations.snapshots(repositoryId, page);
                requireView(repositoryId);
                return snapshots;
            });
        }

        @Override
        public CompletionStage<Optional<Path>> snapshotDirectory(SnapshotRef snapshot) {
            Objects.requireNonNull(snapshot);
            return read(() -> {
                requireView(snapshot.repositoryId());
                var directory = operations.snapshotDirectory(snapshot);
                requireView(snapshot.repositoryId());
                return directory;
            });
        }

        @Override
        public CompletionStage<Optional<SnapshotInfo>> snapshot(SnapshotRef snapshot) {
            Objects.requireNonNull(snapshot);
            return read(() -> {
                Optional<String> continuation = Optional.empty();
                while (true) {
                    requireView(snapshot.repositoryId());
                    var page = operations.snapshots(snapshot.repositoryId(), new PageRequest(MAX_PAGE, continuation));
                    for (var item : page.items()) {
                        if (item.snapshot().equals(snapshot)) return Optional.of(item);
                    }
                    if (page.next().isEmpty()) return Optional.empty();
                    continuation = page.next();
                }
            });
        }

        @Override
        public CompletionStage<ServiceStatus> status() {
            return read(() -> {
                requireActive();
                requireViewAny();
                return operations.status();
            });
        }

        @Override
        public CompletionStage<VersionInfo> version() {
            return read(() -> {
                requireActive();
                requireViewAny();
                return operations.version();
            });
        }

        @Override
        public CompletionStage<java.util.List<ModInfo>> mods() {
            return read(() -> {
                requireActive();
                requireViewAny();
                return operations.mods();
            });
        }

        @Override public void log(LogLevel level, String message) {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(message, "message");
            if (!access.isActive()) return;
            try {
                operations.log(access.owner(), level, message);
            } catch (RuntimeException failure) {
                System.getLogger(BackupApiHost.class.getName()).log(System.Logger.Level.WARNING,
                    "Unable to record an integration log message", failure);
            }
        }

        @Override public CompletionStage<Integer> sendMessage(MessageTarget target, String message) {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(message, "message");
            if (!access.isActive()) return java.util.concurrent.CompletableFuture.completedStage(0);
            try {
                return operations.sendMessage(access.owner(), target, message,
                    Optional.ofNullable(lastOperators.get(access.owner())));
            } catch (RuntimeException failure) {
                return java.util.concurrent.CompletableFuture.failedStage(failure);
            }
        }

        private void requireView(String repositoryId) {
            if (!access.mayViewRepository(repositoryId)) throw new SecurityException("Repository is not authorized");
        }

        private void requireViewAny() {
            if (!access.mayViewAnyRepository()) throw new SecurityException("Repository is not authorized");
        }

        private void requireActive() {
            if (!access.isActive()) throw new SecurityException("Backup access has been revoked");
        }

        @Override
        public CompletionStage<List<CapabilityState>> capabilities() {
            return read(() -> {
                requireActive();
                return Arrays.stream(BackupRequest.Kind.values())
                    .map(kind -> new CapabilityState(kind, operations.availability(kind))).toList();
            });
        }

        @Override
        public CompletionStage<Map<String, String>> configuration() {
            return read(() -> {
                requireActive();
                if (!access.mayConfigure()) throw new SecurityException("Configuration access is not authorized");
                return Map.copyOf(operations.configuration());
            });
        }

        @Override
        public CompletionStage<Void> updateConfiguration(String key, String value) {
            Objects.requireNonNull(key);
            Objects.requireNonNull(value);
            return read(() -> {
                requireActive();
                if (!access.mayConfigure()) throw new SecurityException("Configuration access is not authorized");
                operations.updateConfiguration(key, value);
                return null;
            });
        }

        @Override
        public CompletionStage<Map<String, String>> backendConfiguration(String backend) {
            Objects.requireNonNull(backend);
            return read(() -> {
                requireActive();
                if (!access.mayConfigure()) throw new SecurityException("Configuration access is not authorized");
                return Map.copyOf(operations.backendConfiguration(backend));
            });
        }

        @Override
        public CompletionStage<Void> updateBackendConfiguration(String backend, String key, String value) {
            Objects.requireNonNull(backend);
            Objects.requireNonNull(key);
            Objects.requireNonNull(value);
            return read(() -> {
                requireActive();
                if (!access.mayConfigure()) throw new SecurityException("Configuration access is not authorized");
                operations.updateBackendConfiguration(backend, key, value);
                return null;
            });
        }

        @Override
        public CompletionStage<TaskSubscription> subscribe(Optional<String> repositoryId, Consumer<TaskEvent> listener) {
            return runtime.subscribe(access, Objects.requireNonNull(repositoryId), listener);
        }
    }

    private static void checkPage(PageRequest page) {
        if (page.limit() > MAX_PAGE || page.continuation().filter(value -> value.length() > 128).isPresent())
            throw new IllegalArgumentException("Page exceeds the supported query budget");
    }

    private <T> CompletionStage<T> read(ReadOperation<T> operation) {
        var job = new ReadJob<>(operation);
        try {
            queries.execute(job);
        } catch (RejectedExecutionException stopping) {
            job.result.completeExceptionally(stopping);
        }
        return runtime.resultStage(job.result);
    }

    private static final class ReadJob<T> implements Runnable {
        private final ReadOperation<T> operation;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private ReadJob(ReadOperation<T> operation) {
            this.operation = operation;
        }

        @Override
        public void run() {
            try {
                result.complete(operation.run());
            } catch (IOException failure) {
                result.completeExceptionally(new IOException("Repository query failed"));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }
    }

    @FunctionalInterface
    private interface ReadOperation<T> { T run() throws IOException; }

    public interface TaskControl {
        void progress(long bytes, OptionalLong total);
        boolean mayContinue();
        boolean beginCommit();
        default void ignoreRules(List<String> rules) {}
    }

    public interface Operations {
        default AutoCloseable registerWorldExclusion(String source, String pattern) {
            throw new UnsupportedOperationException("World exclusion registration unavailable");
        }
        default Map<String, String> configuration() throws IOException { throw new UnsupportedOperationException("Configuration unavailable"); }
        default void updateConfiguration(String key, String value) throws IOException { throw new UnsupportedOperationException("Configuration unavailable"); }
        default Map<String, String> backendConfiguration(String backend) throws IOException { throw new UnsupportedOperationException("Configuration unavailable"); }
        default void updateBackendConfiguration(String backend, String key, String value) throws IOException {
            throw new UnsupportedOperationException("Configuration unavailable");
        }
        default ServiceStatus status() throws IOException { throw new UnsupportedOperationException("Service status unavailable"); }
        default VersionInfo version() throws IOException { throw new UnsupportedOperationException("Version information unavailable"); }
        default java.util.List<BackupService.ModInfo> mods() throws IOException { return java.util.List.of(); }

        /**
         * 宿主权威的运行时版本信息（游戏版本、主模组版本、后端版本）。每次备份提交时由
         * 宿主写入快照元数据，第三方集成无需、也无法自行伪造这些字段。默认返回空 Map，
         * 例如独立运行的 javaagent 平台没有游戏版本概念。
         */
        default Map<String, String> runtimeMetadata() { return Map.of(); }

        /** 记录一条带集成来源的日志；默认写入 BaruSikoZako 日志 */
        default void log(String source, BackupService.LogLevel level, String message) {
            var target = switch (level) {
                case ERROR -> System.Logger.Level.ERROR;
                case WARN -> System.Logger.Level.WARNING;
                case INFO -> System.Logger.Level.INFO;
                case DEBUG -> System.Logger.Level.DEBUG;
                case TRACE -> System.Logger.Level.TRACE;
            };
            var text = "[" + source + "] " + String.valueOf(message)
                .replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
            System.getLogger("BaruSikoZako").log(target, text);
        }

        /**
         * 向预设受众投递消息。source 为集成标识，executor 为最近一次经该句柄提交任务的操作人。
         * 默认实现不支持玩家消息并返回 0；平台实现应在服务端主线程读取玩家列表。
         */
        default java.util.concurrent.CompletionStage<Integer> sendMessage(
                String source, BackupService.MessageTarget target, String message,
                java.util.Optional<BackupService.BackupOperator> executor) {
            return java.util.concurrent.CompletableFuture.completedStage(0);
        }

        Availability availability(BackupRequest.Kind kind);
        /** 仅非阻塞平台准入检查 */
        Optional<BackupService.Rejection> evaluate(BackupRequest request);
        List<RepositoryInfo> repositories();
        Page<SnapshotInfo> snapshots(String repository, PageRequest page) throws IOException;
        default Optional<Path> snapshotDirectory(SnapshotRef snapshot) throws IOException { return Optional.empty(); }
        default Runnable before(BackupRequest request, String principal) { return null; }
        default void after(BackupRequest request, String principal, CompletionStage<TaskResult> result) {}
        TaskResult run(BackupRequest request, TaskControl control) throws IOException;
    }
}
