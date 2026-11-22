// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit.bridge;

import io.github.zct_studio.barusikozako.api.BackupLifecycleHook;
import io.github.zct_studio.barusikozako.backend.BackendProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/** 跟踪每个插件进行的注册，以便代理不会保留禁用的插件 */
final class BukkitAdapterService implements BukkitAgentAdapter {
    private final AgentLink link;
    private final Logger logger;
    private final Map<Plugin, List<Lease>> registrations = new HashMap<>();
    private final Map<Plugin, Boolean> disabling = new WeakHashMap<>();
    private boolean closed;

    BukkitAdapterService(AgentLink link, Logger logger) {
        this.link = link;
        this.logger = logger;
    }

    @Override
    public String status() {
        return link.status();
    }

    @Override
    public AutoCloseable registerBackend(Plugin owner, BackendProvider provider) {
        requireOwner(owner, provider);
        return track(owner, link.registerBackend(provider));
    }

    @Override
    public AutoCloseable registerExtension(Plugin owner, BackupLifecycleHook extension) {
        requireOwner(owner, extension);
        return track(owner, link.registerExtension("bukkit." + owner.getName().toLowerCase(Locale.ROOT), extension));
    }

    AutoCloseable registerWorldExclusion(Plugin owner, String pattern) {
        requireOwner(owner, null, false);
        return track(owner, link.registerWorldExclusion("bukkit." + owner.getName().toLowerCase(Locale.ROOT), pattern));
    }

    private void requireOwner(Plugin owner, Object component) {
        requireOwner(owner, Objects.requireNonNull(component, "component"), true);
    }

    private synchronized void requireOwner(Plugin owner, Object component, boolean verifyClassLoader) {
        Objects.requireNonNull(owner, "owner");
        if (verifyClassLoader && component.getClass().getClassLoader() != owner.getClass().getClassLoader()) {
            throw new IllegalArgumentException("A Bukkit component must be defined by its owning plugin");
        }
        if (!owner.getDescription().getDepend().contains("barusikozako"))
            throw new IllegalArgumentException("Declare a Bukkit dependency on barusikozako");
        if (closed || !owner.isEnabled() || disabling.containsKey(owner)) {
            throw new IllegalStateException("The Bukkit plugin or its agent bridge is disabled");
        }
    }

    private AutoCloseable track(Plugin owner, AutoCloseable registration) {
        var lease = new Lease(owner, Objects.requireNonNull(registration, "agent registration"));
        synchronized (this) {
            if (!closed && owner.isEnabled() && !disabling.containsKey(owner)) {
                registrations.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(lease);
                return lease;
            }
        }
        lease.close();
        throw new IllegalStateException("The Bukkit plugin was disabled during registration");
    }

    synchronized void enabled(Plugin owner) {
        disabling.remove(owner);
    }

    void remove(Plugin owner) {
        List<Lease> leases;
        synchronized (this) {
            disabling.put(owner, true);
            leases = registrations.remove(owner);
        }
        if (leases != null) leases.forEach(Lease::close);
    }

    void close() {
        List<Lease> leases;
        synchronized (this) {
            closed = true;
            leases = registrations.values().stream().flatMap(List::stream).toList();
            registrations.clear();
            disabling.clear();
        }
        leases.forEach(Lease::close);
    }

    private final class Lease implements AutoCloseable {
        private final Plugin owner;
        private final AutoCloseable delegate;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Plugin owner, AutoCloseable delegate) {
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            try {
                delegate.close();
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Could not remove a BaruSikoZako registration from " + owner.getName(), failure);
            } finally {
                synchronized (BukkitAdapterService.this) {
                    List<Lease> leases = registrations.get(owner);
                    if (leases != null) {
                        leases.remove(this);
                        if (leases.isEmpty()) registrations.remove(owner);
                    }
                }
            }
        }
    }
}
