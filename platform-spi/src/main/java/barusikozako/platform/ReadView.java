// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.platform;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** A stable view supplied by the platform. Its owner keeps the source stable until close. */
public interface ReadView extends AutoCloseable {
    Session openSession() throws IOException;

    @Override
    void close() throws IOException;

    record Item(List<String> path, boolean directory, long size, Instant modifiedAt) {
        /** Size for a file whose length is not known before opening its content. */
        public static final long UNKNOWN_SIZE = -1;

        public Item {
            path = List.copyOf(path);
            if (directory ? size != 0 : size < UNKNOWN_SIZE) throw new IllegalArgumentException("invalid size");
        }
    }

    /** Sequential cursor. Finish or close the current file stream before advancing. */
    interface Session extends AutoCloseable {
        /** Returns null at end. Directory entries precede their children. */
        Item next() throws IOException;
        InputStream openContent() throws IOException;

        /** Reopens the selected file from the same stable generation, with independent stream ownership. */
        default InputStream reopenContent() throws IOException {
            throw new IOException("This stable view does not support rereading a file");
        }

        @Override
        void close() throws IOException;
    }
}
