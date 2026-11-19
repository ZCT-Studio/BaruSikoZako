// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** A host-issued service for an authorized integration. All entry points return without waiting on I/O. */
public interface BackupService {
    Submission submit(BackupRequest request);

    Cancellation cancel(TaskId id);

    Optional<TaskState> task(TaskId id);

    CompletionStage<Page<RepositoryInfo>> repositories(PageRequest page);

    CompletionStage<Page<SnapshotInfo>> snapshots(String repositoryId, PageRequest page);

    CompletionStage<List<CapabilityState>> capabilities();

    /**
     * Takes an authorized baseline at the same boundary as registering a paused subscription.
     * Later events are buffered until the caller applies the baseline and starts delivery.
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

    /** REQUESTED records intent; ultimate cancellation is reported by the task result. */
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
