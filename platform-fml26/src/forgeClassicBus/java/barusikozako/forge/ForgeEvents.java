// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.forge;

import barusikozako.fabric.BaruSikoZakoServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

final class ForgeEvents {
    private ForgeEvents() {}

    static void bind(BaruSikoZakoServer runtime) {
        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener((RegisterCommandsEvent event) -> runtime.registerCommands(event.getDispatcher()));
        bus.addListener((ServerStartedEvent event) -> runtime.onServerStarted(event.getServer()));
        bus.addListener((ServerStoppingEvent event) -> runtime.onServerStopping());
        bus.addListener((ServerStoppedEvent event) -> runtime.onServerStopped());
        bus.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.START) runtime.tickStart(event.getServer());
            else runtime.tickEnd(event.getServer());
        });
    }
}
