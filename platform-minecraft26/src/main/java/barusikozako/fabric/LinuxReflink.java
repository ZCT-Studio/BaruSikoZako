// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import barusikozako.core.StagedDirectoryView.FileClone;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/** Whole-file FICLONE on the proven Linux x64 XFS path. The caller still isolates the entire world. */
final class LinuxReflink implements FileClone {
    private static final long FICLONE = 0x40049409L;
    private static final int OPEN_FLAGS = (1 << 19) | (1 << 17) | (1 << 11); // CLOEXEC, NOFOLLOW, NONBLOCK
    private static final int CREATE_EXCLUSIVE = 1 | (1 << 6) | (1 << 7); // WRONLY, CREAT, EXCL
    private static final long MIN_FILE_BYTES = 128 * 1024;
    private final Path staging;

    private LinuxReflink(Path staging) {
        this.staging = staging;
    }

    static LinuxReflink probe(Path world, Path staging) throws IOException {
        if (!System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("amd64") ||
            !"UTF-8".equalsIgnoreCase(System.getProperty("sun.jnu.encoding")) ||
            !NativeAccess.permitted(LinuxReflink.class)) return null;
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
        try (var arena = Arena.ofConfined()) {
            var state = arena.allocate(Api.STATE);
            try (var from = open(arena, state, source, OPEN_FLAGS)) {
                if (!Files.isRegularFile(from.path()) || !Files.isSameFile(from.path(), source))
                    throw new IOException("Reflink source identity changed");
                int unsupported;
                try (var to = open(arena, state, target, OPEN_FLAGS | CREATE_EXCLUSIVE)) {
                    if (!Files.isRegularFile(to.path()) || !Files.isSameFile(to.path(), target))
                        throw new IOException("Reflink target identity changed");
                    if ((int) Api.IOCTL.invokeExact(state, to.number(), FICLONE, from.number()) == 0) return true;
                    unsupported = errno(state);
                    if (unsupported != 18 && unsupported != 22 && unsupported != 25 && unsupported != 38 && unsupported != 95)
                        throw new IOException("FICLONE failed (errno " + unsupported + ")");
                }
                Files.delete(target);
                return false;
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IOException("Could not perform XFS file clone", failure);
        }
    }

    private static Descriptor open(Arena arena, MemorySegment state, Path path, int flags) throws Throwable {
        int fd = (int) Api.OPEN.invokeExact(state, arena.allocateFrom(path.toString()), flags, 0600);
        if (fd < 0) throw new IOException("Could not open reflink file (errno " + errno(state) + ")");
        return new Descriptor(fd);
    }

    private static int errno(MemorySegment state) {
        return state.get(JAVA_INT, Api.ERRNO);
    }

    private record Descriptor(int number) implements AutoCloseable {
        Path path() { return Path.of("/proc/self/fd", Integer.toString(number)); }

        @Override
        public void close() throws IOException {
            try {
                if ((int) Api.CLOSE.invokeExact(number) == 0) return;
            } catch (Throwable failure) {
                throw new IOException("Could not close reflink descriptor", failure);
            }
            throw new IOException("Could not close reflink descriptor");
        }
    }

    private static final class Api {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final Linker.Option CAPTURE = Linker.Option.captureCallState("errno");
        private static final java.lang.foreign.StructLayout STATE = Linker.Option.captureStateLayout();
        private static final long ERRNO = STATE.byteOffset(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno"));
        private static final MethodHandle OPEN = handle("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT),
            CAPTURE, Linker.Option.firstVariadicArg(2));
        private static final MethodHandle IOCTL = handle("ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT),
            CAPTURE, Linker.Option.firstVariadicArg(2));
        private static final MethodHandle CLOSE = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

        private static MethodHandle handle(String name, FunctionDescriptor type, Linker.Option... options) {
            return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), type, options);
        }
    }
}
