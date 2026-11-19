// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.platform;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** A platform coordinates saving and hands off a stable view to a backup worker. */
public interface CaptureSource {
    /** A nonblocking admission hint, independent of whether a later save succeeds. */
    boolean available();

    /** The caller owns the returned view. Implementations record their save consistency guarantees separately. */
    ReadView acquire(BooleanSupplier mayContinue) throws IOException;
}
