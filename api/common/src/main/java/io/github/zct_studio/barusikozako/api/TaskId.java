// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

/** 已承认任务的不透明标识符。它的拥有不提供任何权力 */
public record TaskId(String value) {
    public TaskId {
        if (value.isBlank()) throw new IllegalArgumentException("missing task identifier");
    }
}
