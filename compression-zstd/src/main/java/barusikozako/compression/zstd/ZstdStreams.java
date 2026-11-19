// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.compression.zstd;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class ZstdStreams {
    private ZstdStreams() {}

    public static OutputStream compress(OutputStream target) throws IOException {
        return new ZstdOutputStream(target).setChecksum(true);
    }

    public static OutputStream compress(OutputStream target, int level, int threads) throws IOException {
        return compress(target, level, threads, false);
    }

    public static OutputStream compressBlock(OutputStream target, int level) throws IOException {
        return compress(target, level, 1, true);
    }

    private static OutputStream compress(OutputStream target, int level, int threads, boolean block) throws IOException {
        if (level < 1 || level > 9 || threads < 1 || threads > 4)
            throw new IllegalArgumentException("compression settings exceed the supported resource range");
        var stream = new ZstdOutputStream(target, level);
        try {
            if (threads > 1) stream.setWorkers(threads - 1);
            if (block) stream.setWindowLog(16);
            return stream.setChecksum(true);
        } catch (IOException | RuntimeException | LinkageError failure) {
            try {
                stream.close();
            } catch (IOException | RuntimeException | LinkageError closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    public static InputStream decompress(InputStream source) throws IOException {
        return new ZstdInputStream(source);
    }

    public static InputStream decompressBlock(InputStream source) throws IOException {
        var stream = new ZstdInputStream(source);
        try {
            return stream.setLongMax(16);
        } catch (IOException | RuntimeException | LinkageError failure) {
            try {
                stream.close();
            } catch (IOException | RuntimeException | LinkageError closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }
}
