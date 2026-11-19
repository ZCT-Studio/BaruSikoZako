// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.backend;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Open repository session. Close it after all readers and writers have closed. */
public interface Repository extends AutoCloseable {
    Writer prepare(Snapshot snapshot, UUID operationId) throws IOException;
    Reader read(UUID snapshotId, long archiveByteBudget) throws IOException;

    default Writer prepare(Snapshot snapshot, UUID operationId, Preparation limits) throws IOException {
        return prepare(snapshot, operationId);
    }

    default Reader read(UUID snapshotId, long archiveByteBudget, CommitGate checkpoint) throws IOException {
        return read(snapshotId, archiveByteBudget);
    }

    record Preparation(long contentBytes, long archiveBytes, long entries, CommitGate checkpoint,
                       java.util.function.UnaryOperator<InputStream> pace) {
        public Preparation(long contentBytes, long archiveBytes, long entries, CommitGate checkpoint) {
            this(contentBytes, archiveBytes, entries, checkpoint, java.util.function.UnaryOperator.identity());
        }
    }

    @Override
    void close() throws IOException;

    interface Writer extends AutoCloseable {
        /** Consumes the supplied file content without taking ownership. Directories have no content. */
        void add(Entry entry, InputStream content) throws IOException;

        /** The reopener supplies another stream for the same stable entry while this call is active. */
        default void add(Entry entry, InputStream content, ContentSource reopener) throws IOException {
            add(entry, content);
        }
        void commit() throws IOException;

        /** Calls the gate before publishing. Backends can prepare private data before entering the gate. */
        default void commit(CommitGate gate) throws IOException {
            gate.enter();
            commit();
        }

        @Override
        void close() throws IOException;
    }

    @FunctionalInterface
    interface ContentSource {
        InputStream open() throws IOException;
    }

    @FunctionalInterface
    interface CommitGate {
        void enter() throws IOException;
    }

    interface Reader extends AutoCloseable {
        /** Advances to the next item. A null result also verifies the end of the archive and manifest. */
        Item next() throws IOException;
        InputStream openContent() throws IOException;

        @Override
        void close() throws IOException;
    }

    record Item(Entry entry, String sha256) {}
}
