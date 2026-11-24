// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

//? if mc <= 1.21.1
//?~ import io.github.zct_studio.barusikozako.mixin.LegacyPoiWrites;
//? end
import net.minecraft.world.entity.ai.village.poi.PoiManager;

final class PoiWrites {
    private PoiWrites() {}

    static void submit(PoiManager manager) {
//? if mc <= 1.21.1
//?~         ((LegacyPoiWrites) manager).barusikozako$submitDirtyColumns(() -> true);
//? else
        manager.flushAll();
//? end
    }
}
