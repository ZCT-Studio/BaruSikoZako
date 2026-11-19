// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.apihost;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Read-only result with no externally visible access to the future that producers complete. */
final class ResultStage<T> implements CompletionStage<T> {
    private final CompletableFuture<T> source;
    private final ResultDelivery delivery;

    ResultStage(CompletableFuture<T> source, ResultDelivery delivery) {
        this.source = source;
        this.delivery = delivery;
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
        return delivery.copy(source);
    }

    @SuppressWarnings("unchecked")
    private static <U> CompletableFuture<? extends U> unwrap(CompletionStage<? extends U> other) {
        if (other instanceof ResultStage<?> local) return (CompletableFuture<? extends U>) local.source;
        return other.toCompletableFuture();
    }

    private CompletableFuture<Void> eitherSignal(CompletionStage<?> other) {
        return source.thenApply(value -> (Void) null).applyToEither(
            unwrap(other).thenApply(value -> (Void) null), Function.identity());
    }

    private static <T> T complete(BiConsumer<? super T, ? super Throwable> action, T value, Throwable failure) {
        if (failure == null) {
            action.accept(value, null);
            return value;
        }
        try {
            action.accept(value, failure);
        } catch (Throwable callbackFailure) {
            // The source remains authoritative and must not be mutated by any observer.
            var combined = new CompletionException(failure);
            if (failure != callbackFailure) combined.addSuppressed(callbackFailure);
            throw combined;
        }
        throw failure instanceof CompletionException existing ? existing : new CompletionException(failure);
    }

    @Override
    public <U> CompletionStage<U> thenApply(Function<? super T, ? extends U> action) {
        return thenApplyOn(action, null);
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> action) {
        return thenApplyOn(action, null);
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> action, Executor executor) {
        return thenApplyOn(action, Objects.requireNonNull(executor));
    }

    private <U> CompletionStage<U> thenApplyOn(Function<? super T, ? extends U> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.thenCompose(value -> delivery.call(() -> action.apply(value), executor)));
    }

    @Override
    public CompletionStage<Void> thenAccept(Consumer<? super T> action) {
        return thenAcceptOn(action, null);
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) {
        return thenAcceptOn(action, null);
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return thenAcceptOn(action, Objects.requireNonNull(executor));
    }

    private CompletionStage<Void> thenAcceptOn(Consumer<? super T> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.thenCompose(value -> delivery.call(() -> { action.accept(value); return null; }, executor)));
    }

    @Override
    public CompletionStage<Void> thenRun(Runnable action) {
        return thenRunOn(action, null);
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action) {
        return thenRunOn(action, null);
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) {
        return thenRunOn(action, Objects.requireNonNull(executor));
    }

    private CompletionStage<Void> thenRunOn(Runnable action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.thenCompose(value -> delivery.call(() -> { action.run(); return null; }, executor)));
    }

    @Override
    public <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> action) {
        return thenCombineOn(other, action, null);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> action) {
        return thenCombineOn(other, action, null);
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> action, Executor executor) {
        return thenCombineOn(other, action, Objects.requireNonNull(executor));
    }

    private <U, V> CompletionStage<V> thenCombineOn(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.thenCombine(unwrap(other),
            (left, right) -> delivery.<V>call(() -> action.apply(left, right), executor)).thenCompose(Function.identity()));
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return thenAcceptBothOn(other, action, null);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return thenAcceptBothOn(other, action, null);
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
        return thenAcceptBothOn(other, action, Objects.requireNonNull(executor));
    }

    private <U> CompletionStage<Void> thenAcceptBothOn(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.thenCombine(unwrap(other), (left, right) ->
            delivery.<Void>call(() -> { action.accept(left, right); return null; }, executor)).thenCompose(Function.identity()));
    }

    @Override
    public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return runAfterBothOn(other, action, null);
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return runAfterBothOn(other, action, null);
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return runAfterBothOn(other, action, Objects.requireNonNull(executor));
    }

    private CompletionStage<Void> runAfterBothOn(CompletionStage<?> other, Runnable action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.thenCombine(unwrap(other), (left, right) ->
            delivery.<Void>call(() -> { action.run(); return null; }, executor)).thenCompose(Function.identity()));
    }

    @Override
    public <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> action) {
        return applyToEitherOn(other, action, null);
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> action) {
        return applyToEitherOn(other, action, null);
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> action, Executor executor) {
        return applyToEitherOn(other, action, Objects.requireNonNull(executor));
    }

    private <U> CompletionStage<U> applyToEitherOn(CompletionStage<? extends T> other, Function<? super T, U> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.applyToEither(unwrap(other),
            value -> delivery.call(() -> action.apply(value), executor)).thenCompose(Function.identity()));
    }

    @Override
    public CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return acceptEitherOn(other, action, null);
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return acceptEitherOn(other, action, null);
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        return acceptEitherOn(other, action, Objects.requireNonNull(executor));
    }

    private CompletionStage<Void> acceptEitherOn(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.applyToEither(unwrap(other), value ->
            delivery.<Void>call(() -> { action.accept(value); return null; }, executor)).thenCompose(Function.identity()));
    }

    @Override
    public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return runAfterEitherOn(other, action, null);
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return runAfterEitherOn(other, action, null);
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return runAfterEitherOn(other, action, Objects.requireNonNull(executor));
    }

    private CompletionStage<Void> runAfterEitherOn(CompletionStage<?> other, Runnable action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> eitherSignal(other).thenCompose(value -> delivery.call(() -> { action.run(); return null; }, executor)));
    }

    @Override
    public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> action) {
        return thenComposeOn(action, null);
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> action) {
        return thenComposeOn(action, null);
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> action, Executor executor) {
        return thenComposeOn(action, Objects.requireNonNull(executor));
    }

    private <U> CompletionStage<U> thenComposeOn(Function<? super T, ? extends CompletionStage<U>> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.thenCompose(value -> delivery.call(() -> unwrap(action.apply(value)), executor))
            .thenCompose(stage -> stage.thenApply(Function.identity())));
    }

    @Override
    public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> action) {
        return handleOn(action, null);
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> action) {
        return handleOn(action, null);
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> action, Executor executor) {
        return handleOn(action, Objects.requireNonNull(executor));
    }

    private <U> CompletionStage<U> handleOn(BiFunction<? super T, Throwable, ? extends U> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.handle((value, failure) ->
            delivery.<U>call(() -> action.apply(value, failure), executor)).thenCompose(Function.identity()));
    }

    @Override
    public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return whenCompleteOn(action, null);
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return whenCompleteOn(action, null);
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        return whenCompleteOn(action, Objects.requireNonNull(executor));
    }

    private CompletionStage<T> whenCompleteOn(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.handle((value, failure) -> delivery.call(() -> complete(action, value, failure), executor))
            .thenCompose(Function.identity()));
    }

    @Override
    public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> action) {
        return exceptionallyOn(action, null);
    }

    @Override
    public CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> action) {
        return exceptionallyOn(action, null);
    }

    @Override
    public CompletionStage<T> exceptionallyAsync(Function<Throwable, ? extends T> action, Executor executor) {
        return exceptionallyOn(action, Objects.requireNonNull(executor));
    }

    private CompletionStage<T> exceptionallyOn(Function<Throwable, ? extends T> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.exceptionallyCompose(failure -> delivery.call(() -> action.apply(failure), executor)));
    }

    @Override
    public CompletionStage<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> action) {
        return exceptionallyComposeOn(action, null);
    }

    @Override
    public CompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> action) {
        return exceptionallyComposeOn(action, null);
    }

    @Override
    public CompletionStage<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> action, Executor executor) {
        return exceptionallyComposeOn(action, Objects.requireNonNull(executor));
    }

    private CompletionStage<T> exceptionallyComposeOn(Function<Throwable, ? extends CompletionStage<T>> action, Executor executor) {
        Objects.requireNonNull(action);
        return delivery.link(() -> source.exceptionallyCompose(failure -> delivery.call(() -> unwrap(action.apply(failure)), executor)
            .thenCompose(stage -> stage.thenApply(Function.identity()))));
    }

}
