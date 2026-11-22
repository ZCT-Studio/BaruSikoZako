// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.backend;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** 打开存储库会话。所有读者和作者都关闭后关闭它 */
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
        /** 使用提供的文件内容而不获取所有权。目录没有内容 */
        void add(Entry entry, InputStream content) throws IOException;

        /** 当此调用处于活动状态时，重新打开器会为同一稳定条目提供另一个流 */
        default void add(Entry entry, InputStream content, ContentSource reopener) throws IOException {
            add(entry, content);
        }
        void commit() throws IOException;

        /** 发布前先打电话给门。后端可以在进入大门之前准备私有数据 */
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
        /** 前进到下一个项目。空结果还验证存档和清单的结尾 */
        Item next() throws IOException;
        InputStream openContent() throws IOException;

        @Override
        void close() throws IOException;
    }

    record Item(Entry entry, String sha256) {}
}
