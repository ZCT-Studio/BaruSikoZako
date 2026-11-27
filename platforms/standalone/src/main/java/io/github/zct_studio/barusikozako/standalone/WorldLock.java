// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.standalone;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 检测存档目录是否正被游戏占用。 */
final class WorldLock {
    private WorldLock() {}

    /** 游戏运行时持有 {@code session.lock}；能独占锁定或该文件不存在即表示游戏未运行。 */
    static void requireClosed(Path world) throws IOException {
        var session = world.resolve("session.lock");
        try (var channel = FileChannel.open(session, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock = channel.tryLock();
            if (lock == null)
                throw new IOException("World is currently in use (session.lock is held by a running game)");
            // 成功锁定即证明游戏未运行，随 try-with-resources 立即释放
        } catch (NoSuchFileException absent) {
            // 游戏未运行时通常没有 session.lock
        } catch (OverlappingFileLockException overlap) {
            throw new IOException("World is currently in use", overlap);
        }
    }
}
