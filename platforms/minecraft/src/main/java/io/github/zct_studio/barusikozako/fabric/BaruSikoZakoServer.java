// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import io.github.zct_studio.barusikozako.platform.windows.WindowsDirectorySync;

import io.github.zct_studio.barusikozako.runtime.BackupIntegration;
import io.github.zct_studio.barusikozako.runtime.BackupRequest;
import io.github.zct_studio.barusikozako.runtime.BackupService;
import io.github.zct_studio.barusikozako.runtime.BackupService.PageRequest;
import io.github.zct_studio.barusikozako.runtime.SnapshotRef;
import io.github.zct_studio.barusikozako.runtime.TaskId;
import io.github.zct_studio.barusikozako.runtime.TaskResult;
import io.github.zct_studio.barusikozako.runtime.host.BackupApiHost;
import io.github.zct_studio.barusikozako.runtime.host.BoundAccess.Grant;
import io.github.zct_studio.barusikozako.runtime.host.BoundAccess.Right;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class BaruSikoZakoServer {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private static final int PAGE_SIZE = 10;
    private static final String REPOSITORY = FabricOperations.REPOSITORY_ID;
    private static final String SOURCE = FabricOperations.SOURCE_ID;
    private static final class WorldWorkers {
        final java.util.concurrent.ExecutorService integrations = Executors.newSingleThreadExecutor(action ->
            worker(action, "integrations", Thread.NORM_PRIORITY));
        final java.util.concurrent.ScheduledExecutorService schedules = Executors.newSingleThreadScheduledExecutor(action ->
            worker(action, "schedules", Thread.NORM_PRIORITY));
        final java.util.concurrent.ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(action ->
            worker(action, "maintenance", Thread.MIN_PRIORITY));

        private static Thread worker(Runnable action, String name, int priority) {
            var thread = new Thread(action, "BaruSikoZako " + name);
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        }

        void close() {
            integrations.shutdownNow();
            schedules.shutdownNow();
            maintenance.shutdownNow();
        }
    }
    private volatile WorldWorkers workers;
    private volatile BackupService operator;
    private volatile BackupApiHost.Binding commandBinding;
    private static volatile WorldCapture capture;
    private static volatile boolean empty;
    private WorldCapture stoppingCapture;
    private BackupApiHost host;
    private volatile String activeBackend = "";
    private volatile BackupService scheduler;

    public void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
            var root = Commands.literal("barusikozako")
                .requires(GameCommandAccess::isAdministrator)
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(configCommand("server", ServerConfig.defaults(), values -> {
                    var config = ServerConfig.parse(values);
                    if (!BackendRegistry.registered().containsKey(config.backendKeyword()) &&
                        !config.backendKeyword().equals(configuredBackend()))
                        throw new IllegalArgumentException("Backend is not installed: " + config.backendKeyword());
                }))
                .then(Commands.literal("backup")
                    .executes(context -> submitBackup(context.getSource(), BackupRequest.BackupMode.CONFIGURED, Optional.empty())))
                .then(Commands.literal("list").executes(context -> list(context.getSource(), Optional.empty()))
                    .then(Commands.argument("after", StringArgumentType.word()).executes(context ->
                        withId(context.getSource(), StringArgumentType.getString(context, "after"),
                            id -> list(context.getSource(), Optional.of(id))))))
                .then(Commands.literal("verify")
                    .then(Commands.argument("id", StringArgumentType.word()).executes(context ->
                        withId(context.getSource(), StringArgumentType.getString(context, "id"), id ->
                            submit(context.getSource(), new BackupRequest.Verify(new SnapshotRef(REPOSITORY, id),
                                BackupRequest.VerificationLevel.FULL))))))
                .then(Commands.literal("delete").then(Commands.argument("id", StringArgumentType.word()).executes(context ->
                    withId(context.getSource(), StringArgumentType.getString(context, "id"),
                        id -> submit(context.getSource(), new BackupRequest.Delete(new SnapshotRef(REPOSITORY, id)))))))
                .then(Commands.literal("cancel")
                    .then(Commands.argument("task", StringArgumentType.string()).executes(context ->
                        cancel(context.getSource(), StringArgumentType.getString(context, "task")))))
                .then(Commands.literal("task")
                    .then(Commands.argument("task", StringArgumentType.string()).executes(context ->
                        task(context.getSource(), StringArgumentType.getString(context, "task")))));
            root.then(Commands.literal("restore").then(Commands.argument("filename", StringArgumentType.word())
                .suggests((context, builder) -> {
                    var current = operator;
                    if (current == null) return builder.buildFuture();
                    return current.snapshots(REPOSITORY, new PageRequest(PAGE_SIZE, Optional.empty()))
                        .handle((page, failure) -> {
                            if (failure == null) for (var item : page.items()) {
                                var id = item.snapshot().snapshotId();
                                if (id.startsWith(builder.getRemainingLowerCase())) builder.suggest(id);
                            }
                            return builder.build();
                        }).toCompletableFuture();
                })
                .executes(context -> withId(context.getSource(), StringArgumentType.getString(context, "filename"),
                    id -> submit(context.getSource(), new BackupRequest.Restore(new SnapshotRef(REPOSITORY, id), SOURCE))))));
            var selected = configuredBackend();
            for (var provider : BackendRegistry.registered().values()) {
                var backendRoot = Commands.literal(provider.keyword())
                    .requires(GameCommandAccess::isAdministrator)
                    .then(configCommand(provider.keyword(), provider.defaults(), provider::configure));
                dispatcher.register(backendRoot);
                for (var command : provider.commands()) {
                    var mode = BackupRequest.BackupMode.valueOf(command.operation());
                    var node = Commands.literal(command.keyword());
                    if (command.needsBaseline()) {
                        node.then(Commands.argument("baseline", StringArgumentType.word()).executes(context ->
                            withId(context.getSource(), StringArgumentType.getString(context, "baseline"),
                                id -> backendBackup(context.getSource(), provider.keyword(), mode,
                                    Optional.of(new SnapshotRef(REPOSITORY, id))))));
                    } else {
                        node.executes(context -> backendBackup(context.getSource(), provider.keyword(), mode, Optional.empty()));
                    }
                    if (provider.keyword().equals(selected)) {
                        root.then(node);
                        if (!command.keyword().equals("rebuild"))
                            root.then(Commands.literal("backup").then(node));
                    }
                }
            }
            dispatcher.register(root);
    }

    public synchronized void onServerStarted(MinecraftServer server) {
        if (workers != null) return;
        var next = new WorldWorkers();
        workers = next;
        empty = false;
        next.integrations.execute(() -> start(server, next));
    }

    public void onServerStopping() { stop(); }

    public void onServerStopped() {
        var previous = stoppingCapture;
        stoppingCapture = null;
        if (previous != null) previous.awaitCleanup();
    }

    public void tickStart(MinecraftServer server) { captureTick(server, WorldCapture.Edge.TICK_START); }

    public void tickEnd(MinecraftServer server) { captureTick(server, WorldCapture.Edge.TICK_END); }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> configCommand(
        String filename, Map<String, String> defaults, YamlSettings.Validator validator) {
        return Commands.literal("config").executes(context -> configPath(context.getSource(), filename))
            .then(Commands.literal("set").then(Commands.argument("key", StringArgumentType.word())
                .suggests((context, builder) -> {
                    defaults.keySet().stream().sorted().filter(key -> key.startsWith(builder.getRemaining()))
                        .forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .then(Commands.argument("value", StringArgumentType.greedyString()).executes(context -> {
                    var source = context.getSource();
                    var key = StringArgumentType.getString(context, "key");
                    var value = StringArgumentType.getString(context, "value");
                    try {
                        var active = workers;
                        if (active == null) return 0;
                        active.integrations.execute(() -> {
                            try {
                                var file = LoaderBridge.configDirectory().resolve("barusikozako").resolve(filename + ".yml");
                                YamlSettings.change(file, defaults, key, value, validator);
                                reply(source, Translations.text(source, "command.config_saved"), false);
                            } catch (IOException | IllegalArgumentException failure) {
                                reply(source, "Configuration rejected: " + failure.getMessage(), true);
                            }
                        });
                        return 1;
                    } catch (java.util.concurrent.RejectedExecutionException unavailable) {
                        return 0;
                    }
                }))));
    }

    private static int configPath(CommandSourceStack source, String filename) {
        source.sendSuccess(() -> Component.literal("Configuration: config/barusikozako/" + filename + ".yml (restart to apply)"), false);
        return 1;
    }

    private static String configuredBackend() {
        try {
            return ServerConfig.load(false).backendKeyword();
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not read selected backend", failure);
            return "";
        }
    }

    private int backendBackup(CommandSourceStack source, String backend, BackupRequest.BackupMode mode,
                              Optional<SnapshotRef> baseline) {
        if (!backend.equals(activeBackend)) {
            source.sendFailure(Component.literal("Backend is not active: " + backend));
            return 0;
        }
        return submitBackup(source, mode, baseline);
    }

    private int submitBackup(CommandSourceStack source, BackupRequest.BackupMode mode, Optional<SnapshotRef> baseline) {
        return submit(source, new BackupRequest.Backup(REPOSITORY, SOURCE,
            new BackupRequest.Trigger("command", "manual", Map.of()), mode, baseline));
    }

    private void start(MinecraftServer server, WorldWorkers session) {
        try {
            var config = ServerConfig.load(true);
            if (!BackendRegistry.registered().containsKey(config.backendKeyword())) {
                LOG.log(System.Logger.Level.INFO, "No selected backup backend installed: {0}", config.backendKeyword());
                return;
            }
            var backend = BackendRegistry.configure(config.backendKeyword(), true);
            var world = server.getWorldPath(LevelResource.ROOT).toRealPath();
            BackupApiHost running;
            synchronized (this) {
                if (workers != session) return;
                if (config.captureEnabled()) {
                    var staging = ServerRepository.defaultPath().resolveSibling("capture");
                    Files.createDirectories(staging);
                    capture = new WorldCapture(world, ServerRepository.checkPath(staging, world), config);
                }
                var operations = new FabricOperations(config, world, capture, backend,
                    server.isDedicatedServer() || WorldStartupGate.canRestoreClientWorld(world));
                activeBackend = config.backendKeyword();
                running = new BackupApiHost(operations);
                host = running;
                commandBinding = running.bind("server-operator", new Grant(Map.of(REPOSITORY, EnumSet.allOf(Right.class)),
                    Set.of(SOURCE), Set.of(SOURCE)));
                operator = commandBinding.service();
                if (config.backupIntervalSeconds() > 0) {
                    var scheduled = running.bind("server-scheduler", new Grant(Map.of(REPOSITORY, EnumSet.of(Right.BACKUP)),
                        Set.of(SOURCE), Set.of())).service();
                    scheduler = scheduled;
                    session.schedules.scheduleWithFixedDelay(() -> scheduledBackup(scheduled), config.backupIntervalSeconds(),
                        config.backupIntervalSeconds(), TimeUnit.SECONDS);
                }
                if (backend.maintenanceIntervalSeconds() > 0) {
                    session.maintenance.scheduleWithFixedDelay(() -> collect(running, operations), backend.maintenanceIntervalSeconds(),
                        backend.maintenanceIntervalSeconds(), TimeUnit.SECONDS);
                }
            }
            LOG.log(System.Logger.Level.INFO, "BaruSikoZako services ready");
            if (capture != null) LOG.log(System.Logger.Level.WARNING,
                "Online backups use best-effort file capture; cross-file state is not atomic");
            activateIntegrations(running, config);
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "BaruSikoZako unavailable: failed to load server configuration", failure);
        }
    }

    public static void captureWhileEmptyPaused(MinecraftServer server) {
        captureTick(server, WorldCapture.Edge.EMPTY_PAUSE);
    }

    private static void captureTick(MinecraftServer server, WorldCapture.Edge edge) {
        boolean noPlayers = server.getPlayerList().getPlayerCount() == 0;
        if (empty != noPlayers) empty = noPlayers;
        var current = capture;
        if (current != null) current.tick(server, edge);
    }

    private static void collect(BackupApiHost running, FabricOperations operations) {
        if (!empty) return;
        try {
            running.maintainIfIdle(allowed -> operations.collect(() -> empty && allowed.getAsBoolean()));
        } catch (java.io.InterruptedIOException yielded) {
            // 播放器活动、关闭或前台提交会抢占维护
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Repository maintenance did not complete", failure);
        }
    }

    private static void activateIntegrations(BackupApiHost host, ServerConfig config) {
        var accepted = new java.util.HashSet<String>();
        for (var entry : LoaderBridge.entries("barusikozako:backup_api", BackupIntegration.class)) {
            if (Thread.currentThread().isInterrupted()) return;
            String id = entry.owner();
            if (!config.integrations().contains(id) || !accepted.add(id)) continue;
            var rights = EnumSet.of(Right.BACKUP, Right.VERIFY, Right.VIEW, Right.SUBSCRIBE, Right.CANCEL);
            boolean maintenance = config.maintenanceIntegrations().contains(id);
            if (maintenance) rights.addAll(EnumSet.of(Right.RESTORE, Right.DELETE));
            var grant = new Grant(Map.of(REPOSITORY, rights), Set.of(SOURCE), maintenance ? Set.of(SOURCE) : Set.of());
            var binding = host.bind(id, grant);
            try {
                var integration = entry.value().get();
                if (integration == null) binding.close();
                else integration.onBackupService(binding.service());
            } catch (Throwable failure) {
                binding.close();
                LOG.log(System.Logger.Level.WARNING, "Integration {0} failed to accept its backup API ({1})",
                    id, failure.getClass().getName());
            }
        }
    }

    private void stop() {
        BackupApiHost running;
        WorldWorkers session;
        WorldCapture current;
        synchronized (this) {
            session = workers;
            workers = null;
            operator = null;
            commandBinding = null;
            scheduler = null;
            activeBackend = "";
            running = host;
            host = null;
            current = capture;
            capture = null;
        }
        if (session != null) session.close();
        if (current != null) {
            stoppingCapture = current;
            current.close();
        }
        if (running != null) running.close();
    }

    private int status(CommandSourceStack source) {
        var product = LoaderBridge.productVersion();
        var detail = operator == null ? "backup services unavailable. See server log."
            : capture == null || !capture.available() ? "capture unavailable" : "best-effort capture enabled";
        var text = "BaruSikoZako " + product + " · " + detail;
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private void scheduledBackup(BackupService service) {
        if (service != scheduler) return;
        var request = new BackupRequest.Backup(REPOSITORY, SOURCE,
            new BackupRequest.Trigger("schedule", "periodic", Map.of()));
        var submission = service.submit(request);
        if (submission instanceof BackupService.Submission.Accepted accepted) {
            accepted.completion().thenAccept(result ->
                LOG.log(System.Logger.Level.INFO, "Scheduled backup {0}: {1}", accepted.id().value(), summarize(result)));
        } else {
            LOG.log(System.Logger.Level.INFO, "Scheduled backup rejected: {0}", rejection(submission));
        }
    }

    private int list(CommandSourceStack source, Optional<String> after) {
        var service = service(source);
        if (service == null) return 0;
        service.snapshots(REPOSITORY, new PageRequest(PAGE_SIZE, after)).whenComplete((page, failure) -> {
            if (failure != null) {
                reply(source, Translations.text(source, "command.query_failed"), true);
                return;
            }
            var message = new StringBuilder("Committed snapshots");
            for (var entry : page.items())
                message.append('\n').append(entry.snapshot().snapshotId()).append("  ").append(entry.createdAt());
            page.next().ifPresent(id -> message.append("\nNext page: /barusikozako list ").append(id));
            reply(source, page.items().isEmpty() ? Translations.text(source, "command.snapshots_empty") : message.toString(), false);
        });
        source.sendSuccess(() -> Component.literal(Translations.text(source, "command.repository_accepted")), false);
        return 1;
    }

    private int submit(CommandSourceStack source, BackupRequest request) {
        var service = service(source);
        if (service == null) return 0;
        var binding = commandBinding;
        var submission = binding == null ? service.submit(request) : binding.submit(request, rules -> {
            if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer)) return;
            reply(source, io.github.zct_studio.barusikozako.runtime.host.RuleNotice.forPlayer(rules), false);
        });
        if (submission instanceof BackupService.Submission.Accepted accepted) {
            accepted.completion().whenComplete((result, failure) -> {
                if (failure != null) {
                    reply(source, "Unable to deliver task results for " + accepted.id().value() + ".", true);
                } else {
                    String message = result instanceof TaskResult.VerificationComplete verified &&
                        request instanceof BackupRequest.Verify verify ? verificationSummary(verified, " for " + verify.snapshot().snapshotId()) :
                        summarize(result);
                    reply(source, "Task " + accepted.id().value() + " · " + message, result instanceof TaskResult.Failed ||
                        result instanceof TaskResult.CommitUncertain);
                }
            });
            String message = request.kind() == BackupRequest.Kind.BACKUP ? "Backup requested: " :
                request.kind() == BackupRequest.Kind.RESTORE ? "Offline restore request accepted: " : "Requested task ";
            source.sendSuccess(() -> Component.literal(message + accepted.id().value()), false);
            return 1;
        }
        source.sendFailure(Component.literal("Request rejected: " + rejection(submission)));
        return 0;
    }

    private static String rejection(BackupService.Submission submission) {
        return switch (((BackupService.Submission.Rejected) submission).reason()) {
            case UNAVAILABLE -> "operation unavailable in the current server state";
            case NOT_AUTHORIZED -> "operation not authorized";
            case BUSY -> "backup services or repository busy";
            case UNSUPPORTED -> "operation unsupported";
            case INVALID_REQUEST -> "invalid request";
        };
    }

    private static String summarize(TaskResult result) {
        if (result instanceof TaskResult.VerificationComplete verified) {
            return verificationSummary(verified, "");
        }
        if (result instanceof TaskResult.BackupCommitted committed) {
            return "Backup committed " + committed.snapshot().snapshotId() +
                    committed.elapsed().map(value -> " · " + value.toMillis() + " ms").orElse("") +
                    (committed.archiveBytes().isPresent() ? " · " + committed.archiveBytes().getAsLong() + " archive bytes" :
                        committed.logicalBytes().isPresent() ? " · " + committed.logicalBytes().getAsLong() + " logical bytes" : "") + ".";
        }
        if (result instanceof TaskResult.RestoreQueued value) {
            return "Restore queued for " + value.snapshot().snapshotId() +
                    ". Singleplayer restore runs when you reopen this world. On a dedicated server, restart the server.";
        }
        if (result instanceof TaskResult.Deleted deleted) {
            return "Snapshot deleted " + deleted.snapshot().snapshotId() + ".";
        }
        if (result instanceof TaskResult.Failed failed) {
            return switch (failed.reason()) {
                case NATIVE_ACCESS_DENIED -> WindowsDirectorySync.ACCESS_HELP;
                case DIRECTORY_SYNC_FAILED -> WindowsDirectorySync.SYNC_HELP;
                default -> "Backup operation failed (" + failed.reason() + "). See server log.";
            };
        }
        if (result instanceof TaskResult.CommitUncertain) {
            return "Commit outcome is uncertain. Check the repository before retrying.";
        }
        if (result instanceof TaskResult.Cancelled) {
            return "Task cancelled.";
        }
        return "Operation completed.";
    }

    private static String verificationSummary(TaskResult.VerificationComplete verified, String target) {
        return "Full verification passed" + target + " (" + verified.entries() + " entries, " + verified.bytes() + " bytes).";
    }

    private int cancel(CommandSourceStack source, String id) {
        var service = service(source);
        if (service == null) return 0;
        if (id.isBlank() || id.length() > 64) {
            source.sendFailure(Component.literal(Translations.text(source, "command.invalid_task")));
            return 0;
        }
        var result = service.cancel(new TaskId(id));
        source.sendSuccess(() -> Component.literal("Cancellation: " + result), false);
        return result == BackupService.Cancellation.REQUESTED ? 1 : 0;
    }

    private int task(CommandSourceStack source, String id) {
        var service = service(source);
        if (service == null) return 0;
        if (id.isBlank() || id.length() > 64) {
            source.sendFailure(Component.literal(Translations.text(source, "command.invalid_task")));
            return 0;
        }
        var state = service.task(new TaskId(id));
        if (state.isEmpty()) {
            source.sendFailure(Component.literal(Translations.text(source, "command.task_unavailable")));
            return 0;
        }
        var value = state.orElseThrow();
        source.sendSuccess(() -> Component.literal("Task " + id + " · " + value.operation() + " · " + value.phase() +
            " · " + value.processedBytes() + (value.totalBytes().isPresent() ? "/" + value.totalBytes().getAsLong() : "") +
            " bytes · " + value.elapsed().toMillis() + " ms" + value.result().map(result -> " · " + summarize(result)).orElse("")), false);
        return 1;
    }

    private BackupService service(CommandSourceStack source) {
        var service = operator;
        if (service == null) source.sendFailure(Component.literal(Translations.text(source, "command.service_unavailable")));
        return service;
    }

    private static int withId(CommandSourceStack source, String text, java.util.function.ToIntFunction<String> action) {
        try {
            FabricOperations.parseId(text);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal(Translations.text(source, "command.invalid_snapshot")));
            return 0;
        }
        return action.applyAsInt(text);
    }

    private static void reply(CommandSourceStack source, String message, boolean failure) {
        var component = Component.literal(message);
        source.getServer().executeIfPossible(() -> {
            if (failure) source.sendFailure(component);
            else source.sendSuccess(() -> component, false);
        });
    }
}
