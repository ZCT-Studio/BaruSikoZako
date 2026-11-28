// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.backend.ConfigUnits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 独立的 progress.yml 显示配置。局内命令不提供读写入口，修改后需重启生效。
 * 每种操作（备份、校验）拥有独立的显示通道与阶段配色，便于以后继续扩展。
 */
record ProgressConfig(int schema, boolean enabled, long updateIntervalMillis, Size size,
                      Operation backup, Operation verify) {
    static final String DEFAULT_BOSSBAR = "{action} {percent} · {progress} {speed} · {elapsed_raw} ~{remaining_raw}";
    static final String DEFAULT_SUCCESS =
        "{result} · {original} → {archive} {saved} · {entries} · {verified} · {elapsed}";
    static final String DEFAULT_FAILURE = "{result}";
    static final String DEFAULT_CANCELLED = "{result}";
    private static final System.Logger LOG = System.getLogger(ProgressConfig.class.getName());
    static final String FILE = "progress.yml";
    static final int SCHEMA = 3;

    /** 旧版模板结构，直接重写为新默认 */
    private static final class SchemaMismatch extends IOException {
        private SchemaMismatch(int actual) {
            super("Unsupported progress display schema: " + actual);
        }
    }

    private static final Set<String> DISPLAY_COLORS = Set.of(
        "pink", "blue", "red", "green", "yellow", "purple", "white");
    private static final Set<String> BOSS_BAR_STYLES = Set.of(
        "progress", "notched_6", "notched_10", "notched_12", "notched_20");

    enum Channel {
        BOSS_BAR, NONE;

        static Channel parse(Object value, Channel fallback) {
            if (value == null) return fallback;
            return switch (value.toString().trim().toLowerCase(Locale.ROOT)) {
                case "bossbar" -> BOSS_BAR;
                case "none" -> NONE;
                default -> fallback;
            };
        }
    }

    enum Audience {
        ALL, OPERATORS, NONE;

        static Audience parse(Object value, Audience fallback) {
            if (value == null) return fallback;
            return switch (value.toString().trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "operators" -> OPERATORS;
                default -> NONE;
            };
        }
    }

    /** 显示阶段：决定 BossBar 颜色 */
    enum Stage {
        PREPARING, RUNNING, SUCCESS, FAILURE, UNCERTAIN
    }

    record Mode(Channel display, Audience audience) {}

    record BossBar(String style, String textColor) {}

    record Info(boolean stage, boolean percentage, boolean processedSize, boolean totalSize,
                boolean speed, boolean elapsed, boolean estimated) {}

    record Completion(boolean enabled, long durationMillis, boolean originalSize, boolean backupSize,
                      boolean savedSpace, boolean savedPercentage, boolean elapsed) {}

    /** 各阶段的颜色，可写颜色名或 #RRGGBB */
    record Colors(String preparing, String running, String success, String failure, String uncertain) {
        String forStage(Stage stage) {
            return switch (stage) {
                case PREPARING -> preparing;
                case RUNNING -> running;
                case SUCCESS -> success;
                case FAILURE -> failure;
                case UNCERTAIN -> uncertain;
            };
        }
    }

    /** 单种操作（backup / verify）的显示配置 */
    record Operation(Mode initiated, Mode nonInitiated, BossBar bossbar,
                     Info info, Completion completion, Colors colors, Templates templates) {}

    record Size(boolean binary, int precision) {
        String format(long bytes) {
            return ConfigUnits.formatBytes(bytes,
                binary ? ConfigUnits.SizeUnit.BINARY : ConfigUnits.SizeUnit.DECIMAL, precision);
        }

        String formatPair(long first, long second) {
            return ConfigUnits.formatBytesPair(first, second,
                binary ? ConfigUnits.SizeUnit.BINARY : ConfigUnits.SizeUnit.DECIMAL, precision);
        }
    }

    /** 文本模板；空的占位符会被连同相邻分隔符一起省略 */
    record Templates(String bossbar, String success, String failure, String cancelled) {}

    static ProgressConfig load(Path configRoot) {
        var file = configRoot.resolve(FILE);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(configRoot);
                Files.writeString(file, DEFAULT_TEXT, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return defaults();
            }
            try {
                return parse(YamlSettings.readText(file));
            } catch (SchemaMismatch legacy) {
                Files.writeString(file, DEFAULT_TEXT, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
                return defaults();
            }
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING,
                "Could not read progress display configuration; using defaults", failure);
            return defaults();
        }
    }

    static ProgressConfig defaults() {
        try {
            return parse(DEFAULT_TEXT);
        } catch (IOException unexpected) {
            throw new IllegalStateException("Default progress configuration is invalid", unexpected);
        }
    }

    private static ProgressConfig parse(String text) throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(8);
        options.setCodePointLimit(64 * 1024);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(text);
        } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
            throw new IOException("Invalid progress display configuration", invalid);
        }
        if (!(loaded instanceof Map<?, ?> root)) throw new IOException("Expected a YAML mapping");
        int schema = intValue(root.get("schema"), 1);
        if (schema != SCHEMA) throw new SchemaMismatch(schema);
        var size = section(root, "size");
        var template = section(root, "template");
        return new ProgressConfig(
            schema,
            bool(root.get("enabled"), true),
            duration(root.get("update-interval"), 200, 100, 10_000),
            new Size(
                !"decimal".equals(choice(size.get("unit"), Set.of("binary", "decimal"), "binary")),
                clamp(intValue(size.get("precision"), 2), 0, 3)),
            operation(root, template, "backup", "green", Channel.BOSS_BAR, Audience.OPERATORS),
            operation(root, template, "verify", "purple", Channel.NONE, Audience.ALL));
    }

    private static Operation operation(Map<?, ?> root, Map<?, ?> templateRoot, String key,
                                       String defaultRunningColor, Channel nonInitiatedDisplay,
                                       Audience nonInitiatedAudience) {
        var operation = section(root, key);
        var templates = section(templateRoot, key);
        var initiated = section(operation, "initiated");
        var nonInitiated = section(operation, "non-initiated");
        var bossbar = section(operation, "bossbar");
        var info = section(operation, "progress");
        var completion = section(operation, "completion");
        var colors = section(operation, "colors");
        return new Operation(
            new Mode(Channel.parse(initiated.get("display"), Channel.BOSS_BAR), Audience.ALL),
            new Mode(Channel.parse(nonInitiated.get("display"), nonInitiatedDisplay),
                Audience.parse(nonInitiated.get("audience"), nonInitiatedAudience)),
            new BossBar(
                choice(bossbar.get("style"), BOSS_BAR_STYLES, "progress"),
                color(bossbar.get("text-color"), "#FFFFFF")),
            new Info(
                bool(info.get("show-stage"), true),
                bool(info.get("show-percentage"), true),
                bool(info.get("show-processed-size"), true),
                bool(info.get("show-total-size"), true),
                bool(info.get("show-speed"), true),
                bool(info.get("show-elapsed-time"), true),
                bool(info.get("show-estimated-time"), true)),
            new Completion(
                bool(completion.get("enabled"), true),
                duration(completion.get("duration"), 10_000, 500, 120_000),
                bool(completion.get("show-original-size"), true),
                bool(completion.get("show-backup-size"), true),
                bool(completion.get("show-saved-space"), true),
                bool(completion.get("show-saved-percentage"), true),
                bool(completion.get("show-elapsed-time"), true)),
            new Colors(
                displayColor(colors.get("preparing"), "white"),
                displayColor(colors.get("running"), defaultRunningColor),
                displayColor(colors.get("success"), "green"),
                displayColor(colors.get("failure"), "red"),
                displayColor(colors.get("uncertain"), "yellow")),
            new Templates(
                template(templates.get("bossbar"), DEFAULT_BOSSBAR),
                template(templates.get("success"), DEFAULT_SUCCESS),
                template(templates.get("failure"), DEFAULT_FAILURE),
                template(templates.get("cancelled"), DEFAULT_CANCELLED)));
    }

    private static String template(Object value, String fallback) {
        if (value == null) return fallback;
        var text = value.toString();
        return text.isEmpty() || text.length() > 512 ? fallback : text;
    }

    private static Map<?, ?> section(Map<?, ?> root, String key) {
        return root.get(key) instanceof Map<?, ?> value ? value : Map.of();
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean flag ? flag : fallback;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static long duration(Object value, long fallbackMillis, long minimum, long maximum) {
        long millis = fallbackMillis;
        if (value instanceof Number number) {
            millis = number.longValue();
        } else if (value != null) {
            var text = value.toString().trim().toLowerCase(Locale.ROOT);
            try {
                if (text.endsWith("ms")) millis = Long.parseLong(text.substring(0, text.length() - 2).trim());
                else if (text.endsWith("s")) millis = Math.round(Double.parseDouble(
                    text.substring(0, text.length() - 1).trim()) * 1000);
                else if (text.endsWith("m")) millis = Math.round(Double.parseDouble(
                    text.substring(0, text.length() - 1).trim()) * 60_000);
                else millis = Long.parseLong(text);
            } catch (NumberFormatException invalid) {
                millis = fallbackMillis;
            }
        }
        return clamp(millis, minimum, maximum);
    }

    private static String choice(Object value, Set<String> allowed, String fallback) {
        if (value == null) return fallback;
        var text = value.toString().trim().toLowerCase(Locale.ROOT);
        return allowed.contains(text) ? text : fallback;
    }

    private static String color(Object value, String fallback) {
        if (value == null) return fallback;
        var text = value.toString().trim();
        return text.matches("#[0-9a-fA-F]{6}") ? text.toUpperCase(Locale.ROOT) : fallback;
    }

    /** 阶段颜色：接受颜色名或 HEX */
    private static String displayColor(Object value, String fallback) {
        if (value == null) return fallback;
        var text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (DISPLAY_COLORS.contains(text)) return text;
        return text.matches("#[0-9a-f]{6}") ? text.toUpperCase(Locale.ROOT) : fallback;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final String DEFAULT_TEXT = """
        # BaruSikoZako 进度显示 / Progress display
        # 本文件仅供手动编辑，局内命令不会修改它；修改后重启服务器生效。
        # Edit this file manually; in-game commands never change it. Restart the server to apply.
        # 时间支持 ms、s、m，或直接写毫秒数 / Time accepts ms, s, m, or a bare millisecond value.

        # 配置格式版本
        # Configuration schema version
        schema: 3

        # 是否启用进度显示
        # Whether progress display is enabled
        enabled: true

        # 进度刷新间隔
        # Display refresh interval
        update-interval: 200ms

        # 存储大小单位
        # Storage size units
        size:
          # binary: KiB / MiB / GiB
          # decimal: KB / MB / GB
          unit: binary
          precision: 2

        # 文本模板 / Text templates
        # 占位符会按玩家语言输出已翻译文字，空值会自动省略相邻分隔符。
        # Placeholders render translated text per player; empty values drop the adjacent separator.
        # 过程占位符 / Progress placeholders:
        #   {action}     阶段「执行中」/ translated stage
        #   {percent}    42%
        #   {progress}   1.20 GiB / 2.00 GiB
        #   {progress_simple}  0.20 / 2.00 GiB（与总量同单位 / same unit as total）
        #   {speed}      120.00 MiB/s
        #   {elapsed}    耗时 1m 05s / elapsed 1m 05s
        #   {remaining}  剩余约 30s / ~30s left
        # 原始值占位符不含任何翻译文字 / Raw placeholders contain no translated words:
        #   {action_raw} {percent_raw} {progress_raw} {progress_simple_raw} {speed_raw} {elapsed_raw} {remaining_raw}
        # 结果占位符 / Result placeholders:
        #   {result} {original} {archive} {saved} {entries} {verified} {elapsed}
        #   {result_raw} {original_raw} {archive_raw} {saved_raw} {saved_percent_raw}
        #   {entries_raw} {verified_raw} {elapsed_raw}
        # 备份与校验各自使用独立模板 / Backup and verification use separate templates
        template:
          backup:
            bossbar: "{action} {percent} · {progress} {speed} · {elapsed_raw} ~{remaining_raw}"
            success: "{result} · {original} → {archive} {saved} · {entries} · {verified} · {elapsed}"
            failure: "{result}"
            cancelled: "{result}"
          verify:
            bossbar: "{action} {percent} · {progress} {speed} · {elapsed_raw} ~{remaining_raw}"
            success: "{result} · {entries} · {verified} · {elapsed}"
            failure: "{result}"
            cancelled: "{result}"

        # 备份进度 / Backup progress
        backup:
          # 主动发起的备份：只对发起者本人显示
          # Backups started by the viewing player
          initiated:
            # bossbar / none
            display: bossbar

          # 非主动发起的备份：定时备份、其他玩家、控制台及第三方 API
          # 默认向所有管理员展示进度
          # Backups not started by the viewing player: scheduled, other players, console, third-party API
          # Progress is shown to all administrators by default
          non-initiated:
            # bossbar / none
            display: bossbar

            # all / operators / none
            audience: operators

          # 原版 BossBar 样式
          # Vanilla BossBar style
          bossbar:
            # progress / notched_6 / notched_10 / notched_12 / notched_20
            style: progress

            # 标题文字颜色，支持 HEX
            # Title color, HEX supported
            text-color: "#FFFFFF"

          # 备份过程信息
          # Backup Progress text
          progress:
            show-stage: true
            show-percentage: true
            show-processed-size: true
            show-total-size: true
            show-speed: true
            show-elapsed-time: true
            show-estimated-time: true

          # 任务结束后的结果展示
          # Completion display
          completion:
            enabled: true

            # 结果停留时间
            # How long the result stays visible
            duration: 10s

            show-original-size: true
            show-backup-size: true
            show-saved-space: true
            show-saved-percentage: true
            show-elapsed-time: true

          # 阶段颜色 / Stage colors
          # white / pink / blue / red / green / yellow / purple
          colors:
            preparing: white
            running: green
            success: green
            failure: red
            uncertain: yellow

        # 校验进度
        # Verification progress
        verify:
          initiated:
            # bossbar / none
            display: bossbar

          non-initiated:
            # bossbar / none
            display: none

            # all / operators / none
            audience: all

          bossbar:
            # progress / notched_6 / notched_10 / notched_12 / notched_20
            style: progress

            text-color: "#FFFFFF"

          progress:
            show-stage: true
            show-percentage: true
            show-processed-size: true
            show-total-size: true
            show-speed: true
            show-elapsed-time: true
            show-estimated-time: true

          completion:
            enabled: true
            duration: 10s

            show-original-size: true
            show-backup-size: true
            show-saved-space: true
            show-saved-percentage: true
            show-elapsed-time: true

          # 阶段颜色 / Stage colors
          # white / pink / blue / red / green / yellow / purple
          colors:
            preparing: white
            running: purple
            success: green
            failure: red
            uncertain: yellow
        """;
}
