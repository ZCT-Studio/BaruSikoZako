// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.apihost;

import barusikozako.api.BackupRequest;
import barusikozako.api.BackupService;
import barusikozako.api.BackupService.Availability;
import barusikozako.api.BackupService.CapabilityState;
import barusikozako.api.BackupService.Page;
import barusikozako.api.BackupService.PageRequest;
import barusikozako.api.BackupService.RepositoryInfo;
import barusikozako.api.BackupService.SnapshotInfo;
import barusikozako.api.TaskEvent;
import barusikozako.api.TaskId;
import barusikozako.api.TaskResult;
import barusikozako.api.TaskState;
import barusikozako.api.TaskSubscription;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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

/** Internal host assembly. Third-party integrations receive only the public service. */
public final class BackupApiHost implements AutoCloseable {
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

    /** Lets a trusted platform run low-priority upkeep without colliding with capture or foreground mutation. */
    public boolean maintainIfIdle(Maintenance operation) throws IOException {
        return runtime.maintainIfIdle(Objects.requireNonNull(operation));
    }

    @FunctionalInterface
    public interface Maintenance {
        void run(java.util.function.BooleanSupplier mayContinue) throws IOException;
    }

    public final class Binding implements AutoCloseable {
        private final BoundAccess access;
        private final BackupService service;

        private Binding(BoundAccess access) {
            this.access = access;
            service = new AccessService(access);
        }

        public BackupService service() {
            return service;
        }

        @Override
        public void close() {
            runtime.revoke(access);
        }
    }

    @Override
    public void close() {
        closed = true;
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
    }

    public interface Operations {
        Availability availability(BackupRequest.Kind kind);
        /** Nonblocking platform-only admission checks. */
        Optional<BackupService.Rejection> evaluate(BackupRequest request);
        List<RepositoryInfo> repositories();
        Page<SnapshotInfo> snapshots(String repository, PageRequest page) throws IOException;
        default Runnable before(BackupRequest request, String principal) { return null; }
        default void after(BackupRequest request, String principal, CompletionStage<TaskResult> result) {}
        TaskResult run(BackupRequest request, TaskControl control) throws IOException;
    }
}
