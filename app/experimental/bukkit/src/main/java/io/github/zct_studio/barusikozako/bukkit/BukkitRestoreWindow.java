// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit;

import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Bukkit 离线恢复窗口。 Minecraft 不得加载世界目录
 * 当使用该窗口时。调用者必须确保服务器已停止
 */
final class BukkitRestoreWindow implements RestoreWindow {
    private final Path target;

    BukkitRestoreWindow(Path target) throws IOException {
        this.target = target.toRealPath();
    }

    @Override
    public Path target() {
        return target;
    }

    @Override
    public void requireExclusiveAccess() throws IOException {
        // 检查世界当前是否未加载（无 session.lock）
        Path lock = target.resolve("session.lock");
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World appears to be loaded (session.lock exists): " + target
                    + ". Stop the server before running offline restore.");
        }
        // 确保父目录元数据被持久化
        forceDirectory(target.getParent());
    }

    @Override
    public void forceDirectory(Path directory) throws IOException {
        RestoreWindow.forceLocalDirectory(directory);
    }

    @Override
    public void close() throws IOException {
        // 没有要释放的持久资源
    }
}