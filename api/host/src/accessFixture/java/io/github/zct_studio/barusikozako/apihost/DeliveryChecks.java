// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.apihost;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

final class DeliveryChecks {
    static void exercise() throws Exception {
        var caller = Thread.currentThread();
        try (var delivery = new ResultDelivery(1, 2, 16, 4)) {
            var source = new CompletableFuture<Integer>();
            var stage = delivery.view(source);
            var before = stage.thenApply(value -> {
                require(Thread.currentThread() != caller && value == 7, "early callback ran inline");
                return value + 1;
            });
            var detached = stage.toCompletableFuture();
            var nativeCombination = CompletableFuture.allOf(detached).thenApply(ignored -> Thread.currentThread());
            var independent = stage.toCompletableFuture();
            independent.complete(100);
            source.complete(7);
            require(get(before) == 8, "early result changed");
            require(get(nativeCombination) != caller, "JDK combinator ran inline on producer");
            require(get(stage.thenApply(value -> Thread.currentThread())) != caller, "late callback ran inline");
            require(get(stage) == 7 && independent.join() == 100, "caller altered the authority");
            var explicit = stage.thenApplyAsync(value -> Thread.currentThread(), action -> {
                require(Thread.currentThread() != caller, "explicit executor invoked inline");
                action.run();
            });
            require(get(explicit) != caller, "explicit inline executor leaked to the caller");
            var rejected = stage.thenApplyAsync(value -> value, action -> {
                throw new RejectedExecutionException("test executor rejection");
            });
            requireRejected(rejected);
            var exceptional = stage.thenApply(value -> { throw new IllegalStateException("test callback failure"); });
            require(get(exceptional.exceptionallyCompose(failure -> CompletableFuture.completedFuture(9))).equals(9),
                "callback failure could not be recovered");
            require(get(stage) == 7, "callback failure altered the result");
        }

        try (var delivery = new ResultDelivery(1, 1, 8, 2)) {
            var stage = delivery.view(CompletableFuture.completedFuture(11));
            var blocking = new CountDownLatch(1);
            var entered = new CountDownLatch(1);
            try {
                var running = stage.thenRun(() -> {
                    entered.countDown();
                    while (blocking.getCount() != 0) {
                        try {
                            blocking.await();
                        } catch (InterruptedException ignored) {
                            // 故意模拟在关闭时不配合的集成
                        }
                    }
                });
                require(entered.await(5, TimeUnit.SECONDS), "expected active callback");
                var queued = stage.thenRun(() -> { throw new AssertionError("discarded callback ran"); });
                requireRejected(stage.thenRun(() -> { throw new AssertionError("overloaded callback ran"); }));
                require(get(stage) == 11, "saturated delivery lost the result");
                delivery.close();
                requireRejected(queued);
                requireRejected(running);
                require(get(stage) == 11, "closed delivery lost the result");
            } finally {
                blocking.countDown();
            }
        }
        System.out.println("Isolated result delivery checks passed.");
    }

    private static <T> T get(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void requireRejected(CompletionStage<?> stage) throws Exception {
        try {
            get(stage);
            throw new AssertionError("continuation did not report delivery rejection");
        } catch (ExecutionException error) {
            require(error.getCause() instanceof RejectedExecutionException, "unexpected delivery error");
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
