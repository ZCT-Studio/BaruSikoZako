// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.backend;

/** 捕获时使用的一致性等级。 strict 拒绝任何变化，relaxed 只容忍时间点差异 */
public enum Consistency {
    STRICT, RELAXED;

    public static Consistency parse(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "strict" -> STRICT;
            case "relaxed" -> RELAXED;
            default -> throw new IllegalArgumentException("Unknown capture consistency: " + value);
        };
    }
}
