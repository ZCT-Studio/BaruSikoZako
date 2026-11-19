// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.api;

/** An integration entrypoint the host may grant access during its lifecycle. */
@FunctionalInterface
public interface BackupIntegration {
    /** Receives a revocable service on a host integration thread, outside the game and task threads. */
    void onBackupService(BackupService service);
}
