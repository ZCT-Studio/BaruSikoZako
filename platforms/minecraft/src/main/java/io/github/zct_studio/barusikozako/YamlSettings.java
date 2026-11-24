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
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class YamlSettings {
    static final String SCHEMA = "schema";
    private static final int MAX_BYTES = 16 * 1024;

    interface Migration { Map<String, String> read() throws IOException; }

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
                Files.writeString(file, render(settings), StandardOpenOption.CREATE_NEW);
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
        var encoded = render(settings).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IOException("Configuration exceeds 16 KiB");
        var staged = Files.createTempFile(file.getParent(), ".config-", ".yml");
        try {
            Files.write(staged, encoded);
            Files.move(staged, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    interface Validator { void validate(Map<String, String> values) throws IOException; }

    private static String render(Map<String, String> settings) {
        var descriptions = Map.ofEntries(
            Map.entry("schema", "Configuration schema version"),
            Map.entry("backend", "Backup backend keyword"),
            Map.entry("schedule.intervalSeconds", "Scheduled backup interval in seconds, 0 disables scheduling"),
            Map.entry("capture.enabled", "Enable online capture when supported"),
            Map.entry("capture.preparationMillis", "Capture preparation timeout in milliseconds"),
            Map.entry("capture.mainThreadBudgetMillis", "Main-thread preparation limit in milliseconds, 0 disables the limit"),
            Map.entry("capture.fileRetries", "Additional retries when a file changes"),
            Map.entry("capture.fileRetryDelayMillis", "Linear file retry delay in milliseconds"),
            Map.entry("capture.fileClone", "File clone strategy, auto or off"),
            Map.entry("backup.readBytesPerSecond", "Read rate in bytes per second, 0 disables the limit"),
            Map.entry("verify.contentBytes", "Maximum content bytes for verification"),
            Map.entry("verify.archiveBytes", "Maximum archive bytes for verification"),
            Map.entry("verify.entries", "Maximum entries for verification"),
            Map.entry("query.manifestBytes", "Maximum manifest bytes for queries"),
            Map.entry("level", "Zstd compression level, 1 to 9"),
            Map.entry("compressionThreads", "Compression threads, 1 to 4"),
            Map.entry("writeMode", "Backup mode: full, incremental, differential or fixed-chunks"),
            Map.entry("baseline", "Full baseline UUID for differential backups"),
            Map.entry("maxIncrementals", "Maximum consecutive incrementals, 0 disables this trigger"),
            Map.entry("fullThresholdPercent", "Cumulative incremental size percentage, 0 disables this trigger"),
            Map.entry("fullIntervalSeconds", "Maximum full baseline age in seconds, 0 disables this trigger"),
            Map.entry("gcIntervalSeconds", "Garbage collection interval in seconds, 0 disables it")
        );
        var output = new StringBuilder();
        var yaml = new Yaml();
        settings.forEach((key, value) -> {
            var description = descriptions.get(key);
            if (description != null) output.append("# ").append(description).append('\n');
            output.append(yaml.dumpAsMap(Map.of(key, value)));
        });
        return output.toString();
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
        options.setNestingDepthLimit(4);
        options.setCodePointLimit(MAX_BYTES);
        try {
            Object loaded = new Yaml(new SafeConstructor(options)).load(text);
            if (!(loaded instanceof Map<?, ?> fields)) throw new IOException("Expected a YAML configuration mapping");
            var values = new LinkedHashMap<String, String>();
            for (var field : fields.entrySet()) {
                var value = field.getValue();
                if (!(field.getKey() instanceof String key) ||
                    !(value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Boolean))
                    throw new IOException("Expected string keys and scalar YAML configuration values");
                values.put(key, value.toString());
            }
            return values;
        } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
            throw new IOException("Invalid YAML configuration", invalid);
        }
    }
}
