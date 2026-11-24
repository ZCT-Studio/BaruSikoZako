// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import io.github.zct_studio.barusikozako.core.StagedDirectoryView.FileClone;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 经过验证的 Linux x64 XFS 路径上的整个文件 FICLONE。来电者仍然孤立整个世界 */
final class LinuxReflink implements FileClone {
    private static final long FICLONE = 0x40049409L;
    private static final int OPEN_FLAGS = (1 << 19) | (1 << 17) | (1 << 11); // 关闭继承、禁止跟随链接并使用非阻塞模式
    private static final int CREATE_EXCLUSIVE = 1 | (1 << 6) | (1 << 7); // 仅写并独占创建
    private static final long MIN_FILE_BYTES = 128 * 1024;
    private final Path staging;

    private LinuxReflink(Path staging) {
        this.staging = staging;
    }

    static LinuxReflink probe(Path world, Path staging) throws IOException {
        if (!System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("amd64") ||
            !"UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding"))) return null;
        var sourceStore = Files.getFileStore(world);
        if (!sourceStore.type().equals("xfs") || !sourceStore.equals(Files.getFileStore(staging))) return null;
        var strategy = new LinuxReflink(staging);
        var scratch = Files.createTempDirectory(staging, ".barusikozako-view-");
        var from = scratch.resolve("source");
        var to = scratch.resolve("clone");
        try {
            Files.writeString(from, "source payload");
            if (!strategy.cloneFile(from, to)) return null;
            if (!Files.readString(to).equals("source payload")) throw new IOException("Reflink probe did not preserve bytes");
            Files.writeString(from, "changed source");
            if (!Files.readString(to).equals("source payload")) throw new IOException("Reflink did not isolate source writes");
            Files.writeString(to, "changed clone!");
            if (!Files.readString(from).equals("changed source")) throw new IOException("Reflink did not isolate target writes");
            return strategy;
        } finally {
            Files.deleteIfExists(to);
            Files.deleteIfExists(from);
            Files.delete(scratch);
        }
    }

    @Override
    public boolean tryClone(Path source, Path target, long length) throws IOException {
        return length >= MIN_FILE_BYTES && target.startsWith(staging) &&
            Path.of(source.toString()).equals(source) && Path.of(target.toString()).equals(target) &&
            cloneFile(source, target);
    }

    private boolean cloneFile(Path source, Path target) throws IOException {
        try (var from = open(source, OPEN_FLAGS)) {
            if (!Files.isRegularFile(from.path()) || !Files.isSameFile(from.path(), source))
                throw new IOException("Reflink source identity changed");
            int unsupported;
            try (var to = open(target, OPEN_FLAGS | CREATE_EXCLUSIVE)) {
                if (!Files.isRegularFile(to.path()) || !Files.isSameFile(to.path(), target))
                    throw new IOException("Reflink target identity changed");
                if (Api.IOCTL.invokeInt(new Object[]{to.number(), new NativeLong(FICLONE), from.number()}) == 0) return true;
                unsupported = Native.getLastError();
                if (unsupported != 18 && unsupported != 22 && unsupported != 25 && unsupported != 38 && unsupported != 95)
                    throw new IOException("FICLONE failed (errno " + unsupported + ")");
            }
            Files.delete(target);
                return false;
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("Could not perform XFS file clone", failure);
        }
    }

    private static Descriptor open(Path path, int flags) throws IOException {
        int fd = Api.OPEN.invokeInt(new Object[]{Native.toByteArray(path.toString(), "UTF-8"), flags, 0600});
        if (fd < 0) throw new IOException("Could not open reflink file (errno " + Native.getLastError() + ")");
        return new Descriptor(fd);
    }

    private record Descriptor(int number) implements AutoCloseable {
        Path path() { return Path.of("/proc/self/fd", Integer.toString(number)); }

        @Override
        public void close() throws IOException {
            if (Api.CLOSE.invokeInt(new Object[]{number}) != 0)
                throw new IOException("Could not close reflink descriptor (errno " + Native.getLastError() + ")");
        }
    }

    private static final class Api {
        static final NativeLibrary LIBC = NativeLibrary.getInstance("c");
        static final Function OPEN = LIBC.getFunction("open");
        static final Function IOCTL = LIBC.getFunction("ioctl");
        static final Function CLOSE = LIBC.getFunction("close");
    }
}
