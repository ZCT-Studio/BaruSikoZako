// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit.api;

import io.github.zct_studio.barusikozako.api.BackupService;
import io.github.zct_studio.barusikozako.bukkit.bridge.AgentBridgePlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class BackupApiPlugin extends JavaPlugin implements Listener, BukkitBackupApi {
    private final Map<Plugin, Binding> bindings = new HashMap<>();
    private AgentBridgePlugin bridge;

    @Override
    public void onEnable() {
        try {
            if (!(getServer().getPluginManager().getPlugin("barusikozako") instanceof AgentBridgePlugin connected)
                    || !connected.isEnabled())
                throw new IllegalStateException("The BaruSikoZako bridge is unavailable");
            synchronized (this) { bridge = connected; }
            getServer().getServicesManager().register(BukkitBackupApi.class, this, this, ServicePriority.Normal);
            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("BaruSikoZako public backup API registered");
        } catch (RuntimeException | LinkageError failure) {
            getLogger().log(Level.WARNING, "Could not start the BaruSikoZako public backup API", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public synchronized BackupService service(Plugin owner) {
        Objects.requireNonNull(owner);
        // 即使同一 JVM 中的组件共享信任边界，也能识别调用者
        var caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
        if (caller.getClassLoader() != owner.getClass().getClassLoader() ||
                !owner.getDescription().getDepend().contains("barusikozako_api") || !owner.isEnabled())
            throw new SecurityException("Call from the enabled plugin declaring a dependency on barusikozako_api");
        if (bridge == null) throw new IllegalStateException("BaruSikoZako API is stopped");
        return bindings.computeIfAbsent(owner, this::connect).service();
    }

    private Binding connect(Plugin plugin) {
        var registration = bridge.bindPublicApi(this, plugin.getName());
        return new Binding(registration.getKey(), registration.getValue());
    }

    @EventHandler
    public synchronized void onPluginDisable(PluginDisableEvent event) {
        var owner = event.getPlugin();
        if (owner.getName().equals("barusikozako")) getServer().getPluginManager().disablePlugin(this);
        close(bindings.remove(owner));
    }

    @Override
    public synchronized void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        bridge = null;
        bindings.values().forEach(this::close);
        bindings.clear();
    }

    private void close(Binding binding) {
        if (binding == null) return;
        try {
            binding.lease().close();
        } catch (Exception failure) {
            getLogger().log(Level.WARNING, "Could not revoke a BaruSikoZako public API binding", failure);
        }
    }

    private record Binding(BackupService service, AutoCloseable lease) {}
}
