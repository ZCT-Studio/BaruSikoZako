// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import io.github.zct_studio.barusikozako.platform.DirectoryComponents;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 加载程序绑定可用于共享 26.3 专用服务器运行时 */
public final class LoaderBridge {
    private static volatile Binding binding;

    private LoaderBridge() {}

    public interface Binding {
        Path configDirectory();
        String productVersion();
        boolean apiAvailable();
        <T> List<Entry<T>> entries(String channel, Class<T> contract);
    }

    public record Entry<T>(String owner, Supplier<T> value) {}

    public static synchronized void install(Binding source) {
        if (binding != null) throw new IllegalStateException("Loader binding is already installed");
        binding = Objects.requireNonNull(source);
    }

    static Path configDirectory() { return requireBinding().configDirectory(); }

    static String productVersion() { return requireBinding().productVersion(); }

    static <T> List<Entry<T>> entries(String channel, Class<T> contract) {
        var current = requireBinding();
        boolean backend = channel.equals("barusikozako:backend");
        if (!backend && !current.apiAvailable()) return List.of();
        var result = new ArrayList<>(current.entries(channel, contract));
        if (backend) {
            for (var component : Components.BACKENDS.instances(contract)) {
                var owner = component.getClass().getName().toLowerCase(java.util.Locale.ROOT);
                result.add(new Entry<>(owner, () -> component));
            }
        } else if (channel.equals("barusikozako:lifecycle")) {
            for (var entry : PublicApi.directoryLifecycleHooks(Components.EXTENSIONS)) {
                result.add(new Entry<>(entry.owner(), () -> contract.cast(entry.value().get())));
            }
        }
        return List.copyOf(result);
    }

    private static final class Components {
        private static final DirectoryComponents BACKENDS = open("backend", LoaderBridge.class.getClassLoader());
        private static final DirectoryComponents EXTENSIONS = open("extension", BACKENDS.classLoader());

        private static DirectoryComponents open(String name, ClassLoader parent) {
            try {
                return new DirectoryComponents(configDirectory().resolve("barusikozako").resolve(name),
                    parent, List.of());
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to load directory components: " + name, failure);
            }
        }
    }

    private static Binding requireBinding() {
        var current = binding;
        if (current == null) throw new IllegalStateException("Server loader binding is not installed");
        return current;
    }
}
