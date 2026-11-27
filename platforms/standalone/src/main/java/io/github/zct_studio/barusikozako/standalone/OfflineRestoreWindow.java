// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.standalone;

import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 独立运行时的离线恢复窗口：用父目录进程锁保证同一存档不被并发操作，
 * 并在每次独占访问前确认游戏未占用 {@code session.lock}。
 */
public final class OfflineRestoreWindow implements RestoreWindow {
    private final Path target;
    private final FileChannel parentChannel;
    private final FileLock parentLock;
    private boolean closed;

    public OfflineRestoreWindow(Path world) throws IOException {
        var supplied = world.toAbsolutePath().normalize();
        if (supplied.getParent() == null) throw new IOException("World has no parent directory");
        var parent = supplied.getParent().toRealPath();
        target = parent.resolve(supplied.getFileName());
        var lockFile = parent.resolve(".barusikozako-standalone.lock");
        parentChannel = FileChannel.open(lockFile, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
            LinkOption.NOFOLLOW_LINKS);
        try {
            parentLock = parentChannel.tryLock();
            if (parentLock == null)
                throw new IOException("Another BaruSikoZako operation is already running for this directory");
        } catch (IOException | RuntimeException | LinkageError failure) {
            try {
                parentChannel.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    @Override
    public Path target() {
        return target;
    }

    @Override
    public void requireExclusiveAccess() throws IOException {
        if (closed || !parentLock.isValid())
            throw new IOException("Standalone restore window is closed");
        WorldLock.requireClosed(target);
    }

    @Override
    public void forceDirectory(Path directory) throws IOException {
        NativeSync.forceDirectory(directory);
    }

    @Override
    public boolean sameFileStore(Path first, Path second) throws IOException {
        return Files.getFileStore(first).equals(Files.getFileStore(second));
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try (parentChannel; parentLock) {
            // 释放父目录进程锁
        }
    }
}
