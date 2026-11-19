// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import java.util.concurrent.atomic.AtomicReference;

/** Observability at the exact 26.3 persistence call sites used by the capture gate. */
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
