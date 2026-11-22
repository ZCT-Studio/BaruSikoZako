// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit;

import io.github.zct_studio.barusikozako.api.BackupRequest;
import io.github.zct_studio.barusikozako.api.BackupService;
import io.github.zct_studio.barusikozako.api.BackupLifecycleHook;
import io.github.zct_studio.barusikozako.api.SnapshotRef;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.apihost.BackupApiHost;
import io.github.zct_studio.barusikozako.backend.BackendProvider;
import io.github.zct_studio.barusikozako.backend.ManagedRepository;
import io.github.zct_studio.barusikozako.backend.Repository;
import io.github.zct_studio.barusikozako.backend.Snapshot;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.BackupExclusions;
import io.github.zct_studio.barusikozako.platform.CaptureSource;
import io.github.zct_studio.barusikozako.platform.ReadView;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.function.BooleanSupplier;

/**
 * Bukkit 尽力备份插件
 *
 * <p>Provides best-effort online backup when {@code capture.enabled=true} in
 * {@code config/barusikozako/server.yml}. Does not guarantee cross-file consistency.
 * 恢复仅限离线（需要重新启动）
 */
public final class BukkitBestEffortPlugin extends JavaPlugin implements BackupApiHost.Operations, TabCompleter {
    public static AutoCloseable registerBackupExclusion(String scope, String source, String pattern) {
        return BackupExclusions.register(scope, source, pattern);
    }

    @Override public AutoCloseable registerWorldExclusion(String source, String pattern) {
        return registerBackupExclusion("WORLD", source, pattern);
    }
    private static final String REPOSITORY_ID = "local";
    private static final String SOURCE_ID = "world";

    private BukkitConfig config;
    private BukkitBestEffortCapture capture;
    private BackendProvider.Settings backendSettings;
    private ManagedRepository repository;
    private Path worldDir;
    private Path backupConfigDir;
    private Path stagingParent;
    private Path repositoryDir;
    private ExecutorService asyncExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // creates config.yml if missing (Bukkit's own config)
        try {
            // 从 config/barusikozako/server.yml 加载 BaruSikoZako 配置
            File serverRoot = Bukkit.getServer().getWorldContainer().getParentFile();
            if (serverRoot == null) serverRoot = getDataFolder().getParentFile();
            Path configDir = Paths.get(serverRoot.getAbsolutePath(), "config", "barusikozako");
            backupConfigDir = configDir;
            config = BukkitConfig.load(configDir);

            // 解析路径
            World world = Bukkit.getWorlds().stream().findFirst().orElseThrow(
                    () -> new IllegalStateException("No worlds loaded"));
            worldDir = world.getWorldFolder().toPath().toRealPath();
            stagingParent = Paths.get(serverRoot.getAbsolutePath(), "staging");
            repositoryDir = Paths.get(serverRoot.getAbsolutePath(), "repository");

            Files.createDirectories(stagingParent);
            Files.createDirectories(repositoryDir);

            stagingParent = stagingParent.toRealPath();
            repositoryDir = repositoryDir.toRealPath();

            // 初始化后端 - 显式使用 ZstdProvider，因为 ServiceLoader 在 Bukkit 的类加载器中不起作用
            BackendProvider provider;
            if ("barusikozako_backendzstd".equals(config.backendKeyword)) {
                provider = new io.github.zct_studio.barusikozako.zstd.ZstdProvider();
            } else {
                throw new IllegalStateException("Backend not installed: " + config.backendKeyword);
            }
            var settingsMap = new LinkedHashMap<>(provider.defaults());
            // 加载后端特定配置（如果存在）
            var backendConfigFile = Paths.get(serverRoot.getAbsolutePath(), "config", "barusikozako", config.backendKeyword + ".yml");
            if (Files.exists(backendConfigFile)) {
                try {
                    settingsMap.putAll(YamlSettings.load(backendConfigFile, provider.defaults(), true, () -> Map.of()));
                } catch (IOException e) {
                    getLogger().warning("Failed to load backend config: " + e.getMessage());
                }
            }
            backendSettings = provider.configure(settingsMap);
            repository = backendSettings.open(repositoryDir, BukkitConfig::forceDirectory, "FULL", null);

            // 初始化尽力捕获源
            capture = new BukkitBestEffortCapture(this, config, world.getWorldFolder().toPath(), stagingParent);

            // 用于备份/恢复任务的异步执行器
            asyncExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "BaruSikoZako-Async");
                t.setDaemon(true);
                return t;
            });

            getLogger().info("Bukkit best-effort backup plugin enabled. Capture enabled: " + config.captureEnabled);
        } catch (IOException | IllegalStateException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize backup plugin", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (capture != null) capture.close();
        if (repository != null) {
            try { repository.close(); } catch (IOException ignored) {}
        }
        if (asyncExecutor != null) asyncExecutor.shutdown();
        getLogger().info("Bukkit best-effort backup plugin disabled");
    }

    // ===== BackupApiHost.Operations =====

    @Override
    public BackupService.Availability availability(BackupRequest.Kind kind) {
        return switch (kind) {
            case VERIFY -> BackupService.Availability.AVAILABLE;
            case BACKUP -> config.captureEnabled && capture != null && capture.available()
                    ? BackupService.Availability.AVAILABLE
                    : BackupService.Availability.TEMPORARILY_UNAVAILABLE;
            case RESTORE -> BackupService.Availability.UNSUPPORTED;
            case DELETE -> BackupService.Availability.AVAILABLE;
        };
    }

    @Override
    public Optional<BackupService.Rejection> evaluate(BackupRequest request) {
        if (!request.repositoryId().equals(REPOSITORY_ID)) return Optional.of(BackupService.Rejection.INVALID_REQUEST);
        try {
            if (request instanceof BackupRequest.Restore r) parseId(r.snapshot().snapshotId());
            if (request instanceof BackupRequest.Delete r) parseId(r.snapshot().snapshotId());
            if (request instanceof BackupRequest.Backup b) {
                b.baseline().ifPresent(base -> parseId(base.snapshotId()));
                if (!config.backendKeyword.equals("barusikozako_backendzstd")) // backend check
                    return Optional.of(BackupService.Rejection.UNSUPPORTED);
            }
        } catch (IllegalArgumentException e) {
            return Optional.of(BackupService.Rejection.INVALID_REQUEST);
        }
        if (request instanceof BackupRequest.Verify v) {
            if (v.level() != BackupRequest.VerificationLevel.FULL) return Optional.of(BackupService.Rejection.UNSUPPORTED);
            try { parseId(v.snapshot().snapshotId()); }
            catch (IllegalArgumentException e) { return Optional.of(BackupService.Rejection.INVALID_REQUEST); }
        }
        return switch (availability(request.kind())) {
            case AVAILABLE -> Optional.empty();
            case TEMPORARILY_UNAVAILABLE -> Optional.of(BackupService.Rejection.UNAVAILABLE);
            case UNSUPPORTED -> Optional.of(BackupService.Rejection.UNSUPPORTED);
        };
    }

    @Override
    public List<BackupService.RepositoryInfo> repositories() {
        return List.of(new BackupService.RepositoryInfo(REPOSITORY_ID, config.backendKeyword));
    }

    @Override
    public BackupService.Page<BackupService.SnapshotInfo> snapshots(String repositoryId, BackupService.PageRequest page)
            throws IOException {
        if (!REPOSITORY_ID.equals(repositoryId)) throw new IllegalArgumentException("Unknown repository");
        if (Files.notExists(repositoryDir, LinkOption.NOFOLLOW_LINKS))
            return new BackupService.Page<>(List.of(), Optional.empty());
        try (var repo = repository) {
            var snapshots = repo.snapshots(page.continuation().map(BukkitBestEffortPlugin::parseId), page.limit(), config.manifestBudget);
            return new BackupService.Page<>(snapshots.entries().stream()
                    .map(s -> new BackupService.SnapshotInfo(
                            new SnapshotRef(REPOSITORY_ID, s.id().toString()),
                            s.createdAt(), SOURCE_ID,
                            s.trigger().map(t -> new BackupRequest.Trigger(t.source(), t.reason(), t.metadata()))))
                    .toList(), snapshots.next().map(UUID::toString));
        }
    }

    @Override
    public Runnable before(BackupRequest request, String principal) {
        if (!(request instanceof BackupRequest.Backup b) || b.mode() == BackupRequest.BackupMode.REBUILD
                || config.lifecycleIntegrations.isEmpty()) return null;
        var context = new BackupLifecycleHook.Context(
                principal.equals("server-scheduler") ? BackupLifecycleHook.Operation.AUTOMATIC_BACKUP
                        : BackupLifecycleHook.Operation.MANUAL_BACKUP,
                request.repositoryId(), Optional.empty());
        return () -> {
            for (String pluginId : config.lifecycleIntegrations) {
                var plugin = getServer().getPluginManager().getPlugin(pluginId);
                if (plugin instanceof BackupLifecycleHook hook) {
                    try { hook.before(new BackupLifecycleHook.Context(
                            principal.equals("server-scheduler") ? BackupLifecycleHook.Operation.AUTOMATIC_BACKUP
                                    : BackupLifecycleHook.Operation.MANUAL_BACKUP,
                            request.repositoryId(), Optional.empty()));
                        } catch (Exception e) {
                            getLogger().log(Level.WARNING, "Lifecycle hook before failed for " + pluginId, e);
                        }
                }
            };
        };
    }

    @Override
    public void after(BackupRequest request, String principal, java.util.concurrent.CompletionStage<TaskResult> result) {
        if (!(request instanceof BackupRequest.Backup b) || b.mode() == BackupRequest.BackupMode.REBUILD
                || config.lifecycleIntegrations.isEmpty()) return;
        var context = new BackupLifecycleHook.Context(
                principal.equals("server-scheduler") ? BackupLifecycleHook.Operation.AUTOMATIC_BACKUP
                        : BackupLifecycleHook.Operation.MANUAL_BACKUP,
                request.repositoryId(), Optional.empty());
        result.thenAccept(outcome -> {
            for (String pluginId : config.lifecycleIntegrations) {
                var plugin = getServer().getPluginManager().getPlugin(pluginId);
                if (plugin instanceof BackupLifecycleHook hook) {
                    try { hook.after(context, outcome); }
                    catch (Exception e) { getLogger().log(Level.WARNING, "Lifecycle hook after failed for " + pluginId, e); }
                }
            }
        });
    }

    @Override
    public TaskResult run(BackupRequest request, BackupApiHost.TaskControl control) throws IOException {
        if (request instanceof BackupRequest.Backup b)
            return b.mode() == BackupRequest.BackupMode.REBUILD ? rebuild(b, control) : backup(b, control);
        if (request instanceof BackupRequest.Delete d) return delete(d, control);
        if (request instanceof BackupRequest.Restore) return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
        if (request instanceof BackupRequest.Verify v) return verify(v, control);
        return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
    }

    // ===== Command handling (TabCompleter) =====

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "backup", "full", "incremental", "restore", "verify", "config", "delete");
        if (args.length == 2 && args[0].equals("backup")) return List.of("full", "incremental");
        if (args.length == 2 && args[0].equals("config")) return List.of("set", "get");
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("barusikozako")) return false;
        if (args.length == 0 || (args.length == 1 && args[0].equals("status"))) {
            sender.sendMessage("=== BaruSikoZako Bukkit Best-Effort ===");
            sender.sendMessage("Capture enabled: " + config.captureEnabled);
            sender.sendMessage("Capture available: " + (capture != null && capture.available()));
            sender.sendMessage("World: " + worldDir);
            sender.sendMessage("Staging: " + stagingParent);
            sender.sendMessage("Repository: " + repositoryDir);
            sender.sendMessage("Backend: " + config.backendKeyword);
            sender.sendMessage("Best-effort mode: CROSS-FILE CONSISTENCY NOT GUARANTEED");
            return true;
        }
        if (args.length >= 1 && args[0].equals("backup")) {
            String mode = args.length >= 2 ? args[1] : "full";
            asyncExecutor.submit(() -> {
                try {
                    BackupRequest.BackupMode backupMode = "incremental".equalsIgnoreCase(mode)
                            ? BackupRequest.BackupMode.INCREMENTAL : BackupRequest.BackupMode.FULL;
                    var trigger = new BackupRequest.Trigger("player", sender.getName(), Map.of());
                    var req = new BackupRequest.Backup(REPOSITORY_ID, SOURCE_ID, trigger, backupMode, Optional.empty());
                    var result = run(req, new SimpleTaskControl(sender));
                    getServer().getScheduler().runTask(this, () -> sender.sendMessage(formatResult(result)));
                } catch (Exception e) {
                    getServer().getScheduler().runTask(this, () -> sender.sendMessage("§cBackup failed: " + e.getMessage()));
                }
            });
            sender.sendMessage("Backup started (" + mode + ")...");
            return true;
        }
        if (args.length >= 2 && args[0].equals("restore")) {
            sender.sendMessage("Restore requires the Agent's offline startup entry.");
            return true;
        }
        if (args.length >= 2 && args[0].equals("verify")) {
            try {
                UUID snap = parseId(args[1]);
                asyncExecutor.submit(() -> {
                    try {
                        var req = new BackupRequest.Verify(new SnapshotRef(REPOSITORY_ID, args[1]),
                                BackupRequest.VerificationLevel.FULL);
                        var result = run(req, new SimpleTaskControl(sender));
                        getServer().getScheduler().runTask(this, () -> sender.sendMessage(formatResult(result)));
                    } catch (Exception e) {
                        getServer().getScheduler().runTask(this, () -> sender.sendMessage("§cVerify failed: " + e.getMessage()));
                    }
                });
                sender.sendMessage("Verification started...");
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§cInvalid snapshot ID");
            }
            return true;
        }
        if (args.length >= 2 && args[0].equals("config")) {
            if (args[1].equals("set") && args.length >= 4) {
                try {
                    // TODO: Implement config set via YamlSettings.change
                    sender.sendMessage("Config set not yet implemented");
                } catch (Exception e) {
                    sender.sendMessage("§cConfig set failed: " + e.getMessage());
                }
            } else if (args[1].equals("get") && args.length >= 3) {
                // TODO: Implement config get
                sender.sendMessage("Config get not yet implemented");
            }
            return true;
        }
        if (args.length >= 2 && args[0].equals("delete")) {
            try {
                UUID snap = parseId(args[1]);
                asyncExecutor.submit(() -> {
                    try {
                        var req = new BackupRequest.Delete(new SnapshotRef(REPOSITORY_ID, args[1]));
                        var result = run(req, new SimpleTaskControl(sender));
                        getServer().getScheduler().runTask(this, () -> sender.sendMessage(formatResult(result)));
                    } catch (Exception e) {
                        getServer().getScheduler().runTask(this, () -> sender.sendMessage("§cDelete failed: " + e.getMessage()));
                    }
                });
                sender.sendMessage("Delete started...");
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§cInvalid snapshot ID");
            }
            return true;
        }
        sender.sendMessage("Usage: /barusikozako [status|backup <full|incremental>|restore <id>|verify <id>|config <set|get>|delete <id>]");
        return true;
    }

    // ===== Backup implementation =====

    private TaskResult backup(BackupRequest.Backup request, BackupApiHost.TaskControl control) throws IOException {
        if (capture == null) return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
        var trigger = request.trigger();
        UUID operation = UUID.randomUUID();
        long started = System.nanoTime();
        try {
            Files.createDirectories(repositoryDir);
            Path canonical = repositoryDir.toRealPath();
            BukkitConfig.forceDirectory(canonical.getParent());
        } catch (IOException e) {
            return new TaskResult.Failed(TaskResult.Failure.IO);
        }
        ReadView captured;
        BackupExclusions exclusions;
        try {
            exclusions = BackupExclusions.load(backupConfigDir, worldDir, false);
            captured = capture.acquire(control::mayContinue);
        } catch (IOException | LinkageError e) {
            if (control.mayContinue()) getLogger().log(Level.WARNING, "Online capture failed", e);
            return new TaskResult.Failed(TaskResult.Failure.SOURCE_UNAVAILABLE);
        }
        var bytes = new java.util.concurrent.atomic.AtomicLong();
        try {
            var view = captured;
            var snapshot = new Snapshot(UUID.randomUUID(), Instant.now(), SOURCE_ID,
                    Optional.of(new Snapshot.Trigger(trigger.source(), trigger.reason(), trigger.metadata())));
            UUID baseline = request.baseline().map(base -> parseId(base.snapshotId())).orElse(null);
            try (var repo = repository) {
                try {
                    control.progress(0, OptionalLong.empty());
                    new BackupEngine().backup(view, repo, snapshot, operation, config.readLimits,
                            () -> { if (!control.mayContinue()) throw new InterruptedIOException("Backup cancelled"); },
                            () -> { if (!control.beginCommit()) throw new InterruptedIOException("Backup commit not authorized"); },
                            processed -> { bytes.set(processed); control.progress(processed, OptionalLong.empty()); },
                            config.backupReadBytesPerSecond, exclusions, control::ignoreRules);
                } catch (IOException | LinkageError e) {
                    if (e instanceof ManagedRepository.CommitUncertain) throw e;
                    if (!repo.isCommitted(snapshot.id(), operation)) throw e;
                    getLogger().log(Level.WARNING, "Archive committed with later cleanup failure", e);
                }
                try { return new TaskResult.BackupCommitted(new SnapshotRef(REPOSITORY_ID, snapshot.id().toString()),
                        OptionalLong.of(bytes.get()), repo.archiveBytes(snapshot.id()),
                        Optional.of(java.time.Duration.ofNanos(System.nanoTime() - started))); }
                catch (IOException e) { getLogger().log(Level.WARNING, "Backup committed but size unknown", e); }
            }
            return new TaskResult.BackupCommitted(new SnapshotRef(REPOSITORY_ID, snapshot.id().toString()),
                    OptionalLong.of(bytes.get()), OptionalLong.empty(), Optional.of(java.time.Duration.ofNanos(System.nanoTime() - started)));
        } catch (ManagedRepository.CommitUncertain e) {
            return new TaskResult.CommitUncertain();
        } catch (IOException | LinkageError e) {
            if (control.mayContinue()) getLogger().log(Level.WARNING, "Backup failed", e);
            return new TaskResult.Failed(TaskResult.Failure.IO);
        } finally {
            try { if (captured != null) captured.close(); } catch (IOException e) { getLogger().log(Level.WARNING, "Staging cleanup failed", e); }
        }
    }

    private TaskResult rebuild(BackupRequest.Backup request, BackupApiHost.TaskControl control) throws IOException {
        var trigger = request.trigger();
        var saved = new Snapshot(UUID.randomUUID(), Instant.now(), SOURCE_ID,
                Optional.of(new Snapshot.Trigger(trigger.source(), trigger.reason(), trigger.metadata())));
        var limits = config.readLimits;
        UUID baseline = parseId(request.baseline().orElseThrow().snapshotId());
        try (var repo = repository) {
            repo.rebuild(baseline, saved, UUID.randomUUID(),
                    BackupEngine.readPreparation(limits, () -> { if (!control.mayContinue()) throw new InterruptedIOException("Rebuild cancelled"); },
                            config.backupReadBytesPerSecond),
                    () -> { if (!control.beginCommit()) throw new InterruptedIOException("Rebuild not authorized"); });
            return new TaskResult.BackupCommitted(new SnapshotRef(REPOSITORY_ID, saved.id().toString()));
        } catch (ManagedRepository.CommitUncertain e) { return new TaskResult.CommitUncertain(); }
        catch (IOException | LinkageError e) { if (control.mayContinue()) getLogger().log(Level.WARNING, "Rebuild failed", e); return new TaskResult.Failed(TaskResult.Failure.IO); }
    }

    private TaskResult delete(BackupRequest.Delete request, BackupApiHost.TaskControl control) {
        try (var repo = repository) {
            repo.deleteIndependentSnapshot(parseId(request.snapshot().snapshotId()),
                    () -> { if (!control.mayContinue()) throw new InterruptedIOException("Delete cancelled"); },
                    () -> { if (!control.beginCommit()) throw new InterruptedIOException("Delete not authorized"); });
            return new TaskResult.Deleted(request.snapshot());
        } catch (ManagedRepository.CommitUncertain e) { return new TaskResult.CommitUncertain(); }
        catch (IOException | LinkageError e) { if (control.mayContinue()) getLogger().log(Level.WARNING, "Delete failed", e); return new TaskResult.Failed(TaskResult.Failure.IO); }
    }

    private TaskResult verify(BackupRequest.Verify request, BackupApiHost.TaskControl control) throws IOException {
        try (var repo = repository) {
            control.progress(0, OptionalLong.empty());
            var result = new BackupEngine().verify(repo, parseId(request.snapshot().snapshotId()), config.readLimits,
                    () -> { if (!control.mayContinue()) throw new InterruptedIOException("Verification cancelled"); },
                    bytes -> control.progress(bytes, OptionalLong.empty()));
            return new TaskResult.VerificationComplete(request.level(), result.entries(), result.bytes());
        } catch (IOException | LinkageError e) {
            if (control.mayContinue()) getLogger().log(Level.WARNING, "Verification failed", e);
            return new TaskResult.Failed(TaskResult.Failure.IO);
        }
    }

    // ===== Helpers =====

    private static UUID parseId(String text) {
        UUID id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical snapshot ID");
        return id;
    }

    private String formatResult(TaskResult result) {
        if (result instanceof TaskResult.BackupCommitted b) {
            return "§aBackup committed: " + b.snapshot().snapshotId()
                    + (b.archiveBytes().isPresent() ? " (" + b.archiveBytes().getAsLong() + " bytes)" : "");
        } else if (result instanceof TaskResult.RestoreQueued r) {
            return "§aRestore queued for " + r.snapshot().snapshotId() + ". Restart to apply.";
        } else if (result instanceof TaskResult.VerificationComplete v) {
            return "§aVerification passed: " + v.entries() + " entries, " + v.bytes() + " bytes";
        } else if (result instanceof TaskResult.Deleted d) {
            return "§aDeleted: " + d.snapshot().snapshotId();
        } else if (result instanceof TaskResult.CommitUncertain) {
            return "§eCommit outcome uncertain";
        } else if (result instanceof TaskResult.Failed f) {
            return "§cFailed: " + f.reason();
        } else {
            return "§cUnknown result";
        }
    }

    // ===== Simple TaskControl for command execution =====

    private final class SimpleTaskControl implements BackupApiHost.TaskControl {
        private final CommandSender sender;
        private volatile boolean cancelled = false;

        SimpleTaskControl(CommandSender sender) { this.sender = sender; }

        @Override public boolean mayContinue() { return !cancelled; }
        @Override public void progress(long bytes, OptionalLong total) {
            if (total.isPresent()) sender.sendMessage("Progress: " + bytes + " / " + total.getAsLong());
        }
        @Override public boolean beginCommit() { return true; }
        @Override public void ignoreRules(List<String> rules) {
            if (sender instanceof org.bukkit.entity.Player)
                getServer().getScheduler().runTask(BukkitBestEffortPlugin.this, () ->
                    sender.sendMessage(io.github.zct_studio.barusikozako.apihost.RuleNotice.forPlayer(rules)));
        }
        public void cancel() { cancelled = true; }
    }
}
