// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code .lists/} 清单、分组、条件规则与异常处理的自动化测试。 */
final class BuildTargetsTest {
    @TempDir
    Path root;

    @Test
    void defaultsGroupsAndOrderedRulesResolve() throws IOException {
        writeWorkspace();
        BuildTargets targets = load();

        BuildTargets.Target legacy = targets.require("fabric", "1.20.1");
        assertEquals(List.of("common", "legacy", "extra"), legacy.sources());
        assertEquals(List.of("common"), legacy.resources());
        assertEquals(List.of("B"), legacy.mixin().mixins());
        assertTrue(legacy.mixin().injectors());

        BuildTargets.Target modern = targets.require("fabric", "26.3");
        assertEquals(List.of("common", "extra"), modern.sources());
        assertEquals(List.of("A", "C"), modern.mixin().mixins());
        assertFalse(modern.mixin().injectors());
    }

    @Test
    void laterRuleFilesOverrideGroupsFieldsAndBooleans() throws IOException {
        writeWorkspace();
        BuildTargets.Target target = load().require("forge", "1.20.1");
        assertEquals(List.of("common", "legacy", "extra"), target.sources());
        assertEquals(List.of("B"), target.mixin().mixins());
        assertFalse(target.mixin().injectors());
        assertTrue(target.mixin().refmap());
        assertEquals("pkg.Plugin", target.mixin().plugin());
    }

    @Test
    void targetOverrideHasHighestPriority() throws IOException {
        writeWorkspace();
        BuildTargets.Target target = load().require("fabric", "26.3");
        assertEquals(List.of("common", "extra"), target.sources());
        assertFalse(target.mixin().injectors());
        assertNull(target.mixin().plugin());
    }

    @Test
    void layerOutsideCatalogCannotBeSelected() throws IOException {
        writeWorkspace();
        write(".lists/sources.json5", """
            { layers: { common: "common", legacy: "legacy" } }
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::load);
        assertTrue(error.getMessage().contains("Unknown source group or item 'extra'"), error.getMessage());
    }

    @Test
    void layerPathsMustMatchBetweenCatalogs() throws IOException {
        writeWorkspace();
        write(".lists/resources.json5", """
            { layers: { common: "legacy" } }
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::load);
        assertTrue(error.getMessage().contains("has different paths"), error.getMessage());
    }

    @Test
    void catalogsDoNotProvideDefaults() throws IOException {
        writeWorkspace();
        write(".lists/.rules/first.json5", "{ rule: {} }");
        write(".lists/.rules/second.json5", "{ rule: {} }");
        IllegalStateException error = assertThrows(IllegalStateException.class, this::load);
        assertTrue(error.getMessage().contains("resolved no source layers"), error.getMessage());
    }

    @Test
    void unknownGroupOrLayerIsReported() throws IOException {
        writeWorkspace();
        write(".lists/.rules/first.json5", """
            {
              rule: {
                "mc < 26": { source: ["Missing"] },
              },
            }
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::load);
        assertTrue(error.getMessage().contains("Unknown source group or item 'Missing'"), error.getMessage());
    }

    @Test
    void invalidExpressionIsReported() throws IOException {
        writeWorkspace();
        write(".lists/.rules/first.json5", """
            {
              rule: {
                "mc >=": { source: ["common"] },
              },
            }
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::load);
        assertTrue(error.getMessage().contains("Invalid rule condition 'mc >='"), error.getMessage());
    }

    @Test
    void missingRuleFileIsReported() throws IOException {
        writeWorkspace();
        write(".lists/targets.json5", """
            {
              rules: ["absent.json5"],
              jdk: { "fabric@26.3": 25 },
            }
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::load);
        assertTrue(error.getMessage().contains("Missing .lists/.rules/absent.json5"), error.getMessage());
    }

    @Test
    void duplicateRuleConditionIsReported() throws IOException {
        writeWorkspace();
        write(".lists/.rules/first.json5", """
            {
              rule: {
                "mc < 26": { source: ["common"] },
                "mc < 26": { mixin: { injectors: true } },
              },
            }
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::load);
        assertTrue(error.getMessage().contains("Duplicate key 'mc < 26'"), error.getMessage());
    }

    @Test
    void unknownPatchFieldIsReported() throws IOException {
        writeWorkspace();
        write(".lists/.rules/first.json5", """
            {
              rule: {
                "mc < 26": { sources: ["common"] },
              },
            }
            """);
        IllegalStateException error = assertThrows(IllegalStateException.class, this::load);
        assertTrue(error.getMessage().contains("Unknown key 'sources'"), error.getMessage());
    }

    @Test
    void realManifestResolvesAllTargets() {
        Path repository = repositoryRoot();
        BuildTargets targets = BuildTargets.load(repository,
            repository.resolve("gradle/compatibility-targets.properties"));
        assertEquals(20, targets.targets().size());

        BuildTargets.Target modern = targets.require("fabric", "26.3");
        assertEquals(List.of("main", "modernJava", "fabric"), modern.sources());
        assertEquals(List.of("main", "fabric"), modern.resources());
        assertEquals(13, modern.mixin().mixins().size());
        assertFalse(modern.mixin().injectors());

        BuildTargets.Target forgeLegacy = targets.require("forge", "1.20.1");
        assertEquals(List.of("main", "legacyJava", "forgeLegacy", "forgeClassicBus", "forgeSrg"),
            forgeLegacy.sources());
        assertEquals(17, forgeLegacy.mixin().mixins().size());
        assertTrue(forgeLegacy.mixin().injectors());
        assertTrue(forgeLegacy.mixin().refmap());

        BuildTargets.Target neoforgeLegacy = targets.require("neoforge", "1.21.1");
        assertEquals(List.of("main", "legacyJava", "neoforge", "officialLegacy", "neoforge1211"),
            neoforgeLegacy.sources());
        assertEquals("io.github.zct_studio.barusikozako.fabric.NeoForgeSavedDataMixins",
            neoforgeLegacy.mixin().plugin());

        for (BuildTargets.Target target : targets.targets().values()) {
            assertTrue(target.javaRelease() == 17 || target.javaRelease() == 21 || target.javaRelease() == 25,
                target.key());
            assertFalse(target.mixin().mixins().isEmpty(), target.key());
        }
    }

    private BuildTargets load() {
        return BuildTargets.load(root, root.resolve("gradle/compatibility-targets.properties"));
    }

    private void writeWorkspace() throws IOException {
        write("gradle/compatibility-targets.properties", """
            fabric.runtime.api.1.20.1=0.15.0
            fabric.runtime.api.26.3=0.17.0
            fml.runtime.forge.1.20.1=47.2.0
            """);
        write(".lists/targets.json5", """
            {
              rules: ["first.json5", "second.json5"],
              jdk: {
                "fabric@1.20.1": 17,
                "fabric@26.3": 25,
                "forge@1.20.1": 17,
              },
              targets: {
                "fabric@26.3": { source: { add: ["extra"] } },
              },
            }
            """);
        write(".lists/sources.json5", """
            {
              layers: {
                common: "common",
                legacy: "legacy",
                extra: "extra",
              },
            }
            """);
        write(".lists/resources.json5", """
            { layers: { common: "common" } }
            """);
        write(".lists/mixins.json5", """
            { template: "mixin.json5", mixins: ["A", "B", "C", "D"] }
            """);
        write(".lists/.rules/first.json5", """
            {
              groups: {
                source: { Legacy: ["common", "legacy"] },
                mixin: { Base: ["A", "B"] },
              },
              rule: {
                "mc < 26": {
                  source: ["Legacy"],
                  resource: ["common"],
                  mixin: { groups: ["Base"], injectors: true },
                },
                "mc >= 26": {
                  source: ["common"],
                  resource: ["common"],
                  mixin: { mixins: ["A", "C"] },
                },
              },
            }
            """);
        write(".lists/.rules/second.json5", """
            {
              groups: {
                source: { Legacy: ["common", "legacy", "extra"] },
                mixin: { Base: ["A", "D"] },
              },
              rule: {
                "loader == 'forge'": {
                  mixin: { refmap: true, plugin: "pkg.Plugin" },
                },
                "mc < 26": {
                  source: ["Legacy"],
                  mixin: { remove: ["A"] },
                },
                "loader == 'forge' && mc == 1.20.1": {
                  mixin: { injectors: false },
                },
              },
            }
            """);
        for (String layer : List.of("common", "legacy", "extra")) {
            Files.createDirectories(root.resolve("platforms").resolve(layer).resolve("java"));
            write("platforms/" + layer + "/java/keep.txt", layer);
        }
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".lists/targets.json5"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
    }
}
