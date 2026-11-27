// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * 集成授权配置，独立存放在 {@code api.yml}。
 *
 * <p>授权属于高级功能且默认关闭：关闭时不做任何名单检查，所有被发现的外部集成都会获得
 * 全部权限。只有在安装了不完全信任的第三方集成时才需要开启并逐个列名。
 */
record ApiConfig(boolean authorization, Set<String> integrations, Set<String> maintenanceIntegrations,
                 Set<String> lifecycleIntegrations) {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako");

    /** 生命周期回调名单；授权关闭时为空，表示不筛选 */
    Optional<Set<String>> lifecycleAllowlist() {
        return authorization ? Optional.of(lifecycleIntegrations) : Optional.empty();
    }

    private static final String HEADER = """
        # BaruSikoZako integration authorization (advanced)
        # BaruSikoZako 集成授权（高级功能）
        #
        # Authorization is off by default: no allowlist is checked and every discovered
        # integration receives full rights. Only enable it when third-party integrations
        # of unknown origin are installed.
        # 授权默认关闭：不检查任何名单，所有被发现的外部集成都会获得全部权限。
        # 只有安装了来源不明的第三方集成时才需要开启。

        """;

    private static final String DEFAULTS = """
        schema=1
        # Advanced feature. true checks the allowlists below, false activates every discovered integration.
        # 高级功能。true 表示检查下面的授权名单，false 表示所有被发现的集成都会被激活。
        authorization.enabled=false
        # Comma separated mod IDs allowed to use the backup API.
        # 允许使用备份 API 的模组标识，逗号分隔。
        authorization.integrations=""
        # Mod IDs additionally allowed to restore and delete.
        # 额外允许恢复与删除的模组标识。
        authorization.maintenanceIntegrations=""
        # Mod IDs allowed to receive lifecycle hooks.
        # 允许接收生命周期回调的模组标识。
        authorization.lifecycleIntegrations=""
        """;

    private static final Set<String> FIELDS = Set.of("schema", "authorization.enabled",
        "authorization.integrations", "authorization.maintenanceIntegrations", "authorization.lifecycleIntegrations");

    static Path file() {
        return LoaderBridge.configDirectory().resolve("barusikozako").resolve("api.yml");
    }

    static ApiConfig load(boolean createFile) throws IOException {
        return parse(YamlSettings.load(file(), defaults(), createFile, Map::of, HEADER));
    }

    static Map<String, String> defaults() {
        var defaults = new LinkedHashMap<String, String>();
        DEFAULTS.lines().filter(line -> line.contains("=") && !line.startsWith("#")).forEach(line -> {
            int separator = line.indexOf('=');
            defaults.put(line.substring(0, separator), line.substring(separator + 1).replace("\"", ""));
        });
        return defaults;
    }

    static ApiConfig parse(Map<String, String> values) throws IOException {
        var settings = new Properties();
        settings.putAll(defaults());
        settings.putAll(values);
        try {
            for (String field : settings.stringPropertyNames()) {
                if (!FIELDS.contains(field)) throw new IllegalArgumentException("unknown configuration key " + field);
            }
            if (!"1".equals(settings.getProperty("schema"))) throw new IllegalArgumentException("unsupported schema");
            String enabled = settings.getProperty("authorization.enabled", "false");
            if (!enabled.equals("true") && !enabled.equals("false"))
                throw new IllegalArgumentException("invalid authorization.enabled");
            return new ApiConfig(Boolean.parseBoolean(enabled),
                ConfigIds.modIds(settings, "authorization.integrations"),
                ConfigIds.modIds(settings, "authorization.maintenanceIntegrations"),
                ConfigIds.ids(settings, "authorization.lifecycleIntegrations", "[a-z][a-z0-9_.-]{1,127}"));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid API configuration", invalid);
        }
    }

    /** 仍是刚生成、未改动的 api.yml：授权关闭且三份名单为空 */
    private static boolean isPristine(Path target) {
        try {
            var existing = parse(YamlSettings.load(target, defaults(), false, Map::of, HEADER));
            return !existing.authorization() && existing.integrations().isEmpty()
                && existing.maintenanceIntegrations().isEmpty() && existing.lifecycleIntegrations().isEmpty();
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * 把旧版 {@code server.yml} 中的 {@code api.*} 迁移到 {@code api.yml}。
     * 只在 api.yml 尚不存在时写入；已存在时仅提示旧键被忽略，不覆盖用户当前配置。
     */
    static void migrateLegacy(Map<String, String> legacy) {
        if (legacy == null || legacy.isEmpty()) return;
        Path target = file();
        if (Files.exists(target) && !isPristine(target)) {
            LOG.log(System.Logger.Level.INFO,
                "api.* keys in server.yml are ignored; integration authorization now lives in api.yml");
            return;
        }
        var migrated = defaults();
        boolean authorized = false;
        for (var entry : legacy.entrySet()) {
            String key = switch (entry.getKey()) {
                case "api.integrations" -> "authorization.integrations";
                case "api.maintenanceIntegrations" -> "authorization.maintenanceIntegrations";
                case "api.lifecycleIntegrations" -> "authorization.lifecycleIntegrations";
                default -> null;
            };
            if (key == null || entry.getValue() == null) continue;
            String value = entry.getValue().strip();
            if (value.isEmpty()) continue;
            migrated.put(key, value);
            authorized = true;
        }
        // 旧版只要配置了名单就说明在使用授权，迁移后保持开启；否则保持新的默认值（关闭）。
        migrated.put("authorization.enabled", Boolean.toString(authorized));
        try {
            parse(migrated);
        } catch (IOException invalid) {
            LOG.log(System.Logger.Level.WARNING, "Legacy api.* settings are invalid and were not migrated", invalid);
            return;
        }
        try {
            YamlSettings.load(target, migrated, true, Map::of, HEADER);
            LOG.log(System.Logger.Level.INFO,
                "Moved integration authorization from server.yml to api.yml (enabled={0})", authorized);
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not write api.yml", failure);
        }
    }
}
