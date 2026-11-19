// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.paper;

import barusikozako.platform.CaptureSource;
import barusikozako.platform.ReadView;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Rejects captures at PREPARE on servers without a verified save and isolation barrier. */
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
