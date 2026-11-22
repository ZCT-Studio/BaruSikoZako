// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.platform;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;

/**
 * 平台拥有的离线恢复窗口。该平台必须排除世界负载和写入器，直到
 * 恢复成功，包括进程重新启动后。在出现任何不确定结果后，平台必须
 * 保持其启动大门关闭。进程本地互斥体是不够的。平台不得手
 * 为同一目标调出另一个实时窗口，或者在执行操作的同时释放此窗口
 */
public interface RestoreWindow extends AutoCloseable {
    /**
     * 稳定的绝对世界位置，即使在以下情况下也具有相同的最终名称和规范父代：
     * 世界目录暂时不存在。父级受到窗口的信任和保护
     */
    Path target();

    /** 离线访问或无法保证平台重启保护时失败 */
    void requireExclusiveAccess() throws IOException;

    default boolean sameFileStore(Path first, Path second) throws IOException {
        return Files.getFileStore(first).equals(Files.getFileStore(second));
    }

    /** 在此独占恢复窗口内自动移动一个目录 */
    default void moveDirectoryAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 确认平台的目录元数据刷新或拒绝事务 */
    default void forceDirectory(Path directory) throws IOException {
        forceLocalDirectory(directory);
    }

    static void forceLocalDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        } catch (IOException failure) {
            throw new IOException("filesystem cannot confirm directory persistence: " + directory, failure);
        }
    }

    @Override
    void close() throws IOException;
}
