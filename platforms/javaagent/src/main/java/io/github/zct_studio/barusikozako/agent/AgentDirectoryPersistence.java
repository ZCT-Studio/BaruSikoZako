// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import io.github.zct_studio.barusikozako.agent.AgentStartup.DirectoryPersistence;
import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/** 延迟加载 Java 22 Windows 帮助程序，以便其他主机保留其 Java 17 基线 */
final class AgentDirectoryPersistence implements DirectoryPersistence {
    static final DirectoryPersistence INSTANCE = new AgentDirectoryPersistence();
    private Windows windows;

    private AgentDirectoryPersistence() {}

    @Override
    public void force(Path path) throws IOException {
        if (windows(path)) invoke(helper().force(), path);
        else RestoreWindow.forceLocalDirectory(path);
    }

    @Override
    public boolean sameFileStore(Path first, Path second) throws IOException {
        if (!windows(first)) return DirectoryPersistence.super.sameFileStore(first, second);
        var volume = helper().volume();
        return ((String) invoke(volume, first)).equalsIgnoreCase((String) invoke(volume, second));
    }

    private static boolean windows(Path path) {
        return path.getFileSystem().getSeparator().equals("\\");
    }

    private synchronized Windows helper() throws IOException {
        if (windows != null) return windows;
        if (Runtime.version().feature() < 22)
            throw new IOException("Windows NTFS backup and restore require Java 22 or newer with native access available.");
        var archive = Premain.extract("/META-INF/barusikozako/windows-persistence.jar");
        if (archive == null) throw new IOException("Windows NTFS persistence component is missing.");
        var loader = new URLClassLoader(new URL[] {archive.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        try {
            var type = Class.forName("io.github.zct_studio.barusikozako.platform.windows.WindowsDirectorySync", true, loader);
            windows = new Windows(type.getMethod("force", Path.class), type.getMethod("volume", Path.class));
            return windows;
        } catch (ReflectiveOperationException | LinkageError failure) {
            try { loader.close(); } catch (IOException closing) { failure.addSuppressed(closing); }
            throw new IOException("Cannot load Windows NTFS persistence", failure);
        }
    }

    private static Object invoke(Method method, Path path) throws IOException {
        try {
            return method.invoke(null, path);
        } catch (InvocationTargetException reflected) {
            if (reflected.getCause() instanceof IOException failure) throw failure;
            throw new IOException("Windows NTFS persistence failed", reflected.getCause());
        } catch (IllegalAccessException failure) {
            throw new IOException("Windows NTFS persistence is inaccessible", failure);
        }
    }

    private record Windows(Method force, Method volume) {}
}
