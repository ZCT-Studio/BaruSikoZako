// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.platform.windows.WindowsDirectorySync;

import io.github.zct_studio.barusikozako.runtime.BackupRequest;
import io.github.zct_studio.barusikozako.runtime.BackupService;
import io.github.zct_studio.barusikozako.runtime.BackupService.Availability;
import io.github.zct_studio.barusikozako.runtime.BackupService.BackupOperator;
import io.github.zct_studio.barusikozako.runtime.BackupService.Page;
import io.github.zct_studio.barusikozako.runtime.BackupService.PageRequest;
import io.github.zct_studio.barusikozako.runtime.BackupService.Rejection;
import io.github.zct_studio.barusikozako.runtime.BackupService.RepositoryInfo;
import io.github.zct_studio.barusikozako.runtime.BackupService.SnapshotInfo;
import io.github.zct_studio.barusikozako.runtime.BackupService.SnapshotLayout;
import io.github.zct_studio.barusikozako.runtime.SnapshotRef;
import io.github.zct_studio.barusikozako.runtime.TaskResult;
import io.github.zct_studio.barusikozako.runtime.host.BackupApiHost;
import io.github.zct_studio.barusikozako.backend.Snapshot;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.BackupExclusions;
import io.github.zct_studio.barusikozako.core.StagedDirectoryView;
import io.github.zct_studio.barusikozako.platform.CaptureSource;
import io.github.zct_studio.barusikozako.platform.ReadView;
import io.github.zct_studio.barusikozako.backend.BackendProvider;
import io.github.zct_studio.barusikozako.backend.ManagedRepository;
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
    @Override public AutoCloseable registerWorldExclusion(String source, String pattern) {
        return BackupExclusions.register("WORLD", source, pattern);
    }
    static final String REPOSITORY_ID = "local";
    static final String SOURCE_ID = "world";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FabricOperations.class);
    private final ServerConfig config;
    private final Path world;
    private final BackendProvider.Settings backend;
    private final CaptureSource capture;
    private final Path repository;
    private final boolean restoreAvailable;
    /** 最近一次宽松捕获中接受过时间点差异的快照 ID，供进度显示标记「有疑问」 */
    private volatile String relaxedCapture;

    FabricOperations(ServerConfig config, Path world, CaptureSource capture, BackendProvider.Settings backend,
                     boolean restoreAvailable) {
        this.config = config;
        this.world = world;
        this.capture = capture;
        this.backend = backend;
        this.restoreAvailable = restoreAvailable;
        this.repository = ServerRepository.forBackend(config.backendKeyword(), world);
    }

    @Override
    public Availability availability(BackupRequest.Kind kind) {
        return switch (kind) {
            case VERIFY -> Availability.AVAILABLE;
            case BACKUP -> capture != null && capture.available() ? Availability.AVAILABLE :
                Availability.TEMPORARILY_UNAVAILABLE;
            case RESTORE -> restoreAvailable ? Availability.AVAILABLE : Availability.UNSUPPORTED;
            case DELETE -> Availability.AVAILABLE;
        };
    }

    @Override
    public java.util.Map<String, String> configuration() throws IOException {
        var defaults = ServerConfig.defaults();
        var path = LoaderBridge.configDirectory().resolve("barusikozako").resolve("server.yml");
        var values = new java.util.LinkedHashMap<>(defaults);
        values.putAll(YamlSettings.load(path, defaults, false, java.util.Map::of));
        return java.util.Map.copyOf(values);
    }

    @Override
    public void updateConfiguration(String key, String value) throws IOException {
        var path = LoaderBridge.configDirectory().resolve("barusikozako").resolve("server.yml");
        YamlSettings.change(path, ServerConfig.defaults(), key, value, ServerConfig::parse);
    }

    @Override
    public BackupService.ServiceStatus status() {
        return new BackupService.ServiceStatus(
            capture != null && capture.available(),
            Optional.of(config.backendKeyword()),
            Optional.of(new BackupService.RepositoryInfo(REPOSITORY_ID, config.backendKeyword())),
            Optional.of(repository),
            config.backupIntervalSeconds(),
            Optional.of(LoaderBridge.configDirectory().resolve("barusikozako")));
    }

    @Override
    public BackupService.VersionInfo version() {
        return new BackupService.VersionInfo(LoaderBridge.productVersion(), Optional.ofNullable(LoaderBridge.apiVersion()),
            BackendRegistry.versions());
    }

    @Override
    public java.util.Map<String, String> backendConfiguration(String backend) throws IOException {
        var provider = backend(backend);
        var defaults = provider.defaults();
        var path = LoaderBridge.configDirectory().resolve("barusikozako").resolve(backend + ".yml");
        var values = new java.util.LinkedHashMap<>(defaults);
        values.putAll(YamlSettings.load(path, defaults, false, java.util.Map::of));
        return java.util.Map.copyOf(values);
    }

    @Override
    public void updateBackendConfiguration(String backend, String key, String value) throws IOException {
        var provider = backend(backend);
        YamlSettings.change(LoaderBridge.configDirectory().resolve("barusikozako").resolve(backend + ".yml"),
            provider.defaults(), key, value, provider::configure);
    }

    private static BackendProvider backend(String keyword) throws IOException {
        var provider = BackendRegistry.registered().get(keyword);
        if (provider == null) throw new IOException("Backend is not installed: " + keyword);
        return provider;
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

    Path repositoryPath() {
        return repository;
    }

    /** 该快照是否在宽松模式下捕获且接受过时间点差异 */
    boolean relaxedCapture(String snapshotId) {
        return snapshotId.equals(relaxedCapture);
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
                    snapshot.createdAt(), snapshot.source(), snapshot.consistency()
                        == io.github.zct_studio.barusikozako.backend.Consistency.STRICT
                        ? io.github.zct_studio.barusikozako.runtime.BackupService.Consistency.STRICT
                        : io.github.zct_studio.barusikozako.runtime.BackupService.Consistency.RELAXED,
                    snapshot.trigger().map(trigger ->
                        new BackupRequest.Trigger(trigger.source(), trigger.reason(), trigger.metadata())),
                    snapshot.stats().map(Snapshot.Stats::archiveBytes).orElse(OptionalLong.empty()),
                    snapshot.stats().map(Snapshot.Stats::logicalBytes).orElse(OptionalLong.empty()),
                    snapshot.stats().flatMap(Snapshot.Stats::elapsed),
                    snapshot.stats().map(stats -> switch (stats.layout()) {
                        case FULL -> SnapshotLayout.FULL;
                        case INCREMENTAL -> SnapshotLayout.INCREMENTAL;
                        case DIFFERENTIAL -> SnapshotLayout.DIFFERENTIAL;
                    }),
                    operator(snapshot.trigger()))).toList(),
                snapshots.next().map(UUID::toString));
        } catch (IOException | LinkageError failure) {
            LOG.warn("Snapshot query failed", failure);
            throw new IOException("Snapshot query failed");
        }
    }

    @Override
    public Optional<Path> snapshotDirectory(SnapshotRef snapshot) throws IOException {
        if (!REPOSITORY_ID.equals(snapshot.repositoryId())) throw new IllegalArgumentException("Unknown repository");
        var id = parseId(snapshot.snapshotId());
        if (Files.notExists(repository, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var current = open()) {
            return current.snapshotDirectory(id, config.manifestBudget());
        } catch (IOException | LinkageError failure) {
            LOG.warn("Snapshot directory query failed", failure);
            throw new IOException("Snapshot directory query failed");
        }
    }

    private static io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Context hookContext(
            BackupRequest request, String principal) {
        if (request instanceof BackupRequest.Backup backup) {
            if (backup.mode() == BackupRequest.BackupMode.REBUILD) return null;
            return new io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Context(
                principal.equals("server-scheduler") ? io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Operation.AUTOMATIC_BACKUP :
                    io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Operation.MANUAL_BACKUP,
                request.repositoryId(), Optional.empty());
        }
        if (request instanceof BackupRequest.Verify verify)
            return new io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Context(
                io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Operation.VERIFY,
                request.repositoryId(), Optional.of(verify.snapshot()));
        if (request instanceof BackupRequest.Delete delete)
            return new io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Context(
                io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Operation.DELETE,
                request.repositoryId(), Optional.of(delete.snapshot()));
        if (request instanceof BackupRequest.Restore restore)
            return new io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Context(
                io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Operation.RESTORE,
                request.repositoryId(), Optional.of(restore.snapshot()));
        return null;
    }

    @Override
    public Runnable before(BackupRequest request, String principal) {
        var context = hookContext(request, principal);
        return context == null ? null : () -> LifecycleHooks.before(config.lifecycleIntegrations(), context);
    }

    @Override
    public void after(BackupRequest request, String principal, java.util.concurrent.CompletionStage<TaskResult> result) {
        var context = hookContext(request, principal);
        if (context == null) return;
        result.thenAccept(outcome -> LifecycleHooks.after(config.lifecycleIntegrations(), context, outcome));
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
                if (control.mayContinue()) LOG.warn("Full verification failed", failure);
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
        } catch (io.github.zct_studio.barusikozako.backend.SnapshotInUseException inUse) {
            LOG.warn("Snapshot deletion rejected: it is a live backup baseline");
            return new TaskResult.Failed(TaskResult.Failure.SNAPSHOT_IS_BASELINE);
        } catch (ManagedRepository.CommitUncertain uncertain) {
            LOG.warn("Snapshot deletion outcome could not be confirmed", uncertain);
            return new TaskResult.CommitUncertain();
        } catch (IOException | LinkageError failure) {
            if (control.mayContinue()) LOG.warn("Snapshot deletion rejected", failure);
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
            LOG.warn("Snapshot rebuild outcome could not be confirmed", uncertain);
            return new TaskResult.CommitUncertain();
        } catch (IOException | LinkageError failure) {
            if (control.mayContinue()) LOG.warn("Snapshot rebuild failed", failure);
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
            LOG.warn("Could not prepare the backup repository", failure);
            return new TaskResult.Failed(TaskResult.Failure.IO);
        }
        ReadView captured;
        BackupExclusions exclusions;
        try {
            var configRoot = LoaderBridge.configDirectory().toAbsolutePath().normalize();
            var worldRoot = world.toAbsolutePath().normalize();
            exclusions = BackupExclusions.load(configRoot.resolve("barusikozako"), worldRoot, false,
                config.parseGitignore(), config.parsePteroignore(), worldRoot.getParent());
            captured = capture.acquire(control::mayContinue);
        } catch (IOException | LinkageError failure) {
            if (control.mayContinue() && !(failure instanceof InterruptedIOException))
                LOG.warn("Online capture failed: {}", failure.getMessage(), failure);
            return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
        }
        boolean relaxedDifferences = config.consistency() == io.github.zct_studio.barusikozako.backend.Consistency.RELAXED
            && captured instanceof StagedDirectoryView staged && !staged.relaxedChanges().isEmpty();
        int relaxedFiles = captured instanceof StagedDirectoryView staged ? staged.relaxedChanges().size() : 0;
        var bytes = new java.util.concurrent.atomic.AtomicLong();
        try {
            var view = captured;
            var snapshot = new Snapshot(UUID.randomUUID(), Instant.now(), request.sourceId(), config.consistency(),
                Optional.of(new Snapshot.Trigger(trigger.source(), trigger.reason(), trigger.metadata())));
            if (!canonical.equals(ServerRepository.checkPath(repository, world)))
                throw new IOException("Repository path changed during capture");
            UUID baseline = request.baseline().map(base -> parseId(base.snapshotId())).orElse(null);
            var archiveBytes = OptionalLong.empty();
            long capturedBytes = view.totalBytes();
            var knownTotal = capturedBytes > 0 ? OptionalLong.of(capturedBytes) : OptionalLong.empty();
            try (var zstd = backend.open(canonical, ServerRepository::forceDirectory, request.mode().name(),
                request.mode() == BackupRequest.BackupMode.REBUILD ? null : baseline)) {
                try {
                    control.progress(0, knownTotal);
                    new BackupEngine().backup(view, zstd, snapshot, operation, config.readLimits(), () -> {
                        if (!control.mayContinue()) throw new InterruptedIOException("Backup cancelled");
                    }, () -> {
                        if (!control.beginCommit()) throw new InterruptedIOException("Backup commit was not authorized");
                    }, processed -> {
                        bytes.set(processed);
                        control.progress(processed, knownTotal);
                    }, config.backupReadBytesPerSecond(), exclusions, control::ignoreRules);
                } catch (IOException | LinkageError failure) {
                    if (failure instanceof ManagedRepository.CommitUncertain) throw failure;
                    if (!zstd.isCommitted(snapshot.id(), operation)) throw failure;
                    LOG.warn("Archive committed with later cleanup failure", failure);
                }
                try {
                    archiveBytes = zstd.archiveBytes(snapshot.id());
                } catch (IOException failure) {
                    LOG.warn("Backup committed but stored size could not be read", failure);
                }
                var elapsed = java.time.Duration.ofNanos(System.nanoTime() - started);
                try {
                    zstd.recordStats(snapshot.id(), elapsed, bytes.get());
                } catch (IOException failure) {
                    LOG.warn("Backup committed but statistics could not be stored", failure);
                }
            }
            var committed = new TaskResult.BackupCommitted(new SnapshotRef(REPOSITORY_ID, snapshot.id().toString()),
                OptionalLong.of(bytes.get()), archiveBytes,
                Optional.of(java.time.Duration.ofNanos(System.nanoTime() - started)), relaxedFiles);
            if (relaxedDifferences) relaxedCapture = snapshot.id().toString();
            return committed;
        } catch (ManagedRepository.CommitUncertain failure) {
            LOG.warn("Backup commit outcome could not be confirmed", failure);
            return new TaskResult.CommitUncertain();
        } catch (WindowsDirectorySync.Failure failure) {
            return directorySyncFailure(failure);
        } catch (IOException | LinkageError failure) {
            if (control.mayContinue()) LOG.warn("Backup did not confirm publication", failure);
            return new TaskResult.Failed(TaskResult.Failure.IO);
        } finally {
            try {
                captured.close();
            } catch (IOException failure) {
                LOG.warn("Backup staging cleanup failed", failure);
            }
        }
    }

    private static TaskResult directorySyncFailure(WindowsDirectorySync.Failure failure) {
        LOG.warn(failure.getMessage(), failure);
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
            if (reclaimed > 0) LOG.info("Reclaimed {} bytes of unreferenced blocks", reclaimed);
        }
    }

    static UUID parseId(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical snapshot ID");
        return id;
    }

    /** 旧快照没有操作人记录，由界面按 Server 展示 */
    private static Optional<BackupOperator> operator(Optional<Snapshot.Trigger> trigger) {
        var metadata = trigger.map(Snapshot.Trigger::metadata).orElse(java.util.Map.of());
        var name = metadata.get("operator");
        if (name == null || name.isBlank()) return Optional.empty();
        UUID id = null;
        try {
            var value = metadata.get("operatorUuid");
            if (value != null) id = UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            // 操作人名仍可展示，UUID 缺失不影响列表
        }
        return Optional.of(new BackupOperator(name, Optional.ofNullable(id)));
    }
}
