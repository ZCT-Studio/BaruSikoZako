// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import net.minecraft.commands.CommandSourceStack;

final class GameCommandAccess {
    private GameCommandAccess() {}

    static boolean isAdministrator(CommandSourceStack source) {
        return source.hasPermission(3);
    }
}
