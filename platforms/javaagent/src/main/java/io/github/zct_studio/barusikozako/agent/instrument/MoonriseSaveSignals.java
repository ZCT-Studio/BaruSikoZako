// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent.instrument;

import java.util.TreeMap;
import java.util.HashSet;

/** 针对审核的 Moonrise 写入代进行有限诊断。不保留任何游戏对象或异常 */
public final class MoonriseSaveSignals {
    private static final int LIMIT = 65_536;
    private static final TreeMap<Long, Write> ACTIVE = new TreeMap<>();
    private static final TreeMap<Long, Integer> OUTSTANDING = new TreeMap<>();
    private static final TreeMap<Long, Long> PRODUCERS = new TreeMap<>();
    private static final HashSet<Long> FAILED_PRODUCERS = new HashSet<>();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static long sequence;
    private static long producersOpened;
    private static long producersClosed;
    private static long unloadsDetached;
    private static long unloadsResumed;
    private static long unloadsQueued;
    private static long unloadsRemoved;
    private static long created;
    private static long submitted;
    private static long payloads;
    private static long compressionScheduled;
    private static long compression;
    private static long writes;
    private static long completed;
    private static long superseded;
    private static long coalesced;
    private static long failures;
    private static long failedGeneration = Long.MAX_VALUE;
    private static boolean incomplete;
    private static String firstFailure;

    private MoonriseSaveSignals() {}

    public static synchronized long create() {
        long id = next();
        if (id == 0) return 0;
        long origin = origin(id);
        ACTIVE.put(id, new Write(origin));
        register(origin);
        created++;
        return id;
    }

    public static synchronized long openProducer() {
        long id = registerProducer();
        CURRENT.set(new Scope(id, CURRENT.get()));
        return id;
    }

    private static long registerProducer() {
        long id = next();
        if (id != 0) {
            long origin = origin(id);
            PRODUCERS.put(id, origin);
            register(origin);
            producersOpened++;
        }
        return id;
    }

    public static synchronized long queueUnload(long previous) {
        if (previous != 0) {
            incomplete = true;
            return previous;
        }
        unloadsQueued++;
        return registerProducer();
    }

    public static synchronized long beginQueuedUnload(long id) {
        if (!PRODUCERS.containsKey(id)) {
            incomplete = true;
            return openProducer();
        }
        CURRENT.set(new Scope(id, CURRENT.get()));
        return id;
    }

    public static synchronized void removeQueuedUnload(boolean removed, long id) {
        if (!removed) incomplete = true;
        unloadsRemoved++;
        closeProducer(id, null);
    }

    public static long currentProducer() {
        var scope = CURRENT.get();
        return scope == null ? 0 : scope.id;
    }

    public static synchronized long resumeUnload(long id) {
        if (!PRODUCERS.containsKey(id)) incomplete = true;
        unloadsResumed++;
        CURRENT.set(new Scope(id, CURRENT.get()));
        return id;
    }

    public static synchronized void suspendUnload(Object state, long id) {
        if (state == null) finishProducer(id, null);
        else {
            unloadsDetached++;
            leave(id);
        }
    }

    public static synchronized void finishProducer(long id, Throwable failure) {
        leave(id);
        closeProducer(id, failure);
    }

    private static void closeProducer(long id, Throwable failure) {
        var origin = PRODUCERS.remove(id);
        if (origin == null) {
            incomplete = true;
            return;
        }
        if (failure != null && !FAILED_PRODUCERS.contains(id)) {
            failures++;
            noteFailure(origin, "producer", failure);
        }
        FAILED_PRODUCERS.remove(id);
        retire(origin);
        producersClosed++;
    }

    public static synchronized void producerFailure(Throwable failure) {
        long id = currentProducer();
        var origin = PRODUCERS.get(id);
        if (origin == null) incomplete = true;
        else if (FAILED_PRODUCERS.add(id)) {
            failures++;
            noteFailure(origin, "producer", failure);
        }
    }

    private static void leave(long id) {
        var scope = CURRENT.get();
        if (scope == null || scope.id != id) {
            incomplete = true;
            return;
        }
        if (scope.parent == null) CURRENT.remove();
        else CURRENT.set(scope.parent);
    }

    private static long next() {
        if (sequence == Long.MAX_VALUE || ACTIVE.size() + PRODUCERS.size() >= LIMIT) {
            incomplete = true;
            return 0;
        }
        return ++sequence;
    }

    private static long origin(long id) {
        var scope = CURRENT.get();
        return scope == null ? id : PRODUCERS.getOrDefault(scope.id, id);
    }

    private static void register(long origin) {
        OUTSTANDING.merge(origin, 1, Integer::sum);
    }

    private static void retire(long origin) {
        OUTSTANDING.computeIfPresent(origin, (key, count) -> count == 1 ? null : count - 1);
    }

    public static synchronized void submitted(long id, long previous) {
        var write = lookup(id);
        if (write == null) return;
        submitted++;
        if (previous != 0 && previous != id) {
            var old = ACTIVE.get(previous);
            if (old != null) {
                superseded++;
                if (old.group == null) {
                    old.group = new Group();
                    old.group.members.add(previous);
                }
                write.group = old.group;
                write.group.members.add(id);
            }
        }
    }

    public static synchronized void payload(long id, Throwable failure) {
        var write = lookup(id);
        if (write == null) return;
        payloads++;
        write.payload = true;
        recordFailure(write, "payload", failure);
        if (write.skipped) retireCoalesced(id, write);
    }

    /** 在附加压缩回调之前调用，包括当有效负载已经准备好时调用 */
    public static synchronized void compressionScheduled(long id) {
        var write = lookup(id);
        if (write != null) {
            compressionScheduled++;
            write.scheduled = true;
            if (write.skipped) incomplete = true;
        }
    }

    public static synchronized void compress(long id) {
        if (lookup(id) != null) compression++;
    }

    public static synchronized void write(long id) {
        if (lookup(id) != null) writes++;
    }

    public static synchronized void failed(Throwable failure, long id, String stage) {
        var write = lookup(id);
        if (write != null) recordFailure(write, stage, failure);
    }

    public static synchronized void complete(boolean taskEnded, long id) {
        var write = lookup(id);
        if (write == null) return;
        ACTIVE.remove(id);
        retire(write.origin);
        completed++;
        if (write.group == null) return;
        write.group.members.remove(id);
        if (!taskEnded) return;
        // 真正的返回将退出该控制器任务。从未有过的老一辈
        // 压缩回调无法稍后写入，但其上游有效负载仍可能失败
        var members = write.group.members.iterator();
        while (members.hasNext()) {
            long previous = members.next();
            var remaining = ACTIVE.get(previous);
            if (remaining == null || remaining.scheduled) {
                incomplete = true;
                continue;
            }
            remaining.skipped = true;
            if (write.failed) failedGeneration = Math.min(failedGeneration, remaining.origin);
            if (remaining.payload) {
                ACTIVE.remove(previous);
                members.remove();
                retire(remaining.origin);
                coalesced++;
            }
        }
    }

    public static synchronized String snapshot() {
        return "created=" + created + " submitted=" + submitted + " payloads=" + payloads + " scheduled=" + compressionScheduled + " compression=" + compression +
            " writes=" + writes + " completed=" + completed + " superseded=" + superseded + " coalesced=" + coalesced +
            " failed=" + failures +
            " pending=" + ACTIVE.size() + " producersOpened=" + producersOpened + " producersClosed=" + producersClosed +
            " producersPending=" + PRODUCERS.size() + " unloadsDetached=" + unloadsDetached + " unloadsResumed=" + unloadsResumed +
            " unloadsQueued=" + unloadsQueued + " unloadsRemoved=" + unloadsRemoved +
            " incomplete=" + incomplete + " firstFailure=" + (firstFailure == null ? "-" : firstFailure);
    }

    public static synchronized long cut() {
        return sequence;
    }

    /** 涵盖经过审核的 Moonrise 制作人和编剧范围。其他保存域仍然在此结果之外 */
    public static synchronized String state(long cut) {
        if (cut < 0 || cut > sequence || incomplete) return "UNKNOWN";
        if (failedGeneration <= cut && failures != 0) return "FAILED";
        if (!OUTSTANDING.isEmpty() && OUTSTANDING.firstKey() <= cut) return "PENDING";
        return "DRAINED";
    }

    private static Write lookup(long id) {
        var write = ACTIVE.get(id);
        if (write == null) incomplete = true;
        return write;
    }

    private static void recordFailure(Write write, String stage, Throwable failure) {
        if (failure != null && !write.failed) {
            write.failed = true;
            failures++;
            noteFailure(write.origin, stage, failure);
        }
    }

    private static void noteFailure(long origin, String stage, Throwable failure) {
        failedGeneration = Math.min(failedGeneration, origin);
        if (firstFailure == null) firstFailure = stage + ":" + failure.getClass().getName();
    }

    private static void retireCoalesced(long id, Write write) {
        ACTIVE.remove(id);
        write.group.members.remove(id);
        retire(write.origin);
        coalesced++;
    }

    private static final class Write {
        final long origin;
        Group group;
        boolean payload;
        boolean scheduled;
        boolean skipped;
        boolean failed;

        Write(long origin) { this.origin = origin; }
    }

    private static final class Group {
        final HashSet<Long> members = new HashSet<>();
    }

    private record Scope(long id, Scope parent) {}
}
