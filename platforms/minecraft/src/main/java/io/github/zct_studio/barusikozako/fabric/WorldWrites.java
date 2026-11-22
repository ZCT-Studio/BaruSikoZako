// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import java.util.concurrent.atomic.AtomicReference;

/** 捕获门使用的确切 26.3 持久性调用站点的可观察性 */
public final class WorldWrites {
    private static final AtomicReference<String> firstFailure = new AtomicReference<>();

    private WorldWrites() {}

    public static void failed(String area) {
        firstFailure.compareAndSet(null, area);
    }

    static String unresolvedFailure() {
        return firstFailure.get();
    }
}
