// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

public record SnapshotRef(String repositoryId, String snapshotId) {
    public SnapshotRef {
        if (repositoryId.isBlank() || snapshotId.isBlank()) throw new IllegalArgumentException("missing snapshot identifier");
    }
}
