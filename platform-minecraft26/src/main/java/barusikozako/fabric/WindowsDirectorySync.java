// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** NTFS directory flush for the offline startup adapter. Native access is confined to this platform. */
final class WindowsDirectorySync {
    static final String ACCESS_HELP = "Java blocked Windows native access. On Java 25, remove --illegal-native-access=deny " +
        "from the JVM arguments and restart. If the server requires deny mode, ask the administrator to authorize native access.";
    static final String SYNC_HELP = "Windows directory flush failed. Check that the backup and world directories are on " +
        "local NTFS and that the server account can write and flush them. Check the server log for the Windows error.";
    private static volatile boolean accessDenied;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int SHARE_READ_WRITE_DELETE = 7;
    private static final int OPEN_EXISTING = 3;
    private static final int DIRECTORY_FLAGS = 0x02000000 | 0x00200000; // Backup semantics and no reparse following.
    private static final int PATH_CHARS = 32768;
    private static final int VOLUME_CHARS = 64;

    private WindowsDirectorySync() {}

    private static void requireLocalNtfs(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
            !Files.getFileStore(directory).type().equalsIgnoreCase("NTFS"))
            throw new IOException("Windows offline restore requires ordinary directories on local NTFS");
    }

    static void force(Path directory) throws IOException {
        try (var scope = Arena.ofConfined()) {
            requireLocalNtfs(directory);
            var state = scope.allocate(Api.ERROR_LAYOUT);
            var name = scope.allocateFrom(windowsPath(directory), StandardCharsets.UTF_16LE);
            var handle = (MemorySegment) Api.OPEN.invokeExact(state, name, GENERIC_WRITE, SHARE_READ_WRITE_DELETE,
                MemorySegment.NULL, OPEN_EXISTING, DIRECTORY_FLAGS, MemorySegment.NULL);
            if (handle.address() == -1L) throw error("CreateFileW", state);
            IOException failure = null;
            try {
                if ((int) Api.FLUSH.invokeExact(state, handle) == 0) failure = error("FlushFileBuffers", state);
            } finally {
                if ((int) Api.CLOSE.invokeExact(state, handle) == 0) {
                    var closing = error("CloseHandle", state);
                    if (failure == null) failure = closing;
                    else failure.addSuppressed(closing);
                }
            }
            if (failure != null) throw failure;
        } catch (Throwable failure) {
            throw diagnose(failure);
        }
    }

    static String volume(Path directory) throws IOException {
        try (var scope = Arena.ofConfined()) {
            requireLocalNtfs(directory);
            var state = scope.allocate(Api.ERROR_LAYOUT);
            var input = scope.allocateFrom(windowsPath(directory), StandardCharsets.UTF_16LE);
            var root = scope.allocate(PATH_CHARS * 2L, 2);
            if ((int) Api.VOLUME_PATH.invokeExact(state, input, root, PATH_CHARS) == 0)
                throw error("GetVolumePathNameW", state);
            var volume = scope.allocate(VOLUME_CHARS * 2L, 2);
            if ((int) Api.VOLUME_NAME.invokeExact(state, root, volume, VOLUME_CHARS) == 0)
                throw error("GetVolumeNameForVolumeMountPointW", state);
            return volume.getString(0L, StandardCharsets.UTF_16LE);
        } catch (Throwable failure) {
            throw diagnose(failure);
        }
    }

    private static Failure diagnose(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalCallerException) {
                accessDenied = true;
                break;
            }
        }
        return new Failure(accessDenied && (failure instanceof LinkageError || failure instanceof IllegalCallerException), failure);
    }

    static final class Failure extends IOException {
        final boolean denied;

        private Failure(boolean denied, Throwable cause) {
            super(denied ? ACCESS_HELP : SYNC_HELP, cause);
            this.denied = denied;
        }
    }

    private static String windowsPath(Path path) throws IOException {
        var absolute = path.toAbsolutePath().toString();
        if (absolute.startsWith("\\\\"))
            throw new IOException("Windows offline restore requires a local drive path");
        return "\\\\?\\" + absolute;
    }

    private static IOException error(String operation, MemorySegment state) {
        return new IOException(operation + " failed with Windows error " + state.get(JAVA_INT, Api.ERROR_OFFSET));
    }

    private static final class Api {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        static final java.lang.foreign.StructLayout ERROR_LAYOUT = Linker.Option.captureStateLayout();
        static final long ERROR_OFFSET = ERROR_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
        static final MethodHandle OPEN = link("CreateFileW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle FLUSH = link("FlushFileBuffers", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle CLOSE = link("CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle VOLUME_PATH = link("GetVolumePathNameW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        static final MethodHandle VOLUME_NAME = link("GetVolumeNameForVolumeMountPointW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));

        private static MethodHandle link(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(KERNEL.findOrThrow(name), descriptor, Linker.Option.captureCallState("GetLastError"));
        }
    }
}
