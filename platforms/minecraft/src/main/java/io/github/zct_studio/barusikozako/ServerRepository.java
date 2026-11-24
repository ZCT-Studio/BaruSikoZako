// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import io.github.zct_studio.barusikozako.platform.windows.WindowsDirectorySync;
import java.io.IOException;
import java.nio.file.Path;

final class ServerRepository {
    private ServerRepository() {}

    static Path defaultPath() {
        return LoaderBridge.configDirectory().resolve("barusikozako").resolve("repository");
    }

    static Path forBackend(String keyword, Path world) {
        if (!keyword.matches("[a-z][a-z0-9_-]{1,63}") || world.getFileName() == null)
            throw new IllegalArgumentException("Invalid backend or world name");
        return LoaderBridge.configDirectory().toAbsolutePath().normalize().getParent()
            .resolve("barusikozako").resolve(world.getFileName()).resolve(keyword);
    }

    static Path checkPath(Path location, Path world) throws IOException {
        var canonical = location.toRealPath();
        var worldRoot = world.toRealPath();
        if (canonical.startsWith(worldRoot) || worldRoot.startsWith(canonical))
            throw new IOException("repository overlaps the world");
        return canonical;
    }

    static void forceDirectory(Path directory) throws IOException {
        if (directory.getFileSystem().getSeparator().equals("\\")) WindowsDirectorySync.force(directory);
        else RestoreWindow.forceLocalDirectory(directory);
    }
}
