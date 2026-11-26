// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.platform;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/** 由平台提供的稳定视图，所有者须在关闭前保持源数据稳定 */
public interface ReadView extends AutoCloseable {
    Session openSession() throws IOException;

    /** 视图已知的总逻辑字节，实施方无法提前确定时返回 -1 */
    default long totalBytes() {
        return -1;
    }

    @Override
    void close() throws IOException;

    record Item(List<String> path, boolean directory, long size, Instant modifiedAt) {
        /** 打开内容前无法确定长度的文件大小 */
        public static final long UNKNOWN_SIZE = -1;

        public Item {
            path = List.copyOf(path);
            if (directory ? size != 0 : size < UNKNOWN_SIZE) throw new IllegalArgumentException("invalid size");
        }
    }

    /** 顺序游标，前进前须完成或关闭当前文件流 */
    interface Session extends AutoCloseable {
        /** 结束时返回 null，目录条目排在其子条目前 */
        Item next() throws IOException;
        InputStream openContent() throws IOException;

        /** 从同一稳定代次重新打开选定文件，流的所有权相互独立 */
        default InputStream reopenContent() throws IOException {
            throw new IOException("This stable view does not support rereading a file");
        }

        @Override
        void close() throws IOException;
    }
}
