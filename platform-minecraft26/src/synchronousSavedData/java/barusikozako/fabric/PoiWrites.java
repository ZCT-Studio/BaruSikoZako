// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.fabric.mixin.LegacyPoiWrites;
import net.minecraft.world.entity.ai.village.poi.PoiManager;

final class PoiWrites {
    private PoiWrites() {}

    static void submit(PoiManager manager) {
        ((LegacyPoiWrites) manager).barusikozako$submitDirtyColumns(() -> true);
    }
}
