// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService.Cancellation;
import io.github.zct_studio.barusikozako.api.BackupService.Rejection;
import io.github.zct_studio.barusikozako.api.SnapshotRef;
import io.github.zct_studio.barusikozako.api.TaskId;
import io.github.zct_studio.barusikozako.api.TaskResult;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class RuntimeChecks {
    static void exercise() throws Exception {
        var clock = new AtomicLong();
        var rights = Set.of(BoundAccess.Right.BACKUP, BoundAccess.Right.VIEW, BoundAccess.Right.CANCEL);
        var grant = new BoundAccess.Grant(Map.of("one", rights, "two", rights, "three", rights), Set.of("world"), Set.of());
        var owner = new BoundAccess("editor", grant);
        var stranger = new BoundAccess("another", grant);
        TaskId previous;
        try (var runtime = new TaskRuntime(1, 1, 1, Duration.ofSeconds(1), clock::get)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var admission = runtime.admit(owner, request("one"), control -> {
                require(!Thread.holdsLock(runtime), "operation ran under scheduler lock");
                require(control.beginCommit(), "authorized commit rejected");
                entered.countDown();
                await(release);
                return committed("one");
            });
            var first = accepted(admission);
            var receipt = ((TaskRuntime.Admission.Accepted) admission).receipt();
            try {
                await(entered);
                require(receipt.result().isEmpty(), "receipt completed before commit");
                require(runtime.cancel(owner, first) == Cancellation.TOO_LATE, "commit was cancellable");
                require(runtime.cancel(stranger, first) == Cancellation.NOT_AVAILABLE, "foreign task leaked through cancel");
                require(runtime.task(stranger, first).isEmpty(), "foreign task was visible");
                var queued = accepted(runtime.admit(owner, request("two"), control -> {
                    throw new AssertionError("cancelled queued task ran");
                }));
                require(runtime.admit(owner, request("one"), control -> committed("one"))
                    .equals(new TaskRuntime.Admission.Rejected(Rejection.BUSY)), "repository exclusion failed");
                require(runtime.admit(owner, request("three"), control -> committed("three"))
                    .equals(new TaskRuntime.Admission.Rejected(Rejection.BUSY)), "queue was not bounded");
                require(runtime.cancel(owner, queued) == Cancellation.REQUESTED, "queued cancellation rejected");
                require(result(runtime, owner, queued) instanceof TaskResult.Cancelled, "queued task was not cancelled");
                release.countDown();
                var result = result(runtime, owner, first);
                require(result instanceof TaskResult.BackupCommitted, "commit did not retain success");
                require(runtime.task(owner, queued).isEmpty(), "terminal count was not bounded");
                clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
                require(runtime.task(owner, first).isEmpty(), "terminal time limit was ignored");
                require(receipt.result().orElseThrow().equals(result), "task receipt lost its result after eviction");
            } finally {
                release.countDown();
            }
            previous = first;
        }
        try (var runtime = new TaskRuntime(1, 1, 4, Duration.ofMinutes(1), System::nanoTime)) {
            var revocable = new BoundAccess("editor", grant);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var id = accepted(runtime.admit(revocable, request("one"), control -> {
                entered.countDown();
                await(release);
                require(!control.beginCommit(), "revoked request crossed commit boundary");
                return new TaskResult.Failed(TaskResult.Failure.LOST_AUTHORIZATION);
            }));
            try {
                await(entered);
                require(!id.equals(previous), "task ID repeated in another service instance");
                revocable.revoke();
                release.countDown();
                require(result(runtime, owner, id).equals(new TaskResult.Failed(TaskResult.Failure.LOST_AUTHORIZATION)),
                    "revocation was not reflected in the outcome");
            } finally {
                release.countDown();
            }
            var uncertain = accepted(runtime.admit(owner, request("two"), control -> {
                require(control.beginCommit(), "expected commit to start");
                throw new IOException("indeterminate commit");
            }));
            require(result(runtime, owner, uncertain) instanceof TaskResult.CommitUncertain,
                "uncertain commit was reported as known");
            var preparing = new CountDownLatch(1);
            var continuePreparation = new CountDownLatch(1);
            var cancellationCheck = new java.util.concurrent.atomic.AtomicBoolean();
            var cancelled = accepted(runtime.admit(owner, request("three"), control -> {
                preparing.countDown();
                await(continuePreparation);
                boolean canContinue = control.mayContinue();
                boolean canCommit = control.beginCommit();
                cancellationCheck.set(!canContinue && !canCommit);
                return new TaskResult.Cancelled();
            }));
            try {
                await(preparing);
                require(runtime.cancel(owner, cancelled) == Cancellation.REQUESTED, "running cancellation rejected");
                continuePreparation.countDown();
                require(result(runtime, owner, cancelled) instanceof TaskResult.Cancelled,
                    "running cancellation was not reflected in the outcome");
                require(cancellationCheck.get(), "cancelled request could continue or commit");
            } finally {
                continuePreparation.countDown();
            }
            var premature = accepted(runtime.admit(owner, request("one"), control -> committed("one")));
            require(result(runtime, owner, premature).equals(new TaskResult.Failed(TaskResult.Failure.INTERNAL)),
                "backup was reported committed without entering the commit stage");
            runtime.close();
            require(runtime.admit(owner, request("one"), control -> committed("one"))
                .equals(new TaskRuntime.Admission.Rejected(Rejection.UNAVAILABLE)), "closed scheduler admitted a task");
        }
        exercisePreparationIsolation(owner);
        System.out.println("Internal task runtime checks passed.");
    }

    private static void exercisePreparationIsolation(BoundAccess owner) throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        try (var runtime = new TaskRuntime(1, 1, 4, Duration.ofMinutes(1), System::nanoTime)) {
            var first = accepted(runtime.admit(owner, request("one"), control -> {
                ran.set(true);
                require(control.beginCommit(), "prepared backup could not enter commit");
                return committed("one");
            }, () -> {
                require(!Thread.holdsLock(runtime), "integration callback ran under scheduler lock");
                started.countDown();
                try {
                    await(release);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            try {
                await(started);
                var second = accepted(runtime.admit(owner, request("two"), control -> {
                    require(control.beginCommit(), "independent backup could not enter commit");
                    return committed("two");
                }));
                require(result(runtime, owner, second) instanceof TaskResult.BackupCommitted,
                    "a pending hook blocked a different repository");
                require(!ran.get(), "backup ran while its hook was still executing");
            } finally {
                release.countDown();
            }
            require(result(runtime, owner, first) instanceof TaskResult.BackupCommitted, "prepared backup did not complete");
            var refused = accepted(runtime.admit(owner, request("one"), control -> {
                throw new AssertionError("worker ran after failed preparation");
            }, () -> { throw new IllegalStateException("simulated callback failure"); }));
            require(result(runtime, owner, refused) instanceof TaskResult.Failed, "failed preparation was not reported");
        }
    }

    private static BackupRequest.Backup request(String repository) {
        return new BackupRequest.Backup(repository, "world", new BackupRequest.Trigger("fixture", "test", Map.of()));
    }

    private static TaskResult.BackupCommitted committed(String repository) {
        return new TaskResult.BackupCommitted(new SnapshotRef(repository, "snapshot"));
    }

    private static TaskId accepted(TaskRuntime.Admission admission) {
        if (admission instanceof TaskRuntime.Admission.Accepted value) return value.id();
        throw new AssertionError("expected admission, got " + admission);
    }

    private static TaskResult result(TaskRuntime runtime, BoundAccess access, TaskId id) throws InterruptedException {
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
            var result = runtime.task(access, id).flatMap(state -> state.result());
            if (result.isPresent()) return result.orElseThrow();
            Thread.sleep(1);
        }
        throw new AssertionError("task did not reach a visible terminal state");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(5, TimeUnit.SECONDS), "latch timed out");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private RuntimeChecks() {}
}
