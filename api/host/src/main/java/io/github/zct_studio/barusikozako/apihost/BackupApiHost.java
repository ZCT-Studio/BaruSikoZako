// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService;
import io.github.zct_studio.barusikozako.api.BackupService.Availability;
import io.github.zct_studio.barusikozako.api.BackupService.CapabilityState;
import io.github.zct_studio.barusikozako.api.BackupService.Page;
import io.github.zct_studio.barusikozako.api.BackupService.PageRequest;
import io.github.zct_studio.barusikozako.api.BackupService.RepositoryInfo;
import io.github.zct_studio.barusikozako.api.BackupService.SnapshotInfo;
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

        @Override
        public void close() {
            runtime.revoke(access);
            synchronized (BackupApiHost.this) {
                for (var rule : List.copyOf(worldRules.getOrDefault(access, java.util.Set.of()))) rule.close();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
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
            return submit(request, ignored -> {});
        }

        private Submission submit(BackupRequest request, Consumer<List<String>> ignoreNotice) {
            Objects.requireNonNull(request);
            if (closed) return new Submission.Rejected(Rejection.UNAVAILABLE);
            if (!access.maySubmit(request)) return new Submission.Rejected(Rejection.NOT_AUTHORIZED);
            var evaluation = operations.evaluate(request);
            if (evaluation.isPresent()) return new Submission.Rejected(evaluation.orElseThrow());
            var before = operations.before(request, access.owner());
            var admission = runtime.admit(access, request, control -> operations.run(request, new TaskControl() {
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
            }), before);
            if (admission instanceof TaskRuntime.Admission.Rejected rejected)
                return new Submission.Rejected(rejected.reason());
            var accepted = (TaskRuntime.Admission.Accepted) admission;
            var result = accepted.receipt().stage();
            operations.after(request, access.owner(), result);
            return new Submission.Accepted(accepted.id(), result);
        }

        @Override public Cancellation cancel(TaskId id) { return runtime.cancel(access, id); }
        @Override public Optional<TaskState> task(TaskId id) { return runtime.task(access, id); }

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

        private void requireView(String repositoryId) {
            if (!access.mayViewRepository(repositoryId)) throw new SecurityException("Repository is not authorized");
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
