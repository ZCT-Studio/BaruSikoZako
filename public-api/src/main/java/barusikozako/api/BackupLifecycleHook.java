// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.api;

/**
 * Explicitly allowed host lifecycle callbacks. Callbacks run off the game and backup worker threads.
 * Implementations must return promptly, must not wait on backup tasks, and must arrange any
 * game-thread work themselves. A failed or timed-out before callback prevents that operation.
 */
public interface BackupLifecycleHook {
    enum Operation { AUTOMATIC_BACKUP, MANUAL_BACKUP, OFFLINE_RESTORE }

    record Context(Operation operation, String repositoryId, java.util.Optional<SnapshotRef> snapshot) {}

    default void before(Context context) {}

    default void after(Context context, TaskResult outcome) {}
}
