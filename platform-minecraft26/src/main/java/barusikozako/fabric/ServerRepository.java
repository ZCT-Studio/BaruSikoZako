// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.nio.file.Path;

final class ServerRepository {
    private ServerRepository() {}

    static Path defaultPath() {
        return LoaderBridge.configDirectory().resolve("barusikozako").resolve("repository");
    }

    static Path forBackend(String keyword) {
        return keyword.equals("zakobak_zstd") ? defaultPath() : defaultPath().resolveSibling("repositories").resolve(keyword);
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
