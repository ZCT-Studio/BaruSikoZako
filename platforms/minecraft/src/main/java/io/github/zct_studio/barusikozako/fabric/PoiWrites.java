// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import net.minecraft.world.entity.ai.village.poi.PoiManager;

final class PoiWrites {
    private PoiWrites() {}

    static void submit(PoiManager manager) {
        manager.flushAll();
    }
}
