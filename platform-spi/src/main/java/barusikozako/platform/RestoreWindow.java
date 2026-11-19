// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.platform;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Platform-owned offline recovery window. The platform must exclude world loads and writers until
 * recovery succeeds, including after process restart. After any uncertain result the platform must
 * keep its startup gate closed. A process-local mutex is insufficient. The platform must not hand
 * out another live window for the same target or release this window concurrently with an operation.
 */
public interface RestoreWindow extends AutoCloseable {
    /**
     * Stable absolute world location, with the same final name and canonical parent even when
     * the world directory is temporarily absent. The parent is trusted and protected by the window.
     */
    Path target();

    /** Fails when offline access or the platform's restart protection cannot be guaranteed. */
    void requireExclusiveAccess() throws IOException;

    default boolean sameFileStore(Path first, Path second) throws IOException {
        return Files.getFileStore(first).equals(Files.getFileStore(second));
    }

    /** Confirms the platform's directory metadata flush or refuses the transaction. */
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
