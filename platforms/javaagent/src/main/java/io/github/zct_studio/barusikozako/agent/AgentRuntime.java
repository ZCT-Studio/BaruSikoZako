// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import io.github.zct_studio.barusikozako.api.BackupLifecycleHook;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.backend.BackendProvider;
import io.github.zct_studio.barusikozako.apihost.BackupApiHost;
import io.github.zct_studio.barusikozako.apihost.BoundAccess;
import io.github.zct_studio.barusikozako.agent.instrument.AgentHooks;
import io.github.zct_studio.barusikozako.agent.instrument.AgentServerSignals;
import io.github.zct_studio.barusikozako.api.BackupService;
import io.github.zct_studio.barusikozako.platform.DirectoryComponents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.EnumSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 系统加载程序入口点。仅限 Java 的提供程序接口由所有组件加载器共享 */
public final class AgentRuntime {
    private static final Map<String, BackendProvider> BACKENDS = new LinkedHashMap<>();
    private static final Map<String, BackupLifecycleHook> EXTENSIONS = new LinkedHashMap<>();
    private static final System.Logger LOG = System.getLogger("BaruSikoZako Agent");
    private static final ThreadPoolExecutor CALLBACKS = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), action -> {
            var thread = new Thread(action, "BaruSikoZako Agent callbacks");
            thread.setDaemon(true);
            return thread;
        });
    private static volatile boolean ready;
    private static BackupApiHost api;
    private static AgentOperations operations;
    private static boolean captureValidationRequested;
    private static volatile BackupApiHost.Binding captureValidation;

    static { CALLBACKS.allowCoreThreadTimeOut(true); }

    private AgentRuntime() {}

    static void initialize(Path home) throws IOException {
        Path config = directory(directory(home.resolve("config")).resolve("barusikozako"));
        Path bundled = Premain.extract("/META-INF/barusikozako/backend.jar");
        var backends = new DirectoryComponents(directory(config.resolve("backend")),
            AgentRuntime.class.getClassLoader(), bundled == null ? List.of() : List.of(bundled));
        var extensions = new DirectoryComponents(directory(config.resolve("extension")),
            backends.classLoader(), List.of());
        for (var entry : backends.providers(BackendProvider.class)) registerBackend(entry.get());
        for (var entry : extensions.providers(BackupLifecycleHook.class))
            registerExtension(entry.type().getName().toLowerCase(java.util.Locale.ROOT), entry.get());
        ready = true;
        LOG.log(System.Logger.Level.INFO, status());
    }

    private static Path directory(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
            throw new IOException("Agent component directory must be local and unaliased: " + path);
        return path;
    }

    public static synchronized String status() {
        var online = operations == null ? BackupService.Availability.UNSUPPORTED :
            operations.availability(io.github.zct_studio.barusikozako.api.BackupRequest.Kind.BACKUP);
        return "BaruSikoZako Agent " + (ready ? "ready" : "initializing") + "; online backup: " + online + "; backends: "
            + BACKENDS.keySet() + "; extensions: " + EXTENSIONS.keySet() +
            "; observed legacy I/O: " + AgentHooks.legacyQueueState();
    }

    static synchronized void requireReady() throws IOException {
        if (!ready) throw new IOException("Start with -javaagent:<agent JAR> as well as -jar <agent JAR>");
    }

    static synchronized void startApi(Path repository, String keyword, boolean auditedVanilla) {
        if (api != null) throw new IllegalStateException("Repeated Agent API startup");
        AgentVanillaCapture capture = null;
        boolean onlineRequested = captureValidationRequested ||
            Boolean.parseBoolean(System.getProperty("barusikozako.agent.onlineBackup", "true"));
        if (auditedVanilla && onlineRequested) {
            try {
                var home = Path.of(".").toRealPath();
                AgentDirectoryPersistence.INSTANCE.force(home);
                capture = new AgentVanillaCapture(home, AgentOperations.LIMITS);
            } catch (IOException unsupportedStorage) {
                LOG.log(System.Logger.Level.WARNING, "Online backup unavailable: " + unsupportedStorage.getMessage());
            }
        } else if (onlineRequested) {
            LOG.log(System.Logger.Level.WARNING, "Online backup unavailable: server launcher has no audited save profile");
        }
        operations = new AgentOperations(repository, keyword, capture);
        api = new BackupApiHost(operations);
        if (capture != null) {
            var operator = api.bind("agent-console", new BoundAccess.Grant(
                Map.of(AgentOperations.REPOSITORY_ID, EnumSet.allOf(BoundAccess.Right.class)),
                Set.of("worlds"), Set.of()));
            if (captureValidationRequested) captureValidation = operator;
            AgentServerSignals.bind(capture::onTick, capture::stopping, new AgentConsole(operator.service())::accept);
        }
        var diagnostics = Boolean.getBoolean("barusikozako.agent.reportSaveObservations");
        var reportStream = System.err;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            if (diagnostics) reportStream.println("BaruSikoZako save observations: " + AgentHooks.observations());
        }, "BaruSikoZako Agent API shutdown"));
    }

    // 独立测试Agent共享这个包和系统类加载器
    static synchronized void requestCaptureValidation() {
        if (api != null) throw new IllegalStateException("Capture validation must be selected before managed startup");
        captureValidationRequested = true;
    }

    static BackupService captureValidationService() {
        var binding = captureValidation;
        return binding == null ? null : binding.service();
    }

    /** 关闭返回的租约将撤销该插件的任务访问权限 */
    public static synchronized Map.Entry<io.github.zct_studio.barusikozako.api.BackupService, AutoCloseable> bindBukkitApi(String plugin) {
        if (api == null) throw new IllegalStateException("The managed Agent is not running");
        if (!plugin.matches("[a-z0-9_.-]{1,96}")) throw new IllegalArgumentException("Invalid plugin name");
        var binding = api.bind("bukkit." + plugin, new BoundAccess.Grant(
            Map.of(AgentOperations.REPOSITORY_ID, EnumSet.allOf(BoundAccess.Right.class)), Set.of("worlds"), Set.of("worlds")));
        return Map.entry(binding.service(), binding);
    }

    public static AutoCloseable registerBackupExclusion(String scope, String source, String pattern) {
        return io.github.zct_studio.barusikozako.core.BackupExclusions.register(scope, source, pattern);
    }

    public static AutoCloseable registerBackend(BackendProvider provider) {
        String keyword = Objects.requireNonNull(provider).keyword();
        synchronized (AgentRuntime.class) {
            return register(BACKENDS, keyword, provider);
        }
    }

    public static synchronized AutoCloseable registerExtension(String id, BackupLifecycleHook extension) {
        return register(EXTENSIONS, id, Objects.requireNonNull(extension));
    }

    private static <T> AutoCloseable register(Map<String, T> registry, String key, T value) {
        if (key == null || !key.matches("[a-z][a-z0-9_.-]{0,95}") || registry.putIfAbsent(key, value) != null)
            throw new IllegalArgumentException("Invalid or duplicate Agent component: " + key);
        return () -> {
            synchronized (AgentRuntime.class) {
                registry.remove(key, value);
            }
        };
    }

    static BackendProvider.Settings backend(String keyword) throws IOException {
        BackendProvider backend;
        synchronized (AgentRuntime.class) {
            backend = BACKENDS.get(keyword);
        }
        if (backend == null) throw new IOException("Backend is not installed: " + keyword);
        return backend.configure(AgentBackendConfig.load(keyword, backend.defaults()));
    }

    private static synchronized List<BackupLifecycleHook> extensions() {
        return List.copyOf(EXTENSIONS.values());
    }

    static void before(BackupLifecycleHook.Context context) throws IOException {
        var hooks = extensions();
        if (hooks.isEmpty()) return;
        var callback = new FutureTask<Void>(() -> {
            for (var hook : hooks) hook.before(context);
            return null;
        });
        try {
            CALLBACKS.execute(callback);
            callback.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            callback.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Lifecycle callback interrupted", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException |
                 java.util.concurrent.RejectedExecutionException failure) {
            callback.cancel(true);
            throw new IOException("Lifecycle callback failed", failure);
        }
    }

    static void after(BackupLifecycleHook.Context context, TaskResult result) {
        var hooks = extensions();
        if (hooks.isEmpty()) return;
        try {
            CALLBACKS.execute(() -> {
                for (var hook : hooks) {
                    try {
                        hook.after(context, result);
                    } catch (RuntimeException | LinkageError failure) {
                        LOG.log(System.Logger.Level.WARNING, "Agent extension callback failed", failure);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            LOG.log(System.Logger.Level.WARNING, "Agent extension queue is full", failure);
        }
    }
}
