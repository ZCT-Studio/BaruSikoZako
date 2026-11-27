// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.backend.Consistency;
import io.github.zct_studio.barusikozako.backend.ConfigUnits;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

record ServerConfig(BackupEngine.Limits readLimits, long manifestBudget,
                    long backupIntervalSeconds, boolean captureEnabled, long preparationMillis, int mainThreadBudgetMillis,
                    int fileRetries, int fileRetryDelayMillis, int captureRetries, Consistency consistency,
                    boolean fileCloneAuto, long backupReadBytesPerSecond, String backendKeyword,
                    boolean parseGitignore, boolean parsePteroignore) {
    private static final String DEFAULTS = """
        # BaruSikoZako Fabric专用服务器开发配置
        schema=1
        backend=barusikozako_backendzstd
        verify.contentBytes=256GiB
        verify.archiveBytes=320GiB
        verify.entries=1000000
        query.manifestBytes=128MiB
        # 0 disables periodic backup requests.
        schedule.intervalSeconds=0
        # 进行实验，直到活动世界接受完成为止
        capture.enabled=true
        capture.preparationMillis=10s
        # 0 disables rejection based on synchronous save preparation time.
        capture.mainThreadBudgetMillis=0
        # 读取初始文件后进行额外尝试。 0 禁用重试
        capture.fileRetries=8
        # 线性重试延迟。如果为 50，则等待时间为 50 毫秒、100 毫秒，依此类推
        capture.fileRetryDelayMillis=50ms
        # 单文件重试仍失败时整次重新捕获的额外次数。 0 禁用
        capture.retries=1
        # 捕获一致性等级：strict 严格检查，relaxed 允许文件之间存在时间点差异
        capture.consistency=strict
        capture.fileClone=auto
        # 0 disables pacing of reads from the stable view.
        backup.readBytesPerSecond=0
        # 解析各目录的 .gitignore，与 local.gitignore 一样作为局部规则
        ignore.gitignore=false
        # 解析游戏根目录的 .pteroignore，规则路径相对于游戏根目录
        ignore.pteroignore=false
        """;
    private static final Set<String> FIELDS = Set.of("schema", "verify.contentBytes", "verify.archiveBytes",
        "verify.entries", "query.manifestBytes", "schedule.intervalSeconds",
        "capture.enabled", "capture.preparationMillis", "capture.pauseMillis", "capture.readOnlyMods", "capture.fileClone",
        "capture.mainThreadBudgetMillis", "capture.fileRetries", "capture.fileRetryDelayMillis",
        "capture.retries", "capture.consistency",
        "backup.readBytesPerSecond", "backend", "ignore.gitignore", "ignore.pteroignore");

    private static int bounded(Properties settings, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(settings.getProperty(key, Integer.toString(fallback)));
        if (value < min || value > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }

    static ServerConfig load(boolean createFile) throws IOException {
        var directory = LoaderBridge.configDirectory().resolve("barusikozako");
        var values = YamlSettings.load(directory.resolve("server.yml"), defaults(), createFile, Map::of);
        // 授权已迁移到 api.yml；旧键在此剥离，避免未知键校验失败，并尽量自动迁移一次。
        var legacy = new LinkedHashMap<String, String>();
        for (String key : new java.util.ArrayList<>(values.keySet())) {
            if (key.startsWith("api.")) legacy.put(key, values.remove(key));
        }
        if (!legacy.isEmpty()) ApiConfig.migrateLegacy(legacy);
        return parse(values);
    }

    static Map<String, String> defaults() {
        var defaults = new LinkedHashMap<String, String>();
        DEFAULTS.lines().filter(line -> line.contains("=") && !line.startsWith("#")).forEach(line -> {
            int separator = line.indexOf('=');
            defaults.put(line.substring(0, separator), line.substring(separator + 1));
        });
        return defaults;
    }

    static ServerConfig parse(Map<String, String> values) throws IOException {
        var settings = new Properties();
        settings.putAll(defaults());
        settings.putAll(values);
        try {
            for (String field : settings.stringPropertyNames()) {
                if (!FIELDS.contains(field)) throw new IllegalArgumentException("unknown configuration key " + field);
            }
            if (!"1".equals(settings.getProperty("schema"))) throw new IllegalArgumentException("unsupported schema");
            var read = new BackupEngine.Limits(positiveBytes(settings, "verify.contentBytes"),
                positiveBytes(settings, "verify.archiveBytes"), positive(settings, "verify.entries"));
            long interval = ConfigUnits.seconds(settings.getProperty("schedule.intervalSeconds", "0"));
            if (interval < 0) throw new IllegalArgumentException("backup interval must be zero or positive");
            String enabled = settings.getProperty("capture.enabled", "true");
            if (!enabled.equals("true") && !enabled.equals("false")) throw new IllegalArgumentException("invalid capture.enabled");
            String fileClone = settings.getProperty("capture.fileClone", "auto");
            if (!fileClone.equals("auto") && !fileClone.equals("off")) throw new IllegalArgumentException("invalid capture.fileClone");
            String gitignore = settings.getProperty("ignore.gitignore", "false");
            if (!gitignore.equals("true") && !gitignore.equals("false")) throw new IllegalArgumentException("invalid ignore.gitignore");
            String pteroignore = settings.getProperty("ignore.pteroignore", "false");
            if (!pteroignore.equals("true") && !pteroignore.equals("false")) throw new IllegalArgumentException("invalid ignore.pteroignore");
            long preparation = ConfigUnits.milliseconds(settings.getProperty("capture.preparationMillis", "10000"));
            if (preparation < 100 || preparation > 60_000)
                throw new IllegalArgumentException("invalid capture timing budget");
            if (settings.containsKey("capture.pauseMillis"))
                System.getLogger("BaruSikoZako").log(System.Logger.Level.WARNING,
                    "Legacy capture.pauseMillis is ignored by online capture; remove it from server.properties");
            if (settings.containsKey("capture.readOnlyMods"))
                System.getLogger("BaruSikoZako").log(System.Logger.Level.WARNING,
                    "Legacy capture.readOnlyMods is ignored by online capture; remove it from server.properties");
            long readRate = ConfigUnits.bytes(settings.getProperty("backup.readBytesPerSecond", "0"));
            if (readRate < 0) throw new IllegalArgumentException("invalid read rate");
            var backend = settings.getProperty("backend");
            if (!backend.matches("[a-z][a-z0-9_-]{1,63}"))
                throw new IllegalArgumentException("invalid backend keyword");
            int mainThreadBudget = boundedMillis(settings, "capture.mainThreadBudgetMillis", 0, 60_000);
            int retries = bounded(settings, "capture.fileRetries", 8, 0, 20);
            int retryDelay = boundedMillis(settings, "capture.fileRetryDelayMillis", 50, 1000);
            int captureRetries = bounded(settings, "capture.retries", 1, 0, 5);
            var consistency = Consistency.parse(settings.getProperty("capture.consistency", "strict"));
            return new ServerConfig(read, positiveBytes(settings, "query.manifestBytes"),
                interval, Boolean.parseBoolean(enabled), preparation, mainThreadBudget, retries, retryDelay,
                captureRetries, consistency, fileClone.equals("auto"), readRate, backend,
                Boolean.parseBoolean(gitignore), Boolean.parseBoolean(pteroignore));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid backup configuration", invalid);
        }
    }

    private static long positive(Properties settings, String key) {
        long value = Long.parseLong(settings.getProperty(key));
        if (value < 1) throw new IllegalArgumentException("nonpositive configuration value for " + key);
        return value;
    }

    private static long positiveBytes(Properties settings, String key) {
        long value = ConfigUnits.bytes(settings.getProperty(key));
        if (value < 1) throw new IllegalArgumentException("nonpositive configuration value for " + key);
        return value;
    }

    private static int boundedMillis(Properties settings, String key, int fallback, int max) {
        long value = ConfigUnits.milliseconds(settings.getProperty(key, Integer.toString(fallback)));
        if (value < 0 || value > max) throw new IllegalArgumentException(key + " must be between 0 and " + max);
        return (int) value;
    }
}
