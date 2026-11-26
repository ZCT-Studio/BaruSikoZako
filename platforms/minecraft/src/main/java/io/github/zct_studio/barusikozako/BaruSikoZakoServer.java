// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.backend.ConfigUnits;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

public final class BaruSikoZakoServer {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private static final int PAGE_SIZE = 4;
    private static final int SUGGESTION_LIMIT = 20;
    private static final int SNAPSHOT_LOOKUP_PAGE_SIZE = 256;
    /** 快照列表使用的 UTC 时间格式 */
    private static final DateTimeFormatter SNAPSHOT_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
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
    private volatile BackupProgressDisplay progressDisplay;
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
            .then(Commands.literal("version").executes(context -> version(context.getSource())))
            .then(Commands.literal("help").executes(context -> help(context.getSource())))
            .then(configCommand("server", ServerConfig.defaults(), values -> {
                var config = ServerConfig.parse(values);
                if (!BackendRegistry.registered().containsKey(config.backendKeyword()) &&
                    !config.backendKeyword().equals(configuredBackend()))
                    throw new IllegalArgumentException("Backend is not installed: " + config.backendKeyword());
            }))
            .then(Commands.literal("list")
                .executes(context -> list(context.getSource(), 1))
                .then(Commands.literal("page").then(Commands.argument("page",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                    .executes(context -> list(context.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "page"))))))
            .then(snapshotSelection("detail", BaruSikoZakoServer::detail))
            .then(snapshotCommand("verify",
                snapshot -> new BackupRequest.Verify(snapshot, BackupRequest.VerificationLevel.FULL)))
            .then(snapshotCommand("delete", BackupRequest.Delete::new))
            .then(snapshotSelection("rollback", (source, snapshot) -> {
                if (snapshot.consistency() == BackupService.Consistency.RELAXED)
                    Chat.line(source, Chat.Tone.WARNING, Translations.text(source, "notice.relaxed_restore"));
                return submit(source, new BackupRequest.Restore(snapshot.snapshot(), SOURCE));
            }))
            .then(taskCommand("cancel", true))
            .then(taskCommand("task", false)
                .executes(context -> listTasks(context.getSource(), 1))
                .then(Commands.literal("page").then(Commands.argument("page",
                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                    .executes(context -> listTasks(context.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "page"))))));
        var backup = Commands.literal("backup")
            .executes(context -> submitBackup(context.getSource(), BackupRequest.BackupMode.CONFIGURED, Optional.empty()));
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
                backup.then(node);
            }
        }
        root.then(backup);
        return root;
    }

    private static int help(CommandSourceStack source) {
        Chat.lines(source, Chat.Tone.INFO, Translations.text(source, "help.header"), List.of(
            Translations.text(source, "help.status"),
            Translations.text(source, "help.version"),
            Translations.text(source, "help.backup"),
            Translations.text(source, "help.list"),
            Translations.text(source, "help.detail"),
            Translations.text(source, "help.verify"),
            Translations.text(source, "help.delete"),
            Translations.text(source, "help.rollback"),
            Translations.text(source, "help.cancel"),
            Translations.text(source, "help.task"),
            Translations.text(source, "help.config"),
            Translations.text(source, "help.help"),
            Translations.text(source, "help.backend"),
            Translations.text(source, "help.footer")));
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
                .executes(context -> withSnapshotFolder(context.getSource(), StringArgumentType.getString(context, "folder"), action))))
            .then(Commands.argument("time", StringArgumentType.greedyString())
                .suggests((context, builder) -> suggestSnapshots(builder, item -> item.createdAt().toString()))
                .executes(context -> withSnapshotTime(context.getSource(), StringArgumentType.getString(context, "time"), action)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> snapshotUuid(
            ToIntBiFunction<CommandSourceStack, BackupService.SnapshotInfo> action) {
        return Commands.literal("fromuuid").then(Commands.argument("uuid", StringArgumentType.word())
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
            return matches.size() >= SUGGESTION_LIMIT;
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
            Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.invalid_snapshot_time"));
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
        Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.invalid_snapshot_folder"));
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
                Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, key));
                return;
            }
            action.applyAsInt(source, matches.get(0));
        }));
        Chat.line(source, Chat.Tone.INFO, Translations.text(source, "command.repository_accepted"));
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

    private int listTasks(CommandSourceStack source, int page) {
        if (service(source) == null) return 0;
        var tasks = visibleTasks();
        if (tasks.isEmpty()) {
            Chat.line(source, Chat.Tone.INFO, Translations.text(source, "command.tasks_empty"));
            return 1;
        }
        int requested = Math.min(Math.max(1, page), 1024);
        int from = (requested - 1) * PAGE_SIZE;
        if (from >= tasks.size()) {
            Chat.line(source, Chat.Tone.INFO, Translations.text(source, "command.tasks_empty"));
            return 1;
        }
        int to = Math.min(tasks.size(), from + PAGE_SIZE);
        var blocks = new ArrayList<List<List<Chat.Piece>>>();
        for (var task : tasks.subList(from, to)) blocks.add(taskBlock(source, task));
        var navigation = new ArrayList<Chat.Piece>();
        if (requested > 1) navigation.add(Chat.suggest(Translations.text(source, "action.previous_page"),
            "/barusikozako task page " + (requested - 1)));
        if (tasks.size() > to) {
            if (!navigation.isEmpty()) navigation.add(Chat.text(" "));
            navigation.add(Chat.suggest(Translations.text(source, "action.next_page"),
                "/barusikozako task page " + (requested + 1)));
        }
        if (!navigation.isEmpty()) blocks.add(List.of(navigation));
        Chat.blocks(source, Chat.Tone.INFO, Translations.format(source, "command.tasks_page", requested), blocks);
        return 1;
    }

    /** 任务条目三行：时间/ID/操作人，状态与结果，操作按钮 */
    private static List<List<Chat.Piece>> taskBlock(CommandSourceStack source, TimedTask task) {
        var state = task.state();
        var first = new ArrayList<Chat.Piece>();
        first.add(Chat.copy(SNAPSHOT_TIME.format(task.submittedAt()), task.submittedAt().toString()));
        first.add(Chat.text(" · "));
        first.add(taskId(state.id().value()));
        first.add(Chat.text(" · "));
        first.addAll(operatorPieces(source, state.operator()));

        var second = new ArrayList<Chat.Piece>();
        second.add(Chat.text(taskLabel(source, state)));
        second.add(Chat.text(" · "));
        second.add(Chat.text(progressText(state)));
        state.result().ifPresent(result -> {
            second.add(Chat.text(" · "));
            second.addAll(summaryPieces(source, result));
        });

        var third = new ArrayList<Chat.Piece>();
        if (cancellable(state)) {
            third.add(Chat.suggest(Translations.text(source, "action.cancel"),
                "/barusikozako cancel fromid " + state.id().value()));
            third.add(Chat.text(" "));
        }
        third.add(Chat.suggest(Translations.text(source, "action.inspect"),
            "/barusikozako task fromid " + state.id().value()));
        return List.<List<Chat.Piece>>of(first, second, third);
    }

    private static boolean cancellable(TaskState state) {
        return state.phase() != TaskState.Phase.TERMINAL && state.phase() != TaskState.Phase.COMMITTING;
    }

    /** 任务 ID 以短格式显示，点击复制完整值 */
    private static Chat.Piece taskId(String id) {
        return Chat.copy(Chat.shortId(id), id);
    }

    private int taskByTime(CommandSourceStack source, String text, boolean cancel) {
        final Instant time;
        try {
            time = Instant.parse(text);
        } catch (DateTimeParseException invalid) {
            // 旧版允许直接输入带引号的任务 ID
            if (taskIdText(text).matches("[a-fA-F0-9-]{36}:[0-9]+")) return executeTaskCommand(source, text, cancel);
            Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.invalid_task_time"));
            return 0;
        }
        if (service(source) == null) return 0;
        var matches = visibleTasks().stream().filter(task -> time.equals(task.submittedAt())).limit(2).toList();
        if (matches.size() != 1) {
            Chat.line(source, Chat.Tone.FAILURE, Translations.text(source,
                matches.isEmpty() ? "command.task_unavailable" : "command.task_time_ambiguous"));
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
        ProgressConfig.load(LoaderBridge.configDirectory().resolve("barusikozako"));
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
        var config = LoaderBridge.configDirectory().resolve("barusikozako");
        var repository = repositoryPath == null
            ? Translations.languageText(language, "status.unknown")
            : displayPath(repositoryPath);
        return String.join("\n",
            String.format(Locale.ROOT, Translations.languageText(language, "notice.startup_config"), displayPath(config)),
            String.format(Locale.ROOT, Translations.languageText(language, "notice.startup_repository"), repository),
            Translations.languageText(language, "notice.startup_schedule"));
    }

    /** 向玩家播报启动信息表格，内容与服务器日志保持一致 */
    private void sendStartupNotice(CommandSourceStack source) {
        var config = LoaderBridge.configDirectory().resolve("barusikozako").toAbsolutePath().normalize();
        var rows = new ArrayList<Chat.Row>();
        rows.add(Chat.row(Translations.text(source, "label.config"), displayPath(config)));
        rows.add(Chat.row(Translations.text(source, "label.repository"), repositoryPath == null
            ? Translations.text(source, "status.unknown")
            : displayPath(repositoryPath)));
        rows.add(Chat.row(Translations.text(source, "label.schedule"),
            Translations.text(source, "notice.startup_schedule")));
        Chat.send(source, Chat.Tone.INFO, "BaruSikoZako " + LoaderBridge.productVersion(), rows);
    }

    /** 仅在版本标记缺失或与当前模组版本不一致时向首个上线管理员发送启动提示 */
    private boolean startupPromptDue() {
        var repository = repositoryPath;
        if (repository == null) return true;
        var marker = repository.resolve(".cache").resolve("admin_prompted_version");
        try {
            if (Files.notExists(marker)) return true;
            return !LoaderBridge.productVersion().equals(Files.readString(marker).strip());
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not read the startup notice version marker", failure);
            return true;
        }
    }

    /** 记录已提示的模组版本；失败只影响下次是否重复提示 */
    private void markStartupPrompted() {
        var repository = repositoryPath;
        if (repository == null) return;
        var cache = repository.resolve(".cache");
        var marker = cache.resolve("admin_prompted_version");
        try {
            Files.createDirectories(cache);
            Files.writeString(marker, LoaderBridge.productVersion(),
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                try {
                    Files.setAttribute(cache, "dos:hidden", true);
                } catch (IOException | UnsupportedOperationException ignored) {
                    // 无法设置隐藏属性时仍保留点号前缀目录
                }
            }
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not record the startup notice version", failure);
        }
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
        var display = progressDisplay;
        if (display != null) display.tick(server);
        if (!firstPlayerNoticeSent) {
            for (var player : server.getPlayerList().getPlayers()) {
                var source = player.createCommandSourceStack();
                if (!GameCommandAccess.isAdministrator(source)) continue;
                firstPlayerNoticeSent = true;
                if (startupPromptDue()) {
                    sendStartupNotice(source);
                    markStartupPrompted();
                    LOG.log(System.Logger.Level.INFO, startupNotice(null));
                }
                break;
            }
        }
        var session = workers;
        if (session == null || session.restoreNotice == null) return;
        for (var player : server.getPlayerList().getPlayers()) {
            var language = PlayerLanguage.get(player);
            if (language == null || !session.restoreNoticeRecipients.add(player.getUUID())) continue;
            var source = player.createCommandSourceStack();
            var time = session.restoreNotice.snapshotTime();
            var timeRow = time.isPresent()
                ? Chat.row(Translations.text(source, "label.time"),
                    Chat.copy(time.orElseThrow().toString(), time.orElseThrow().toString()))
                : Chat.row(Translations.text(source, "label.time"),
                    Translations.text(source, "notice.snapshot_time_unavailable"));
            Chat.send(source, Chat.Tone.SUCCESS,
                Translations.format(source, "notice.world_restored", server.getWorldData().getLevelName()),
                List.of(timeRow));
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
                                Chat.line(source, Chat.Tone.SUCCESS, Translations.text(source, "command.config_saved"));
                            } catch (IOException | IllegalArgumentException failure) {
                                LOG.log(System.Logger.Level.WARNING, "Configuration rejected", failure);
                                Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.config_rejected"));
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
            Chat.line(source, Chat.Tone.FAILURE, Translations.format(source, "command.config_unknown", key));
            return 0;
        }
        var active = workers;
        if (active == null) return 0;
        try {
            active.integrations.execute(() -> {
                try {
                    var file = LoaderBridge.configDirectory().resolve("barusikozako").resolve(filename + ".yml");
                    var settings = YamlSettings.load(file, defaults, false, () -> Map.of());
                    Chat.line(source, Chat.Tone.INFO,
                        Translations.format(source, "command.config_value", key, settings.get(key)));
                } catch (IOException | RuntimeException failure) {
                    LOG.log(System.Logger.Level.WARNING, "Could not read configuration", failure);
                    Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.config_read_failed"));
                }
            });
            return 1;
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            return 0;
        }
    }

    private static int configPath(CommandSourceStack source, String filename) {
        Chat.line(source, Chat.Tone.INFO, Translations.format(source, "command.config_path", filename));
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
            Chat.line(source, Chat.Tone.FAILURE, Translations.format(source, "command.backend_inactive", backend));
            return 0;
        }
        return submitBackup(source, mode, baseline);
    }

    private int submitBackup(CommandSourceStack source, BackupRequest.BackupMode mode, Optional<SnapshotRef> baseline) {
        return submit(source, new BackupRequest.Backup(REPOSITORY, SOURCE,
            new BackupRequest.Trigger("command", "manual", snapshotMetadata(source)), mode, baseline));
    }

    /** 快照附加元信息：操作人、游戏版本、模组版本与后端版本 */
    private Map<String, String> snapshotMetadata(CommandSourceStack source) {
        var metadata = new java.util.LinkedHashMap<>(runtimeMetadata());
        metadata.putAll(operatorMetadata(source));
        return metadata;
    }

    private Map<String, String> runtimeMetadata() {
        var metadata = new java.util.LinkedHashMap<String, String>();
        var running = server;
        metadata.put("gameVersion", running == null ? "unknown" : running.getServerVersion());
        metadata.put("modVersion", LoaderBridge.productVersion());
        metadata.put("backendVersion", activeBackend.isEmpty() ? "unknown"
            : BackendRegistry.versions().getOrDefault(activeBackend, "unknown"));
        return metadata;
    }

    /** 记录备份操作人；控制台与命令方块没有玩家时由界面显示为 Server */
    private static Map<String, String> operatorMetadata(CommandSourceStack source) {
        return operator(source)
            .<Map<String, String>>map(value -> Map.of("operator", value.name(),
                "operatorUuid", value.id().map(java.util.UUID::toString).orElse("")))
            .orElse(Map.of());
    }

    /** 当前命令执行者；没有玩家实体时为空，界面按 Server 展示 */
    private static Optional<BackupService.BackupOperator> operator(CommandSourceStack source) {
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
            return Optional.of(new BackupService.BackupOperator(player.getScoreboardName(),
                Optional.of(player.getUUID())));
        return Optional.empty();
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
                progressDisplay = new BackupProgressDisplay(
                    ProgressConfig.load(LoaderBridge.configDirectory().resolve("barusikozako")),
                    this::visibleTasks, operations::relaxedCapture);
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
                "Online backups capture files while the server runs; cross-file state is not atomic");
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
        BackupProgressDisplay display;
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
            display = progressDisplay;
            progressDisplay = null;
        }
        if (display != null) display.close();
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
        var rows = new ArrayList<Chat.Row>();
        rows.add(Chat.row(Translations.text(source, "label.service"), Translations.text(source, state)));
        rows.add(Chat.row(Translations.text(source, "label.backend"), activeBackend.isEmpty()
            ? Translations.text(source, "status.backend_none") : activeBackend));
        rows.add(Chat.row(Translations.text(source, "label.repository"), repositoryPath == null
            ? Translations.text(source, "status.unknown")
            : displayPath(repositoryPath)));
        rows.add(Chat.row(Translations.text(source, "label.schedule"), backupIntervalSeconds > 0
            ? Translations.format(source, "status.schedule_every", backupIntervalSeconds)
            : Translations.text(source, "status.schedule_off")));
        if (lastBackupTime == null || lastBackupResult == null) {
            rows.add(Chat.row(Translations.text(source, "label.last_backup"),
                Translations.text(source, "status.none")));
        } else if (lastBackupResult instanceof TaskResult.BackupCommitted committed) {
            rows.add(Chat.row(Translations.text(source, "label.last_backup"),
                Chat.copy(SNAPSHOT_TIME.format(lastBackupTime), lastBackupTime.toString()),
                Chat.text(" · "), snapshotId(committed.snapshot().snapshotId())));
        } else {
            var value = new ArrayList<Chat.Piece>();
            value.add(Chat.copy(SNAPSHOT_TIME.format(lastBackupTime), lastBackupTime.toString()));
            value.add(Chat.text(" · "));
            value.addAll(summaryPieces(source, lastBackupResult));
            rows.add(Chat.row(Translations.text(source, "label.last_backup"),
                value.toArray(Chat.Piece[]::new)));
        }
        rows.add(Chat.row(Translations.text(source, "label.config"),
            displayPath(LoaderBridge.configDirectory().resolve("barusikozako"))));
        Chat.send(source, Chat.Tone.INFO, "BaruSikoZako " + product, rows);
        return 1;
    }

    /** 打印本体、API 与已注册后端的版本信息 */
    private static int version(CommandSourceStack source) {
        var rows = new ArrayList<Chat.Row>();
        rows.add(Chat.row(Translations.text(source, "label.mod_version"), LoaderBridge.productVersion()));
        var api = LoaderBridge.apiVersion();
        if (api != null) rows.add(Chat.row(Translations.text(source, "label.api_version"), api));
        var versions = BackendRegistry.versions();
        var backends = versions.entrySet().stream()
            .map(entry -> entry.getKey() + " " + entry.getValue()).toList();
        rows.add(Chat.row(Translations.text(source, "label.backends"),
            backends.isEmpty() ? Translations.text(source, "status.none") : String.join(" · ", backends)));
        Chat.send(source, Chat.Tone.INFO, Translations.text(source, "chat.title.version"), rows);
        return 1;
    }

    /** 相对于游戏根目录显示路径，无法相对化时回退为绝对路径 */
    private String displayPath(Path path) {
        var absolute = path.toAbsolutePath().normalize();
        var current = server;
        if (current == null) return absolute.toString();
        var root = current.getWorldPath(LevelResource.ROOT).getParent();
        if (root == null) return absolute.toString();
        var base = root.toAbsolutePath().normalize();
        if (!absolute.startsWith(base)) return absolute.toString();
        var relative = base.relativize(absolute).toString().replace('\\', '/');
        return relative.isEmpty() ? "." : relative;
    }

    private void scheduledBackup(BackupService service) {
        if (service != scheduler) return;
        var request = new BackupRequest.Backup(REPOSITORY, SOURCE,
            new BackupRequest.Trigger("schedule", "periodic", runtimeMetadata()));
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
            var source = player.createCommandSourceStack();
            if (!GameCommandAccess.isAdministrator(source)) continue;
            Chat.line(source, Chat.Tone.WARNING, Translations.playerText(player, key));
        }
    }

    private int list(CommandSourceStack source, int page) {
        var service = service(source);
        if (service == null) return 0;
        int requested = Math.min(Math.max(1, page), 1024);
        int limit = requested * PAGE_SIZE + 1;
        service.snapshots(REPOSITORY, new PageRequest(limit, Optional.empty())).whenComplete((result, failure) -> {
            if (failure != null) {
                Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.query_failed"));
                return;
            }
            var items = result.items();
            int from = (requested - 1) * PAGE_SIZE;
            if (from >= items.size()) {
                Chat.line(source, Chat.Tone.INFO, Translations.text(source, "command.snapshots_empty"));
                return;
            }
            int to = Math.min(items.size(), from + PAGE_SIZE);
            var blocks = new ArrayList<List<List<Chat.Piece>>>();
            for (var entry : items.subList(from, to)) blocks.add(snapshotBlock(source, entry));
            var navigation = new ArrayList<Chat.Piece>();
            if (requested > 1) navigation.add(Chat.suggest(Translations.text(source, "action.previous_page"),
                "/barusikozako list page " + (requested - 1)));
            if (items.size() > to) {
                if (!navigation.isEmpty()) navigation.add(Chat.text(" "));
                navigation.add(Chat.suggest(Translations.text(source, "action.next_page"),
                    "/barusikozako list page " + (requested + 1)));
            }
            if (!navigation.isEmpty()) blocks.add(List.of(navigation));
            Chat.blocks(source, Chat.Tone.INFO,
                Translations.format(source, "command.snapshots_page", requested), blocks);
        });
        Chat.line(source, Chat.Tone.INFO, Translations.text(source, "command.repository_accepted"));
        return 1;
    }

    /** 快照条目三行：时间/UUID/操作人，类型/约束/体积/耗时，操作按钮 */
    private static List<List<Chat.Piece>> snapshotBlock(CommandSourceStack source, BackupService.SnapshotInfo entry) {
        var first = new ArrayList<Chat.Piece>();
        first.add(Chat.copy(SNAPSHOT_TIME.format(entry.createdAt()), entry.createdAt().toString()));
        first.add(Chat.text(" · "));
        first.add(snapshotId(entry.snapshot().snapshotId()));
        first.add(Chat.text(" · "));
        first.addAll(operatorPieces(source, entry.operator()));

        var second = new ArrayList<Chat.Piece>();
        second.add(Chat.text(entry.layout()
            .map(layout -> Translations.text(source, "snapshot.layout." + layout.name().toLowerCase(Locale.ROOT)))
            .orElse("—")));
        boolean relaxed = entry.consistency() == BackupService.Consistency.RELAXED;
        second.add(Chat.colored(Translations.text(source,
            relaxed ? "consistency.short.relaxed" : "consistency.short.strict"), relaxed ? 0xFFFF55 : 0x55FF55));
        var size = entry.archiveBytes().isPresent() ? entry.archiveBytes() : entry.logicalBytes();
        second.add(Chat.text(" · "));
        second.add(Chat.text(size.isPresent() ? bytes(size.getAsLong()) : "—"));
        second.add(Chat.text(" · "));
        second.add(Chat.text(entry.elapsed().map(ConfigUnits::formatDuration).orElse("—")));

        String id = entry.snapshot().snapshotId();
        var third = List.of(
            snapshotAction(source, "action.detail", "detail", id),
            Chat.text(" "),
            snapshotAction(source, "action.rollback", "rollback", id),
            Chat.text(" "),
            snapshotAction(source, "action.verify", "verify", id),
            Chat.text(" "),
            snapshotAction(source, "action.delete", "delete", id));
        return List.<List<Chat.Piece>>of(first, second, third);
    }

    private static Chat.Piece snapshotAction(CommandSourceStack source, String key, String command, String id) {
        return Chat.suggest(Translations.text(source, key), "/barusikozako " + command + " fromuuid " + id);
    }

    /** 快照详细信息：基础展示字段与备份时记录的运行环境 */
    private static int detail(CommandSourceStack source, BackupService.SnapshotInfo entry) {
        var rows = new ArrayList<Chat.Row>();
        rows.add(Chat.row(Translations.text(source, "label.time"),
            Chat.copy(SNAPSHOT_TIME.format(entry.createdAt()), entry.createdAt().toString())));
        rows.add(Chat.row(Translations.text(source, "label.snapshot"),
            snapshotId(entry.snapshot().snapshotId())));
        rows.add(Chat.row(Translations.text(source, "label.operator"),
            operatorPieces(source, entry.operator()).toArray(Chat.Piece[]::new)));
        rows.add(Chat.row(Translations.text(source, "label.layout"), entry.layout()
            .map(layout -> Translations.text(source, "snapshot.layout." + layout.name().toLowerCase(Locale.ROOT)))
            .orElse("—")));
        rows.add(Chat.row(Translations.text(source, "label.consistency"),
            Translations.text(source, "consistency." + entry.consistency().name().toLowerCase(Locale.ROOT))));
        var size = entry.archiveBytes().isPresent() ? entry.archiveBytes() : entry.logicalBytes();
        rows.add(Chat.row(Translations.text(source, "label.size"),
            size.isPresent() ? bytes(size.getAsLong()) : "—"));
        rows.add(Chat.row(Translations.text(source, "label.elapsed"),
            entry.elapsed().map(ConfigUnits::formatDuration).orElse("—")));
        var metadata = entry.trigger().map(BackupRequest.Trigger::metadata).orElse(Map.of());
        rows.add(Chat.row(Translations.text(source, "label.game_version"),
            metadata.getOrDefault("gameVersion", "unknown")));
        rows.add(Chat.row(Translations.text(source, "label.mod_version"),
            metadata.getOrDefault("modVersion", "unknown")));
        rows.add(Chat.row(Translations.text(source, "label.backend_version"),
            metadata.getOrDefault("backendVersion", "unknown")));
        String id = entry.snapshot().snapshotId();
        rows.add(Chat.row(Translations.text(source, "label.action"),
            snapshotAction(source, "action.rollback", "rollback", id),
            Chat.text(" "),
            snapshotAction(source, "action.verify", "verify", id),
            Chat.text(" "),
            snapshotAction(source, "action.delete", "delete", id)));
        Chat.send(source, Chat.Tone.INFO, Translations.text(source, "chat.title.detail"), rows);
        return 1;
    }

    /** 快照 UUID 以短格式显示，点击复制完整值 */
    private static Chat.Piece snapshotId(String id) {
        return Chat.copy(Chat.shortId(id), id);
    }

    /** 忽略规则行：文件来源灰色，规则内容保持默认颜色 */
    private static List<Chat.Piece> rulePieces(String line) {
        int separator = line.indexOf(": ");
        if (separator < 0) return List.of(Chat.text(line));
        return List.of(Chat.colored(line.substring(0, separator + 2), 0xAAAAAA),
            Chat.text(line.substring(separator + 2)));
    }

    /** 操作人显示为「玩家名#截断UUID」，两段分别可复制；缺少记录时显示 Server */
    private static List<Chat.Piece> operatorPieces(CommandSourceStack source,
            Optional<BackupService.BackupOperator> operator) {
        if (operator.isEmpty()) return List.of(Chat.colored(Translations.text(source, "operator.server"), 0xAAAAAA));
        var value = operator.orElseThrow();
        var pieces = new ArrayList<Chat.Piece>(3);
        pieces.add(Chat.copy(value.name(), value.name()));
        value.id().ifPresent(id -> {
            pieces.add(Chat.text("#"));
            pieces.add(Chat.copy(Chat.shortId(id.toString()), id.toString()));
        });
        return pieces;
    }

    /** 从触发器元数据读取操作人；旧快照没有记录时返回空值 */
    private static Optional<BackupService.BackupOperator> operator(BackupRequest.Trigger trigger) {
        var name = trigger.metadata().get("operator");
        if (name == null || name.isBlank()) return Optional.empty();
        java.util.UUID id = null;
        try {
            var value = trigger.metadata().get("operatorUuid");
            if (value != null) id = java.util.UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            // 操作人名仍可展示
        }
        return Optional.of(new BackupService.BackupOperator(name, Optional.ofNullable(id)));
    }

    private int submit(CommandSourceStack source, BackupRequest request) {
        var service = service(source);
        if (service == null) return 0;
        var binding = commandBinding;
        var submission = binding == null ? service.submit(request, operator(source)) : binding.submit(request, rules -> {
            if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer)) return;
            var notice = io.github.zct_studio.barusikozako.runtime.host.RuleNotice.forPlayer(rules,
                Translations.format(source, "command.ignore_rules", rules.size()),
                Translations.text(source, "command.ignore_rules_more")).split("\n");
            var lines = new ArrayList<List<Chat.Piece>>();
            for (int index = 1; index < notice.length; index++) lines.add(rulePieces(notice[index]));
            Chat.richLines(source, Chat.Tone.INFO, notice[0], lines);
        }, operator(source));
        if (submission instanceof BackupService.Submission.Accepted accepted) {
            var display = progressDisplay;
            if (display != null && (request.kind() == BackupRequest.Kind.BACKUP ||
                request.kind() == BackupRequest.Kind.VERIFY) &&
                source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                display.track(accepted.id(), player);
            }
            accepted.completion().whenComplete((result, failure) -> {
                if (failure != null) {
                    Chat.line(source, Chat.Tone.FAILURE,
                        Chat.text(Translations.text(source, "command.task_delivery_failed_prefix")),
                        taskId(accepted.id().value()));
                } else {
                    if (request instanceof BackupRequest.Backup) {
                        lastBackupTime = Instant.now();
                        lastBackupResult = result;
                    }
                    sendResult(source, service, accepted.id(), request, result);
                }
            });
            String key = request.kind() == BackupRequest.Kind.BACKUP ? "command.backup_requested_prefix" :
                request.kind() == BackupRequest.Kind.RESTORE ? "command.rollback_requested_prefix" :
                    "command.task_requested_prefix";
            Chat.line(source, Chat.Tone.SUCCESS, Chat.text(Translations.text(source, key)),
                taskId(accepted.id().value()));
            return 1;
        }
        Chat.line(source, Chat.Tone.FAILURE,
            Translations.format(source, "command.request_rejected", rejection(source, submission)));
        return 0;
    }

    /** 以表格形式播报任务结果；聊天中只展示短标识符，完整 UUID 保留给日志与命令 */
    private static void sendResult(CommandSourceStack source, BackupService service, TaskId id,
                                   BackupRequest request, TaskResult result) {
        var rows = new ArrayList<Chat.Row>();
        rows.add(Chat.row(Translations.text(source, "label.task"), taskId(id.value())));
        Chat.Tone tone;
        String title;
        if (result instanceof TaskResult.BackupCommitted committed) {
            boolean relaxed = committed.relaxedFiles() > 0;
            tone = relaxed ? Chat.Tone.WARNING : Chat.Tone.SUCCESS;
            title = Translations.text(source, relaxed ? "chat.result.backup_relaxed" : "completion.backup");
            rows.add(Chat.row(Translations.text(source, "label.snapshot"),
                snapshotId(committed.snapshot().snapshotId())));
            if (request instanceof BackupRequest.Backup backup)
                rows.add(Chat.row(Translations.text(source, "label.operator"),
                    operatorPieces(source, operator(backup.trigger())).toArray(Chat.Piece[]::new)));
            if (relaxed) rows.add(Chat.row(Translations.text(source, "label.differences"),
                Integer.toString(committed.relaxedFiles())));
            committed.logicalBytes().ifPresent(size ->
                rows.add(Chat.row(Translations.text(source, "label.original"), bytes(size))));
            committed.archiveBytes().ifPresent(size ->
                rows.add(Chat.row(Translations.text(source, "label.archive"), bytes(size))));
            if (committed.logicalBytes().isPresent() && committed.archiveBytes().isPresent()) {
                long logical = committed.logicalBytes().getAsLong();
                long archive = committed.archiveBytes().getAsLong();
                if (logical > archive)
                    rows.add(Chat.row(Translations.text(source, "label.saved"), bytes(logical - archive)));
            }
        } else if (result instanceof TaskResult.VerificationComplete verified) {
            tone = Chat.Tone.SUCCESS;
            title = Translations.text(source, "completion.verify");
            if (request instanceof BackupRequest.Verify verify)
                rows.add(Chat.row(Translations.text(source, "label.snapshot"),
                    snapshotId(verify.snapshot().snapshotId())));
            rows.add(Chat.row(Translations.text(source, "label.entries"), Long.toString(verified.entries())));
            rows.add(Chat.row(Translations.text(source, "label.verified"), bytes(verified.bytes())));
        } else if (result instanceof TaskResult.Failed failed) {
            tone = Chat.Tone.FAILURE;
            title = Translations.text(source, "chat.result.failed");
            rows.add(Chat.row(Translations.text(source, "label.reason"), failureText(source, failed.reason())));
        } else if (result instanceof TaskResult.Cancelled) {
            tone = Chat.Tone.WARNING;
            title = Translations.text(source, "chat.result.cancelled");
            rows.add(Chat.row(Translations.text(source, "label.result"),
                Translations.text(source, "result.cancelled")));
        } else if (result instanceof TaskResult.CommitUncertain) {
            tone = Chat.Tone.WARNING;
            title = Translations.text(source, "chat.result.uncertain");
            rows.add(Chat.row(Translations.text(source, "label.result"),
                Translations.text(source, "result.uncertain")));
        } else if (result instanceof TaskResult.RestoreQueued queued) {
            tone = Chat.Tone.SUCCESS;
            title = Translations.text(source, "chat.result.restore_queued");
            rows.add(Chat.row(Translations.text(source, "label.snapshot"),
                snapshotId(queued.snapshot().snapshotId())));
            rows.add(Chat.row(Translations.text(source, "label.result"),
                Translations.text(source, "chat.note.restore")));
        } else if (result instanceof TaskResult.Restored restored) {
            tone = Chat.Tone.SUCCESS;
            title = Translations.text(source, "chat.result.restored");
            rows.add(Chat.row(Translations.text(source, "label.snapshot"),
                snapshotId(restored.snapshot().snapshotId())));
        } else if (result instanceof TaskResult.Deleted deleted) {
            tone = Chat.Tone.SUCCESS;
            title = Translations.text(source, "chat.result.deleted");
            rows.add(Chat.row(Translations.text(source, "label.snapshot"),
                snapshotId(deleted.snapshot().snapshotId())));
        } else {
            tone = Chat.Tone.INFO;
            title = Translations.text(source, "result.completed");
        }
        var elapsed = result instanceof TaskResult.BackupCommitted committed && committed.elapsed().isPresent()
            ? committed.elapsed() : service.task(id).map(TaskState::elapsed);
        elapsed.ifPresent(duration ->
            rows.add(Chat.row(Translations.text(source, "label.elapsed"), ConfigUnits.formatDuration(duration))));
        Chat.send(source, tone, title, rows);
    }

    /** 任务查询使用的简短结果说明；快照标识符显示截断值但点击复制完整值 */
    private static List<Chat.Piece> summaryPieces(CommandSourceStack source, TaskResult result) {
        if (result instanceof TaskResult.BackupCommitted committed) {
            var pieces = new ArrayList<Chat.Piece>();
            pieces.add(Chat.text(Translations.text(source, "summary.backup_prefix")));
            pieces.add(snapshotId(committed.snapshot().snapshotId()));
            if (committed.relaxedFiles() > 0) pieces.add(Chat.text(Translations.format(source,
                "summary.backup_relaxed_suffix", committed.relaxedFiles())));
            return pieces;
        }
        if (result instanceof TaskResult.RestoreQueued queued) return List.of(
            Chat.text(Translations.text(source, "summary.restore_prefix")),
            snapshotId(queued.snapshot().snapshotId()));
        if (result instanceof TaskResult.Deleted deleted) return List.of(
            Chat.text(Translations.text(source, "summary.deleted_prefix")),
            snapshotId(deleted.snapshot().snapshotId()));
        if (result instanceof TaskResult.VerificationComplete verified) return List.of(
            Chat.text(Translations.format(source, "result.verified", verified.entries(), verified.bytes())));
        if (result instanceof TaskResult.Restored) return List.of(
            Chat.text(Translations.text(source, "chat.result.restored")));
        if (result instanceof TaskResult.Failed failed) return List.of(
            Chat.text(failureText(source, failed.reason())));
        if (result instanceof TaskResult.CommitUncertain) return List.of(
            Chat.text(Translations.text(source, "result.uncertain")));
        if (result instanceof TaskResult.Cancelled) return List.of(
            Chat.text(Translations.text(source, "result.cancelled")));
        return List.of(Chat.text(Translations.text(source, "result.completed")));
    }

    /** 失败原因的本地化文本；原生访问与目录刷新失败给出完整处置建议 */
    private static String failureText(CommandSourceStack source, TaskResult.Failure reason) {
        return switch (reason) {
            case NATIVE_ACCESS_DENIED -> Translations.text(source, "failure.native_access_denied");
            case DIRECTORY_SYNC_FAILED -> Translations.text(source, "failure.directory_sync_failed");
            default -> Translations.text(source, "failure." + reason.name().toLowerCase(Locale.ROOT));
        };
    }

    /** 容量按二进制进位格式化，与进度显示保持一致 */
    private static String bytes(long value) {
        return ConfigUnits.formatBytes(value, ConfigUnits.SizeUnit.BINARY, 2);
    }

    /** 任务进度文本；总量未知时只显示已处理容量 */
    private static String progressText(TaskState state) {
        var processed = ConfigUnits.formatBytes(state.processedBytes(), ConfigUnits.SizeUnit.BINARY, 2);
        return state.totalBytes().isPresent()
            ? processed + " / " + ConfigUnits.formatBytes(state.totalBytes().getAsLong(),
                ConfigUnits.SizeUnit.BINARY, 2)
            : processed;
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
                case NATIVE_ACCESS_DENIED, DIRECTORY_SYNC_FAILED -> failureText(source, failed.reason());
                default -> Translations.format(source, "result.failed", failureText(source, failed.reason()));
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
            Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.invalid_task"));
            return 0;
        }
        var result = service.cancel(new TaskId(id));
        var tone = result == BackupService.Cancellation.REQUESTED ? Chat.Tone.SUCCESS
            : result == BackupService.Cancellation.TOO_LATE ? Chat.Tone.WARNING : Chat.Tone.FAILURE;
        Chat.line(source, tone, Translations.text(source, "cancel." + result.name().toLowerCase(Locale.ROOT)));
        return result == BackupService.Cancellation.REQUESTED ? 1 : 0;
    }

    private int task(CommandSourceStack source, String id) {
        var service = service(source);
        if (service == null) return 0;
        if (id.isBlank() || id.length() > 64) {
            Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.invalid_task"));
            return 0;
        }
        var state = service.task(new TaskId(id));
        if (state.isEmpty()) {
            Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.task_unavailable"));
            return 0;
        }
        var value = state.orElseThrow();
        var rows = new ArrayList<Chat.Row>();
        rows.add(Chat.row(Translations.text(source, "label.task"), taskId(id)));
        visibleTasks().stream().filter(task -> task.state().id().value().equals(id)).findFirst().ifPresent(task ->
            rows.add(Chat.row(Translations.text(source, "label.time"),
                Chat.copy(SNAPSHOT_TIME.format(task.submittedAt()), task.submittedAt().toString()))));
        rows.add(Chat.row(Translations.text(source, "label.state"), taskLabel(source, value)));
        rows.add(Chat.row(Translations.text(source, "label.operator"),
            operatorPieces(source, value.operator()).toArray(Chat.Piece[]::new)));
        rows.add(Chat.row(Translations.text(source, "label.progress"), progressText(value)));
        rows.add(Chat.row(Translations.text(source, "label.elapsed"), ConfigUnits.formatDuration(value.elapsed())));
        value.result().ifPresent(result -> rows.add(Chat.row(Translations.text(source, "label.result"),
            summaryPieces(source, result).toArray(Chat.Piece[]::new))));
        if (cancellable(value)) {
            rows.add(Chat.row(Translations.text(source, "label.action"),
                Chat.suggest(Translations.text(source, "action.cancel"),
                    "/barusikozako cancel fromid " + id)));
        }
        Chat.send(source, Chat.Tone.INFO, Translations.text(source, "chat.title.task"), rows);
        return 1;
    }

    private BackupService service(CommandSourceStack source) {
        var service = operator;
        if (service == null) Chat.line(source, Chat.Tone.FAILURE,
            Translations.text(source, "command.service_unavailable"));
        return service;
    }

    private int withId(CommandSourceStack source, String text,
            ToIntBiFunction<CommandSourceStack, BackupService.SnapshotInfo> action) {
        try {
            FabricOperations.parseId(text);
        } catch (IllegalArgumentException invalid) {
            Chat.line(source, Chat.Tone.FAILURE, Translations.text(source, "command.invalid_snapshot"));
            return 0;
        }
        return resolveSnapshot(source, snapshot -> snapshot.snapshot().snapshotId().equals(text),
            "command.snapshot_not_found", action);
    }
}
