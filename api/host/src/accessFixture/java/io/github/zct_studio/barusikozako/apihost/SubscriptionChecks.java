// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService.Cancellation;
import io.github.zct_studio.barusikozako.api.TaskEvent;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.api.TaskState;
import io.github.zct_studio.barusikozako.api.TaskSubscription;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class SubscriptionChecks {
    static void exercise() throws Exception {
        var grant = new BoundAccess.Grant(Map.of("local", Set.of(BoundAccess.Right.BACKUP,
            BoundAccess.Right.VIEW, BoundAccess.Right.SUBSCRIBE, BoundAccess.Right.CANCEL)), Set.of("world"), Set.of());
        var owner = new BoundAccess("owner", grant);
        var other = new BoundAccess("other", grant);
        var request = new BackupRequest.Backup("local", "world", new BackupRequest.Trigger("manual", "test", Map.of()));
        try (var runtime = new TaskRuntime(1, 2, 1, Duration.ofMinutes(1), System::nanoTime)) {
            var entered = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            try {
                var first = (TaskRuntime.Admission.Accepted) runtime.admit(owner, request, control -> {
                    entered.countDown();
                    require(finish.await(5, TimeUnit.SECONDS), "finish signal was not released");
                    return new TaskResult.Failed(TaskResult.Failure.IO);
                });
                require(entered.await(5, TimeUnit.SECONDS), "task did not reach preparation");
                var observed = new LinkedBlockingQueue<TaskEvent>();
                var scope = Optional.of("local");
                var sub = runtime.subscribe(owner, scope, observed::add).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var restricted = runtime.subscribe(other, scope, event -> {
                    throw new AssertionError("foreign task should be filtered");
                }).toCompletableFuture().get(5, TimeUnit.SECONDS);
                require(restricted.baseline().isEmpty(), "foreign task leaked into the baseline");
                require(sub.baseline().size() == 1 && sub.baseline().get(0).phase() == TaskState.Phase.PREPARING,
                    "baseline missed the established task state");
                require(runtime.cancel(owner, first.id()) == Cancellation.REQUESTED, "cancel was not accepted");
                finish.countDown();
                require(first.receipt().stage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                    instanceof TaskResult.Cancelled, "cancel did not complete");
                require(observed.isEmpty(), "subscription sent events before acknowledgement");
                require(sub.start().state() == TaskSubscription.State.ACTIVE && sub.start().state() == TaskSubscription.State.ACTIVE,
                    "subscription acknowledgement was not idempotent");
                require(observed.poll(5, TimeUnit.SECONDS).equals(new TaskEvent.CancellationRequested(first.id())),
                    "boundary missed cancellation");
                var ended = observed.poll(5, TimeUnit.SECONDS);
                require(ended instanceof TaskEvent.StateChanged change && change.state().phase() == TaskState.Phase.TERMINAL,
                    "boundary missed the terminal event");
                var second = (TaskRuntime.Admission.Accepted) runtime.admit(owner, request,
                    control -> new TaskResult.Failed(TaskResult.Failure.IO));
                second.receipt().stage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                require(observed.poll(5, TimeUnit.SECONDS) instanceof TaskEvent.StateChanged, "task creation was missed");
                require(observed.poll(5, TimeUnit.SECONDS) instanceof TaskEvent.StateChanged, "task preparation was missed");
                require(observed.poll(5, TimeUnit.SECONDS) instanceof TaskEvent.StateChanged, "task completion was missed");
                require(observed.poll(5, TimeUnit.SECONDS).equals(new TaskEvent.Evicted(first.id())), "eviction was missed");
                runtime.revoke(owner);
                require(sub.status().reason().orElseThrow() == TaskSubscription.CloseReason.AUTHORIZATION_CHANGED &&
                    sub.baseline().isEmpty(), "removed grant still exposes state");
                require(restricted.status().state() != TaskSubscription.State.CLOSED, "foreign delivery closed the observer");
            } finally {
                finish.countDown();
            }
        }

        try (var notifications = new TaskNotifications(1, 2, 1, Duration.ofMillis(200))) {
            var access = new BoundAccess("owner", grant);
            var pending = notifications.register(access, Optional.empty(), List.of(), event -> {});
            var overflow = notifications.register(access, Optional.empty(), List.of(), event -> {});
            var id = new io.github.zct_studio.barusikozako.api.TaskId("fixture");
            notifications.publish("local", "owner", new TaskEvent.CancellationRequested(id));
            notifications.publish("local", "owner", new TaskEvent.Evicted(id));
            require(overflow.start().reason().orElseThrow() == TaskSubscription.CloseReason.OVERFLOW &&
                overflow.status().eventGap(), "overflow silently dropped a lifecycle event");
            var expires = notifications.register(access, Optional.empty(), List.of(), event -> {});
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (expires.status().state() != TaskSubscription.State.CLOSED && System.nanoTime() < deadline)
                TimeUnit.MILLISECONDS.sleep(10);
            require(expires.start().reason().orElseThrow() == TaskSubscription.CloseReason.TIMEOUT,
                "unacknowledged subscription did not expire");
            pending.close();
        }
        System.out.println("Bounded task subscription checks passed.");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
