// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class AgentConfig {
    private static final Map<String, String> OPTIONS = Map.of(
        "backend", "barusikozako.agent.backend",
        "onlineBackup", "barusikozako.agent.onlineBackup",
        "language", "barusikozako.language",
        "api.readers", "barusikozako.agent.api.readers",
        "api.operators", "barusikozako.agent.api.operators",
        "reportSaveObservations", "barusikozako.agent.reportSaveObservations",
        "observeMoonrise", "barusikozako.agent.observeMoonrise"
    );

    private AgentConfig() {}

    static void load(Path home) throws IOException {
        Path path = home.resolve("barusikozako.yml");
        try {
            Files.writeString(path, """
                # BaruSikoZako JavaAgent configuration / JavaAgent 配置
                # Restart the server after making changes / 修改后重启服务器
                # Backup backend keyword / 备份后端标识
                backend: barusikozako_backendzstd
                # Request online capture when supported / 平台支持时启用在线捕获
                onlineBackup: true
                # Console language / 控制台语言
                language: en_us
                # Report save observations / 输出保存观察结果
                reportSaveObservations: false
                # Enable Moonrise observation probes / 启用 Moonrise 观察探针
                observeMoonrise: false
                """, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.err.println("已创建 " + path + "，请按需填写并重启");
            System.err.println("启动方式：java -javaagent:<Agent.jar> -jar <Agent.jar> start <服务端.jar>");
        } catch (java.nio.file.FileAlreadyExistsException existing) {
            // 使用已有配置
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid Agent configuration: " + path);
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(16 * 1024 + 1);
        }
        if (bytes.length > 16 * 1024) throw new IOException("Agent configuration exceeds 16 KiB");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(4);
        options.setCodePointLimit(16 * 1024);
        Object document;
        try {
            var text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            document = new Yaml(new SafeConstructor(options)).load(text);
        } catch (org.yaml.snakeyaml.error.YAMLException invalid) {
            throw new IOException("Invalid Agent YAML", invalid);
        }
        if (!(document instanceof Map<?, ?> values)) throw new IOException("Expected an Agent YAML mapping");
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) ||
                !(entry.getValue() instanceof String || entry.getValue() instanceof Boolean))
                throw new IOException("Expected string keys and string or boolean values");
            String property = OPTIONS.get(key);
            if (property == null) throw new IOException("Unknown Agent configuration key: " + key);
            if (System.getProperty(property) == null) System.setProperty(property, entry.getValue().toString().strip());
        }
    }
}
