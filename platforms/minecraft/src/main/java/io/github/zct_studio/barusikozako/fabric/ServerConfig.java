// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import io.github.zct_studio.barusikozako.core.BackupEngine;
import java.io.IOException;
import java.util.HashSet;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

record ServerConfig(BackupEngine.Limits readLimits, long manifestBudget, Set<String> integrations, Set<String> maintenanceIntegrations, Set<String> lifecycleIntegrations,
                    long backupIntervalSeconds, boolean captureEnabled, long preparationMillis, int mainThreadBudgetMillis,
                    int fileRetries, int fileRetryDelayMillis, boolean fileCloneAuto,
                    long backupReadBytesPerSecond, String backendKeyword) {
    private static final String DEFAULTS = """
        # BaruSikoZako Fabric专用服务器开发配置
        schema=1
        verify.contentBytes=274877906944
        verify.archiveBytes=343597383680
        verify.entries=1000000
        query.manifestBytes=134217728
        # 0 disables periodic backup requests.
        schedule.intervalSeconds=0
        # 进行实验，直到活动世界接受完成为止
        capture.enabled=true
        capture.preparationMillis=10000
        # 0 disables rejection based on synchronous save preparation time.
        capture.mainThreadBudgetMillis=0
        # 读取初始文件后进行额外尝试。 0 禁用重试
        capture.fileRetries=2
        # 线性重试延迟。如果为 10，则等待时间为 10 毫秒、20 毫秒，依此类推
        capture.fileRetryDelayMillis=10
        capture.fileClone=auto
        # 0 disables pacing of reads from the stable view.
        backup.readBytesPerSecond=0
        backend=barusikozako_backendzstd
        """;
    private static final Set<String> FIELDS = Set.of("schema", "verify.contentBytes", "verify.archiveBytes",
        "verify.entries", "query.manifestBytes", "api.integrations", "api.maintenanceIntegrations", "api.lifecycleIntegrations", "schedule.intervalSeconds",
        "capture.enabled", "capture.preparationMillis", "capture.pauseMillis", "capture.readOnlyMods", "capture.fileClone",
        "capture.mainThreadBudgetMillis", "capture.fileRetries", "capture.fileRetryDelayMillis",
        "backup.readBytesPerSecond", "backend");

    private static int bounded(Properties settings, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(settings.getProperty(key, Integer.toString(fallback)));
        if (value < min || value > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }

    static ServerConfig load(boolean createFile) throws IOException {
        var directory = LoaderBridge.configDirectory().resolve("barusikozako");
        var defaults = defaults();
        return parse(YamlSettings.load(directory.resolve("server.yml"), defaults, createFile, Map::of));
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
            var read = new BackupEngine.Limits(positive(settings, "verify.contentBytes"),
                positive(settings, "verify.archiveBytes"), positive(settings, "verify.entries"));
            long interval = Long.parseLong(settings.getProperty("schedule.intervalSeconds", "0"));
            if (interval < 0) throw new IllegalArgumentException("backup interval must be zero or positive");
            String enabled = settings.getProperty("capture.enabled", "true");
            if (!enabled.equals("true") && !enabled.equals("false")) throw new IllegalArgumentException("invalid capture.enabled");
            String fileClone = settings.getProperty("capture.fileClone", "auto");
            if (!fileClone.equals("auto") && !fileClone.equals("off")) throw new IllegalArgumentException("invalid capture.fileClone");
            long preparation = Long.parseLong(settings.getProperty("capture.preparationMillis", "10000"));
            if (preparation < 100 || preparation > 60_000)
                throw new IllegalArgumentException("invalid capture timing budget");
            if (settings.containsKey("capture.pauseMillis"))
                System.getLogger("BaruSikoZako").log(System.Logger.Level.WARNING,
                    "Legacy capture.pauseMillis is ignored by best-effort capture; remove it from server.properties");
            if (settings.containsKey("capture.readOnlyMods"))
                System.getLogger("BaruSikoZako").log(System.Logger.Level.WARNING,
                    "Legacy capture.readOnlyMods is ignored by best-effort capture; remove it from server.properties");
            long readRate = Long.parseLong(settings.getProperty("backup.readBytesPerSecond", "0"));
            if (readRate < 0) throw new IllegalArgumentException("invalid read rate");
            var backend = settings.getProperty("backend");
            if (!backend.matches("[a-z][a-z0-9_-]{1,63}"))
                throw new IllegalArgumentException("invalid backend keyword");
            int mainThreadBudget = bounded(settings, "capture.mainThreadBudgetMillis", 0, 0, 60_000);
            int retries = bounded(settings, "capture.fileRetries", 2, 0, 20);
            int retryDelay = bounded(settings, "capture.fileRetryDelayMillis", 10, 0, 1000);
            var integrations = modIds(settings, "api.integrations");
            var maintenance = modIds(settings, "api.maintenanceIntegrations");
            return new ServerConfig(read, positive(settings, "query.manifestBytes"), integrations, maintenance,
                ids(settings, "api.lifecycleIntegrations", "[a-z][a-z0-9_.-]{1,127}"),
                interval, Boolean.parseBoolean(enabled), preparation, mainThreadBudget, retries, retryDelay,
                fileClone.equals("auto"), readRate, backend);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid backup configuration", invalid);
        }
    }

    private static Set<String> modIds(Properties settings, String field) {
        return ids(settings, field, "[a-z][a-z0-9_-]{1,63}");
    }

    private static Set<String> ids(Properties settings, String field, String pattern) {
        var ids = new HashSet<String>();
        var values = settings.getProperty(field, "").strip();
        if (!values.isEmpty()) {
            for (String id : values.split(",", -1)) {
                var trimmed = id.strip();
                if (!trimmed.matches(pattern) || !ids.add(trimmed) || ids.size() > 16)
                    throw new IllegalArgumentException("invalid or repeated component ID in " + field);
            }
        }
        return Set.copyOf(ids);
    }

    private static long positive(Properties settings, String key) {
        long value = Long.parseLong(settings.getProperty(key));
        if (value < 1) throw new IllegalArgumentException("nonpositive configuration value for " + key);
        return value;
    }
}
