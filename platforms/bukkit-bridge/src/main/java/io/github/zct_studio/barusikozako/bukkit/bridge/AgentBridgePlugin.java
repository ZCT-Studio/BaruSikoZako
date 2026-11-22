// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit.bridge;

import io.github.zct_studio.barusikozako.api.BackupService;
import java.util.Locale;
import java.util.Map;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** 在 Bukkit 的服务注册表中发布代理的注册适配器 */
public final class AgentBridgePlugin extends JavaPlugin implements Listener {
    private BukkitAdapterService adapter;
    private volatile AgentLink link;

    @Override
    public void onEnable() {
        try {
            link = AgentLink.connect();
            adapter = new BukkitAdapterService(link, getLogger());
            getServer().getServicesManager().register(BukkitAgentAdapter.class, adapter, this, ServicePriority.Normal);
            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("BaruSikoZako agent bridge connected: " + adapter.status());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            getLogger().warning("BaruSikoZako agent bridge unavailable. Start the server with its matching -javaagent JAR. "
                + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** 配套业务 API 插件的内部链接 */
    public Map.Entry<BackupService, AutoCloseable> bindPublicApi(org.bukkit.plugin.Plugin api, String plugin) {
        var connection = link;
        if (connection == null || api != getServer().getPluginManager().getPlugin("barusikozako_api") ||
                !api.isEnabled() || StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .getCallerClass().getClassLoader() != api.getClass().getClassLoader())
            throw new IllegalStateException("The companion BaruSikoZako public API is unavailable");
        var entry = connection.bindApi(plugin.toLowerCase(Locale.ROOT));
        return Map.entry((BackupService) entry.getKey(), (AutoCloseable) entry.getValue());
    }

    /** 可从 Bukkit 插件管理器取得本插件，再以反射注册世界排除 */
    public AutoCloseable registerWorldExclusion(org.bukkit.plugin.Plugin owner, String pattern) {
        var service = adapter;
        if (service == null) throw new IllegalStateException("The BaruSikoZako agent bridge is unavailable");
        return service.registerWorldExclusion(owner, pattern);
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (adapter != null) adapter.remove(event.getPlugin());
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (adapter != null) adapter.enabled(event.getPlugin());
    }

    @Override
    public void onDisable() {
        link = null;
        getServer().getServicesManager().unregisterAll(this);
        if (adapter != null) {
            adapter.close();
            adapter = null;
        }
    }
}
