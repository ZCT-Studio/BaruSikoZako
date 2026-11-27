// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.standalone;

import io.github.zct_studio.barusikozako.backend.Consistency;
import io.github.zct_studio.barusikozako.backend.ConfigUnits;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.zstd.ZstdProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 独立工作站配置。键名与游戏内 {@code config/barusikozako/server.yml} 及
 * {@code barusikozako_backendzstd.yml} 保持一致，使命令行操作与游戏内一致。
 */
public final class StandaloneConfig {
    public static final String DEFAULT_BACKEND = "barusikozako_backendzstd";

    private static final int MAX_BYTES = 16 * 1024;

    private static final Map<String, String> SERVER_DEFAULTS = Map.ofEntries(
        Map.entry("schema", "1"),
        Map.entry("backend", DEFAULT_BACKEND),
        Map.entry("verify.contentBytes", "256GiB"),
        Map.entry("verify.archiveBytes", "320GiB"),
        Map.entry("verify.entries", "1000000"),
        Map.entry("query.manifestBytes", "128MiB"),
        Map.entry("backup.readBytesPerSecond", "0"),
        Map.entry("capture.consistency", "strict"),
        Map.entry("ignore.gitignore", "false"),
        Map.entry("ignore.pteroignore", "false"));

    private static final String SERVER_HEADER = """
        # BaruSikoZako 独立工作站配置（离线备份 / 回滚）
        # Time: ms, s, m, h, d, w, M/month (30 days), y (365 days)
        # Size: B/Byte, KB, MB, GB, TB (decimal); KiB, MiB, GiB, TiB (binary)

        """;

    private final String backendKeyword;
    private final BackupEngine.Limits readLimits;
    private final long manifestBudget;
    private final long readBytesPerSecond;
    private final Consistency consistency;
    private final boolean parseGitignore;
    private final boolean parsePteroignore;
    private final Map<String, String> backendSettings;

    private StandaloneConfig(String backendKeyword, BackupEngine.Limits readLimits, long manifestBudget,
                             long readBytesPerSecond, Consistency consistency, boolean parseGitignore,
                             boolean parsePteroignore, Map<String, String> backendSettings) {
        this.backendKeyword = backendKeyword;
        this.readLimits = readLimits;
        this.manifestBudget = manifestBudget;
        this.readBytesPerSecond = readBytesPerSecond;
        this.consistency = consistency;
        this.parseGitignore = parseGitignore;
        this.parsePteroignore = parsePteroignore;
        this.backendSettings = backendSettings;
    }

    public String backendKeyword() { return backendKeyword; }
    public BackupEngine.Limits readLimits() { return readLimits; }
    public long manifestBudget() { return manifestBudget; }
    public long readBytesPerSecond() { return readBytesPerSecond; }
    public Consistency consistency() { return consistency; }
    public boolean parseGitignore() { return parseGitignore; }
    public boolean parsePteroignore() { return parsePteroignore; }
    public Map<String, String> backendSettings() { return backendSettings; }

    public static StandaloneConfig load(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        var server = loadYaml(configDir.resolve("server.yml"), SERVER_DEFAULTS, SERVER_HEADER);
        String backend = server.getOrDefault("backend", DEFAULT_BACKEND);
        if (!backend.matches("[a-z][a-z0-9_-]{1,63}"))
            throw new IOException("Invalid backend keyword: " + backend);

        var provider = new ZstdProvider();
        var backendDefaults = provider.defaults();
        var backendValues = new LinkedHashMap<>(backendDefaults);
        backendValues.putAll(loadYaml(configDir.resolve(backend + ".yml"), backendDefaults, backendHeader(backend)));
        // 校验后端配置（复用 ZstdProvider 的校验语义）
        provider.configure(Map.copyOf(backendValues));

        var limits = new BackupEngine.Limits(bytes(server, "verify.contentBytes"),
            bytes(server, "verify.archiveBytes"), positive(server, "verify.entries"));
        long manifest = bytes(server, "query.manifestBytes");
        long rate = ConfigUnits.bytes(server.getOrDefault("backup.readBytesPerSecond", "0"));
        if (rate < 0) throw new IOException("invalid read rate");
        var consistency = Consistency.parse(server.getOrDefault("capture.consistency", "strict"));
        boolean gitignore = bool(server, "ignore.gitignore");
        boolean ptero = bool(server, "ignore.pteroignore");
        return new StandaloneConfig(backend, limits, manifest, rate, consistency, gitignore, ptero,
            Map.copyOf(backendValues));
    }

    private static String backendHeader(String backend) {
        return "# BaruSikoZako backend configuration: " + backend + "\n"
            + "# Time: ms, s, m, h, d, w, M/month (30 days), y (365 days)\n\n";
    }

    /** 返回生效中的 server.yml 扁平键值。 */
    public static Map<String, String> serverValues(Path configDir) throws IOException {
        return loadYaml(configDir.resolve("server.yml"), SERVER_DEFAULTS, SERVER_HEADER);
    }

    /** 返回生效中的后端 yml 扁平键值。 */
    public static Map<String, String> backendValues(Path configDir) throws IOException {
        var server = serverValues(configDir);
        var backend = server.getOrDefault("backend", DEFAULT_BACKEND);
        return loadYaml(configDir.resolve(backend + ".yml"), new ZstdProvider().defaults(), backendHeader(backend));
    }

    public static void setServerValue(Path configDir, String key, String value) throws IOException {
        var defaults = SERVER_DEFAULTS;
        if (!defaults.containsKey(key)) throw new IOException("Unknown server configuration key: " + key);
        if ("schema".equals(key)) throw new IOException("The schema key is read-only");
        var values = new LinkedHashMap<>(serverValues(configDir));
        values.put(key, value);
        validateServer(values);
        atomicWrite(configDir.resolve("server.yml"), render(values, SERVER_HEADER));
    }

    public static void setBackendValue(Path configDir, String key, String value) throws IOException {
        var backend = serverValues(configDir).getOrDefault("backend", DEFAULT_BACKEND);
        var defaults = new ZstdProvider().defaults();
        if (!defaults.containsKey(key)) throw new IOException("Unknown backend configuration key: " + key);
        if ("schema".equals(key)) throw new IOException("The schema key is read-only");
        var values = new LinkedHashMap<>(backendValues(configDir));
        values.put(key, value);
        new ZstdProvider().configure(Map.copyOf(values));
        atomicWrite(configDir.resolve(backend + ".yml"), render(values, backendHeader(backend)));
    }

    private static void validateServer(Map<String, String> server) {
        String backend = server.getOrDefault("backend", DEFAULT_BACKEND);
        if (!backend.matches("[a-z][a-z0-9_-]{1,63}")) throw new IllegalArgumentException("invalid backend keyword");
        bytes(server, "verify.contentBytes");
        bytes(server, "verify.archiveBytes");
        positive(server, "verify.entries");
        bytes(server, "query.manifestBytes");
        if (ConfigUnits.bytes(server.getOrDefault("backup.readBytesPerSecond", "0")) < 0)
            throw new IllegalArgumentException("invalid read rate");
        Consistency.parse(server.getOrDefault("capture.consistency", "strict"));
        bool(server, "ignore.gitignore");
        bool(server, "ignore.pteroignore");
    }

    private static void atomicWrite(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        var staged = Files.createTempFile(file.getParent(), ".config-", ".yml");
        try {
            Files.writeString(staged, content, StandardCharsets.UTF_8);
            Files.move(staged, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static long bytes(Map<String, String> values, String key) {
        long value = ConfigUnits.bytes(values.getOrDefault(key, "0"));
        if (value < 1) throw new IllegalArgumentException("nonpositive value for " + key);
        return value;
    }

    private static long positive(Map<String, String> values, String key) {
        long value = Long.parseLong(values.getOrDefault(key, "0"));
        if (value < 1) throw new IllegalArgumentException("nonpositive value for " + key);
        return value;
    }

    private static boolean bool(Map<String, String> values, String key) {
        String value = values.getOrDefault(key, "false");
        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException("invalid boolean for " + key);
        return Boolean.parseBoolean(value);
    }

    /** 读取并扁平化 YAML 映射，缺失时创建默认文件。 */
    private static Map<String, String> loadYaml(Path file, Map<String, String> defaults, String header)
            throws IOException {
        try {
            return flatten(readText(file));
        } catch (NoSuchFileException absent) {
            var settings = new LinkedHashMap<>(defaults);
            try {
                Files.write(file, render(settings, header).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException concurrent) {
                return flatten(readText(file));
            }
            return settings;
        }
    }

    private static String readText(Path file) throws IOException {
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var content = stream.readNBytes(MAX_BYTES + 1);
            if (content.length > MAX_BYTES) throw new IOException("Configuration exceeds 16 KiB");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
        }
    }

    private static Map<String, String> flatten(String text) throws IOException {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(4);
        options.setCodePointLimit(MAX_BYTES);
        try {
            Object loaded = new Yaml(new SafeConstructor(options)).load(text);
            if (loaded == null) return new LinkedHashMap<>();
            if (!(loaded instanceof Map<?, ?> fields)) throw new IOException("Expected a YAML mapping");
            var values = new LinkedHashMap<String, String>();
            flattenInto(fields, "", values);
            return values;
        } catch (YAMLException invalid) {
            throw new IOException("Invalid YAML configuration", invalid);
        }
    }

    private static void flattenInto(Map<?, ?> fields, String prefix, Map<String, String> values) throws IOException {
        for (var field : fields.entrySet()) {
            if (!(field.getKey() instanceof String key) || key.isEmpty())
                throw new IOException("Expected string configuration keys");
            var path = prefix.isEmpty() ? key : prefix + "." + key;
            var value = field.getValue();
            if (value instanceof Map<?, ?> nested) {
                flattenInto(nested, path, values);
            } else {
                if (!(value instanceof String || value instanceof Integer || value instanceof Long
                    || value instanceof Boolean))
                    throw new IOException("Expected a scalar configuration value: " + path);
                if (values.putIfAbsent(path, value.toString()) != null)
                    throw new IOException("Duplicate configuration key: " + path);
            }
        }
    }

    private static String render(Map<String, String> settings, String header) {
        var out = new StringBuilder(header);
        var options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultScalarStyle(org.yaml.snakeyaml.DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        options.setSplitLines(false);
        var yaml = new Yaml(options);
        var root = new LinkedHashMap<String, Object>();
        for (var entry : settings.entrySet()) {
            putPath(root, entry.getKey().split("\\.", -1), 0, entry.getValue());
        }
        for (var entry : root.entrySet()) {
            out.append(yaml.dump(Map.of(entry.getKey(), entry.getValue()))).append('\n');
        }
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void putPath(Map<String, Object> root, String[] parts, int index, String value) {
        if (index == parts.length - 1) {
            root.put(parts[index], value);
            return;
        }
        var nested = (Map<String, Object>) root.computeIfAbsent(parts[index], ignored -> new LinkedHashMap<String, Object>());
        putPath(nested, parts, index + 1, value);
    }
}
