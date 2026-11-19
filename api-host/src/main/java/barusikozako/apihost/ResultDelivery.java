// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.apihost;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Single-result continuations have their own bounded execution and registration budgets. */
final class ResultDelivery implements AutoCloseable {
    private final ThreadPoolExecutor callbacks;
    private final ThreadPoolExecutor detachedCopies;
    private final Semaphore registrations;
    private final java.util.Set<Continuation<?>> outstanding = ConcurrentHashMap.newKeySet();

    ResultDelivery(int workers, int queueCapacity, int pendingContinuations, int pendingCopies) {
        callbacks = pool(workers, new ArrayBlockingQueue<>(queueCapacity), "BaruSikoZako results");
        // A native detached future may run user callbacks inline through JDK combinators. Each
        // outstanding copy therefore completes on its own bounded worker, never on a task thread.
        detachedCopies = pool(pendingCopies, new SynchronousQueue<>(), "BaruSikoZako result copy");
        registrations = new Semaphore(pendingContinuations);
    }

    private static ThreadPoolExecutor pool(int workers, BlockingQueue<Runnable> queue, String name) {
        var pool = new ThreadPoolExecutor(workers, workers, 15, TimeUnit.SECONDS, queue, task -> {
            var worker = new Thread(task, name);
            worker.setDaemon(true);
            return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    <T> ResultStage<T> view(CompletableFuture<T> source) {
        return new ResultStage<>(source, this);
    }

    <T> ResultStage<T> link(Supplier<CompletableFuture<T>> continuation) {
        if (!registrations.tryAcquire()) {
            return view(CompletableFuture.failedFuture(new RejectedExecutionException("Result continuation limit reached")));
        }
        CompletableFuture<T> stage;
        try {
            stage = continuation.get();
        } catch (RuntimeException | Error failure) {
            registrations.release();
            throw failure;
        }
        stage.whenComplete((value, failure) -> registrations.release());
        return view(stage);
    }

    <T> CompletableFuture<T> call(Supplier<T> action, Executor requested) {
        var job = new Continuation<>(action, requested);
        outstanding.add(job);
        job.result.whenComplete((value, failure) -> outstanding.remove(job));
        try {
            callbacks.execute(job);
        } catch (RejectedExecutionException rejected) {
            job.result.completeExceptionally(rejected);
        }
        return job.result;
    }

    <T> CompletableFuture<T> copy(CompletableFuture<T> source) {
        if (source.isDone()) return source.copy(); // No external code can have registered on this new copy.
        var detached = new CompletableFuture<T>();
        detachedCopies.execute(() -> {
            try {
                detached.complete(source.get());
            } catch (ExecutionException failure) {
                detached.completeExceptionally(failure.getCause());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                detached.completeExceptionally(new RejectedExecutionException("Result copy delivery stopped", failure));
            } catch (java.util.concurrent.CancellationException failure) {
                detached.completeExceptionally(failure);
            }
        });
        return detached;
    }

    @Override
    public void close() {
        callbacks.shutdownNow();
        for (var continuation : outstanding) {
            continuation.result.completeExceptionally(new RejectedExecutionException("Result delivery stopped"));
        }
        detachedCopies.shutdownNow();
    }

    private final class Continuation<T> implements Runnable {
        private final Supplier<T> action;
        private final Executor requested;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Continuation(Supplier<T> action, Executor requested) {
            this.action = action;
            this.requested = requested;
        }

        @Override
        public void run() {
            if (requested == null) {
                invoke();
                return;
            }
            try {
                requested.execute(this::invoke);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        private void invoke() {
            if (callbacks.isShutdown() || result.isDone()) {
                result.completeExceptionally(new RejectedExecutionException("Result delivery stopped"));
                return;
            }
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }
    }
}
