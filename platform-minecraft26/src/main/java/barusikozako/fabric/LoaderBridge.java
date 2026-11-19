// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Loader bindings available to the shared 26.3 dedicated server runtime. */
public final class LoaderBridge {
    private static volatile Binding binding;

    private LoaderBridge() {}

    public interface Binding {
        Path configDirectory();
        String productVersion();
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
        return requireBinding().entries(channel, contract);
    }

    private static Binding requireBinding() {
        var current = binding;
        if (current == null) throw new IllegalStateException("Server loader binding is not installed");
        return current;
    }
}
