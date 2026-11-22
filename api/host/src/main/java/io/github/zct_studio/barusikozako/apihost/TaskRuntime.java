// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService.Cancellation;
import io.github.zct_studio.barusikozako.api.BackupService.Rejection;
import io.github.zct_studio.barusikozako.api.TaskId;
import io.github.zct_studio.barusikozako.api.TaskEvent;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.api.TaskState;
import io.github.zct_studio.barusikozako.api.TaskState.Phase;
import io.github.zct_studio.barusikozako.api.TaskSubscription;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 内部宿主调度器，仅执行受信任的宿主操作 */
final class TaskRuntime implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TaskRuntime.class.getName());
    private static final long PROGRESS_INTERVAL = Duration.ofMillis(200).toNanos();

    private final String instance = UUID.randomUUID().toString();
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor preparations = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), action -> {
            var thread = new Thread(action, "BaruSikoZako integration preparation");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final ResultDelivery results = new ResultDelivery(2, 32, 128, 8);
    private final TaskNotifications notifications = new TaskNotifications(2, 16, 64, Duration.ofSeconds(15));
    private final int terminalLimit;
    private final long retentionNanos;
    private final LongSupplier clock;
    private final HashMap<TaskId, Work> active = new HashMap<>();
    private final HashSet<String> occupiedRepositories = new HashSet<>();
    private final LinkedHashMap<TaskId, Terminal> terminal = new LinkedHashMap<>();
    private long sequence;
    private boolean closed;
    private Maintenance maintenance;

    TaskRuntime(int concurrency, int queueCapacity, int terminalLimit, Duration retention, LongSupplier clock) {
        retentionNanos = retention.toNanos();
        if (terminalLimit < 1 || retentionNanos < 1) throw new IllegalArgumentException("invalid terminal retention");
        this.terminalLimit = terminalLimit;
        this.clock = Objects.requireNonNull(clock);
        workers = new ThreadPoolExecutor(concurrency, concurrency, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    synchronized Admission admit(BoundAccess access, BackupRequest request, Operation operation) {
        return admit(access, request, operation, null);
    }

    synchronized Admission admit(BoundAccess access, BackupRequest request, Operation operation, Runnable before) {
        Objects.requireNonNull(operation);
        if (closed) return new Admission.Rejected(Rejection.UNAVAILABLE);
        synchronized (access) {
            if (!access.maySubmit(request)) return new Admission.Rejected(Rejection.NOT_AUTHORIZED);
            if (maintenance != null) {
                maintenance.stop = true;
                return new Admission.Rejected(Rejection.BUSY);
            }
            if (!occupiedRepositories.add(request.repositoryId())) return new Admission.Rejected(Rejection.BUSY);
            if (sequence == Long.MAX_VALUE) {
                occupiedRepositories.remove(request.repositoryId());
                return new Admission.Rejected(Rejection.UNAVAILABLE);
            }
            var id = new TaskId(instance + ":" + ++sequence);
            var work = new Work(id, access, request, operation);
            active.put(id, work);
            try {
                if (before == null) {
                    workers.execute(work);
                    publish(work, new TaskEvent.StateChanged(work.state));
                } else {
                    var preparation = new CompletableFuture<Void>();
                    var callback = preparations.submit(() -> {
                        try {
                            if (new Control(work).mayContinue()) before.run();
                            preparation.complete(null);
                        } catch (Throwable failure) {
                            preparation.completeExceptionally(failure);
                        }
                    });
                    publish(work, new TaskEvent.StateChanged(work.state));
                    preparation.orTimeout(5, TimeUnit.SECONDS).whenComplete((ignored, failure) -> {
                        if (failure instanceof java.util.concurrent.TimeoutException) callback.cancel(true);
                        prepared(work, failure);
                    });
                }
                return new Admission.Accepted(id, work.receipt);
            } catch (java.util.concurrent.RejectedExecutionException full) {
                release(work);
                return new Admission.Rejected(Rejection.BUSY);
            }
        }
    }

    private synchronized void prepared(Work work, Throwable failure) {
        if (active.get(work.id) != work) return;
        if (failure != null) LOG.log(System.Logger.Level.WARNING, "Task {0} preparation callback failed ({1})",
            work.id.value(), failure.getClass().getName());
        if (failure != null || closed || stopReason(work) != null) {
            complete(work, new TaskResult.Failed(TaskResult.Failure.INTERNAL));
            return;
        }
        try {
            workers.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            complete(work, new TaskResult.Failed(TaskResult.Failure.RESOURCE_LIMIT));
        }
    }

    boolean maintainIfIdle(BackupApiHost.Maintenance operation) throws java.io.IOException {
        final Maintenance lease;
        synchronized (this) {
            if (closed || maintenance != null || !active.isEmpty()) return false;
            lease = new Maintenance();
            maintenance = lease;
        }
        try {
            operation.run(() -> !lease.stop && !Thread.currentThread().isInterrupted());
            return true;
        } finally {
            synchronized (this) { maintenance = null; }
        }
    }

    private static final class Maintenance {
        volatile boolean stop;
    }

    synchronized CompletionStage<TaskSubscription> subscribe(BoundAccess access, Optional<String> repository,
                                                             Consumer<TaskEvent> listener) {
        try {
            if (closed) throw new java.util.concurrent.RejectedExecutionException("Task host is closed");
            pruneTerminal();
            var baseline = new ArrayList<TaskState>();
            for (var work : active.values()) addToBaseline(access, repository, baseline, work.access.owner(),
                state(work, work.state.phase(), work.state.processedBytes(), work.state.totalBytes(), null));
            for (var value : terminal.values()) addToBaseline(access, repository, baseline, value.owner, value.state);
            return results.view(CompletableFuture.completedFuture(notifications.register(access, repository, baseline, listener)));
        } catch (RuntimeException failure) {
            return results.view(CompletableFuture.failedFuture(failure));
        }
    }

    private static void addToBaseline(BoundAccess access, Optional<String> repository, ArrayList<TaskState> baseline,
                                      String owner, TaskState state) {
        if ((repository.isEmpty() || repository.orElseThrow().equals(state.repositoryId())) &&
            access.mayViewTask(state.repositoryId(), owner, true)) baseline.add(state);
    }

    synchronized void revoke(BoundAccess access) {
        access.revoke();
        notifications.revoke(access);
    }

    <T> CompletionStage<T> resultStage(CompletableFuture<T> source) {
        return results.view(source);
    }

    private void publish(Work work, TaskEvent event) {
        notifications.publish(work.request.repositoryId(), work.access.owner(), event);
    }

    synchronized Optional<TaskState> task(BoundAccess access, TaskId id) {
        var work = active.get(id);
        if (work != null) {
            return access.mayViewTask(work.request.repositoryId(), work.access.owner(), false)
                ? Optional.of(state(work, work.state.phase(), work.state.processedBytes(), work.state.totalBytes(), null))
                : Optional.empty();
        }
        pruneTerminal();
        var completed = terminal.get(id);
        if (completed == null ||
            !access.mayViewTask(completed.state.repositoryId(), completed.owner, false)) return Optional.empty();
        return Optional.of(completed.state);
    }

    synchronized Cancellation cancel(BoundAccess access, TaskId id) {
        var work = active.get(id);
        synchronized (access) {
            if (work == null || !access.mayCancel(work.request.repositoryId(), work.access.owner()))
                return Cancellation.NOT_AVAILABLE;
            if (work.state.phase() == Phase.COMMITTING) return Cancellation.TOO_LATE;
            requestCancellation(work);
            return Cancellation.REQUESTED;
        }
    }

    private void requestCancellation(Work work) {
        if (work.cancellation) return;
        work.cancellation = true;
        publish(work, new TaskEvent.CancellationRequested(work.id));
        if (work.state.phase() == Phase.QUEUED && workers.remove(work))
            complete(work, new TaskResult.Cancelled());
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (maintenance != null) maintenance.stop = true;
        workers.shutdown();
        preparations.shutdownNow();
        for (var work : java.util.List.copyOf(active.values())) {
            if (work.state.phase() != Phase.COMMITTING) requestCancellation(work);
        }
        notifications.close();
        results.close();
    }

    private synchronized boolean start(Work work) {
        var denied = stopReason(work);
        if (denied != null) {
            complete(work, denied);
            return false;
        }
        work.state = state(work, Phase.PREPARING, 0, OptionalLong.empty(), null);
        publish(work, new TaskEvent.StateChanged(work.state));
        return true;
    }

    private TaskResult stopReason(Work work) {
        if (work.cancellation) return new TaskResult.Cancelled();
        if (!work.access.maySubmit(work.request)) return new TaskResult.Failed(TaskResult.Failure.LOST_AUTHORIZATION);
        return null;
    }

    private synchronized void complete(Work work, TaskResult outcome) {
        Phase phase = work.state.phase();
        if (phase != Phase.COMMITTING) {
            var denied = stopReason(work);
            if (denied != null) outcome = denied;
        }
        if (!validOutcome(work.request, phase, outcome)) {
            outcome = phase == Phase.COMMITTING ? new TaskResult.CommitUncertain() :
                new TaskResult.Failed(TaskResult.Failure.INTERNAL);
        }
        release(work);
        var state = state(work, Phase.TERMINAL, work.state.processedBytes(), work.state.totalBytes(), outcome);
        terminal.put(work.id, new Terminal(work.access.owner(), state, clock.getAsLong()));
        publish(work, new TaskEvent.StateChanged(state));
        work.receipt.result.complete(outcome);
        pruneTerminal();
    }

    private void pruneTerminal() {
        var oldest = terminal.values().iterator();
        while (oldest.hasNext()) {
            var value = oldest.next();
            if (terminal.size() <= terminalLimit && elapsed(value) < retentionNanos) break;
            oldest.remove();
            notifications.publish(value.state.repositoryId(), value.owner, new TaskEvent.Evicted(value.state.id()));
        }
    }

    private void release(Work work) {
        active.remove(work.id);
        occupiedRepositories.remove(work.request.repositoryId());
    }

    private long elapsed(Terminal value) {
        return clock.getAsLong() - value.completedAt;
    }

    private static boolean isSuccess(TaskResult result) {
        return result instanceof TaskResult.BackupCommitted || result instanceof TaskResult.Restored ||
            result instanceof TaskResult.RestoreQueued ||
            result instanceof TaskResult.Deleted || result instanceof TaskResult.VerificationComplete;
    }

    private static boolean validOutcome(BackupRequest request, Phase phase, TaskResult result) {
        if (result instanceof TaskResult.CommitUncertain) return phase == Phase.COMMITTING;
        if (!isSuccess(result)) return phase != Phase.COMMITTING;
        return switch (request.kind()) {
            case BACKUP -> phase == Phase.COMMITTING && result instanceof TaskResult.BackupCommitted value &&
                value.snapshot().repositoryId().equals(request.repositoryId());
            case RESTORE -> phase == Phase.COMMITTING &&
                ((result instanceof TaskResult.Restored value &&
                    restoreMatches((BackupRequest.Restore) request, value.snapshot(), value.targetId())) ||
                 (result instanceof TaskResult.RestoreQueued queued &&
                    restoreMatches((BackupRequest.Restore) request, queued.snapshot(), queued.targetId())));
            case DELETE -> phase == Phase.COMMITTING && result instanceof TaskResult.Deleted value &&
                ((BackupRequest.Delete) request).snapshot().equals(value.snapshot());
            case VERIFY -> phase != Phase.COMMITTING && result instanceof TaskResult.VerificationComplete value &&
                value.coverage() == ((BackupRequest.Verify) request).level();
        };
    }

    private static boolean restoreMatches(BackupRequest.Restore request, io.github.zct_studio.barusikozako.api.SnapshotRef snapshot,
                                          String target) {
        return request.snapshot().equals(snapshot) && request.targetId().equals(target);
    }

    private TaskState state(Work work, Phase phase, long bytes, OptionalLong total, TaskResult outcome) {
        return new TaskState(work.id, work.request.kind(), work.request.repositoryId(), phase, bytes, total,
            Optional.ofNullable(outcome), Duration.ofNanos(Math.max(0, clock.getAsLong() - work.createdAt)));
    }

    final class Control {
        private final Work work;

        private Control(Work work) {
            this.work = work;
        }

        void progress(long bytes, OptionalLong total) {
            synchronized (TaskRuntime.this) {
                requirePreparing();
                boolean first = work.state.phase() == Phase.PREPARING;
                work.state = state(work, Phase.RUNNING, bytes, total, null);
                long now = clock.getAsLong();
                if (first || now - work.lastProgress >= PROGRESS_INTERVAL) {
                    work.lastProgress = now;
                    publish(work, first ? new TaskEvent.StateChanged(work.state) : new TaskEvent.Progress(work.state));
                }
            }
        }

        boolean mayContinue() {
            synchronized (TaskRuntime.this) {
                return active.get(work.id) == work &&
                    (work.state.phase() == Phase.COMMITTING || stopReason(work) == null);
            }
        }

        /** 返回 false 时适配器必须放弃已准备的变更，不得发布 */
        boolean beginCommit() {
            synchronized (TaskRuntime.this) {
                requirePreparing();
                synchronized (work.access) {
                    if (work.request.kind() == BackupRequest.Kind.VERIFY)
                        throw new IllegalStateException("verification has no commit stage");
                    if (stopReason(work) != null) return false;
                    work.state = state(work, Phase.COMMITTING, work.state.processedBytes(), work.state.totalBytes(), null);
                    publish(work, new TaskEvent.StateChanged(work.state));
                    return true;
                }
            }
        }

        private void requirePreparing() {
            if (active.get(work.id) != work || (work.state.phase() != Phase.PREPARING && work.state.phase() != Phase.RUNNING))
                throw new IllegalStateException("task has left its preparatory stages");
        }
    }

    private final class Work implements Runnable {
        private final TaskId id;
        private final BoundAccess access;
        private final BackupRequest request;
        private final Operation operation;
        private final long createdAt = clock.getAsLong();
        private final Receipt receipt = new Receipt(results);
        private TaskState state;
        private boolean cancellation;
        private long lastProgress;

        private Work(TaskId id, BoundAccess access, BackupRequest request, Operation operation) {
            this.id = id;
            this.access = access;
            this.request = request;
            this.operation = operation;
            state = state(this, Phase.QUEUED, 0, OptionalLong.empty(), null);
        }

        @Override
        public void run() {
            if (!start(this)) return;
            TaskResult result = new TaskResult.Failed(TaskResult.Failure.INTERNAL);
            try {
                result = Objects.requireNonNull(operation.run(new Control(this)));
            } catch (Exception | LinkageError failure) {
                if (failure instanceof java.io.IOException) result = new TaskResult.Failed(TaskResult.Failure.IO);
                LOG.log(System.Logger.Level.DEBUG, "Task {0} did not return an outcome ({1})",
                    id.value(), failure.getClass().getName());
            } finally {
                complete(this, result);
            }
        }
    }

    @FunctionalInterface
    interface Operation {
        TaskResult run(Control control) throws Exception;
    }

    private record Terminal(String owner, TaskState state, long completedAt) {}

    /** 只允许赋值一次，持有回执不会延长任务或其适配器的生命周期 */
    static final class Receipt {
        private final CompletableFuture<TaskResult> result = new CompletableFuture<>();
        private final CompletionStage<TaskResult> stage;

        Receipt(ResultDelivery delivery) {
            stage = delivery.view(result);
        }

        Optional<TaskResult> result() {
            return Optional.ofNullable(result.getNow(null));
        }

        CompletionStage<TaskResult> stage() {
            return stage;
        }
    }

    sealed interface Admission {
        record Accepted(TaskId id, Receipt receipt) implements Admission {}
        record Rejected(Rejection reason) implements Admission {}
    }
}
