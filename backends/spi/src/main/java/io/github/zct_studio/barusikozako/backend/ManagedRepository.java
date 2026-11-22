// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.backend;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 向主机公开的快照操作与后端的存储格式无关 */
public interface ManagedRepository extends Repository {
    SnapshotPage snapshots(Optional<UUID> after, int limit, long manifestBytes) throws IOException;
    boolean isCommitted(UUID snapshotId, UUID operationId) throws IOException;
    void deleteIndependentSnapshot(UUID id, CommitGate checkpoint, CommitGate gate) throws IOException;

    default java.util.OptionalLong archiveBytes(UUID id) throws IOException {
        return java.util.OptionalLong.empty();
    }

    default void rebuild(UUID source, Snapshot target, UUID operation, Preparation limits, CommitGate gate)
        throws IOException {
        throw new IOException("Snapshot rebuild unsupported");
    }

    default long collectUnreferencedBlocks(int limit, long metadataBudget, CommitGate checkpoint) throws IOException {
        return 0;
    }

    record SnapshotPage(List<Snapshot> entries, Optional<UUID> next) {
        public SnapshotPage {
            entries = List.copyOf(entries);
        }
    }

    class CommitUncertain extends IOException {
        public CommitUncertain(String message, IOException cause) { super(message, cause); }
        public CommitUncertain(IOException cause) { super(cause.getMessage(), cause); }
    }
}
