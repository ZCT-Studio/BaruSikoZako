// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.paper;

import io.github.zct_studio.barusikozako.platform.CaptureSource;
import io.github.zct_studio.barusikozako.platform.ReadView;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** 在没有经过验证的保存和隔离屏障的情况下，拒绝服务器上 PREPARE 的捕获 */
final class PaperCaptureSource implements CaptureSource {
    static final String REJECTION = "UNSUPPORTED: online backup requires verified save outcomes and an isolated read view.";

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public ReadView acquire(BooleanSupplier mayContinue) throws IOException {
        throw new IOException(REJECTION);
    }
}
