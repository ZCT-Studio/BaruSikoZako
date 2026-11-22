// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent.instrument;

import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongFunction;

/** 对已审计的遗留卸载和存储路径进行引导跟踪 */
public final class AgentCaptureSignals {
    private static final int MAX_PENDING = 65_536;
    private static final Object LOCK = new Object();
    private static final TreeMap<Long, Integer> ACTIVE = new TreeMap<>();
    private static final ThreadLocal<Long> CAUSE = new ThreadLocal<>();
    private static long sequence;
    private static int pending;
    private static int observed; // Two independent hook types must be observed before sampling is meaningful.
    private static long firstFailure = Long.MAX_VALUE;
    private static boolean incomplete;

    private AgentCaptureSignals() {}

    /** 注册发生时，原始制作人仍然可以创作儿童作品 */
    public static <T> CompletableFuture<Void> observeUnload(CompletableFuture<T> input, Consumer<T> callback,
                                                            Executor executor) {
        return observeProducer(cause -> input.thenAcceptAsync(value -> runInCause(cause,
            () -> callback.accept(value)), executor));
    }

    public static CompletableFuture<Void> observeUnload(CompletableFuture<?> input, Runnable callback, Executor executor) {
        return observeProducer(cause -> input.thenRunAsync(() -> runInCause(cause, callback), executor));
    }

    private static CompletableFuture<Void> observeProducer(LongFunction<CompletableFuture<Void>> original) {
        long cause = begin(1);
        if (cause == 0) return original.apply(0);
        CompletableFuture<Void> stage;
        try {
            stage = original.apply(cause);
        } catch (RuntimeException | Error failure) {
            end(cause, failure);
            throw failure;
        }
        stage.whenComplete((ignored, failure) -> end(cause, failure));
        return stage;
    }

    private static void runInCause(long cause, Runnable callback) {
        if (cause == 0) {
            callback.run();
            return;
        }
        Long previous = CAUSE.get();
        CAUSE.set(cause);
        try {
            callback.run();
        } finally {
            if (previous == null) CAUSE.remove();
            else CAUSE.set(previous);
        }
    }

    /** 遵守最初的完成情况，而不交换或完成 Minecraft 的未来 */
    public static void observeStore(CompletableFuture<?> stage) {
        long cause = begin(2);
        if (cause != 0) stage.whenComplete((ignored, failure) -> end(cause, failure));
    }

    private static long begin(int kind) {
        synchronized (LOCK) {
            observed |= kind;
            if (incomplete || pending >= MAX_PENDING || sequence == Long.MAX_VALUE - 1) {
                incomplete = true;
                return 0;
            }
            Long parent = CAUSE.get();
            long cause = parent == null ? ++sequence : parent;
            ACTIVE.merge(cause, 1, Integer::sum);
            pending++;
            return cause;
        }
    }

    private static void end(long cause, Throwable failure) {
        synchronized (LOCK) {
            if (failure != null) firstFailure = Math.min(firstFailure, cause);
            ACTIVE.computeIfPresent(cause, (id, count) -> count == 1 ? null : count - 1);
            pending--;
        }
    }

    public static long cut() {
        synchronized (LOCK) {
            return sequence;
        }
    }

    /** 仅报告此钩子集。静态结果并不能证明保存域未经检测 */
    public static State state(long cut) {
        return state(cut, false);
    }

    /** 托管适配器已独立确认所需的类转换 */
    public static State stateForAuditedHooks(long cut) {
        return state(cut, true);
    }

    private static State state(long cut, boolean audited) {
        synchronized (LOCK) {
            if (cut < 0 || cut > sequence || incomplete || (!audited && observed != 3)) return State.UNKNOWN;
            if (firstFailure <= cut) return State.FAILED;
            if (!ACTIVE.isEmpty() && ACTIVE.firstKey() <= cut) return State.PENDING;
            return State.QUIESCENT;
        }
    }

    public enum State { UNKNOWN, PENDING, FAILED, QUIESCENT }
}
