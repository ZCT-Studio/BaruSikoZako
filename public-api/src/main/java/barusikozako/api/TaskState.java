// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record TaskState(TaskId id, BackupRequest.Kind operation, String repositoryId, Phase phase,
                        long processedBytes, OptionalLong totalBytes, Optional<TaskResult> result, Duration elapsed) {
    public TaskState {
        Objects.requireNonNull(id);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(phase);
        if (elapsed.isNegative()) throw new IllegalArgumentException("negative task elapsed time");
        if (repositoryId.isBlank()) throw new IllegalArgumentException("missing repository identifier");
        if (processedBytes < 0 || (totalBytes.isPresent() && totalBytes.getAsLong() < processedBytes))
            throw new IllegalArgumentException("invalid progress");
        if (result.isPresent() != (phase == Phase.TERMINAL)) throw new IllegalArgumentException("invalid task result");
    }

    public TaskState(TaskId id, BackupRequest.Kind operation, String repositoryId, Phase phase,
                     long processedBytes, OptionalLong totalBytes, Optional<TaskResult> result) {
        this(id, operation, repositoryId, phase, processedBytes, totalBytes, result, Duration.ZERO);
    }

    public enum Phase { QUEUED, PREPARING, RUNNING, COMMITTING, TERMINAL }
}
