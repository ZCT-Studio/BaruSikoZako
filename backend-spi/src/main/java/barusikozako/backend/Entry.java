// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.backend;

import java.time.Instant;
import java.util.List;

public record Entry(List<String> path, boolean directory, long size, Instant modifiedAt) {
    public Entry {
        path = List.copyOf(path);
        if (path.isEmpty() || (directory ? size != 0 : size < 0))
            throw new IllegalArgumentException("invalid entry");
        for (var name : path) {
            if (name.isEmpty() || name.equals(".") || name.equals("..") ||
                name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(0) >= 0)
                throw new IllegalArgumentException("invalid path segment");
        }
    }
}
