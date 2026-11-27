// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 独立验证 api.yml 的创建、解析与从 server.yml 的迁移，不依赖游戏或加载器。
 *
 * <pre>
 * javac -encoding UTF-8 -d /tmp/config-check -cp &lt;编译产物&gt; \
 *   platforms/minecraft/tools/ConfigMigrationCheck.java
 * java -cp /tmp/config-check:&lt;编译产物&gt; io.github.zct_studio.barusikozako.ConfigMigrationCheck
 * </pre>
 */
public final class ConfigMigrationCheck {
    private static int failures;
    private static volatile Path configDirectory;

    public static void main(String[] args) throws Exception {
        LoaderBridge.install(new LoaderBridge.Binding() {
            @Override public Path configDirectory() { return configDirectory; }
            @Override public String productVersion() { return "test"; }
            @Override public boolean apiAvailable() { return true; }
            @Override public <T> List<LoaderBridge.Entry<T>> entries(String channel, Class<T> contract) {
                return List.of();
            }
        });

        legacyServerConfigIsMigrated();
        freshInstallKeepsAuthorizationDisabled();
        unknownKeyIsRejected();
        invalidEnabledValueIsRejected();

        if (failures > 0) {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("全部检查通过");
    }

    private static void legacyServerConfigIsMigrated() throws Exception {
        var root = Files.createTempDirectory("cfg-legacy");
        configDirectory = root;
        var directory = root.resolve("barusikozako");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("server.yml"), """
            schema: "1"
            backend: "barusikozako_backendzstd"
            api:
              integrations: "zakobakui"
              maintenanceIntegrations: "zakobakui"
            """, StandardCharsets.UTF_8);

        var config = ServerConfig.load(true);
        check("旧 server.yml 仍可解析", config != null);
        var apiFile = directory.resolve("api.yml");
        check("迁移后生成 api.yml", Files.isRegularFile(apiFile));
        var text = Files.readString(apiFile, StandardCharsets.UTF_8);
        check("迁移后开启授权", text.contains("enabled: \"true\""));
        check("迁移保留 integrations", text.contains("integrations: \"zakobakui\""));

        var api = ApiConfig.load(false);
        check("授权开启", api.authorization());
        check("名单包含 zakobakui", api.integrations().contains("zakobakui"));
        check("维护名单包含 zakobakui", api.maintenanceIntegrations().contains("zakobakui"));
    }

    private static void freshInstallKeepsAuthorizationDisabled() throws Exception {
        var root = Files.createTempDirectory("cfg-fresh");
        configDirectory = root;
        var directory = root.resolve("barusikozako");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("server.yml"),
            "schema: \"1\"\nbackend: \"barusikozako_backendzstd\"\n", StandardCharsets.UTF_8);

        ServerConfig.load(true);
        var api = ApiConfig.load(true);
        check("全新安装默认关闭授权", !api.authorization());
        check("全新安装名单为空", api.integrations().isEmpty() && api.maintenanceIntegrations().isEmpty());
        var text = Files.readString(directory.resolve("api.yml"), StandardCharsets.UTF_8);
        check("api.yml 带 schema", text.contains("schema: \"1\""));
        check("api.yml 带双语注释", text.contains("高级功能") && text.contains("advanced"));
    }

    private static void unknownKeyIsRejected() throws Exception {
        var root = Files.createTempDirectory("cfg-unknown");
        configDirectory = root;
        var directory = Files.createDirectories(root.resolve("barusikozako"));
        Files.writeString(directory.resolve("api.yml"), """
            schema: "1"
            authorization:
              enabled: "false"
              mystery: "x"
            """, StandardCharsets.UTF_8);
        boolean rejected = false;
        try {
            ApiConfig.load(false);
        } catch (IOException expected) {
            rejected = true;
        }
        check("未知键被拒绝", rejected);
    }

    private static void invalidEnabledValueIsRejected() throws Exception {
        var root = Files.createTempDirectory("cfg-invalid");
        configDirectory = root;
        var directory = Files.createDirectories(root.resolve("barusikozako"));
        Files.writeString(directory.resolve("api.yml"), """
            schema: "1"
            authorization:
              enabled: "maybe"
            """, StandardCharsets.UTF_8);
        boolean rejected = false;
        try {
            ApiConfig.load(false);
        } catch (IOException expected) {
            rejected = true;
        }
        check("非法开关值被拒绝", rejected);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}
