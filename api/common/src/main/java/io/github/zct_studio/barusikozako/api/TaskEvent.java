// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.api;

public sealed interface TaskEvent {
    record StateChanged(TaskState state) implements TaskEvent {}
    record Progress(TaskState state) implements TaskEvent {}
    record CancellationRequested(TaskId id) implements TaskEvent {}
    record Evicted(TaskId id) implements TaskEvent {}
}
