// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.core.BackupEngine;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.NoSuchFileException;
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
        # BaruSikoZako Fabric dedicated server development configuration.
        schema=1
        verify.contentBytes=274877906944
        verify.archiveBytes=343597383680
        verify.entries=1000000
        query.manifestBytes=134217728
        # 0 disables periodic backup requests.
        schedule.intervalSeconds=0
        # Experimental until active-world acceptance is complete.
        capture.enabled=false
        capture.preparationMillis=10000
        # 0 disables rejection based on synchronous save preparation time.
        capture.mainThreadBudgetMillis=0
        # Extra attempts after the initial file read. 0 disables retries.
        capture.fileRetries=2
        # Linear retry delay. With 10, waits are 10 ms, 20 ms, and so on.
        capture.fileRetryDelayMillis=10
        capture.fileClone=auto
        # 0 disables pacing of reads from the stable view.
        backup.readBytesPerSecond=0
        backend=zakobak_zstd
        # Comma-separated Fabric mod IDs. Only enable integrations you trust.
        api.maintenanceIntegrations=
        api.lifecycleIntegrations=
        api.integrations=
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
        return parse(YamlSettings.load(directory.resolve("server.yml"), defaults, createFile, () -> {
            var migrated = new LinkedHashMap<>(legacy(directory));
            migrated.keySet().removeIf(key -> key.startsWith("zstd."));
            return migrated;
        }));
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
            String enabled = settings.getProperty("capture.enabled", "false");
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
            if (!integrations.containsAll(maintenance))
                throw new IllegalArgumentException("api.maintenanceIntegrations must be included in api.integrations");
            return new ServerConfig(read, positive(settings, "query.manifestBytes"), integrations, maintenance,
                modIds(settings, "api.lifecycleIntegrations"),
                interval, Boolean.parseBoolean(enabled), preparation, mainThreadBudget, retries, retryDelay,
                fileClone.equals("auto"), readRate, backend);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid backup configuration", invalid);
        }
    }

    static Map<String, String> legacy(Path directory) throws IOException {
        var settings = new Properties();
        try {
            settings.load(new StringReader(YamlSettings.readText(directory.resolve("server.properties"))));
        } catch (NoSuchFileException absent) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, String>();
        for (var key : settings.stringPropertyNames()) result.put(key, settings.getProperty(key));
        return result;
    }

    private static Set<String> modIds(Properties settings, String field) {
        var ids = new HashSet<String>();
        var values = settings.getProperty(field, "").strip();
        if (!values.isEmpty()) {
            for (String id : values.split(",", -1)) {
                var trimmed = id.strip();
                if (!trimmed.matches("[a-z][a-z0-9_-]{1,63}") || !ids.add(trimmed) || ids.size() > 16)
                    throw new IllegalArgumentException("invalid or repeated mod ID in " + field);
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
