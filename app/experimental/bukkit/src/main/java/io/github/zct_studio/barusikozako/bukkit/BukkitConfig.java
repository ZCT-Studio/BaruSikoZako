// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit;

import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Bukkit best-effort capture configuration. Loaded from {@code config/barusikozako/server.yml}.
 * Mirrors Fabric {@code ServerConfig} but uses Bukkit-friendly defaults and keys.
 */
public final class BukkitConfig {
    private static final String DEFAULTS = """
        # BaruSikoZako Bukkit 尽力配置
        schema=1
        verify.contentBytes=274877906944
        verify.archiveBytes=343597383680
        verify.entries=1000000
        query.manifestBytes=134217728
        # 0 disables periodic backup requests.
        schedule.intervalSeconds=0
        # 实验性：尽力而为的在线捕获。为了安全起见默认 false
        # 启用后，将发出全部保存并获取实时文件副本，而无需等待
        // 异步块/实体/POI/保存的数据写入以完成或验证其结果
        // 不保证跨文件一致性。仅当您接受此风险时才启用
        capture.enabled=false
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
        # 以逗号分隔的插件 ID。仅启用您信任的集成
        api.maintenanceIntegrations=
        api.lifecycleIntegrations=
        api.integrations=
        """;

    private static final Set<String> FIELDS = Set.of("schema", "verify.contentBytes", "verify.archiveBytes",
        "verify.entries", "query.manifestBytes", "api.integrations", "api.maintenanceIntegrations", "api.lifecycleIntegrations", "schedule.intervalSeconds",
        "capture.enabled", "capture.preparationMillis", "capture.mainThreadBudgetMillis",
        "capture.fileRetries", "capture.fileRetryDelayMillis", "capture.fileClone",
        "backup.readBytesPerSecond", "backend");

    public final BackupEngine.Limits readLimits;
    public final long manifestBudget;
    public final Set<String> integrations;
    public final Set<String> maintenanceIntegrations;
    public final Set<String> lifecycleIntegrations;
    public final long backupIntervalSeconds;
    public final boolean captureEnabled;
    public final long preparationMillis;
    public final int mainThreadBudgetMillis;
    public final int fileRetries;
    public final int fileRetryDelayMillis;
    public final boolean fileCloneAuto;
    public final long backupReadBytesPerSecond;
    public final String backendKeyword;

    private BukkitConfig(BackupEngine.Limits readLimits, long manifestBudget, Set<String> integrations,
                         Set<String> maintenanceIntegrations, Set<String> lifecycleIntegrations,
                         long backupIntervalSeconds, boolean captureEnabled, long preparationMillis,
                         int mainThreadBudgetMillis, int fileRetries, int fileRetryDelayMillis,
                         boolean fileCloneAuto, long backupReadBytesPerSecond, String backendKeyword) {
        this.readLimits = readLimits;
        this.manifestBudget = manifestBudget;
        this.integrations = integrations;
        this.maintenanceIntegrations = maintenanceIntegrations;
        this.lifecycleIntegrations = lifecycleIntegrations;
        this.backupIntervalSeconds = backupIntervalSeconds;
        this.captureEnabled = captureEnabled;
        this.preparationMillis = preparationMillis;
        this.mainThreadBudgetMillis = mainThreadBudgetMillis;
        this.fileRetries = fileRetries;
        this.fileRetryDelayMillis = fileRetryDelayMillis;
        this.fileCloneAuto = fileCloneAuto;
        this.backupReadBytesPerSecond = backupReadBytesPerSecond;
        this.backendKeyword = backendKeyword;
    }

    private static int bounded(Properties settings, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(settings.getProperty(key, Integer.toString(fallback)));
        if (value < min || value > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }

    private static long positive(Properties settings, String key) {
        long value = Long.parseLong(settings.getProperty(key));
        if (value < 1) throw new IllegalArgumentException("nonpositive configuration value for " + key);
        return value;
    }

    private static Set<String> modIds(Properties settings, String field) {
        var ids = new HashSet<String>();
        var values = settings.getProperty(field, "").strip();
        if (!values.isEmpty()) {
            for (String id : values.split(",", -1)) {
                var trimmed = id.strip();
                if (!trimmed.matches("[a-z][a-z0-9_-]{1,63}") || !ids.add(trimmed) || ids.size() > 16)
                    throw new IllegalArgumentException("invalid or repeated plugin ID in " + field);
            }
        }
        return Set.copyOf(ids);
    }

    static Map<String, String> defaults() {
        var defaults = new LinkedHashMap<String, String>();
        DEFAULTS.lines().filter(line -> line.contains("=") && !line.startsWith("#")).forEach(line -> {
            int separator = line.indexOf('=');
            defaults.put(line.substring(0, separator), line.substring(separator + 1));
        });
        return defaults;
    }

    public static BukkitConfig load(Path configDir) throws IOException {
        var directory = configDir.resolve("barusikozako");
        var defaultsMap = defaults();
        return parse(YamlSettings.load(directory.resolve("server.yml"), defaultsMap, true, () -> Map.of()));
    }

    static BukkitConfig parse(Map<String, String> values) throws IOException {
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
            String enabled = settings.getProperty("capture.enabled", "false");
            if (!enabled.equals("true") && !enabled.equals("false")) throw new IllegalArgumentException("invalid capture.enabled");
            String fileClone = settings.getProperty("capture.fileClone", "auto");
            if (!fileClone.equals("auto") && !fileClone.equals("off")) throw new IllegalArgumentException("invalid capture.fileClone");
            long preparation = Long.parseLong(settings.getProperty("capture.preparationMillis", "10000"));
            if (preparation < 100 || preparation > 60_000)
                throw new IllegalArgumentException("invalid capture timing budget");
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
            if (!integrations.containsAll(maintenance))
                throw new IllegalArgumentException("api.maintenanceIntegrations must be included in api.integrations");
            return new BukkitConfig(BackupEngine.Limits.class.cast(read),  // type hint for record
                positive(settings, "query.manifestBytes"), modIds(settings, "api.integrations"),
                maintenance, modIds(settings, "api.lifecycleIntegrations"),
                Long.parseLong(settings.getProperty("schedule.intervalSeconds", "0")),
                Boolean.parseBoolean(settings.getProperty("capture.enabled", "false")),
                Long.parseLong(settings.getProperty("capture.preparationMillis", "10000")),
                bounded(settings, "capture.mainThreadBudgetMillis", 0, 0, 60_000),
                bounded(settings, "capture.fileRetries", 2, 0, 20),
                bounded(settings, "capture.fileRetryDelayMillis", 10, 0, 1000),
                "auto".equals(settings.getProperty("capture.fileClone", "auto")),
                Long.parseLong(settings.getProperty("backup.readBytesPerSecond", "0")),
                settings.getProperty("backend"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid backup configuration", invalid);
        }
    }

    public record Limits(long contentBytes, long archiveBytes, long entries) {}

    static void forceDirectory(Path path) throws IOException {
        RestoreWindow.forceLocalDirectory(path);
    }
}