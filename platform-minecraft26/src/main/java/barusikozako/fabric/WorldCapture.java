// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.core.StagedDirectoryView;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Fabric 26.3 best-effort capture. The world is copied outside the game thread. */
final class WorldCapture implements barusikozako.platform.CaptureSource, AutoCloseable {
    enum Edge { TICK_START, TICK_END, EMPTY_PAUSE }

    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private static FileLock stageLifetime;
    private final Path world;
    private final Path stagingParent;
    private final ServerConfig config;
    private final LinuxReflink reflink;
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private final AtomicBoolean diskOwned = new AtomicBoolean();
    private final java.util.concurrent.ExecutorService disk = Executors.newSingleThreadExecutor(action -> {
        var worker = new Thread(action, "BaruSikoZako capture");
        worker.setDaemon(true);
        return worker;
    });
    private volatile boolean stopped;

    WorldCapture(Path world, Path stagingParent, ServerConfig config) throws IOException {
        this.world = world;
        this.stagingParent = stagingParent;
        this.config = config;
        ownStagingParent(stagingParent);
        LinuxReflink strategy = null;
        if (config.fileCloneAuto()) {
            try {
                strategy = LinuxReflink.probe(world, stagingParent);
            } catch (IOException | RuntimeException | LinkageError failure) {
                LOG.log(System.Logger.Level.WARNING, "File clone probe failed. Using bounded Java staging.", failure);
            }
        }
        reflink = strategy;
        LOG.log(System.Logger.Level.INFO, "Capture strategy: {0}", reflink == null ? "Java copy" : "XFS file clone with Java fallback");
    }

    private static synchronized void ownStagingParent(Path parent) throws IOException {
        if (stageLifetime != null) throw new IOException("Repeated use of dedicated capture staging");
        var file = FileChannel.open(parent.resolve(".capture.lock"), StandardOpenOption.WRITE,
            StandardOpenOption.CREATE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = file.tryLock();
            if (lock == null) throw new IOException("Capture staging is already in use");
            StagedDirectoryView.cleanupAbandoned(parent);
            stageLifetime = lock;
        } catch (IOException | RuntimeException failure) {
            try {
                file.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    @Override
    public boolean available() {
        return !stopped && !diskOwned.get() && WorldWrites.unresolvedFailure() == null;
    }

    @Override
    public StagedDirectoryView acquire(BooleanSupplier authorized) throws IOException {
        var request = new Request(authorized, TimeUnit.MILLISECONDS.toNanos(config.preparationMillis()));
        if (!available() || !pending.compareAndSet(null, request)) throw new IOException("Capture is occupied or unavailable");
        try {
            while (true) {
                if (!request.mayContinue() || stopped)
                    request.fail("Capture timed out or was cancelled: " + request.reason);
                try {
                    return request.result.get(40, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waitingForGame) {
                    // Cancellation and expiration remain observable even when empty-server ticks are paused.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!request.fail("Capture worker was interrupted") && !request.result.isCompletedExceptionally())
                closeView(request.result.getNow(null));
            throw new InterruptedIOException("Capture worker was interrupted");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            throw new IOException("Capture failed", failure.getCause());
        } finally {
            pending.compareAndSet(request, null);
        }
    }

    void tick(MinecraftServer server, Edge edge) {
        var request = pending.get();
        if (request == null || request.result.isDone()) return;
        try {
            if (!request.mayContinue() || stopped || !server.isRunning()) {
                request.fail("Capture expired, cancelled or server stopping");
                return;
            }
            String unresolved = WorldWrites.unresolvedFailure();
            if (unresolved != null) {
                request.fail("Unresolved world save failure: " + unresolved);
                return;
            }
            if (server.isCurrentlySaving() || !server.isSameThread()) return;
            if (request.copying) return;
            var levels = levels(server);
            if (levels.isEmpty() || levels.stream().anyMatch(ServerLevel::noSave)) {
                request.fail("World saving is disabled or no loaded worlds are available");
                return;
            }
            if (request.fence != null) {
                if (edge == Edge.TICK_START && request.fence.preparedWhilePaused) return;
                if (!request.fence.ready.isDone()) return;
                request.fence.ready.join();
                startCopy(request, edge, server.getTickCount());
                return;
            }
            if (edge != Edge.TICK_START) prepare(server, levels, request, edge);
        } catch (RuntimeException | LinkageError failed) {
            request.result.completeExceptionally(new IOException("Fabric save preparation could not be confirmed", failed));
        }
    }

    private void prepare(MinecraftServer server, List<ServerLevel> levels, Request request, Edge edge) {
        long started = System.nanoTime();
        if (!server.saveEverything(true, false, false)) {
            request.fail("Vanilla did not save the loaded levels");
            return;
        }
        CaptureDomains.flushPoi(server);
        var barriers = new ArrayList<CompletableFuture<?>>();
        SavedDataBarriers.add(server, levels, barriers);
        for (var level : levels) {
            barriers.add(ChunkBarrier.pending(level));
            barriers.add(((barusikozako.fabric.mixin.PoiStorageAccess) level.getPoiManager())
                .barusikozako$Storage().synchronize(false));
        }
        CaptureDomains.addEntityBarriers(server, barriers);
        long elapsed = System.nanoTime() - started;
        int budget = config.mainThreadBudgetMillis();
        if (budget > 0 && elapsed > TimeUnit.MILLISECONDS.toNanos(budget)) {
            request.fail("World save preparation took " + millis(elapsed) + " ms on the game thread (budget: " +
                budget + " ms)");
            return;
        }
        request.fence = new Fence(CompletableFuture.allOf(barriers.toArray(CompletableFuture[]::new)),
            elapsed, edge == Edge.EMPTY_PAUSE);
    }

    private void startCopy(Request request, Edge edge, int worldTick) {
        if (!diskOwned.compareAndSet(false, true)) {
            request.fail("Previous capture still owns disk resources");
            return;
        }
        request.copying = true;
        var fence = request.fence;
        try {
            disk.execute(() -> {
                StagedDirectoryView view = null;
                long start = System.nanoTime();
                long[] cloned = {0};
                try {
                    LOG.log(System.Logger.Level.INFO, "Best-effort background capture started at world tick {0}", worldTick);
                    var limits = config.readLimits();
                    view = StagedDirectoryView.copyLive(world, stagingParent,
                        new StagedDirectoryView.CopyBudget(limits.contentBytes(), limits.entries(), 128), () -> {
                            if (request.result.isDone() || !request.mayContinue() || stopped)
                                throw new InterruptedIOException("World capture was cancelled or server stopped");
                            if (WorldWrites.unresolvedFailure() != null)
                                throw new IOException("Unresolved world save failure: " + WorldWrites.unresolvedFailure());
                        }, (file, target, size) -> {
                            if (reflink == null || !reflink.tryClone(file, target, size)) return false;
                            cloned[0] += size;
                            return true;
                        }, config.backupReadBytesPerSecond(), config.fileRetries(), config.fileRetryDelayMillis());
                    if (!request.mayContinue() || stopped || WorldWrites.unresolvedFailure() != null)
                        throw new InterruptedIOException("Capture was cancelled, stopped or observed a save failure");
                    if (request.result.complete(view)) {
                        view = null;
                        LOG.log(System.Logger.Level.INFO,
                            "Best-effort capture prepared: save {0} ms, background copy {1} ms, edge {2}, world tick {3}, cloned bytes {4}",
                            millis(fence.saveNanos), millis(System.nanoTime() - start), edge, worldTick, cloned[0]);
                    }
                } catch (IOException | RuntimeException | LinkageError failure) {
                    request.result.completeExceptionally(failure);
                } finally {
                    closeView(view);
                    diskOwned.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            diskOwned.set(false);
            request.fail("Capture disk worker is stopping");
        }
    }

    private static void closeView(StagedDirectoryView view) {
        if (view == null) return;
        try {
            view.close();
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.ERROR, "Could not remove a capture stage", failure);
        }
    }

    @Override
    public void close() {
        stopped = true;
        var request = pending.get();
        if (request != null) request.fail("Server stopped before the capture completed");
        disk.shutdown();
    }

    void awaitCleanup() {
        try {
            if (!disk.awaitTermination(250, TimeUnit.MILLISECONDS))
                LOG.log(System.Logger.Level.WARNING, "A capture still owns staging. Cleanup will resume at startup.");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<ServerLevel> levels(MinecraftServer server) {
        var levels = new ArrayList<ServerLevel>();
        server.getAllLevels().forEach(levels::add);
        return levels;
    }

    private static long millis(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }

    private static final class Request {
        final long started = System.nanoTime();
        final CompletableFuture<StagedDirectoryView> result = new CompletableFuture<>();
        final BooleanSupplier authorized;
        final long budget;
        volatile String reason = "waiting for a server capture boundary";
        Fence fence;
        volatile boolean copying;

        Request(BooleanSupplier authorized, long budget) {
            this.authorized = authorized;
            this.budget = budget;
        }

        boolean mayContinue() {
            return (copying || System.nanoTime() - started < budget) && authorized.getAsBoolean();
        }

        boolean fail(String message) {
            return result.completeExceptionally(new IOException(message));
        }
    }

    private record Fence(CompletableFuture<Void> ready, long saveNanos, boolean preparedWhilePaused) {}

}
