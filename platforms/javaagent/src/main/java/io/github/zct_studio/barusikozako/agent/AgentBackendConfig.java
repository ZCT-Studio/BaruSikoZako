// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class AgentBackendConfig {
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
        Map.entry("schema", "Configuration schema version"),
        Map.entry("level", "Zstd compression level, 1 to 9"),
        Map.entry("compressionThreads", "Compression threads, 1 to 4"),
        Map.entry("writeMode", "Backup mode: full, incremental, differential or fixed-chunks"),
        Map.entry("baseline", "Full baseline UUID for differential backups"),
        Map.entry("maxIncrementals", "Maximum consecutive incrementals, 0 disables this trigger"),
        Map.entry("fullThresholdPercent", "Cumulative incremental size percentage, 0 disables this trigger"),
        Map.entry("fullIntervalSeconds", "Maximum full baseline age in seconds, 0 disables this trigger"),
        Map.entry("gcIntervalSeconds", "Garbage collection interval in seconds, 0 disables it")
    );

    private AgentBackendConfig() {}

    static Map<String, String> load(String keyword, Map<String, String> defaults) throws IOException {
        if (!keyword.matches("[a-z][a-z0-9_-]{1,63}"))
            throw new IOException("Invalid backend keyword");
        Path file = Path.of(".").toRealPath().resolve("config/barusikozako").resolve(keyword + ".yml");
        var yaml = new Yaml();
        var initial = new StringBuilder();
        defaults.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var description = DESCRIPTIONS.get(entry.getKey());
            if (description != null) initial.append("# ").append(description).append('\n');
            initial.append(yaml.dumpAsMap(Map.of(entry.getKey(), entry.getValue())));
        });
        try {
            Files.writeString(file, initial, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException existing) {
            // 使用已有配置
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid backend configuration: " + file);
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(16 * 1024 + 1);
        }
        if (bytes.length > 16 * 1024) throw new IOException("Backend configuration exceeds 16 KiB");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(4);
        options.setCodePointLimit(16 * 1024);
        try {
            var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            Object document = new Yaml(new SafeConstructor(options)).load(text);
            if (!(document instanceof Map<?, ?> fields)) throw new IOException("Expected a backend YAML mapping");
            var values = new LinkedHashMap<>(defaults);
            for (var entry : fields.entrySet()) {
                if (!(entry.getKey() instanceof String key) ||
                    !(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean))
                    throw new IOException("Expected scalar backend configuration");
                if (!defaults.containsKey(key)) throw new IOException("Unknown backend configuration key: " + key);
                values.put(key, entry.getValue().toString());
            }
            return Map.copyOf(values);
        } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
            throw new IOException("Invalid backend YAML", invalid);
        }
    }
}
