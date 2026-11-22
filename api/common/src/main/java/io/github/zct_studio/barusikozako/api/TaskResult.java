// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

import java.util.Objects;

/** 权威的任务结果。回调失败不会更改该值 */
public sealed interface TaskResult {
    record BackupCommitted(SnapshotRef snapshot, java.util.OptionalLong logicalBytes, java.util.OptionalLong archiveBytes,
                           java.util.Optional<java.time.Duration> elapsed) implements TaskResult {
        public BackupCommitted(SnapshotRef snapshot) {
            this(snapshot, java.util.OptionalLong.empty(), java.util.OptionalLong.empty(), java.util.Optional.empty());
        }

        public BackupCommitted {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(logicalBytes);
            Objects.requireNonNull(archiveBytes);
            Objects.requireNonNull(elapsed);
        }
    }

    record VerificationComplete(BackupRequest.VerificationLevel coverage, long entries, long bytes)
        implements TaskResult {
        public VerificationComplete {
            Objects.requireNonNull(coverage);
            if (entries < 0 || bytes < 0) throw new IllegalArgumentException("negative coverage");
        }
    }

    record RestoreQueued(SnapshotRef snapshot, String targetId) implements TaskResult {}
    record Restored(SnapshotRef snapshot, String targetId) implements TaskResult {}
    record Deleted(SnapshotRef snapshot) implements TaskResult {}
    record Failed(Failure reason) implements TaskResult {}
    record Cancelled() implements TaskResult {}
    record CommitUncertain() implements TaskResult {}

    enum Failure { LOST_AUTHORIZATION, SOURCE_UNAVAILABLE, INTEGRITY, RESOURCE_LIMIT, IO, INTERNAL,
        NATIVE_ACCESS_DENIED, DIRECTORY_SYNC_FAILED }
}
