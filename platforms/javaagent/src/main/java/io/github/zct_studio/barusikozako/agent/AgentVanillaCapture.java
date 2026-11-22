// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import io.github.zct_studio.barusikozako.agent.instrument.AgentCaptureSignals;
import io.github.zct_studio.barusikozako.agent.instrument.AgentHooks;
import io.github.zct_studio.barusikozako.agent.instrument.AgentWriteSignals;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.StagedDirectoryView;
import io.github.zct_studio.barusikozako.platform.CaptureSource;
import io.github.zct_studio.barusikozako.platform.ReadView;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 捕获字节码审核的 Vanilla 专用服务器的协调 */
final class AgentVanillaCapture implements CaptureSource {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako Agent capture");
    private static final List<String> REQUIRED = List.of("server/MinecraftServer", "server/level/ChunkMap",
        "world/level/chunk/storage/IOWorker", "world/level/storage/SavedDataStorage", "util/Util",
        "world/level/storage/PlayerDataStorage", "world/level/storage/LevelStorageSource$LevelStorageAccess",
        "stats/ServerStatsCounter", "server/PlayerAdvancements");
    private static final List<String> REQUIRED_12111 = List.of("net.minecraft.server.MinecraftServer",
        "awj", "ert", "fnc", "bhs", "fnl", "fni$c", "bdg", "anb");
    private final AtomicReference<Request> active = new AtomicReference<>();
    private final Path worlds;
    private final StagedDirectoryView.CopyBudget budget;
    private volatile boolean running;
    private volatile boolean stopped;

    AgentVanillaCapture(Path home, BackupEngine.Limits limits) {
        worlds = home.resolve("worlds");
        budget = new StagedDirectoryView.CopyBudget(limits.contentBytes(), limits.entries(), 128);
    }

    @Override
    public boolean available() {
        return running && !stopped && active.get() == null && AgentWriteSignals.failures() == 0;
    }

    void onTick(Object server) {
        running = true;
        var request = active.get();
        if (request == null || request.plan.isDone()) return;
        try {
            check(request);
            boolean mapped = server.getClass().getName().equals("ary");
            if (!(boolean) call(server, mapped ? "al" : "isReady") ||
                    !(boolean) call(server, mapped ? "z" : "isRunning") ||
                    (boolean) call(server, mapped ? "bh" : "isCurrentlySaving")) return;
            if (!request.claimed.compareAndSet(false, true)) return;
            try {
                request.plan.complete(prepare(server, mapped));
            } finally {
                request.gameReleased.complete(null);
            }
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError failure) {
            request.plan.completeExceptionally(failure);
        }
    }

    void stopping() {
        stopped = true;
    }

    @Override
    public ReadView acquire(BooleanSupplier mayContinue) throws IOException {
        var request = new Request(mayContinue);
        if (!available() || !active.compareAndSet(null, request)) throw new IOException("Agent capture is unavailable");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            Plan plan = waitPlan(request, deadline);
            waitDrain(request, plan, deadline);
            check(request);
            LOG.log(System.Logger.Level.INFO, "Agent capture drain confirmed at cut {0}, tick {1}, save {2} ms",
                plan.cut, plan.tick, plan.saveMillis);
            var stage = StagedDirectoryView.copyLive(worlds.resolve("world"), worlds.getParent(), budget,
                () -> check(request), (file, target, size) -> false, 0, 2, 10);
            try {
                check(request);
                return stage;
            } catch (IOException failure) {
                try { stage.close(); }
                catch (IOException closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        } finally {
            // 已执行的游戏线程保存必须先完成，之后的请求才能获取此插槽
            request.gameReleased.whenComplete((ignored, failure) -> active.compareAndSet(request, null));
            if (request.claimed.compareAndSet(false, true)) request.gameReleased.complete(null);
        }
    }

    private Plan prepare(Object server, boolean mapped) throws ReflectiveOperationException, IOException {
        long start = System.nanoTime();
        var type = server.getClass();
        if (!mapped && !type.getName().equals("net.minecraft.server.dedicated.DedicatedServer"))
            throw new IOException("Unsupported game server class: " + type.getName());
        var loader = type.getClassLoader();
        var required = mapped ? REQUIRED_12111 : REQUIRED.stream().map(name -> "net.minecraft." + name.replace('/', '.')).toList();
        for (String name : required) {
            var defined = Class.forName(name, false, loader);
            if (defined.getClassLoader() != loader || !AgentHooks.observed(loader, name.replace('.', '/')))
                throw new IOException("Missing audited save observation: " + name);
        }
        var resource = Class.forName(mapped ? "fng" : "net.minecraft.world.level.storage.LevelResource", false, loader);
        var world = ((Path) type.getMethod(mapped ? "a" : "getWorldPath", resource)
            .invoke(server, resource.getField(mapped ? "l" : "ROOT").get(null))).toRealPath();
        if (!world.equals(worlds.resolve("world")) || !worlds.toRealPath().equals(worlds))
            throw new IOException("The server is not using the managed world container");

        var levels = new ArrayList<>();
        for (Object level : (Iterable<?>) call(server, mapped ? "P" : "getAllLevels")) {
            if ((boolean) call(level, mapped ? "z" : "noSave")) throw new IOException("World saving is disabled");
            levels.add(level);
        }
        if (levels.isEmpty()) throw new IOException("No loaded levels");
        if (!(boolean) type.getMethod(mapped ? "b" : "saveEverything", boolean.class, boolean.class, boolean.class)
                .invoke(server, true, false, false))
            throw new IOException("Minecraft did not submit the world save");

        var barriers = new ArrayList<CompletableFuture<?>>();
        // 1.21.11 saves shared data through the overworld's DimensionDataStorage.
        if (!mapped) barriers.add(pendingSavedData(server, false));
        for (Object level : levels) {
            var poi = call(level, mapped ? "F" : "getPoiManager");
            Object poiStorage;
            if (mapped) {
                var sectionStorage = Class.forName("esb", false, loader);
                sectionStorage.getMethod("a").invoke(poi);
                var storage = sectionStorage.getDeclaredField("d");
                storage.setAccessible(true);
                poiStorage = storage.get(poi);
            } else {
                call(poi, "flushAll");
                poiStorage = field(poi, "simpleRegionStorage");
            }
            var chunks = call(level, mapped ? "p" : "getChunkSource");
            barriers.add(synchronize(chunks.getClass().getField(mapped ? "a" : "chunkMap").get(chunks), mapped));
            barriers.add(synchronize(poiStorage, mapped));
            var entities = field(field(level, mapped ? "M" : "entityManager"), mapped ? "d" : "permanentStorage");
            barriers.add(synchronize(field(entities, mapped ? "e" : "simpleRegionStorage"), mapped));
            barriers.add(pendingSavedData(level, mapped));
        }
        requireNoWriteFailure();
        return new Plan(AgentCaptureSignals.cut(), (int) call(server, mapped ? "am" : "getTickCount"),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), CompletableFuture.allOf(barriers.toArray(CompletableFuture[]::new)));
    }

    private static CompletableFuture<?> synchronize(Object storage, boolean mapped) throws ReflectiveOperationException {
        return (CompletableFuture<?>) storage.getClass().getMethod(mapped ? "b" : "synchronize", boolean.class).invoke(storage, false);
    }

    private static CompletableFuture<?> pendingSavedData(Object holder, boolean mapped) throws ReflectiveOperationException {
        return (CompletableFuture<?>) field(call(holder, mapped ? "A" : "getDataStorage"), mapped ? "f" : "pendingWriteFuture");
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            java.lang.reflect.Field field;
            try { field = type.getDeclaredField(name); }
            catch (NoSuchFieldException absent) { continue; }
            field.setAccessible(true);
            return field.get(target);
        }
        throw new NoSuchFieldException(name);
    }

    private Plan waitPlan(Request request, long deadline) throws IOException {
        for (;;) {
            checkDeadline(request, deadline);
            try { return request.plan.get(25, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { /* The game thread has not reached its audited edge. */ }
            catch (InterruptedException interrupted) { throw interrupted(interrupted); }
            catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof InvocationTargetException reflected) cause = reflected.getCause();
                throw new IOException("Agent save preparation failed", cause);
            }
        }
    }

    private void waitDrain(Request request, Plan plan, long deadline) throws IOException {
        for (;;) {
            checkDeadline(request, deadline);
            var state = AgentCaptureSignals.stateForAuditedHooks(plan.cut);
            if (state == AgentCaptureSignals.State.FAILED || state == AgentCaptureSignals.State.UNKNOWN)
                throw new IOException("Save queue could not confirm completion: " + state);
            try {
                plan.barriers.get(25, TimeUnit.MILLISECONDS);
                if (state == AgentCaptureSignals.State.QUIESCENT) return;
                Thread.sleep(15);
            } catch (TimeoutException waiting) { /* Observe the same cut on the next poll. */ }
            catch (InterruptedException interrupted) { throw interrupted(interrupted); }
            catch (ExecutionException failure) { throw new IOException("Storage barrier failed", failure.getCause()); }
        }
    }

    private void checkDeadline(Request request, long deadline) throws IOException {
        check(request);
        if (System.nanoTime() - deadline >= 0) throw new IOException("Agent save preparation timed out");
    }

    private void check(Request request) throws IOException {
        checkLive(request.mayContinue);
    }

    void checkLive(BooleanSupplier mayContinue) throws IOException {
        if (stopped || !mayContinue.getAsBoolean())
            throw new InterruptedIOException("Agent capture cancelled or server stopping");
        requireNoWriteFailure();
    }

    private static void requireNoWriteFailure() throws IOException {
        if (AgentWriteSignals.failures() != 0)
            throw new IOException("Unresolved game write failure: " + AgentWriteSignals.firstFailure());
    }

    private static InterruptedIOException interrupted(InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        var failure = new InterruptedIOException("Agent capture was interrupted");
        failure.initCause(interrupted);
        return failure;
    }

    private record Plan(long cut, int tick, long saveMillis, CompletableFuture<?> barriers) {}
    private record Request(BooleanSupplier mayContinue, CompletableFuture<Plan> plan, AtomicBoolean claimed,
                           CompletableFuture<Void> gameReleased) {
        Request(BooleanSupplier mayContinue) {
            this(mayContinue, new CompletableFuture<>(), new AtomicBoolean(), new CompletableFuture<>());
        }
    }
}
