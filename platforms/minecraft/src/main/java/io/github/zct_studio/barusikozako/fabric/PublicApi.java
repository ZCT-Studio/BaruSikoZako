// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import io.github.zct_studio.barusikozako.api.BackupIntegration;
import io.github.zct_studio.barusikozako.api.BackupLifecycleHook;
import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService;
import io.github.zct_studio.barusikozako.api.SnapshotRef;
import io.github.zct_studio.barusikozako.api.TaskEvent;
import io.github.zct_studio.barusikozako.api.TaskId;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.api.TaskState;
import io.github.zct_studio.barusikozako.api.TaskSubscription;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Adapts callbacks after the loader confirms the separately installed API is present. */
public final class PublicApi {
    private PublicApi() {}

    static List<LoaderBridge.Entry<io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook>> directoryLifecycleHooks(
            io.github.zct_studio.barusikozako.platform.DirectoryComponents directory) {
        return directory.instances(BackupLifecycleHook.class).stream().map(hook ->
            new LoaderBridge.Entry<>(hook.getClass().getName().toLowerCase(java.util.Locale.ROOT), () -> lifecycle(hook))).toList();
    }

    public static io.github.zct_studio.barusikozako.runtime.BackupIntegration integration(BackupIntegration callback) {
        return service -> callback.onBackupService(new Service(service));
    }

    public static io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook lifecycle(BackupLifecycleHook callback) {
        return new io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook() {
            @Override public void before(Context context) {
                callback.before(context(context));
            }

            @Override public void after(Context context, io.github.zct_studio.barusikozako.runtime.TaskResult outcome) {
                callback.after(context(context), result(outcome));
            }

            private BackupLifecycleHook.Context context(Context context) {
                return new BackupLifecycleHook.Context(BackupLifecycleHook.Operation.valueOf(context.operation().name()),
                    context.repositoryId(), context.snapshot().map(PublicApi::snapshot));
            }
        };
    }

    private record Service(io.github.zct_studio.barusikozako.runtime.BackupService runtime) implements BackupService {
        @Override public Submission submit(BackupRequest request) {
            var submission = runtime.submit(request(request));
            if (submission instanceof io.github.zct_studio.barusikozako.runtime.BackupService.Submission.Accepted accepted) {
                return new Submission.Accepted(new TaskId(accepted.id().value()), accepted.completion().thenApply(PublicApi::result));
            }
            if (submission instanceof io.github.zct_studio.barusikozako.runtime.BackupService.Submission.Rejected rejected) {
                return new Submission.Rejected(Rejection.valueOf(rejected.reason().name()));
            }
            throw new IllegalArgumentException("Unknown submission type: " + submission);
        }

        @Override public Cancellation cancel(TaskId id) {
            return Cancellation.valueOf(runtime.cancel(internalId(id)).name());
        }

        @Override public Optional<TaskState> task(TaskId id) {
            return runtime.task(internalId(id)).map(PublicApi::state);
        }

        @Override public CompletionStage<Page<RepositoryInfo>> repositories(PageRequest page) {
            return runtime.repositories(page(page)).thenApply(result -> new Page<>(result.items().stream()
                .map(info -> new RepositoryInfo(info.id(), info.displayName())).toList(), result.next()));
        }

        @Override public CompletionStage<Page<SnapshotInfo>> snapshots(String repositoryId, PageRequest page) {
            return runtime.snapshots(repositoryId, page(page)).thenApply(result -> new Page<>(result.items().stream()
                .map(info -> new SnapshotInfo(snapshot(info.snapshot()), info.createdAt(), info.sourceId(),
                    info.trigger().map(trigger -> new BackupRequest.Trigger(trigger.source(), trigger.reason(), trigger.metadata()))))
                .toList(), result.next()));
        }

        @Override public CompletionStage<Optional<Path>> snapshotDirectory(SnapshotRef snapshot) {
            return runtime.snapshotDirectory(internalSnapshot(snapshot));
        }

        @Override public CompletionStage<List<CapabilityState>> capabilities() {
            return runtime.capabilities().thenApply(states -> states.stream()
                .map(state -> new CapabilityState(BackupRequest.Kind.valueOf(state.operation().name()),
                    Availability.valueOf(state.availability().name()))).toList());
        }

        @Override public AutoCloseable registerWorldExclusion(String pattern) {
            return runtime.registerWorldExclusion(pattern);
        }

        @Override public CompletionStage<Map<String, String>> configuration() {
            return runtime.configuration();
        }

        @Override public CompletionStage<Void> updateConfiguration(String key, String value) {
            return runtime.updateConfiguration(key, value);
        }

        @Override public CompletionStage<TaskSubscription> subscribe(Optional<String> repositoryId, Consumer<TaskEvent> listener) {
            java.util.Objects.requireNonNull(listener);
            return runtime.subscribe(repositoryId, event -> listener.accept(event(event))).thenApply(Subscription::new);
        }

        private static io.github.zct_studio.barusikozako.runtime.BackupService.PageRequest page(PageRequest page) {
            return new io.github.zct_studio.barusikozako.runtime.BackupService.PageRequest(page.limit(), page.continuation());
        }
    }

    private record Subscription(io.github.zct_studio.barusikozako.runtime.TaskSubscription runtime) implements TaskSubscription {
        @Override public List<TaskState> baseline() { return runtime.baseline().stream().map(PublicApi::state).toList(); }
        @Override public Status start() { return status(runtime.start()); }
        @Override public Status status() { return status(runtime.status()); }
        @Override public void close() { runtime.close(); }

        private static Status status(io.github.zct_studio.barusikozako.runtime.TaskSubscription.Status status) {
            return new Status(State.valueOf(status.state().name()), status.reason().map(reason -> CloseReason.valueOf(reason.name())),
                status.eventGap());
        }
    }

    private static io.github.zct_studio.barusikozako.runtime.TaskId internalId(TaskId id) {
        return new io.github.zct_studio.barusikozako.runtime.TaskId(id.value());
    }

    private static io.github.zct_studio.barusikozako.runtime.SnapshotRef internalSnapshot(SnapshotRef snapshot) {
        return new io.github.zct_studio.barusikozako.runtime.SnapshotRef(snapshot.repositoryId(), snapshot.snapshotId());
    }

    private static SnapshotRef snapshot(io.github.zct_studio.barusikozako.runtime.SnapshotRef snapshot) {
        return new SnapshotRef(snapshot.repositoryId(), snapshot.snapshotId());
    }

    private static io.github.zct_studio.barusikozako.runtime.BackupRequest request(BackupRequest request) {
        if (request instanceof BackupRequest.Backup backup) {
            return new io.github.zct_studio.barusikozako.runtime.BackupRequest.Backup(
                    backup.repositoryId(), backup.sourceId(), new io.github.zct_studio.barusikozako.runtime.BackupRequest.Trigger(
                        backup.trigger().source(), backup.trigger().reason(), backup.trigger().metadata()),
                    io.github.zct_studio.barusikozako.runtime.BackupRequest.BackupMode.valueOf(backup.mode().name()),
                    backup.baseline().map(PublicApi::internalSnapshot));
        }
        if (request instanceof BackupRequest.Verify verify) {
            return new io.github.zct_studio.barusikozako.runtime.BackupRequest.Verify(
                    internalSnapshot(verify.snapshot()),
                    io.github.zct_studio.barusikozako.runtime.BackupRequest.VerificationLevel.valueOf(verify.level().name()));
        }
        if (request instanceof BackupRequest.Restore restore) {
            return new io.github.zct_studio.barusikozako.runtime.BackupRequest.Restore(
                    internalSnapshot(restore.snapshot()), restore.targetId());
        }
        if (request instanceof BackupRequest.Delete delete) {
            return new io.github.zct_studio.barusikozako.runtime.BackupRequest.Delete(
                    internalSnapshot(delete.snapshot()));
        }
        throw new IllegalArgumentException("Unknown request type: " + request);
    }

    private static TaskState state(io.github.zct_studio.barusikozako.runtime.TaskState state) {
        return new TaskState(new TaskId(state.id().value()), BackupRequest.Kind.valueOf(state.operation().name()),
            state.repositoryId(), TaskState.Phase.valueOf(state.phase().name()), state.processedBytes(),
            state.totalBytes(), state.result().map(PublicApi::result), state.elapsed());
    }

    private static TaskEvent event(io.github.zct_studio.barusikozako.runtime.TaskEvent event) {
        if (event instanceof io.github.zct_studio.barusikozako.runtime.TaskEvent.StateChanged changed) {
            return new TaskEvent.StateChanged(state(changed.state()));
        }
        if (event instanceof io.github.zct_studio.barusikozako.runtime.TaskEvent.Progress progress) {
            return new TaskEvent.Progress(state(progress.state()));
        }
        if (event instanceof io.github.zct_studio.barusikozako.runtime.TaskEvent.CancellationRequested cancelled) {
            return new TaskEvent.CancellationRequested(new TaskId(cancelled.id().value()));
        }
        if (event instanceof io.github.zct_studio.barusikozako.runtime.TaskEvent.Evicted evicted) {
            return new TaskEvent.Evicted(new TaskId(evicted.id().value()));
        }
        throw new IllegalArgumentException("Unknown event type: " + event);
    }

    private static TaskResult result(io.github.zct_studio.barusikozako.runtime.TaskResult result) {
        if (result instanceof io.github.zct_studio.barusikozako.runtime.TaskResult.BackupCommitted backup) {
            return new TaskResult.BackupCommitted(snapshot(backup.snapshot()), backup.logicalBytes(), backup.archiveBytes(), backup.elapsed());
        }
        if (result instanceof io.github.zct_studio.barusikozako.runtime.TaskResult.VerificationComplete verify) {
            return new TaskResult.VerificationComplete(
                    BackupRequest.VerificationLevel.valueOf(verify.coverage().name()), verify.entries(), verify.bytes());
        }
        if (result instanceof io.github.zct_studio.barusikozako.runtime.TaskResult.RestoreQueued queued) {
            return new TaskResult.RestoreQueued(snapshot(queued.snapshot()), queued.targetId());
        }
        if (result instanceof io.github.zct_studio.barusikozako.runtime.TaskResult.Restored restored) {
            return new TaskResult.Restored(snapshot(restored.snapshot()), restored.targetId());
        }
        if (result instanceof io.github.zct_studio.barusikozako.runtime.TaskResult.Deleted deleted) {
            return new TaskResult.Deleted(snapshot(deleted.snapshot()));
        }
        if (result instanceof io.github.zct_studio.barusikozako.runtime.TaskResult.Failed failed) {
            return new TaskResult.Failed(TaskResult.Failure.valueOf(failed.reason().name()));
        }
        if (result instanceof io.github.zct_studio.barusikozako.runtime.TaskResult.Cancelled) {
            return new TaskResult.Cancelled();
        }
        if (result instanceof io.github.zct_studio.barusikozako.runtime.TaskResult.CommitUncertain) {
            return new TaskResult.CommitUncertain();
        }
        throw new IllegalArgumentException("Unknown result type: " + result);
    }
}
