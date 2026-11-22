// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit;

import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.StagedDirectoryView;
import io.github.zct_studio.barusikozako.platform.CaptureSource;
import io.github.zct_studio.barusikozako.platform.ReadView;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Bukkit 尽力捕获源
 *
 * <p>When {@code capture.enabled=true} in {@code config/barusikozako/server.yml}, this source issues
 * {@code save-all} (or {@code save-all flush} on Paper/Purpur) and then performs a live file copy
 * of the world directory using {@link StagedDirectoryView#copyLive}. It does <strong>not</strong>
 * wait for asynchronous chunk/entity/POI/saved-data writes to complete, and does <strong>not</strong>
 * verify save outcomes. Cross-file consistency is <strong>not guaranteed</strong>.
 *
 * <p>This source is only available when {@code capture.enabled=true} in {@code config/barusikozako/server.yml}.
 * The default is {@code false} to preserve the safe default behavior.
 */
public final class BukkitBestEffortCapture implements CaptureSource, AutoCloseable {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako.BukkitBestEffort");

    private final JavaPlugin plugin;
    private final BukkitConfig config;
    private final Path worldDir;
    private final Path stagingParent;
    private final ExecutorService diskExecutor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public BukkitBestEffortCapture(JavaPlugin plugin, BukkitConfig config, Path worldDir, Path stagingParent) throws IOException {
        this.plugin = plugin;
        this.config = config;
        this.worldDir = worldDir.toRealPath();
        this.stagingParent = stagingParent.toRealPath();
        this.diskExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BaruSikoZako-Bukkit-Capture");
            t.setDaemon(true);
            return t;
        });
        StagedDirectoryView.cleanupAbandoned(stagingParent);
        LOG.log(System.Logger.Level.INFO, "Best-effort capture initialized: world={0}, staging={1}", worldDir, stagingParent);
    }

    @Override
    public boolean available() {
        return config.captureEnabled && !stopped.get() && !worldDir.toFile().isDirectory() == false;
    }

    @Override
    public ReadView acquire(BooleanSupplier mayContinue) throws IOException {
        if (!available()) throw new IOException("Best-effort capture unavailable or stopped");
        if (!mayContinue.getAsBoolean()) throw new IOException("Capture cancelled before start");

        // 1) Issue save-all on the main thread and wait for synchronous completion
        LOG.log(System.Logger.Level.INFO, "Best-effort capture: issuing save-all");
        CompletableFuture<Void> saveFuture = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                CommandSender console = Bukkit.getServer().getConsoleSender();
                // Paper/Purpur 支持等待 RegionFileIOThread 的“保存全部刷新”
                // CraftBukkit/Spigot 仅支持“save-all”，它在主线程工作后返回
                plugin.getServer().dispatchCommand(console, "save-all");
                saveFuture.complete(null);
            } catch (Exception e) {
                saveFuture.completeExceptionally(e);
            }
        });

        try {
            long start = System.nanoTime();
            saveFuture.get(config.preparationMillis, TimeUnit.MILLISECONDS);
            long prepElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            if (config.mainThreadBudgetMillis > 0 && prepElapsed > config.mainThreadBudgetMillis) {
                throw new IOException("Save preparation took " + prepElapsed + " ms on the game thread (budget: " +
                        config.mainThreadBudgetMillis + " ms)");
            }
        } catch (CompletionException e) {
            throw new IOException("Save command failed", e.getCause());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new InterruptedIOException("Save preparation cancelled or timed out: " + e.getMessage());
        }

        // 2) Live copy the world directory - source remains writable
        LOG.log(System.Logger.Level.INFO, "Best-effort capture: starting live copy");
        Path stagingRoot = stagingParent.toRealPath();
        var budget = new StagedDirectoryView.CopyBudget(config.readLimits.contentBytes(),
                config.readLimits.entries(), 128); // depth 128

        // 使用尊重取消的检查点
        BackupEngine.Checkpoint checkpoint = () -> {
            if (!mayContinue.getAsBoolean() || stopped.get())
                throw new IOException("Capture cancelled during copy");
        };

        // 检测文件克隆功能（Linux XFS reflink）
        StagedDirectoryView.FileClone cloner = (src, target, len) -> false; // default to Java copy
        // TODO: Add LinuxReflink probe similar to Fabric if needed

        return StagedDirectoryView.copyLive(worldDir, stagingParent, budget, () -> {
            if (!mayContinue.getAsBoolean() || stopped.get())
                throw new IOException("Capture cancelled during copy");
        }, cloner, config.backupReadBytesPerSecond, config.fileRetries, config.fileRetryDelayMillis);
    }

    @Override
    public void close() {
        stopped.set(true);
        diskExecutor.shutdown();
        try {
            if (!diskExecutor.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "Capture disk executor did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}