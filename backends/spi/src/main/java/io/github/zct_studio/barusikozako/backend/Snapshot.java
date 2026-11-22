// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.backend;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record Snapshot(UUID id, Instant createdAt, String source, Optional<Trigger> trigger) {
    public Snapshot {
        Objects.requireNonNull(id);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(trigger);
        if (source.isBlank()) throw new IllegalArgumentException("missing logical source identifier");
    }

    public Snapshot(UUID id, Instant createdAt, String source) {
        this(id, createdAt, source, Optional.empty());
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
}
