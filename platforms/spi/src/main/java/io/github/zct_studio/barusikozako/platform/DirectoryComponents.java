// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.platform;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/** 用于显式安装的可信组件 JAR 的进程生命周期加载器 */
public final class DirectoryComponents {
    private final URLClassLoader loader;
    private final Map<Class<?>, List<?>> instances = new HashMap<>();

    public DirectoryComponents(Path directory, ClassLoader parent, List<Path> bundled) throws IOException {
        var urls = new ArrayList<URL>();
        for (var jar : bundled) urls.add(jar.toUri().toURL());
        if (!Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().equals(directory.toAbsolutePath().normalize()))
                throw new IOException("Component directory must be unaliased: " + directory);
            try (var entries = Files.list(directory)) {
                for (var jar : entries.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Component must be a regular JAR: " + jar);
                    urls.add(jar.toUri().toURL());
                }
            }
        }
        loader = new URLClassLoader(urls.toArray(URL[]::new), parent);
    }

    public ClassLoader classLoader() { return loader; }

    public <T> List<ServiceLoader.Provider<T>> providers(Class<T> contract) {
        return ServiceLoader.load(contract, loader).stream()
            .filter(provider -> provider.type().getClassLoader() == loader).toList();
    }

    public synchronized <T> List<T> instances(Class<T> contract) {
        return instances.computeIfAbsent(contract, key -> providers(contract).stream()
            .map(ServiceLoader.Provider::get).toList()).stream().map(contract::cast).toList();
    }
}
