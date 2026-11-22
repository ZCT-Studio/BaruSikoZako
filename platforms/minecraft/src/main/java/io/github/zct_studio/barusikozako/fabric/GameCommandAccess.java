// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

final class GameCommandAccess {
    private GameCommandAccess() {}

    static boolean isAdministrator(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_ADMINS).test(source);
    }
}
