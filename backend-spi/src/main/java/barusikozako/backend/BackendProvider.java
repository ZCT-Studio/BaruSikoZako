// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.backend;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loader-neutral registration contract. The host owns capture, scheduling, authorization and restore. */
public interface BackendProvider {
    String keyword();
    Map<String, String> defaults();
    Settings configure(Map<String, String> settings);
    List<Command> commands();

    record Command(String keyword, String operation, boolean needsBaseline) {}

    interface Settings {
        default long maintenanceIntervalSeconds() { return 0; }

        default boolean supports(String operation) { return operation.equals("FULL") || operation.equals("CONFIGURED"); }

        ManagedRepository open(Path path, DirectoryPersistence persistence, String operation, UUID baseline)
            throws IOException;
    }

    @FunctionalInterface
    interface DirectoryPersistence {
        void force(Path directory) throws IOException;
    }
}
