// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public sealed interface BackupRequest {
    String repositoryId();

    Kind kind();

    enum Kind { BACKUP, VERIFY, RESTORE, DELETE }

    enum VerificationLevel { QUICK, FULL }
    enum BackupMode { CONFIGURED, FULL, INCREMENTAL, DIFFERENTIAL, FIXED_CHUNKS, REBUILD }

    /** Trigger details are diagnostic, never an authorization identity. */
    record Trigger(String source, String reason, Map<String, String> metadata) {
        public Trigger {
            requireId(source);
            Objects.requireNonNull(reason);
            if (source.length() > 128 || reason.length() > 512 || metadata.size() > 16)
                throw new IllegalArgumentException("trigger exceeds metadata budget");
            metadata.forEach((key, value) -> {
                if (key.isBlank() || key.length() > 64 || value.length() > 256)
                    throw new IllegalArgumentException("invalid trigger metadata entry");
            });
            metadata = Map.copyOf(metadata);
        }
    }

    record Backup(String repositoryId, String sourceId, Trigger trigger, BackupMode mode, Optional<SnapshotRef> baseline)
        implements BackupRequest {
        public Backup(String repositoryId, String sourceId, Trigger trigger) {
            this(repositoryId, sourceId, trigger, BackupMode.CONFIGURED, Optional.empty());
        }

        public Backup {
            requireId(repositoryId);
            requireId(sourceId);
            Objects.requireNonNull(trigger);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(baseline);
            if ((mode == BackupMode.DIFFERENTIAL || mode == BackupMode.REBUILD) != baseline.isPresent() ||
                baseline.filter(snapshot -> !repositoryId.equals(snapshot.repositoryId())).isPresent())
                throw new IllegalArgumentException("differential and rebuild requests require a point from the same repository");
        }

        @Override public Kind kind() { return Kind.BACKUP; }
    }

    record Verify(SnapshotRef snapshot, VerificationLevel level) implements BackupRequest {
        public Verify {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(level);
        }

        @Override public String repositoryId() { return snapshot.repositoryId(); }
        @Override public Kind kind() { return Kind.VERIFY; }
    }

    record Restore(SnapshotRef snapshot, String targetId) implements BackupRequest {
        public Restore {
            Objects.requireNonNull(snapshot);
            requireId(targetId);
        }

        @Override public String repositoryId() { return snapshot.repositoryId(); }
        @Override public Kind kind() { return Kind.RESTORE; }
    }

    record Delete(SnapshotRef snapshot) implements BackupRequest {
        public Delete {
            Objects.requireNonNull(snapshot);
        }

        @Override public String repositoryId() { return snapshot.repositoryId(); }
        @Override public Kind kind() { return Kind.DELETE; }
    }

    private static void requireId(String id) {
        if (id.isBlank()) throw new IllegalArgumentException("identifier is blank");
    }
}
