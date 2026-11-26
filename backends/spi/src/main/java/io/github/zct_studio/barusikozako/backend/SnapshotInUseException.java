// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.backend;

import java.io.IOException;

/** 快照仍是其他差异备份的基线，无法独立删除 */
public final class SnapshotInUseException extends IOException {
    public SnapshotInUseException(String message) {
        super(message);
    }
}
