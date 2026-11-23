// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.platform.windows;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 用于脱机启动适配器的 NTFS 目录刷新。本机访问仅限于该平台 */
public final class WindowsDirectorySync {
    public static final String ACCESS_HELP = "Java blocked Windows native access. On Java 25, remove --illegal-native-access=deny " +
        "from the JVM arguments and restart. If the server requires deny mode, ask the administrator to authorize native access.";
    public static final String SYNC_HELP = "Windows directory flush failed. Check that the backup and world directories are on " +
        "local NTFS and that the server account can write and flush them. Check the server log for the Windows error.";
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int SHARE_READ_WRITE_DELETE = 7;
    private static final int OPEN_EXISTING = 3;
    private static final int DIRECTORY_FLAGS = 0x02000000 | 0x00200000; // 目录访问及禁止跟随重解析点
    private static final int PATH_CHARS = 32768;
    private static final int VOLUME_CHARS = 64;

    private WindowsDirectorySync() {}

    private static void requireLocalNtfs(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
            !Files.getFileStore(directory).type().equalsIgnoreCase("NTFS"))
            throw new IOException("Windows offline restore requires ordinary directories on local NTFS");
    }

    public static void force(Path directory) throws IOException {
        try {
            requireLocalNtfs(directory);
            var handle = Api.OPEN.invokePointer(new Object[]{windowsPath(directory), GENERIC_WRITE,
                SHARE_READ_WRITE_DELETE, null, OPEN_EXISTING, DIRECTORY_FLAGS, null});
            if (handle == null || Pointer.nativeValue(handle) == -1L) throw error("CreateFileW");
            IOException failure = null;
            try {
                if (Api.FLUSH.invokeInt(new Object[]{handle}) == 0) failure = error("FlushFileBuffers");
            } finally {
                if (Api.CLOSE.invokeInt(new Object[]{handle}) == 0) {
                    var closing = error("CloseHandle");
                    if (failure == null) failure = closing;
                    else failure.addSuppressed(closing);
                }
            }
            if (failure != null) throw failure;
        } catch (IOException | RuntimeException | LinkageError failure) {
            throw new Failure(false, failure);
        }
    }

    public static String volume(Path directory) throws IOException {
        try (var root = new Memory(PATH_CHARS * 2L); var volume = new Memory(VOLUME_CHARS * 2L)) {
            requireLocalNtfs(directory);
            if (Api.VOLUME_PATH.invokeInt(new Object[]{windowsPath(directory), root, PATH_CHARS}) == 0)
                throw error("GetVolumePathNameW");
            if (Api.VOLUME_NAME.invokeInt(new Object[]{root, volume, VOLUME_CHARS}) == 0)
                throw error("GetVolumeNameForVolumeMountPointW");
            return volume.getWideString(0);
        } catch (IOException | RuntimeException | LinkageError failure) {
            throw new Failure(false, failure);
        }
    }

    public static final class Failure extends IOException {
        public final boolean denied;

        private Failure(boolean denied, Throwable cause) {
            super(denied ? ACCESS_HELP : SYNC_HELP, cause);
            this.denied = denied;
        }
    }

    private static WString windowsPath(Path path) throws IOException {
        var absolute = path.toAbsolutePath().toString();
        if (absolute.startsWith("\\\\"))
            throw new IOException("Windows offline restore requires a local drive path");
        return new WString("\\\\?\\" + absolute);
    }

    private static IOException error(String operation) {
        return new IOException(operation + " failed with Windows error " + Native.getLastError());
    }

    private static final class Api {
        static final NativeLibrary KERNEL = NativeLibrary.getInstance("kernel32");
        static final Function OPEN = link("CreateFileW");
        static final Function FLUSH = link("FlushFileBuffers");
        static final Function CLOSE = link("CloseHandle");
        static final Function VOLUME_PATH = link("GetVolumePathNameW");
        static final Function VOLUME_NAME = link("GetVolumeNameForVolumeMountPointW");

        private static Function link(String name) {
            return KERNEL.getFunction(name, Function.ALT_CONVENTION);
        }
    }
}
