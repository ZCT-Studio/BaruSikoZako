// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import net.minecraft.server.level.ServerPlayer;

final class PlayerLanguage {
    private PlayerLanguage() {}

    static String get(ServerPlayer player) {
//? if mc == 1.20.1
//?~         return ((ClientLanguageAccess) player).barusikozako$getLanguage();
//? else
        return player.clientInformation().language();
//? end
    }
}
