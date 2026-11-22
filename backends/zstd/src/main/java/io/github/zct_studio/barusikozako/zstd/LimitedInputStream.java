// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.zstd;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class LimitedInputStream extends FilterInputStream {
    private final Budget budget;

    LimitedInputStream(InputStream source, long limit) {
        this(source, new Budget(limit));
    }

    LimitedInputStream(InputStream source, Budget budget) {
        super(source);
        this.budget = budget;
    }

    static final class Budget {
        private long remaining;
        Budget(long limit) { remaining = limit; }
        long remaining() { return remaining; }
        void consume(long count) throws IOException {
            if (count < 0 || remaining < count) throw new IOException("archive exceeds read budget");
            remaining -= count;
        }
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value != -1) {
            budget.consume(1);
        }
        return value;
    }

    @Override
    public int read(byte[] data, int offset, int count) throws IOException {
        if (count == 0) return 0;
        if (budget.remaining() == 0) return read();
        int size = super.read(data, offset, (int) Math.min(count, budget.remaining()));
        if (size > 0) budget.consume(size);
        return size;
    }

    @Override
    public long skip(long count) throws IOException {
        if (count <= 0) return 0;
        long skipped = super.skip(Math.min(count, budget.remaining()));
        budget.consume(skipped);
        return skipped;
    }
}
