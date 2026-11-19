// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.api;

/** Opaque identifier of an admitted task. Its possession provides no authority. */
public record TaskId(String value) {
    public TaskId {
        if (value.isBlank()) throw new IllegalArgumentException("missing task identifier");
    }
}
