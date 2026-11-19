// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.probe.bootstrap;

import java.nio.file.Path;

/** JDK-only observation targets visible to isolated server class loaders during tests. */
public final class SaveHooks {
    private SaveHooks() {}

    public static void replacementResult(boolean success, Path target) {
        if (!success && target.endsWith("level.dat")) System.out.println("ZAKO_BARRIER_PROBE_FAILURE level.dat");
    }

    public static void savedDataFailure(Path target) {
        if (target.endsWith("scoreboard.dat")) System.out.println("ZAKO_BARRIER_PROBE_FAILURE scoreboard.dat");
    }
}
