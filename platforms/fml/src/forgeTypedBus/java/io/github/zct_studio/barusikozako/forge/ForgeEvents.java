// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.forge;

import io.github.zct_studio.barusikozako.fabric.BaruSikoZakoServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

final class ForgeEvents {
    private ForgeEvents() {}

    static void bind(BaruSikoZakoServer runtime) {
        RegisterCommandsEvent.BUS.addListener(event -> runtime.registerCommands(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event -> runtime.onServerStarted(event.getServer()));
        ServerStoppingEvent.BUS.addListener(event -> runtime.onServerStopping());
        ServerStoppedEvent.BUS.addListener(event -> runtime.onServerStopped());
        ServerTickEvent.Pre.BUS.addListener(event -> runtime.tickStart(event.server()));
        ServerTickEvent.Post.BUS.addListener(event -> runtime.tickEnd(event.server()));
    }
}
