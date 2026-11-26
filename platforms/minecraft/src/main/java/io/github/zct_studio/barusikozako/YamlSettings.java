// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

final class YamlSettings {
    static final String SCHEMA = "schema";
    private static final int MAX_BYTES = 16 * 1024;
    private static final int MAX_DEPTH = 4;

    interface Migration { Map<String, String> read() throws IOException; }
    interface Validator { void validate(Map<String, String> values) throws IOException; }

    private YamlSettings() {}

    static Map<String, String> load(Path file, Map<String, String> defaults, boolean create, Migration migration)
            throws IOException {
        try {
            return parse(readText(file));
        } catch (NoSuchFileException absent) {
            var settings = new LinkedHashMap<>(defaults);
            settings.putAll(migration.read());
            if (!create) return settings;
            Files.createDirectories(file.getParent());
            try {
                Files.write(file, encode(settings), StandardOpenOption.CREATE_NEW);
                return settings;
            } catch (FileAlreadyExistsException concurrent) {
                return parse(readText(file));
            }
        }
    }

    static synchronized void change(Path file, Map<String, String> defaults, String key, String value,
                                    Validator validator) throws IOException {
        if (SCHEMA.equals(key)) throw new IOException("The configuration schema is read-only");
        if (!defaults.containsKey(key)) throw new IOException("Unknown configuration key " + key);
        var settings = new LinkedHashMap<>(defaults);
        settings.putAll(parse(readText(file)));
        settings.put(key, value);
        validator.validate(settings);
        var encoded = encode(settings);
        var staged = Files.createTempFile(file.getParent(), ".config-", ".yml");
        try {
            Files.write(staged, encoded);
            Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static byte[] encode(Map<String, String> settings) throws IOException {
        var encoded = render(settings).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IOException("Configuration exceeds 16 KiB");
        return encoded;
    }

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("schema", "Configuration schema version\n配置格式版本"),
            Map.entry("backend", "Backup backend keyword\n备份后端标识"),
            Map.entry("schedule.intervalSeconds", "Scheduled backup interval in seconds, 0 disables scheduling\n定时备份间隔，0 表示关闭"),
            Map.entry("capture.enabled", "Enable online capture when supported\n在平台支持时启用在线捕获"),
            Map.entry("capture.preparationMillis", "Capture preparation timeout in milliseconds\n捕获准备超时"),
            Map.entry("capture.mainThreadBudgetMillis", "Main-thread preparation limit in milliseconds, 0 disables the limit\n主线程准备时间上限，0 表示不限制"),
            Map.entry("capture.fileRetries", "Additional retries when a file changes\n文件变化时的额外重试次数"),
            Map.entry("capture.fileRetryDelayMillis", "Linear file retry delay in milliseconds\n文件重试的线性延迟"),
            Map.entry("capture.retries", "Whole-capture retry count\n整次捕获重试次数"),
            Map.entry("capture.consistency", "Capture consistency: strict or relaxed\n捕获一致性，可选 strict 或 relaxed"),
            Map.entry("api.integrations", "Allowed integration IDs\n允许的集成标识"),
            Map.entry("api.maintenanceIntegrations", "Maintenance integration IDs\n允许维护操作的集成标识"),
            Map.entry("api.lifecycleIntegrations", "Lifecycle integration IDs\n生命周期集成标识"),
            Map.entry("capture.fileClone", "File clone strategy, auto or off\n文件克隆策略，可选 auto 或 off"),
            Map.entry("backup.readBytesPerSecond", "Read rate in bytes per second, 0 disables the limit\n每秒读取速率上限，0 表示不限制"),
            Map.entry("ignore.gitignore", "Parse .gitignore files as local rules, like local.gitignore\n解析各目录的 .gitignore，与 local.gitignore 一样作为局部规则"),
            Map.entry("ignore.pteroignore", "Parse the game root .pteroignore, paths are relative to the game root\n解析游戏根目录的 .pteroignore，规则路径相对于游戏根目录"),
            Map.entry("verify.contentBytes", "Maximum content bytes for verification\n完整校验的内容大小上限"),
            Map.entry("verify.archiveBytes", "Maximum archive bytes for verification\n完整校验的归档大小上限"),
            Map.entry("verify.entries", "Maximum entries for verification\n完整校验的条目数量上限"),
            Map.entry("query.manifestBytes", "Maximum manifest bytes for queries\n查询时清单大小上限"),
            Map.entry("level", "Zstd compression level, 1 to 9\nZstd 压缩等级，1 至 9"),
            Map.entry("compressionThreads", "Compression threads, 1 to 4\n压缩线程数量，1 至 4"),
            Map.entry("writeMode", "Backup mode: full, incremental, differential or fixed-chunks\n备份模式，full、incremental、differential 或 fixed-chunks"),
            Map.entry("baseline", "Full baseline UUID for differential backups\n差异备份使用的全量基线 UUID"),
            Map.entry("maxIncrementals", "Maximum consecutive incrementals, 0 disables this trigger\n连续增量次数上限，0 表示关闭此条件"),
            Map.entry("fullThresholdPercent", "Cumulative incremental size percentage, 0 disables this trigger\n累计增量大小百分比上限，0 表示关闭此条件"),
            Map.entry("fullIntervalSeconds", "Maximum full baseline age in seconds, 0 disables this trigger\n全量基线最长使用时间，0 表示关闭此条件"),
            Map.entry("gcIntervalSeconds", "Garbage collection interval in seconds, 0 disables it\n垃圾回收间隔，0 表示关闭")
    );

    private static final class ConfigNode {
        final Map<String, ConfigNode> children = new LinkedHashMap<>();
        String value;
    }

    private static ConfigNode expand(Map<String, String> settings) throws IOException {
        var root = new ConfigNode();
        for (var entry : settings.entrySet()) {
            var path = entry.getKey();
            if (path == null || entry.getValue() == null) throw new IOException("Null configuration key or value");
            var parts = path.split("\\.", -1);
            var current = root;
            for (var part : parts) {
                if (part.isEmpty()) throw new IOException("Invalid configuration path: " + path);
                if (current.value != null) throw new IOException("Conflicting configuration path: " + path);
                current = current.children.computeIfAbsent(part, ignored -> new ConfigNode());
            }
            if (!current.children.isEmpty() || current.value != null)
                throw new IOException("Conflicting configuration path: " + path);
            current.value = entry.getValue();
        }
        return root;
    }

    private static String render(Map<String, String> settings) throws IOException {
        var output = new StringBuilder("""
            # Time: ms, s, m, h, d, w, M/month (30 days), y (365 days)
            # 时间：ms、s、m、h、d、w、M/month（30 天）、y（365 天）
            # Size: B/Byte, KB, MB, GB, TB (decimal); KiB, MiB, GiB, TiB (binary)
            # 容量：B/Byte、KB、MB、GB、TB 为十进制，KiB、MiB、GiB、TiB 为二进制
            # Bare numbers use the unit named by each key
            # 纯数字使用配置项名称中的单位

            """);
        var options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultScalarStyle(org.yaml.snakeyaml.DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        options.setSplitLines(false);
        var yaml = new Yaml(options);
        var root = expand(settings);
        // schema 始终写在最开头
        var schema = root.children.remove(SCHEMA);
        if (schema != null) {
            var ordered = new LinkedHashMap<String, ConfigNode>();
            ordered.put(SCHEMA, schema);
            ordered.putAll(root.children);
            root.children.clear();
            root.children.putAll(ordered);
        }
        renderMapping(output, root, yaml, "", 0);
        if (settings.isEmpty()) output.append("{}\n");
        return output.toString();
    }

    private static void renderMapping(StringBuilder output, ConfigNode parent, Yaml yaml, String prefix, int depth) {
        var indent = "  ".repeat(depth);
        for (var entry : parent.children.entrySet()) {
            var key = entry.getKey();
            var node = entry.getValue();
            var path = prefix.isEmpty() ? key : prefix + "." + key;
            // Keep unusually deep legacy paths dotted at the nesting limit.
            if (node.value == null && depth >= MAX_DEPTH - 1) {
                renderFlat(output, node, yaml, prefix, key, indent);
                continue;
            }
            appendDescription(output, path, indent);
            if (node.value == null) {
                output.append(indent).append(scalarKey(yaml, key)).append(":\n");
                renderMapping(output, node, yaml, path, depth + 1);
            } else {
                appendValue(output, yaml, key, node.value, indent);
            }
        }
    }

    private static void renderFlat(StringBuilder output, ConfigNode node, Yaml yaml,
                                   String prefix, String key, String indent) {
        if (node.value != null) {
            appendDescription(output, prefix.isEmpty() ? key : prefix + "." + key, indent);
            appendValue(output, yaml, key, node.value, indent);
        } else {
            node.children.forEach((part, child) -> renderFlat(output, child, yaml, prefix, key + "." + part, indent));
        }
    }

    private static String scalarKey(Yaml yaml, String key) {
        if (key.matches("[A-Za-z_][A-Za-z0-9_.-]*") && !key.matches("(?i)true|false|yes|no|on|off|null|y|n")) return key;
        return yaml.dump(key).stripTrailing();
    }

    private static void appendDescription(StringBuilder output, String path, String indent) {
        var description = DESCRIPTIONS.get(path);
        if (description != null) description.lines().forEach(line -> output.append(indent).append("# ").append(line).append('\n'));
    }

    private static void appendValue(StringBuilder output, Yaml yaml, String key, String value, String indent) {
        output.append(indent).append(scalarKey(yaml, key)).append(": ")
                .append(yaml.dump(value).stripTrailing()).append("\n\n");
    }

    static String readText(Path file) throws IOException {
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var content = stream.readNBytes(MAX_BYTES + 1);
            if (content.length > MAX_BYTES) throw new IOException("Configuration exceeds 16 KiB");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
        }
    }

    private static Map<String, String> parse(String text) throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(MAX_BYTES);
        try {
            Object loaded = new Yaml(new SafeConstructor(options)).load(text);
            if (!(loaded instanceof Map<?, ?> fields)) throw new IOException("Expected a YAML configuration mapping");
            var values = new LinkedHashMap<String, String>();
            flatten(fields, "", values);
            expand(values);
            return values;
        } catch (YAMLException invalid) {
            throw new IOException("Invalid YAML configuration", invalid);
        }
    }

    private static void flatten(Map<?, ?> fields, String prefix, Map<String, String> values) throws IOException {
        for (var field : fields.entrySet()) {
            if (!(field.getKey() instanceof String key) || key.isEmpty())
                throw new IOException("Expected non-empty string configuration keys");
            var path = prefix.isEmpty() ? key : prefix + "." + key;
            var value = field.getValue();
            if (value instanceof Map<?, ?> nested) {
                if (nested.isEmpty()) throw new IOException("Empty configuration mapping: " + path);
                flatten(nested, path, values);
            } else {
                if (!(value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Boolean))
                    throw new IOException("Expected scalar YAML configuration value: " + path);
                if (values.putIfAbsent(path, value.toString()) != null)
                    throw new IOException("Duplicate configuration key: " + path);
            }
        }
    }
}
