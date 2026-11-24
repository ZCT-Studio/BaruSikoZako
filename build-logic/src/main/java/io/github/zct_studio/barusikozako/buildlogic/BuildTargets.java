// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * {@code targets.json5} 的构建目标清单模型与严格校验。
 *
 * <p>清单是源码层、资源层、Java 版本与 Mixin 配置的唯一事实来源。新增版本或兼容目录
 * 原则上只需要修改清单与对应源码。源码层路径相对于仓库的 {@code platforms/} 目录。
 */
public final class BuildTargets {
    /** Mixin 配置描述；{@code mixins} 为最终顺序。 */
    public record MixinConfig(boolean injectors, String plugin, boolean refmap, List<String> mixins) {}

    /** 单个构建目标。 */
    public record Target(String key, String game, String loader, int javaRelease,
                         List<String> sources, List<String> resources, MixinConfig mixin) {
        public ConditionalProcessor.Context conditionContext() {
            return new ConditionalProcessor.Context(game, loader, javaRelease);
        }
    }

    private static final Set<String> TOP_KEYS = Set.of("version", "layout", "layers", "targets", "mixinTemplate");
    private static final Set<String> TARGET_KEYS = Set.of("java", "sources", "resources", "mixin");
    private static final Set<String> MIXIN_KEYS = Set.of("injectors", "plugin", "refmap", "mixins");
    private static final Set<Integer> JAVA_RELEASES = Set.of(17, 21, 25);

    private final Path root;
    private final Map<String, String> layers;
    private final Map<String, Target> targets;
    private final boolean strict;
    private final String mixinTemplate;

    private BuildTargets(Path root, Map<String, String> layers, Map<String, Target> targets,
                         boolean strict, String mixinTemplate) {
        this.root = root;
        this.layers = layers;
        this.targets = targets;
        this.strict = strict;
        this.mixinTemplate = mixinTemplate;
    }

    public static BuildTargets load(Path root, Path compatibilityFile) {
        Path manifest = root.resolve("targets.json5");
        if (!Files.isRegularFile(manifest)) throw new IllegalStateException("Missing build target manifest: " + manifest);
        Map<String, Object> document;
        try {
            document = Json5.parseObject(Files.readString(manifest, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + manifest, e);
        }
        rejectUnknown(document, TOP_KEYS, "targets.json5");
        long version = number(document.get("version"), 1, "version");
        if (version != 1) throw new IllegalStateException("Unsupported targets.json5 version: " + version);

        Map<String, Object> layout = table(document.get("layout"));
        rejectUnknown(layout, Set.of("strict"), "layout");
        boolean strict = bool(layout.get("strict"), true, "layout.strict");

        Map<String, String> layers = new LinkedHashMap<>();
        Path platforms = root.resolve("platforms");
        for (Map.Entry<String, Object> entry : table(document.get("layers")).entrySet()) {
            if (!(entry.getValue() instanceof String path)) {
                throw new IllegalStateException("Layer '" + entry.getKey() + "' must be a platforms-relative path");
            }
            Path directory = platforms.resolve(path).normalize();
            if (!directory.startsWith(platforms)) throw new IllegalStateException("Layer '" + entry.getKey() + "' escapes platforms/");
            if (!Files.isDirectory(directory)) throw new IllegalStateException("Layer '" + entry.getKey() + "' does not exist: " + path);
            if (!Files.isDirectory(directory.resolve("java")) && !Files.isDirectory(directory.resolve("resources"))) {
                throw new IllegalStateException("Layer '" + entry.getKey() + "' has neither java/ nor resources/: " + path);
            }
            layers.put(entry.getKey(), path);
        }

        Properties compatibility = new Properties();
        try (Reader reader = Files.newBufferedReader(compatibilityFile, StandardCharsets.UTF_8)) {
            compatibility.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + compatibilityFile, e);
        }

        Map<String, Target> targets = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : table(document.get("targets")).entrySet()) {
            String key = entry.getKey();
            Map<String, Object> node = table(entry.getValue());
            rejectUnknown(node, TARGET_KEYS, "target " + key);
            int separator = key.indexOf('@');
            if (separator <= 0 || separator == key.length() - 1 || key.indexOf('@', separator + 1) >= 0) {
                throw new IllegalStateException("Target key '" + key + "' must be '<loader>@<minecraft>'");
            }
            String loader = key.substring(0, separator);
            String game = key.substring(separator + 1);
            int java = (int) number(node.get("java"), 25, key + ".java");
            if (!JAVA_RELEASES.contains(java)) {
                throw new IllegalStateException("Target '" + key + "' has unsupported Java release " + java);
            }
            List<String> sources = strings(node.get("sources"), key + ".sources");
            if (sources.isEmpty()) throw new IllegalStateException("Target '" + key + "' has no source layers");
            validateLayerReferences(key, "sources", sources, layers);
            List<String> resources = node.containsKey("resources")
                ? strings(node.get("resources"), key + ".resources") : List.of();
            validateLayerReferences(key, "resources", resources, layers);
            MixinConfig mixin = node.containsKey("mixin") ? mixin(key, table(node.get("mixin"))) : null;
            validateCompatibility(key, game, loader, compatibility);
            Target target = new Target(key, game, loader, java, List.copyOf(sources), List.copyOf(resources), mixin);
            if (targets.put(key, target) != null) throw new IllegalStateException("Duplicate target '" + key + "'");
        }
        if (targets.isEmpty()) throw new IllegalStateException("targets.json5 declares no build targets");

        String mixinTemplate = document.containsKey("mixinTemplate")
            ? string(document.get("mixinTemplate"), "mixinTemplate")
            : "platforms/minecraft/src/main/resources/barusikozako.mixins.json";
        return new BuildTargets(root, Map.copyOf(layers), Map.copyOf(targets), strict, mixinTemplate);
    }

    private static void validateCompatibility(String key, String game, String loader, Properties compatibility) {
        boolean supported = switch (loader) {
            case "fabric", "quilt" -> game.equals(compatibility.getProperty("fabric.development.minecraft"))
                || compatibility.containsKey("fabric.runtime.api." + game)
                || compatibility.containsKey("fabric.legacy.api." + game);
            case "forge", "neoforge" -> compatibility.containsKey("fml.runtime." + loader + "." + game);
            default -> false;
        };
        if (!supported) {
            throw new IllegalStateException("Target '" + key + "' has no reviewed " + loader + " build input for Minecraft " + game
                + " in gradle/compatibility-targets.properties");
        }
    }

    private static void validateLayerReferences(String key, String field, List<String> names, Map<String, String> layers) {
        Set<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (!layers.containsKey(name)) {
                throw new IllegalStateException("Target '" + key + "' references unknown layer '" + name + "' in " + field);
            }
            if (!seen.add(name)) {
                throw new IllegalStateException("Target '" + key + "' repeats layer '" + name + "' in " + field);
            }
        }
    }

    private static MixinConfig mixin(String key, Map<String, Object> node) {
        rejectUnknown(node, MIXIN_KEYS, "target " + key + " mixin");
        boolean injectors = bool(node.get("injectors"), false, key + ".mixin.injectors");
        boolean refmap = bool(node.get("refmap"), false, key + ".mixin.refmap");
        String plugin = node.containsKey("plugin") ? string(node.get("plugin"), key + ".mixin.plugin") : null;
        List<String> mixins = strings(node.get("mixins"), key + ".mixin.mixins");
        if (mixins.isEmpty()) throw new IllegalStateException("Target '" + key + "' declares an empty mixin list");
        return new MixinConfig(injectors, plugin, refmap, List.copyOf(mixins));
    }

    private static void rejectUnknown(Map<String, Object> node, Set<String> allowed, String where) {
        for (String key : node.keySet()) {
            if (!allowed.contains(key)) throw new IllegalStateException("Unknown key '" + key + "' in " + where);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException("Expected an object, got " + value);
        return (Map<String, Object>) map;
    }

    private static List<String> strings(Object value, String where) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new IllegalStateException(where + " must be an array");
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String text)) throw new IllegalStateException(where + " must contain strings");
            result.add(text);
        }
        return result;
    }

    private static String string(Object value, String where) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalStateException(where + " must be a non-empty string");
        return text;
    }

    private static boolean bool(Object value, boolean fallback, String where) {
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw new IllegalStateException(where + " must be a boolean");
        return flag;
    }

    private static long number(Object value, long fallback, String where) {
        if (value == null) return fallback;
        if (value instanceof Long number) return number;
        if (value instanceof Double number) return number.longValue();
        throw new IllegalStateException(where + " must be a number");
    }

    public Path root() {
        return root;
    }

    public boolean strict() {
        return strict;
    }

    public String mixinTemplate() {
        return mixinTemplate;
    }

    public Map<String, String> layers() {
        return layers;
    }

    public Map<String, Target> targets() {
        return targets;
    }

    public Target require(String loader, String game) {
        Target target = targets.get(loader + "@" + game);
        if (target == null) {
            throw new IllegalStateException("No build target '" + loader + "@" + game + "' in targets.json5");
        }
        return target;
    }

    public Path layerDirectory(String name) {
        String relative = layers.get(name);
        if (relative == null) throw new IllegalStateException("Unknown layer '" + name + "'");
        return root.resolve("platforms").resolve(relative).normalize();
    }

    /** Minecraft 版本对应的目录名，例如 26.3 -> mc26_3。 */
    public static String minecraftDirectory(String game) {
        return "mc" + game.replace('.', '_');
    }
}
