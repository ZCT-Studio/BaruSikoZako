// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.core;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public record RestoreResult(Status status, Optional<UUID> operationId, Optional<Path> previousWorld) {
    public enum Status {
        NO_PENDING_RESTORE, RESTORED, ROLLED_BACK
    }
}
