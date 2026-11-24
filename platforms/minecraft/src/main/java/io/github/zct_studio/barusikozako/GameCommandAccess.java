// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import net.minecraft.commands.CommandSourceStack;
//? if mc >= 1.21.11
import net.minecraft.commands.Commands;
//? end

final class GameCommandAccess {
    private GameCommandAccess() {}

    static boolean isAdministrator(CommandSourceStack source) {
//? if mc < 1.21.11
//?~         return source.hasPermission(3);
//? else
        return Commands.hasPermission(Commands.LEVEL_ADMINS).test(source);
//? end
    }

    static boolean canManageBackups(CommandSourceStack source) {
        var server = source.getServer();
        if (server == null) {
            // 指令树序列化时使用无服务端上下文的探测源，只能按权限等级判断。
            return isAdministrator(source);
        }

        if (server.isDedicatedServer()) {
            return isAdministrator(source);
        }

        var player = source.getPlayer();
        if (player == null) {
            return false;
        }

        return server.isSingleplayerOwner(
                //? if mc < 1.21.9
//?~                 player.getGameProfile()
                //? else
                player.nameAndId()
                //? end
        );
    }

    static boolean canManageBackupsConfigs(CommandSourceStack source) {
        return canManageBackups(source);
    }
}
