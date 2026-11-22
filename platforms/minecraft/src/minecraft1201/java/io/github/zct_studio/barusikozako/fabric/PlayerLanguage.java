// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import net.minecraft.server.level.ServerPlayer;

final class PlayerLanguage {
    private PlayerLanguage() {}

    static String get(ServerPlayer player) {
        return ((ClientLanguageAccess) player).barusikozako$getLanguage();
    }
}
