// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.api.BackupRequest;
import barusikozako.api.BackupService.Availability;
import barusikozako.api.BackupService.Page;
import barusikozako.api.BackupService.PageRequest;
import barusikozako.api.BackupService.Rejection;
import barusikozako.api.BackupService.RepositoryInfo;
import barusikozako.api.BackupService.SnapshotInfo;
import barusikozako.api.SnapshotRef;
import barusikozako.api.TaskResult;
import barusikozako.apihost.BackupApiHost;
import barusikozako.backend.Snapshot;
import barusikozako.core.BackupEngine;
import barusikozako.platform.CaptureSource;
import barusikozako.platform.ReadView;
import barusikozako.backend.BackendProvider;
import barusikozako.backend.ManagedRepository;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

final class FabricOperations implements BackupApiHost.Operations {
    static final String REPOSITORY_ID = "local";
    static final String SOURCE_ID = "world";
    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private final ServerConfig config;
    private final Path world;
    private final BackendProvider.Settings backend;
    private final CaptureSource capture;
    private final Path repository;

    FabricOperations(ServerConfig config, Path world, CaptureSource capture, BackendProvider.Settings backend) {
        this.config = config;
        this.world = world;
        this.capture = capture;
        this.backend = backend;
        this.repository = ServerRepository.forBackend(config.backendKeyword());
    }

    @Override
    public Availability availability(BackupRequest.Kind kind) {
        return switch (kind) {
            case VERIFY -> Availability.AVAILABLE;
            case BACKUP -> capture != null && capture.available() ? Availability.AVAILABLE :
                Availability.TEMPORARILY_UNAVAILABLE;
            case RESTORE -> Availability.AVAILABLE;
            case DELETE -> Availability.AVAILABLE;
        };
    }

    @Override
    public Optional<Rejection> evaluate(BackupRequest request) {
        if (!request.repositoryId().equals(REPOSITORY_ID)) return Optional.of(Rejection.INVALID_REQUEST);
        try {
            if (request instanceof BackupRequest.Restore value) parseId(value.snapshot().snapshotId());
            if (request instanceof BackupRequest.Delete value) parseId(value.snapshot().snapshotId());
            if (request instanceof BackupRequest.Backup value) {
                value.baseline().ifPresent(base -> parseId(base.snapshotId()));
                if (!backend.supports(value.mode().name())) return Optional.of(Rejection.UNSUPPORTED);
                if (value.mode() == BackupRequest.BackupMode.REBUILD) return Optional.empty();
            }
        } catch (IllegalArgumentException invalid) {
            return Optional.of(Rejection.INVALID_REQUEST);
        }
        if (request instanceof BackupRequest.Verify verify) {
            if (verify.level() != BackupRequest.VerificationLevel.FULL) return Optional.of(Rejection.UNSUPPORTED);
            try {
                parseId(verify.snapshot().snapshotId());
            } catch (IllegalArgumentException invalid) {
                return Optional.of(Rejection.INVALID_REQUEST);
            }
        }
        return switch (availability(request.kind())) {
            case AVAILABLE -> Optional.empty();
            case TEMPORARILY_UNAVAILABLE -> Optional.of(Rejection.UNAVAILABLE);
            case UNSUPPORTED -> Optional.of(Rejection.UNSUPPORTED);
        };
    }

    @Override
    public List<RepositoryInfo> repositories() {
        return List.of(new RepositoryInfo(REPOSITORY_ID, config.backendKeyword()));
    }

    @Override
    public Page<SnapshotInfo> snapshots(String repositoryId, PageRequest page) throws IOException {
        if (!REPOSITORY_ID.equals(repositoryId)) throw new IllegalArgumentException("Unknown repository");
        if (Files.notExists(repository, LinkOption.NOFOLLOW_LINKS)) return new Page<>(List.of(), Optional.empty());
        try (var zstd = open()) {
            var snapshots = zstd.snapshots(page.continuation().map(FabricOperations::parseId), page.limit(),
                config.manifestBudget());
            return new Page<>(snapshots.entries().stream()
                .map(snapshot -> new SnapshotInfo(new SnapshotRef(REPOSITORY_ID, snapshot.id().toString()),
                    snapshot.createdAt(), snapshot.source(), snapshot.trigger().map(trigger ->
                        new BackupRequest.Trigger(trigger.source(), trigger.reason(), trigger.metadata())))).toList(),
                snapshots.next().map(UUID::toString));
        } catch (IOException | LinkageError failure) {
            LOG.log(System.Logger.Level.WARNING, "Snapshot query failed", failure);
            throw new IOException("Snapshot query failed");
        }
    }

    private static barusikozako.api.BackupLifecycleHook.Context backupContext(BackupRequest request, String principal) {
        return new barusikozako.api.BackupLifecycleHook.Context(
            principal.equals("server-scheduler") ? barusikozako.api.BackupLifecycleHook.Operation.AUTOMATIC_BACKUP :
                barusikozako.api.BackupLifecycleHook.Operation.MANUAL_BACKUP, request.repositoryId(), Optional.empty());
    }

    @Override
    public Runnable before(BackupRequest request, String principal) {
        if (!(request instanceof BackupRequest.Backup backup) || backup.mode() == BackupRequest.BackupMode.REBUILD ||
            config.lifecycleIntegrations().isEmpty()) return null;
        return () -> LifecycleHooks.before(config.lifecycleIntegrations(), backupContext(request, principal));
    }

    @Override
    public void after(BackupRequest request, String principal, java.util.concurrent.CompletionStage<TaskResult> result) {
        if (!(request instanceof BackupRequest.Backup backup) || backup.mode() == BackupRequest.BackupMode.REBUILD ||
            config.lifecycleIntegrations().isEmpty()) return;
        result.thenAccept(outcome -> LifecycleHooks.after(config.lifecycleIntegrations(), backupContext(request, principal), outcome));
    }

    @Override
    public TaskResult run(BackupRequest request, BackupApiHost.TaskControl control) throws IOException {
        if (request instanceof BackupRequest.Backup backup)
            return backup.mode() == BackupRequest.BackupMode.REBUILD ? rebuild(backup, control) : backup(backup, control);
        if (request instanceof BackupRequest.Delete delete) {
            if (WorldStartupGate.hasQueuedRestore(world, config.backendKeyword(), parseId(delete.snapshot().snapshotId())))
                return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
            return delete(delete, control);
        }
        if (request instanceof BackupRequest.Restore restore) {
            var id = parseId(restore.snapshot().snapshotId());
            try (var repository = open(); var reader = repository.read(id, config.readLimits().archiveBytes())) {
                WorldStartupGate.queueRestore(world, config.backendKeyword(), id, () -> {
                    if (!control.beginCommit()) throw new InterruptedIOException("Restore scheduling was cancelled");
                });
                return new TaskResult.RestoreQueued(restore.snapshot(), restore.targetId());
            } catch (WindowsDirectorySync.Failure failure) {
                return directorySyncFailure(failure);
            }
        }
        if (request instanceof BackupRequest.Verify verify) {
            try (var zstd = open()) {
                control.progress(0, OptionalLong.empty());
                var result = new BackupEngine().verify(zstd, parseId(verify.snapshot().snapshotId()), config.readLimits(), () -> {
                    if (!control.mayContinue()) throw new InterruptedIOException("Verification cancelled");
                }, bytes -> control.progress(bytes, OptionalLong.empty()));
                return new TaskResult.VerificationComplete(verify.level(), result.entries(), result.bytes());
            } catch (IOException | LinkageError failure) {
                if (control.mayContinue()) LOG.log(System.Logger.Level.WARNING, "Full verification failed", failure);
                return new TaskResult.Failed(TaskResult.Failure.IO);
            }
        }
        return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
    }

    private TaskResult delete(BackupRequest.Delete request, BackupApiHost.TaskControl control) {
        try (var zstd = open()) {
            zstd.deleteIndependentSnapshot(parseId(request.snapshot().snapshotId()), () -> {
                if (!control.mayContinue()) throw new InterruptedIOException("Snapshot deletion cancelled");
            }, () -> {
                if (!control.beginCommit()) throw new InterruptedIOException("Snapshot deletion not authorized");
            });
            return new TaskResult.Deleted(request.snapshot());
        } catch (ManagedRepository.CommitUncertain uncertain) {
            LOG.log(System.Logger.Level.WARNING, "Snapshot deletion outcome could not be confirmed", uncertain);
            return new TaskResult.CommitUncertain();
        } catch (IOException | LinkageError failure) {
            if (control.mayContinue()) LOG.log(System.Logger.Level.WARNING, "Snapshot deletion rejected", failure);
            return new TaskResult.Failed(TaskResult.Failure.IO);
        }
    }

    private TaskResult rebuild(BackupRequest.Backup request, BackupApiHost.TaskControl control) {
        var trigger = request.trigger();
        var saved = new Snapshot(UUID.randomUUID(), Instant.now(), request.sourceId(),
            Optional.of(new Snapshot.Trigger(trigger.source(), trigger.reason(), trigger.metadata())));
        var limits = config.readLimits();
        try (var zstd = open()) {
            zstd.rebuild(parseId(request.baseline().orElseThrow().snapshotId()), saved, UUID.randomUUID(),
                BackupEngine.readPreparation(limits, () -> {
                    if (!control.mayContinue()) throw new InterruptedIOException("Snapshot rebuild cancelled");
                }, config.backupReadBytesPerSecond()), () -> {
                    if (!control.beginCommit()) throw new InterruptedIOException("Snapshot rebuild not authorized");
                });
            return new TaskResult.BackupCommitted(new SnapshotRef(REPOSITORY_ID, saved.id().toString()));
        } catch (ManagedRepository.CommitUncertain uncertain) {
            LOG.log(System.Logger.Level.WARNING, "Snapshot rebuild outcome could not be confirmed", uncertain);
            return new TaskResult.CommitUncertain();
        } catch (IOException | LinkageError failure) {
            if (control.mayContinue()) LOG.log(System.Logger.Level.WARNING, "Snapshot rebuild failed", failure);
            return new TaskResult.Failed(TaskResult.Failure.IO);
        }
    }

    private TaskResult backup(BackupRequest.Backup request, BackupApiHost.TaskControl control) {
        if (capture == null) return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
        var trigger = request.trigger();
        UUID operation = UUID.randomUUID();
        long started = System.nanoTime();
        Path canonical;
        try {
            Files.createDirectories(repository);
            canonical = ServerRepository.checkPath(repository, world);
            ServerRepository.forceDirectory(canonical.getParent());
        } catch (WindowsDirectorySync.Failure failure) {
            return directorySyncFailure(failure);
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not prepare the backup repository", failure);
            return new TaskResult.Failed(TaskResult.Failure.IO);
        }
        ReadView captured;
        try {
            captured = capture.acquire(control::mayContinue);
        } catch (IOException | LinkageError failure) {
            if (control.mayContinue()) LOG.log(System.Logger.Level.WARNING, "Online capture failed", failure);
            return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
        }
        var bytes = new java.util.concurrent.atomic.AtomicLong();
        try {
            var view = captured;
            var snapshot = new Snapshot(UUID.randomUUID(), Instant.now(), request.sourceId(),
                Optional.of(new Snapshot.Trigger(trigger.source(), trigger.reason(), trigger.metadata())));
            if (!canonical.equals(ServerRepository.checkPath(repository, world)))
                throw new IOException("Repository path changed during capture");
            UUID baseline = request.baseline().map(base -> parseId(base.snapshotId())).orElse(null);
            var archiveBytes = OptionalLong.empty();
            try (var zstd = backend.open(canonical, ServerRepository::forceDirectory, request.mode().name(),
                request.mode() == BackupRequest.BackupMode.REBUILD ? null : baseline)) {
                try {
                    control.progress(0, OptionalLong.empty());
                    new BackupEngine().backup(view, zstd, snapshot, operation, config.readLimits(), () -> {
                        if (!control.mayContinue()) throw new InterruptedIOException("Backup cancelled");
                    }, () -> {
                        if (!control.beginCommit()) throw new InterruptedIOException("Backup commit was not authorized");
                    }, processed -> {
                        bytes.set(processed);
                        control.progress(processed, OptionalLong.empty());
                    }, config.backupReadBytesPerSecond());
                } catch (IOException | LinkageError failure) {
                    if (failure instanceof ManagedRepository.CommitUncertain) throw failure;
                    if (!zstd.isCommitted(snapshot.id(), operation)) throw failure;
                    LOG.log(System.Logger.Level.WARNING, "Archive committed with later cleanup failure", failure);
                }
                try {
                    archiveBytes = zstd.archiveBytes(snapshot.id());
                } catch (IOException failure) {
                    LOG.log(System.Logger.Level.WARNING, "Backup committed but stored size could not be read", failure);
                }
            }
            return new TaskResult.BackupCommitted(new SnapshotRef(REPOSITORY_ID, snapshot.id().toString()),
                OptionalLong.of(bytes.get()), archiveBytes, Optional.of(java.time.Duration.ofNanos(System.nanoTime() - started)));
        } catch (ManagedRepository.CommitUncertain failure) {
            LOG.log(System.Logger.Level.WARNING, "Backup commit outcome could not be confirmed", failure);
            return new TaskResult.CommitUncertain();
        } catch (WindowsDirectorySync.Failure failure) {
            return directorySyncFailure(failure);
        } catch (IOException | LinkageError failure) {
            if (control.mayContinue()) LOG.log(System.Logger.Level.WARNING, "Backup did not confirm publication", failure);
            return new TaskResult.Failed(TaskResult.Failure.IO);
        } finally {
            try {
                captured.close();
            } catch (IOException failure) {
                LOG.log(System.Logger.Level.WARNING, "Backup staging cleanup failed", failure);
            }
        }
    }

    private static TaskResult directorySyncFailure(WindowsDirectorySync.Failure failure) {
        org.slf4j.LoggerFactory.getLogger(FabricOperations.class).warn(failure.getMessage(), failure);
        return new TaskResult.Failed(failure.denied ? TaskResult.Failure.NATIVE_ACCESS_DENIED :
            TaskResult.Failure.DIRECTORY_SYNC_FAILED);
    }

    private ManagedRepository open() throws IOException {
        return backend.open(ServerRepository.checkPath(repository, world), ServerRepository::forceDirectory, "FULL", null);
    }

    void collect(java.util.function.BooleanSupplier mayContinue) throws IOException {
        if (!mayContinue.getAsBoolean() || Files.notExists(repository, LinkOption.NOFOLLOW_LINKS)) return;
        try (var zstd = open()) {
            long reclaimed = zstd.collectUnreferencedBlocks(1_000_000, config.manifestBudget(), () -> {
                if (!mayContinue.getAsBoolean()) throw new InterruptedIOException("Maintenance yielded");
            });
            if (reclaimed > 0) LOG.log(System.Logger.Level.INFO, "Reclaimed {0} bytes of unreferenced blocks", reclaimed);
        }
    }

    static UUID parseId(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical snapshot ID");
        return id;
    }
}
