// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.backend;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public record Snapshot(UUID id, Instant createdAt, String source, Consistency consistency, Optional<Trigger> trigger,
                       Optional<Stats> stats) {
    public Snapshot {
        Objects.requireNonNull(id);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(consistency);
        Objects.requireNonNull(trigger);
        Objects.requireNonNull(stats);
        if (source.isBlank()) throw new IllegalArgumentException("missing logical source identifier");
    }

    public Snapshot(UUID id, Instant createdAt, String source, Consistency consistency, Optional<Trigger> trigger) {
        this(id, createdAt, source, consistency, trigger, Optional.empty());
    }

    public Snapshot(UUID id, Instant createdAt, String source, Optional<Trigger> trigger) {
        this(id, createdAt, source, Consistency.STRICT, trigger, Optional.empty());
    }

    public Snapshot(UUID id, Instant createdAt, String source) {
        this(id, createdAt, source, Consistency.STRICT, Optional.empty(), Optional.empty());
    }

    public record Trigger(String source, String reason, Map<String, String> metadata) {
        public Trigger {
            if (source.isBlank() || source.length() > 128 || reason.length() > 512 || metadata.size() > 16)
                throw new IllegalArgumentException("invalid trigger fields");
            metadata.forEach((key, value) -> {
                if (key.isBlank() || key.length() > 64 || value.length() > 256)
                    throw new IllegalArgumentException("invalid trigger metadata entry");
            });
            metadata = Map.copyOf(metadata);
        }
    }

    /** 新快照的提交布局；旧后端可以不提供 */
    public enum Layout { FULL, INCREMENTAL, DIFFERENTIAL }

    /** 备份完成时记录的展示信息；无法取得时保持空值 */
    public record Stats(OptionalLong archiveBytes, OptionalLong logicalBytes, Optional<Duration> elapsed, Layout layout) {
        public Stats {
            Objects.requireNonNull(archiveBytes);
            Objects.requireNonNull(logicalBytes);
            Objects.requireNonNull(elapsed);
            Objects.requireNonNull(layout);
            if (archiveBytes.isPresent() && archiveBytes.getAsLong() < 0 ||
                logicalBytes.isPresent() && logicalBytes.getAsLong() < 0)
                throw new IllegalArgumentException("negative snapshot size");
            if (elapsed.isPresent() && elapsed.orElseThrow().isNegative())
                throw new IllegalArgumentException("negative snapshot duration");
        }

        public Stats(Layout layout) {
            this(OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), layout);
        }
    }
}
