// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService.*;
import io.github.zct_studio.barusikozako.api.SnapshotRef;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.apihost.BackupApiHost;
import io.github.zct_studio.barusikozako.backend.ManagedRepository;
import io.github.zct_studio.barusikozako.backend.Snapshot;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.BackupExclusions;
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

final class AgentOperations implements BackupApiHost.Operations {
    @Override public AutoCloseable registerWorldExclusion(String source, String pattern) {
        return BackupExclusions.register("WORLD", source, pattern);
    }
    static final String REPOSITORY_ID = "agent";
    static final BackupEngine.Limits LIMITS = new BackupEngine.Limits(256L << 30, 300L << 30, 2_000_000);
    private final Path repository;
    private final String keyword;
    private final AgentVanillaCapture capture;

    AgentOperations(Path repository, String keyword) {
        this(repository, keyword, null);
    }

    AgentOperations(Path repository, String keyword, AgentVanillaCapture capture) {
        this.repository = repository;
        this.keyword = keyword;
        this.capture = capture;
    }

    @Override
    public Availability availability(BackupRequest.Kind kind) {
        return switch (kind) {
            case RESTORE -> Availability.UNSUPPORTED;
            case BACKUP -> capture == null ? Availability.UNSUPPORTED :
                capture.available() ? Availability.AVAILABLE : Availability.TEMPORARILY_UNAVAILABLE;
            case VERIFY, DELETE -> Availability.AVAILABLE;
        };
    }

    @Override
    public Optional<Rejection> evaluate(BackupRequest request) {
        if (!REPOSITORY_ID.equals(request.repositoryId())) return Optional.of(Rejection.INVALID_REQUEST);
        try {
            if (request instanceof BackupRequest.Verify verify) {
                if (verify.level() != BackupRequest.VerificationLevel.FULL) return Optional.of(Rejection.UNSUPPORTED);
                snapshotId(verify.snapshot().snapshotId());
            } else if (request instanceof BackupRequest.Delete delete) {
                snapshotId(delete.snapshot().snapshotId());
            }
        } catch (IllegalArgumentException invalid) {
            return Optional.of(Rejection.INVALID_REQUEST);
        }
        if (request instanceof BackupRequest.Backup backup &&
                (backup.mode() != BackupRequest.BackupMode.FULL || !backup.sourceId().equals("worlds")))
            return Optional.of(Rejection.UNSUPPORTED);
        return switch (availability(request.kind())) {
            case AVAILABLE -> Optional.empty();
            case TEMPORARILY_UNAVAILABLE -> Optional.of(Rejection.UNAVAILABLE);
            case UNSUPPORTED -> Optional.of(Rejection.UNSUPPORTED);
        };
    }

    @Override
    public List<RepositoryInfo> repositories() {
        return List.of(new RepositoryInfo(REPOSITORY_ID, keyword));
    }

    @Override
    public Page<SnapshotInfo> snapshots(String repositoryId, PageRequest page) throws IOException {
        if (!REPOSITORY_ID.equals(repositoryId)) throw new IllegalArgumentException("Unknown repository");
        if (Files.notExists(repository, LinkOption.NOFOLLOW_LINKS)) return new Page<>(List.of(), Optional.empty());
        try (var opened = open()) {
            var snapshots = opened.snapshots(page.continuation().map(AgentOperations::snapshotId), page.limit(), LIMITS.archiveBytes());
            return new Page<>(snapshots.entries().stream().map(snapshot -> new SnapshotInfo(
                new SnapshotRef(REPOSITORY_ID, snapshot.id().toString()), snapshot.createdAt(), snapshot.source(),
                snapshot.trigger().map(trigger -> new BackupRequest.Trigger(trigger.source(), trigger.reason(), trigger.metadata()))
            )).toList(), snapshots.next().map(UUID::toString));
        }
    }

    @Override
    public TaskResult run(BackupRequest request, BackupApiHost.TaskControl control) throws IOException {
        if (request instanceof BackupRequest.Backup backup && capture != null) return backup(backup, control);
        try (var opened = open()) {
            if (request instanceof BackupRequest.Verify verify) {
                var result = new BackupEngine().verify(opened, snapshotId(verify.snapshot().snapshotId()), LIMITS,
                    () -> checkCancellation(control), bytes -> control.progress(bytes, OptionalLong.empty()));
                return new TaskResult.VerificationComplete(verify.level(), result.entries(), result.bytes());
            }
            if (request instanceof BackupRequest.Delete delete) {
                opened.deleteIndependentSnapshot(snapshotId(delete.snapshot().snapshotId()), () -> checkCancellation(control),
                    () -> {
                        if (!control.beginCommit()) throw new InterruptedIOException("Snapshot deletion was cancelled");
                    });
                return new TaskResult.Deleted(delete.snapshot());
            }
        } catch (ManagedRepository.CommitUncertain uncertain) {
            System.getLogger(AgentOperations.class.getName()).log(System.Logger.Level.WARNING,
                "Could not confirm Agent repository commit", uncertain);
            return new TaskResult.CommitUncertain();
        }
        return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
    }

    private TaskResult backup(BackupRequest.Backup request, BackupApiHost.TaskControl control) throws IOException {
        if (Files.notExists(repository, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(repository);
        AgentDirectoryPersistence.INSTANCE.force(repository.getParent());
        var point = new Snapshot(UUID.randomUUID(), Instant.now(), request.sourceId(),
            Optional.of(new Snapshot.Trigger(request.trigger().source(), request.trigger().reason(), request.trigger().metadata())));
        var home = Path.of(".").toRealPath();
        var exclusions = BackupExclusions.load(home.resolve("config/barusikozako"), home.resolve("worlds/world"), false);
        try (var view = capture.acquire(control::mayContinue); var repository = open()) {
            new BackupEngine().backup(view, repository, point, UUID.randomUUID(), LIMITS,
                () -> capture.checkLive(control::mayContinue), () -> {
                    capture.checkLive(control::mayContinue);
                    if (!control.beginCommit()) throw new InterruptedIOException("Capture commit was cancelled");
                }, bytes -> control.progress(bytes, OptionalLong.empty()), 0, exclusions, control::ignoreRules);
            return new TaskResult.BackupCommitted(new SnapshotRef(REPOSITORY_ID, point.id().toString()));
        } catch (ManagedRepository.CommitUncertain uncertain) {
            return new TaskResult.CommitUncertain();
        } catch (IOException failure) {
            if (control.mayContinue())
                System.getLogger(AgentOperations.class.getName()).log(System.Logger.Level.WARNING,
                    "Agent candidate backup did not publish", failure);
            throw failure;
        }
    }

    private static void checkCancellation(BackupApiHost.TaskControl control) throws InterruptedIOException {
        if (!control.mayContinue()) throw new InterruptedIOException("Agent task was cancelled");
    }

    private ManagedRepository open() throws IOException {
        if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS) || !repository.toRealPath().equals(repository))
            throw new IOException("Expected a local, unaliased Agent repository");
        return AgentRuntime.backend(keyword).open(repository, AgentDirectoryPersistence.INSTANCE::force, "FULL", null);
    }

    private static UUID snapshotId(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical snapshot ID");
        return id;
    }
}
