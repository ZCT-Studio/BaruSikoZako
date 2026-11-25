// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.core.BackupExclusions;
import io.github.zct_studio.barusikozako.runtime.BackupIntegration;
import io.github.zct_studio.barusikozako.runtime.BackupRequest;
import io.github.zct_studio.barusikozako.runtime.BackupService;
import io.github.zct_studio.barusikozako.runtime.BackupService.PageRequest;
import io.github.zct_studio.barusikozako.runtime.SnapshotRef;
import io.github.zct_studio.barusikozako.runtime.TaskId;
import io.github.zct_studio.barusikozako.runtime.TaskResult;
import io.github.zct_studio.barusikozako.runtime.TaskState;
import io.github.zct_studio.barusikozako.runtime.host.BackupApiHost;
import io.github.zct_studio.barusikozako.runtime.host.BackupApiHost.TimedTask;
import io.github.zct_studio.barusikozako.runtime.host.BoundAccess.Grant;
import io.github.zct_studio.barusikozako.runtime.host.BoundAccess.Right;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class BaruSikoZakoServer {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private static final int PAGE_SIZE = 10;
    private static final int SNAPSHOT_LOOKUP_PAGE_SIZE = 256;
    private static final String REPOSITORY = FabricOperations.REPOSITORY_ID;
    private static final String SOURCE = FabricOperations.SOURCE_ID;
    private static final class WorldWorkers {
        final WorldStartupGate.RestoreNotice restoreNotice;
        final Set<UUID> restoreNoticeRecipients;

        WorldWorkers(WorldStartupGate.RestoreNotice restoreNotice) {
            this.restoreNotice = restoreNotice;
            restoreNoticeRecipients = restoreNotice == null ? null : new java.util.HashSet<>();
        }

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
    private volatile MinecraftServer server;
    private volatile Path repositoryPath;
    private volatile long backupIntervalSeconds;
    private volatile Instant lastBackupTime;
    private volatile TaskResult lastBackupResult;
    private boolean startupNoticeSent;
    private boolean firstPlayerNoticeSent;

    public void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        for (var provider : BackendRegistry.registered().values()) {
            dispatcher.register(Commands.literal(provider.keyword())
                .requires(GameCommandAccess::canManageBackupsConfigs)
                .then(configCommand(provider.keyword(), provider.defaults(), provider::configure)));
        }
        var selected = configuredBackend();
        for (String name : List.of("barusikozako", "zakobak", "zakobackup")) {
            dispatcher.register(root(name, selected));
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> root(String name, String selected) {
        var root = Commands.literal(name)
            .requires(GameCommandAccess::canManageBackups)
            .then(Commands.literal("status").executes(context -> status(context.getSource())))
            .then(Commands.literal("help").executes(context -> help(context.getSource())))
            .then(configCommand("server", ServerConfig.defaults(), values -> {
                var config = ServerConfig.parse(values);
                if (!BackendRegistry.registered().containsKey(config.backendKeyword()) &&
                    !config.backendKeyword().equals(configuredBackend()))
                    throw new IllegalArgumentException("Backend is not installed: " + config.backendKeyword());
            }))
            .then(Commands.literal("backup")
                .executes(context -> submitBackup(context.getSource(), BackupRequest.BackupMode.CONFIGURED, Optional.empty())))
            .then(snapshotSelection("list", (source, snapshot) -> list(source, Optional.of(snapshot.snapshot().snapshotId())))
                .executes(context -> list(context.getSource(), Optional.empty())))
            .then(snapshotCommand("verify",
                snapshot -> new BackupRequest.Verify(snapshot, BackupRequest.VerificationLevel.FULL)))
            .then(snapshotCommand("delete", BackupRequest.Delete::new))
            .then(snapshotSelection("restore", (source, snapshot) -> {
                if (snapshot.consistency() == BackupService.Consistency.RELAXED)
                    source.sendSuccess(() -> Component.literal(Translations.text(source, "notice.relaxed_restore")), false);
                return submit(source, new BackupRequest.Restore(snapshot.snapshot(), SOURCE));
            }))
            .then(taskCommand("cancel", true))
            .then(taskCommand("task", false).executes(context -> listTasks(context.getSource())));
        for (var provider : BackendRegistry.registered().values()) {
            if (!provider.keyword().equals(selected)) continue;
            for (var command : provider.commands()) {
                var mode = BackupRequest.BackupMode.valueOf(command.operation());
                var node = Commands.literal(command.keyword());
                if (command.needsBaseline()) {
                    node = snapshotSelection(command.keyword(), (source, baseline) ->
                        backendBackup(source, provider.keyword(), mode, Optional.of(baseline.snapshot())));
                } else {
                    node.executes(context -> backendBackup(context.getSource(), provider.keyword(), mode, Optional.empty()));
                }
                root.then(node);
                if (!command.keyword().equals("rebuild"))
                    root.then(Commands.literal("backup").then(node));
            }
        }
        return root;
    }

    private static int help(CommandSourceStack source) {
        var text = String.join("\n",
            Translations.text(source, "help.header"),
            Translations.text(source, "help.status"),
            Translations.text(source, "help.backup"),
            Translations.text(source, "help.list"),
            Translations.text(source, "help.verify"),
            Translations.text(source, "help.delete"),
            Translations.text(source, "help.restore"),
            Translations.text(source, "help.cancel"),
            Translations.text(source, "help.task"),
            Translations.text(source, "help.config"),
            Translations.text(source, "help.help"),
            Translations.text(source, "help.backend"),
            Translations.text(source, "help.footer"));
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private LiteralArgumentBuilder<CommandSourceStack> snapshotCommand(
            String name, Function<SnapshotRef, BackupRequest> request) {
        return snapshotSelection(name, (source, snapshot) -> submit(source, request.apply(snapshot.snapshot())));
    }

    private LiteralArgumentBuilder<CommandSourceStack> snapshotSelection(
            String name, ToIntBiFunction<CommandSourceStack, BackupService.SnapshotInfo> action) {
        return Commands.literal(name)
            .then(snapshotUuid(action))
            .then(Commands.literal("fullname").then(Commands.argument("folder", StringArgumentType.word())
                .suggests((context, builder) -> suggestSnapshots(builder, BaruSikoZakoServer::snapshotFolderName))
                .executes(context -> withSnapshotFolder(context.getSource(), StringArgumentType.getString(context, "folder"), action))))
            .then(Commands.argument("time", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestSnapshots(builder, item -> item.createdAt().toString()))
                .executes(context -> withSnapshotTime(context.getSource(), StringArgumentType.getString(context, "time"), action)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> snapshotUuid(
            ToIntBiFunction<CommandSourceStack, BackupService.SnapshotInfo> action) {
        return Commands.literal("fromuuid").then(Commands.argument("uuid", StringArgumentType.word())
            .suggests((context, builder) -> suggestSnapshots(builder, item -> item.snapshot().snapshotId()))
            .executes(context -> withId(context.getSource(), StringArgumentType.getString(context, "uuid"), action)));
    }

    private CompletableFuture<Suggestions> suggestSnapshots(
            SuggestionsBuilder builder, Function<BackupService.SnapshotInfo, String> value) {
        var service = operator;
        if (service == null) return builder.buildFuture();
        var prefix = builder.getRemainingLowerCase();
        var matches = new LinkedHashSet<String>();
        return scanSnapshots(service, Optional.empty(), snapshot -> {
            var candidate = value.apply(snapshot);
            if (candidate.regionMatches(true, 0, prefix, 0, prefix.length())) matches.add(candidate);
            return matches.size() >= PAGE_SIZE;
        }).handle((result, failure) -> {
            if (failure == null && operator == service) matches.forEach(builder::suggest);
            return builder.build();
        }).toCompletableFuture();
    }

    private int withSnapshotTime(CommandSourceStack source, String text,
            ToIntBiFunction<CommandSourceStack, BackupService.SnapshotInfo> action) {
        final Instant time;
        try {
            time = Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            source.sendFailure(Component.literal(Translations.text(source, "command.invalid_snapshot_time")));
            return 0;
        }
        return resolveSnapshot(source, snapshot -> time.equals(snapshot.createdAt()),
            "command.snapshot_time_not_found", action);
    }

    private static String snapshotFolderName(BackupService.SnapshotInfo snapshot) {
        // 与 Zstd 快照目录的命名规则一致
        return snapshot.createdAt().toString().replace(':', '-') + "--" + snapshot.snapshot().snapshotId();
    }

    private int withSnapshotFolder(CommandSourceStack source, String name,
            ToIntBiFunction<CommandSourceStack, BackupService.SnapshotInfo> action) {
        int separator = name.lastIndexOf("--");
        int time = name.indexOf('T') + 1;
        if (time > 0 && separator > time) {
            try {
                FabricOperations.parseId(name.substring(separator + 2));
                var instant = Instant.parse(name.substring(0, time) + name.substring(time, separator).replace('-', ':'));
                if ((instant.toString().replace(':', '-') + name.substring(separator)).equals(name)) {
                    return resolveSnapshot(source, snapshot -> snapshotFolderName(snapshot).equals(name),
                        "command.snapshot_folder_not_found", action);
                }
            } catch (IllegalArgumentException | DateTimeParseException invalid) {
                // 格式错误统一在下面报告
            }
        }
        source.sendFailure(Component.literal(Translations.text(source, "command.invalid_snapshot_folder")));
        return 0;
    }

    private int resolveSnapshot(CommandSourceStack source, Predicate<BackupService.SnapshotInfo> match,
            String notFound, ToIntBiFunction<CommandSourceStack, BackupService.SnapshotInfo> action) {
        var service = service(source);
        if (service == null) return 0;
        var matches = new ArrayList<BackupService.SnapshotInfo>(2);
        scanSnapshots(service, Optional.empty(), snapshot -> {
            if (match.test(snapshot)) matches.add(snapshot);
            return matches.size() == 2;
        }).whenComplete((result, failure) -> source.getServer().executeIfPossible(() -> {
            if (operator != service) return;
            if (failure != null || matches.size() != 1) {
                String key = failure != null ? "command.query_failed" : matches.isEmpty()
                    ? notFound : "command.snapshot_time_ambiguous";
                source.sendFailure(Component.literal(Translations.text(source, key)));
                return;
            }
            action.applyAsInt(source, matches.get(0));
        }));
        source.sendSuccess(() -> Component.literal(Translations.text(source, "command.repository_accepted")), false);
        return 1;
    }

    private CompletionStage<Void> scanSnapshots(BackupService service, Optional<String> after,
            Predicate<BackupService.SnapshotInfo> stop) {
        return service.snapshots(REPOSITORY, new PageRequest(SNAPSHOT_LOOKUP_PAGE_SIZE, after)).thenCompose(page -> {
            for (var snapshot : page.items()) {
                if (stop.test(snapshot)) return CompletableFuture.completedFuture(null);
            }
            return page.next().isEmpty() || operator != service ? CompletableFuture.completedFuture(null) :
                scanSnapshots(service, page.next(), stop);
        });
    }

    private LiteralArgumentBuilder<CommandSourceStack> taskCommand(String name, boolean cancel) {
        return Commands.literal(name)
            .then(Commands.literal("fromid").then(Commands.argument("id", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestTasks(builder, cancel, task -> task.state().id().value()))
                .executes(context -> executeTaskCommand(context.getSource(), StringArgumentType.getString(context, "id"), cancel))))
            .then(Commands.argument("time", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestTasks(builder, cancel, task -> task.submittedAt().toString()))
                .executes(context -> taskByTime(context.getSource(), StringArgumentType.getString(context, "time"), cancel)));
    }

    private CompletableFuture<Suggestions> suggestTasks(SuggestionsBuilder builder, boolean cancel,
            Function<TimedTask, String> value) {
        var prefix = builder.getRemainingLowerCase();
        visibleTasks().stream()
            .filter(task -> !cancel || (task.state().phase() != TaskState.Phase.TERMINAL &&
                task.state().phase() != TaskState.Phase.COMMITTING))
            .map(value).filter(candidate -> candidate.regionMatches(true, 0, prefix, 0, prefix.length()))
            .distinct().limit(PAGE_SIZE).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private List<TimedTask> visibleTasks() {
        var binding = commandBinding;
        return binding == null ? List.of() : binding.tasks().stream()
            .sorted(Comparator.comparing(TimedTask::submittedAt).reversed()).toList();
    }

    private int listTasks(CommandSourceStack source) {
        if (service(source) == null) return 0;
        var tasks = visibleTasks();
        if (tasks.isEmpty()) {
            source.sendSuccess(() -> Component.literal(Translations.text(source, "command.tasks_empty")), false);
            return 1;
        }
        var message = new StringBuilder(Translations.text(source, "command.tasks_recent"));
        tasks.stream().limit(PAGE_SIZE).forEach(task -> message.append('\n').append(task.submittedAt())
            .append(" · ").append(taskLabel(source, task.state())).append(" · ").append(task.state().id().value()));
        source.sendSuccess(() -> Component.literal(message.toString()), false);
        return 1;
    }

    private int taskByTime(CommandSourceStack source, String text, boolean cancel) {
        final Instant time;
        try {
            time = Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            // 旧版允许直接输入带引号的任务 ID
            if (taskIdText(text).matches("[a-fA-F0-9-]{36}:[0-9]+")) return executeTaskCommand(source, text, cancel);
            source.sendFailure(Component.literal(Translations.text(source, "command.invalid_task_time")));
            return 0;
        }
        if (service(source) == null) return 0;
        var matches = visibleTasks().stream().filter(task -> time.equals(task.submittedAt())).limit(2).toList();
        if (matches.size() != 1) {
            source.sendFailure(Component.literal(Translations.text(source,
                matches.isEmpty() ? "command.task_unavailable" : "command.task_time_ambiguous")));
            return 0;
        }
        return executeTaskCommand(source, matches.get(0).state().id().value(), cancel);
    }

    private int executeTaskCommand(CommandSourceStack source, String text, boolean cancel) {
        String id = taskIdText(text);
        return cancel ? cancel(source, id) : task(source, id);
    }

    private static String taskIdText(String text) {
        return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"") ?
            text.substring(1, text.length() - 1) : text;
    }

    private static String taskLabel(CommandSourceStack source, TaskState state) {
        return Translations.text(source, "task.operation." + state.operation().name().toLowerCase(Locale.ROOT)) + " · " +
            Translations.text(source, "task.phase." + state.phase().name().toLowerCase(Locale.ROOT));
    }

    /** 在模组初始化阶段创建默认配置，无需等待服务器启动完成。 */
    public static void bootstrapConfig() {
        try {
            ServerConfig.load(true);
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not create server configuration", failure);
        }
        try {
            BackupExclusions.ensureConfig(LoaderBridge.configDirectory().resolve("barusikozako"));
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not create backup ignore configuration", failure);
        }
    }

    public synchronized void onServerStarted(MinecraftServer server) {
        if (workers != null) return;
        this.server = server;
        loadStartupInfo(server);
        var next = new WorldWorkers(WorldStartupGate.takeRestoreNotice(server.getWorldPath(LevelResource.ROOT)));
        workers = next;
        empty = false;
        next.integrations.execute(() -> start(server, next));
        notifyStartup();
    }

    private void loadStartupInfo(MinecraftServer server) {
        try {
            var config = ServerConfig.load(false);
            backupIntervalSeconds = config.backupIntervalSeconds();
            repositoryPath = ServerRepository.forBackend(config.backendKeyword(), server.getWorldPath(LevelResource.ROOT));
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not read startup configuration", failure);
        }
    }

    private void notifyStartup() {
        if (startupNoticeSent) return;
        startupNoticeSent = true;
        LOG.log(System.Logger.Level.INFO, startupNotice(null));
    }

    private String startupNotice(String language) {
        var config = LoaderBridge.configDirectory().resolve("barusikozako").toAbsolutePath().normalize();
        var repository = repositoryPath == null
            ? Translations.languageText(language, "status.unknown")
            : repositoryPath.toAbsolutePath().normalize().toString();
        return String.join("\n",
            String.format(Locale.ROOT, Translations.languageText(language, "notice.startup_config"), config),
            String.format(Locale.ROOT, Translations.languageText(language, "notice.startup_repository"), repository),
            Translations.languageText(language, "notice.startup_schedule"));
    }

    public void onServerStopping() { stop(); }

    public void onServerStopped() {
        var previous = stoppingCapture;
        stoppingCapture = null;
        if (previous != null) previous.awaitCleanup();
    }

    public void tickStart(MinecraftServer server) { captureTick(server, WorldCapture.Edge.TICK_START); }

    public void tickEnd(MinecraftServer server) {
        captureTick(server, WorldCapture.Edge.TICK_END);
        if (!firstPlayerNoticeSent && server.getPlayerList().getPlayerCount() > 0) {
            firstPlayerNoticeSent = true;
            for (var player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(Component.literal(startupNotice(PlayerLanguage.get(player))));
            }
            LOG.log(System.Logger.Level.INFO, startupNotice(null));
        }
        var session = workers;
        if (session == null || session.restoreNotice == null) return;
        for (var player : server.getPlayerList().getPlayers()) {
            var language = PlayerLanguage.get(player);
            if (language == null || !session.restoreNoticeRecipients.add(player.getUUID())) continue;
            var time = session.restoreNotice.snapshotTime().map(Instant::toString)
                .orElseGet(() -> Translations.languageText(language, "notice.snapshot_time_unavailable"));
            var message = String.format(Locale.ROOT, Translations.languageText(language, "notice.world_restored"),
                server.getWorldData().getLevelName(), time);
            player.sendSystemMessage(Component.literal(message));
        }
    }

    private LiteralArgumentBuilder<CommandSourceStack> configCommand(
        String filename, Map<String, String> defaults, YamlSettings.Validator validator) {
        return Commands.literal("config").executes(context -> configPath(context.getSource(), filename))
            .then(Commands.literal("get").then(Commands.argument("key", StringArgumentType.word())
                .suggests((context, builder) -> suggestConfigKeys(defaults, builder))
                .executes(context -> configGet(context.getSource(), filename, defaults,
                    StringArgumentType.getString(context, "key")))))
            .then(Commands.literal("set").then(Commands.argument("key", StringArgumentType.word())
                .suggests((context, builder) -> suggestConfigKeys(defaults, builder))
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
                                LOG.log(System.Logger.Level.WARNING, "Configuration rejected", failure);
                                reply(source, Translations.text(source, "command.config_rejected"), true);
                            }
                        });
                        return 1;
                    } catch (java.util.concurrent.RejectedExecutionException unavailable) {
                        return 0;
                    }
                }))));
    }

    private static java.util.concurrent.CompletableFuture<Suggestions> suggestConfigKeys(
            Map<String, String> defaults, SuggestionsBuilder builder) {
        defaults.keySet().stream().sorted().filter(key -> !YamlSettings.SCHEMA.equals(key))
            .filter(key -> key.startsWith(builder.getRemaining()))
            .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private int configGet(CommandSourceStack source, String filename, Map<String, String> defaults, String key) {
        if (!defaults.containsKey(key)) {
            source.sendFailure(Component.literal(Translations.format(source, "command.config_unknown", key)));
            return 0;
        }
        var active = workers;
        if (active == null) return 0;
        try {
            active.integrations.execute(() -> {
                try {
                    var file = LoaderBridge.configDirectory().resolve("barusikozako").resolve(filename + ".yml");
                    var settings = YamlSettings.load(file, defaults, false, () -> Map.of());
                    reply(source, Translations.format(source, "command.config_value", key, settings.get(key)), false);
                } catch (IOException | RuntimeException failure) {
                    LOG.log(System.Logger.Level.WARNING, "Could not read configuration", failure);
                    reply(source, Translations.text(source, "command.config_read_failed"), true);
                }
            });
            return 1;
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            return 0;
        }
    }

    private static int configPath(CommandSourceStack source, String filename) {
        source.sendSuccess(() -> Component.literal(Translations.format(source, "command.config_path", filename)), false);
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
            source.sendFailure(Component.literal(Translations.format(source, "command.backend_inactive", backend)));
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
                backupIntervalSeconds = config.backupIntervalSeconds();
                repositoryPath = operations.repositoryPath();
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
        var state = operator == null ? "command.service_unavailable"
            : capture == null || !capture.available() ? "command.capture_unavailable" : "command.capture_enabled";
        var lines = new StringBuilder("BaruSikoZako ").append(product).append(" · ")
            .append(Translations.text(source, state));
        lines.append('\n').append(Translations.format(source, "status.backend",
            activeBackend.isEmpty() ? Translations.text(source, "status.backend_none") : activeBackend));
        lines.append('\n').append(Translations.format(source, "status.repository",
            repositoryPath == null ? Translations.text(source, "status.unknown")
                : repositoryPath.toAbsolutePath().normalize()));
        lines.append('\n').append(backupIntervalSeconds > 0
            ? Translations.format(source, "status.schedule", backupIntervalSeconds)
            : Translations.text(source, "status.schedule_disabled"));
        if (lastBackupTime == null || lastBackupResult == null) {
            lines.append('\n').append(Translations.text(source, "status.last_backup_none"));
        } else {
            lines.append('\n').append(Translations.format(source, "status.last_backup",
                lastBackupTime, summarize(source, lastBackupResult)));
        }
        lines.append('\n').append(Translations.format(source, "status.config",
            LoaderBridge.configDirectory().resolve("barusikozako").toAbsolutePath().normalize()));
        source.sendSuccess(() -> Component.literal(lines.toString()), false);
        return 1;
    }

    private void scheduledBackup(BackupService service) {
        if (service != scheduler) return;
        var request = new BackupRequest.Backup(REPOSITORY, SOURCE,
            new BackupRequest.Trigger("schedule", "periodic", Map.of()));
        var submission = service.submit(request);
        if (submission instanceof BackupService.Submission.Accepted accepted) {
            accepted.completion().whenComplete((result, failure) -> {
                lastBackupTime = Instant.now();
                if (failure != null) {
                    LOG.log(System.Logger.Level.WARNING,
                        "Scheduled backup " + accepted.id().value() + " failed", failure);
                    notifyAdmins("notice.scheduled_failed");
                    return;
                }
                lastBackupResult = result;
                var summary = summarize(null, result);
                if (result instanceof TaskResult.Failed || result instanceof TaskResult.CommitUncertain) {
                    LOG.log(System.Logger.Level.WARNING, "Scheduled backup {0} failed: {1}", accepted.id().value(), summary);
                    notifyAdmins("notice.scheduled_failed");
                } else {
                    LOG.log(System.Logger.Level.INFO, "Scheduled backup {0}: {1}", accepted.id().value(), summary);
                }
            });
        } else {
            LOG.log(System.Logger.Level.WARNING, "Scheduled backup rejected: {0}", rejection(null, submission));
            notifyAdmins("notice.scheduled_rejected");
        }
    }

    private void notifyAdmins(String key) {
        var running = server;
        if (running == null) return;
        for (var player : running.getPlayerList().getPlayers()) {
            if (!GameCommandAccess.isAdministrator(player.createCommandSourceStack())) continue;
            player.sendSystemMessage(Component.literal(Translations.playerText(player, key)));
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
            var message = new StringBuilder(Translations.text(source, "command.snapshots_list"));
            for (var entry : page.items())
                message.append('\n').append(entry.snapshot().snapshotId()).append("  ").append(entry.createdAt())
                    .append("  ").append(Translations.text(source,
                        "consistency." + entry.consistency().name().toLowerCase(Locale.ROOT)));
            page.next().ifPresent(id -> message.append('\n').append(Translations.format(source, "command.snapshots_next", id)));
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
            reply(source, io.github.zct_studio.barusikozako.runtime.host.RuleNotice.forPlayer(rules,
                Translations.format(source, "command.ignore_rules", rules.size()),
                Translations.text(source, "command.ignore_rules_more")), false);
        });
        if (submission instanceof BackupService.Submission.Accepted accepted) {
            accepted.completion().whenComplete((result, failure) -> {
                if (failure != null) {
                    reply(source, Translations.format(source, "command.task_delivery_failed", accepted.id().value()), true);
                } else {
                    if (request instanceof BackupRequest.Backup) {
                        lastBackupTime = Instant.now();
                        lastBackupResult = result;
                    }
                    String message = result instanceof TaskResult.VerificationComplete verified &&
                        request instanceof BackupRequest.Verify verify ?
                        Translations.format(source, "result.verified_snapshot", verify.snapshot().snapshotId(),
                            verified.entries(), verified.bytes()) : summarize(source, result);
                    reply(source, Translations.format(source, "command.task_result", accepted.id().value(), message), result instanceof TaskResult.Failed ||
                        result instanceof TaskResult.CommitUncertain);
                }
            });
            String key = request.kind() == BackupRequest.Kind.BACKUP ? "command.backup_requested" :
                request.kind() == BackupRequest.Kind.RESTORE ? "command.restore_requested" : "command.task_requested";
            source.sendSuccess(() -> Component.literal(Translations.format(source, key, accepted.id().value())), false);
            return 1;
        }
        source.sendFailure(Component.literal(Translations.format(source, "command.request_rejected", rejection(source, submission))));
        return 0;
    }

    private static String rejection(CommandSourceStack source, BackupService.Submission submission) {
        var reason = ((BackupService.Submission.Rejected) submission).reason();
        return Translations.text(source, "rejection." + reason.name().toLowerCase(Locale.ROOT));
    }

    private static String summarize(CommandSourceStack source, TaskResult result) {
        if (result instanceof TaskResult.VerificationComplete verified) {
            return Translations.format(source, "result.verified", verified.entries(), verified.bytes());
        }
        if (result instanceof TaskResult.BackupCommitted committed) {
            var elapsed = committed.elapsed().map(value -> " · " + Translations.format(source, "result.elapsed", value.toMillis())).orElse("");
            var size = committed.archiveBytes().isPresent() ?
                " · " + Translations.format(source, "result.archive_bytes", committed.archiveBytes().getAsLong()) :
                committed.logicalBytes().isPresent() ?
                    " · " + Translations.format(source, "result.logical_bytes", committed.logicalBytes().getAsLong()) : "";
            return Translations.format(source, "result.backup_committed", committed.snapshot().snapshotId(), elapsed, size);
        }
        if (result instanceof TaskResult.RestoreQueued value) {
            return Translations.format(source, "result.restore_queued", value.snapshot().snapshotId());
        }
        if (result instanceof TaskResult.Deleted deleted) {
            return Translations.format(source, "result.deleted", deleted.snapshot().snapshotId());
        }
        if (result instanceof TaskResult.Failed failed) {
            return switch (failed.reason()) {
                case NATIVE_ACCESS_DENIED -> Translations.text(source, "failure.native_access_denied");
                case DIRECTORY_SYNC_FAILED -> Translations.text(source, "failure.directory_sync_failed");
                default -> Translations.format(source, "result.failed",
                    Translations.text(source, "failure." + failed.reason().name().toLowerCase(Locale.ROOT)));
            };
        }
        if (result instanceof TaskResult.CommitUncertain) {
            return Translations.text(source, "result.uncertain");
        }
        if (result instanceof TaskResult.Cancelled) {
            return Translations.text(source, "result.cancelled");
        }
        return Translations.text(source, "result.completed");
    }

    private int cancel(CommandSourceStack source, String id) {
        var service = service(source);
        if (service == null) return 0;
        if (id.isBlank() || id.length() > 64) {
            source.sendFailure(Component.literal(Translations.text(source, "command.invalid_task")));
            return 0;
        }
        var result = service.cancel(new TaskId(id));
        source.sendSuccess(() -> Component.literal(Translations.text(source, "cancel." + result.name().toLowerCase(Locale.ROOT))), false);
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
        var submittedAt = visibleTasks().stream().filter(task -> task.state().id().equals(value.id())).findFirst()
            .map(task -> task.submittedAt() + " · ").orElse("");
        var progress = Long.toString(value.processedBytes()) +
            (value.totalBytes().isPresent() ? "/" + value.totalBytes().getAsLong() : "");
        source.sendSuccess(() -> Component.literal(submittedAt + taskLabel(source, value) + " · " + id + " · " +
            Translations.format(source, "command.task_progress", progress, value.elapsed().toMillis()) +
            value.result().map(result -> " · " + summarize(source, result)).orElse("")), false);
        return 1;
    }

    private BackupService service(CommandSourceStack source) {
        var service = operator;
        if (service == null) source.sendFailure(Component.literal(Translations.text(source, "command.service_unavailable")));
        return service;
    }

    private int withId(CommandSourceStack source, String text,
            ToIntBiFunction<CommandSourceStack, BackupService.SnapshotInfo> action) {
        try {
            FabricOperations.parseId(text);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal(Translations.text(source, "command.invalid_snapshot")));
            return 0;
        }
        return resolveSnapshot(source, snapshot -> snapshot.snapshot().snapshotId().equals(text),
            "command.snapshot_not_found", action);
    }

    private static void reply(CommandSourceStack source, String message, boolean failure) {
        var component = Component.literal(message);
        source.getServer().executeIfPossible(() -> {
            if (failure) source.sendFailure(component);
            else source.sendSuccess(() -> component, false);
        });
    }
}
