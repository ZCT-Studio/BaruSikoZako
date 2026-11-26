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
 * {@code ;lists/} 声明式构建清单的模型、规则解析与严格校验。
 *
 * <p>清单是源码层、资源层、Java 版本与 Mixin 配置的唯一事实来源：
 * <ul>
 *   <li>{@code ;lists/targets.json5}：目标注册（{@code jdk}）、规则文件列表 {@code rules}
 *       与可选的单目标覆盖 {@code targets}。</li>
 *   <li>{@code ;lists/sources.json5}、{@code ;lists/resources.json5}：可选源码层与资源层目录，
 *       每项声明 {@code platforms/} 相对路径；{@code ;lists/mixins.json5}：Mixin 模板与可选类名目录。</li>
 *   <li>{@code ;lists/;rules/*.json5}：命名分组与条件规则，按 {@code rules} 顺序应用。</li>
 * </ul>
 *
 * <p>源码层路径相对于仓库的 {@code platforms/} 目录；{@code mixinTemplate} 相对于仓库根目录。
 * 后声明的规则覆盖先声明的字段；集合默认替换，可用 {@code add}/{@code remove} 追加或移除。
 */
public final class BuildTargets {
    /** Mixin 配置描述；{@code mixins} 为最终顺序。 */
    public record MixinConfig(boolean injectors, String plugin, boolean refmap, List<String> mixins) {}

    /** 单个构建目标。 */
    public record Target(String key, String game, String loader, int javaRelease,
                         List<String> sources, List<String> resources, boolean merged, MixinConfig mixin) {
        public ConditionalProcessor.Context conditionContext() {
            return new ConditionalProcessor.Context(game, loader, javaRelease);
        }

        /**
         * Mixin 配置文件名。合并发布的目标按 Loader 区分，避免多个 Loader 的配置在合并 jar 中同名冲突；
         * 其余目标保持原有的单一文件名。
         */
        public String mixinConfigName() {
            return merged ? "barusikozako." + loader + ".mixins.json" : "barusikozako.mixins.json";
        }
    }

    private static final String LISTS = ";lists";
    private static final String RULES = ";rules";
    private static final Set<String> TOP_KEYS = Set.of("rules", "jdk", "targets", "merge");
    private static final Set<String> TARGET_KEYS = Set.of("source", "resource", "mixin");
    private static final Set<String> MIXIN_KEYS = Set.of("injectors", "plugin", "refmap", "mixins", "groups", "add", "remove");
    private static final Set<String> COLLECTION_KEYS = Set.of("groups", "add", "remove");
    private static final Set<String> RULE_FILE_KEYS = Set.of("groups", "rule");
    private static final Set<String> GROUP_KEYS = Set.of("source", "resource", "mixin");
    private static final List<String> GROUP_CATEGORIES = List.of("source", "resource", "mixin");
    private static final Set<Integer> JAVA_RELEASES = Set.of(17, 21, 25);

    private final Path root;
    private final Map<String, String> layers;
    private final Map<String, Target> targets;
    private final String mixinTemplate;

    private BuildTargets(Path root, Map<String, String> layers, Map<String, Target> targets, String mixinTemplate) {
        this.root = root;
        this.layers = layers;
        this.targets = targets;
        this.mixinTemplate = mixinTemplate;
    }

    public static BuildTargets load(Path root, Path compatibilityFile) {
        Path lists = root.resolve(LISTS);
        Map<String, Object> document = readObject(lists.resolve("targets.json5"), ";lists/targets.json5");
        rejectUnknown(document, TOP_KEYS, ";lists/targets.json5");

        LayerCatalog sourceLayers = layerCatalog(root, lists, "sources.json5");
        LayerCatalog resourceLayers = layerCatalog(root, lists, "resources.json5");
        Map<String, String> layers = mergeLayers(sourceLayers.layers(), resourceLayers.layers());
        MixinCatalog mixins = mixinCatalog(lists);

        Map<String, Integer> jdk = jdkTargets(document.get("jdk"));
        List<RuleFile> ruleFiles = ruleFiles(lists.resolve(RULES),
            strings(document.get("rules"), ";lists/targets.json5.rules"));
        Set<String> sourceCatalog = sourceLayers.names();
        Set<String> resourceCatalog = resourceLayers.names();
        Set<String> mixinCatalog = mixins.names();
        Set<String> mergeGames = uniqueNames(document.get("merge"), ";lists/targets.json5.merge");
        Map<String, Object> overrides = table(document.get("targets"));
        for (String key : overrides.keySet()) {
            if (!jdk.containsKey(key)) {
                throw new IllegalStateException("Target override '" + key + "' is not registered in ;lists/targets.json5 jdk");
            }
        }

        Properties compatibility = new Properties();
        try (Reader reader = Files.newBufferedReader(compatibilityFile, StandardCharsets.UTF_8)) {
            compatibility.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + compatibilityFile, e);
        }

        Map<String, Target> targets = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : jdk.entrySet()) {
            String key = entry.getKey();
            TargetId id = targetId(key);
            int java = entry.getValue();
            ConditionalProcessor.Context context = new ConditionalProcessor.Context(id.game, id.loader, java);
            MutableTarget state = new MutableTarget(new ArrayList<>(), new ArrayList<>(),
                new MutableMixin(false, false, null, new ArrayList<>()));
            Map<String, List<String>> sourceGroups = new LinkedHashMap<>();
            Map<String, List<String>> resourceGroups = new LinkedHashMap<>();
            Map<String, List<String>> mixinGroups = new LinkedHashMap<>();
            for (RuleFile file : ruleFiles) {
                sourceGroups.putAll(file.groups().get("source"));
                resourceGroups.putAll(file.groups().get("resource"));
                mixinGroups.putAll(file.groups().get("mixin"));
                for (RuleEntry rule : file.rules()) {
                    boolean matches;
                    try {
                        matches = rule.expression().evaluate(context);
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("Cannot evaluate rule condition '" + rule.condition()
                            + "' for target '" + key + "' in " + file.label() + ": " + e.getMessage(), e);
                    }
                    if (!matches) continue;
                    applyPatch(state, rule.patch(), "rule '" + rule.condition() + "' in " + file.label(), key,
                        sourceCatalog, resourceCatalog, mixinCatalog, sourceGroups, resourceGroups, mixinGroups);
                }
            }
            if (overrides.containsKey(key)) {
                applyPatch(state, table(overrides.get(key)), "target override", key,
                    sourceCatalog, resourceCatalog, mixinCatalog, sourceGroups, resourceGroups, mixinGroups);
            }

            if (state.sources.isEmpty()) throw new IllegalStateException("Target '" + key + "' resolved no source layers");
            validateLayerReferences(key, "sources", state.sources, layers);
            validateLayerReferences(key, "resources", state.resources, layers);
            if (state.mixin.mixins.isEmpty()) {
                throw new IllegalStateException("Target '" + key + "' resolved an empty mixin list");
            }
            validateCompatibility(key, id.game, id.loader, compatibility);
            Target target = new Target(key, id.game, id.loader, java,
                List.copyOf(state.sources), List.copyOf(state.resources), mergeGames.contains(id.game),
                state.mixin.freeze());
            if (targets.put(key, target) != null) throw new IllegalStateException("Duplicate target '" + key + "'");
        }
        if (targets.isEmpty()) throw new IllegalStateException(";lists/targets.json5 registers no build targets");

        return new BuildTargets(root, Map.copyOf(layers), Map.copyOf(targets), mixins.template());
    }

    private static TargetId targetId(String key) {
        int separator = key.indexOf('@');
        if (separator <= 0 || separator == key.length() - 1 || key.indexOf('@', separator + 1) >= 0) {
            throw new IllegalStateException("Target key '" + key + "' must be '<loader>@<minecraft>'");
        }
        return new TargetId(key.substring(0, separator), key.substring(separator + 1));
    }

    private static Map<String, Integer> jdkTargets(Object value) {
        Map<String, Object> node = table(value);
        if (node.isEmpty()) throw new IllegalStateException(";lists/targets.json5 must register targets under jdk");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            targetId(key);
            int java = (int) number(entry.getValue(), -1, "jdk." + key);
            if (!JAVA_RELEASES.contains(java)) {
                throw new IllegalStateException("Target '" + key + "' has unsupported Java release " + java);
            }
            result.put(key, java);
        }
        return result;
    }

    /** 读取层目录：名称映射到 {@code platforms/} 下的相对路径。 */
    private static LayerCatalog layerCatalog(Path root, Path lists, String fileName) {
        String label = ";lists/" + fileName;
        Map<String, Object> document = readObject(lists.resolve(fileName), label);
        rejectUnknown(document, Set.of("layers"), label);

        Map<String, String> result = new LinkedHashMap<>();
        Path platforms = root.resolve("platforms");
        for (Map.Entry<String, Object> entry : table(document.get("layers")).entrySet()) {
            if (!(entry.getValue() instanceof String path)) {
                throw new IllegalStateException("Layer '" + entry.getKey() + "' must be a platforms-relative path in " + label);
            }
            Path directory = platforms.resolve(path).normalize();
            if (!directory.startsWith(platforms)) {
                throw new IllegalStateException("Layer '" + entry.getKey() + "' escapes platforms/ in " + label);
            }
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException("Layer '" + entry.getKey() + "' does not exist: " + path + " in " + label);
            }
            if (!Files.isDirectory(directory.resolve("java")) && !Files.isDirectory(directory.resolve("resources"))) {
                throw new IllegalStateException("Layer '" + entry.getKey() + "' has neither java/ nor resources/: " + path + " in " + label);
            }
            result.put(entry.getKey(), path);
        }
        return new LayerCatalog(Set.copyOf(result.keySet()), Map.copyOf(result));
    }

    private static Map<String, String> mergeLayers(Map<String, String> sources, Map<String, String> resources) {
        Map<String, String> result = new LinkedHashMap<>(sources);
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            String previous = result.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) {
                throw new IllegalStateException("Layer '" + entry.getKey()
                    + "' has different paths in ;lists/sources.json5 and ;lists/resources.json5");
            }
        }
        return result;
    }

    private static MixinCatalog mixinCatalog(Path lists) {
        Map<String, Object> document = readObject(lists.resolve("mixins.json5"), ";lists/mixins.json5");
        rejectUnknown(document, Set.of("template", "mixins"), ";lists/mixins.json5");
        String template = string(document.get("template"), ";lists/mixins.json5.template");
        return new MixinCatalog(template,
            uniqueNames(document.get("mixins"), ";lists/mixins.json5.mixins"));
    }

    private static Set<String> uniqueNames(Object value, String where) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : strings(value, where)) {
            if (!result.add(name)) throw new IllegalStateException("Duplicate '" + name + "' in " + where);
        }
        return result;
    }

    private static List<RuleFile> ruleFiles(Path directory, List<String> entries) {
        List<RuleFile> result = new ArrayList<>();
        for (String entry : entries) {
            Path file = directory.resolve(entry).normalize();
            if (!file.startsWith(directory)) {
                throw new IllegalStateException("Rule file '" + entry + "' escapes ;lists/;rules/");
            }
            result.add(ruleFile(file, entry));
        }
        return result;
    }

    private static RuleFile ruleFile(Path file, String entry) {
        String label = ";lists/" + RULES + "/" + entry;
        Map<String, Object> document = readObject(file, label);
        rejectUnknown(document, RULE_FILE_KEYS, label);
        Map<String, Object> groupTable = table(document.get("groups"));
        rejectUnknown(groupTable, GROUP_KEYS, label + " groups");
        Map<String, Map<String, List<String>>> groups = new LinkedHashMap<>();
        for (String category : GROUP_CATEGORIES) {
            Map<String, List<String>> names = new LinkedHashMap<>();
            for (Map.Entry<String, Object> group : table(groupTable.get(category)).entrySet()) {
                names.put(group.getKey(), strings(group.getValue(), label + " group '" + group.getKey() + "'"));
            }
            groups.put(category, names);
        }
        List<RuleEntry> rules = new ArrayList<>();
        for (Map.Entry<String, Object> entryNode : table(document.get("rule")).entrySet()) {
            String condition = entryNode.getKey();
            ConditionalProcessor.Expression expression;
            try {
                expression = ConditionalProcessor.Expression.parse(condition);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Invalid rule condition '" + condition + "' in " + label
                    + ": " + e.getMessage(), e);
            }
            rules.add(new RuleEntry(condition, expression, table(entryNode.getValue())));
        }
        return new RuleFile(label, Map.copyOf(groups), List.copyOf(rules));
    }

    private static void applyPatch(MutableTarget state, Map<String, Object> patch, String origin, String target,
                                   Set<String> sourceCatalog, Set<String> resourceCatalog, Set<String> mixinCatalog,
                                   Map<String, List<String>> sourceGroups,
                                   Map<String, List<String>> resourceGroups,
                                   Map<String, List<String>> mixinGroups) {
        rejectUnknown(patch, TARGET_KEYS, origin + " for target '" + target + "'");
        if (patch.containsKey("source")) {
            state.sources = collection("source", patch.get("source"), state.sources,
                sourceCatalog, sourceCatalog, sourceGroups, origin, target);
        }
        if (patch.containsKey("resource")) {
            state.resources = collection("resource", patch.get("resource"), state.resources,
                resourceCatalog, resourceCatalog, resourceGroups, origin, target);
        }
        if (patch.containsKey("mixin")) {
            mixin(state.mixin, table(patch.get("mixin")), origin, target, mixinCatalog, mixinGroups);
        }
    }

    private static List<String> collection(String category, Object value, List<String> base,
                                           Set<String> known, Set<String> singles,
                                           Map<String, List<String>> groups, String origin, String target) {
        String where = origin + " " + category + " for target '" + target + "'";
        if (value instanceof List<?> list) {
            return expand(strings(list, where), groups, singles, known, category, origin, target);
        }
        Map<String, Object> node = table(value);
        rejectUnknown(node, COLLECTION_KEYS, where);
        if (!node.containsKey("groups") && !node.containsKey("add") && !node.containsKey("remove")) {
            throw new IllegalStateException(where + " must declare groups, add or remove");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (node.containsKey("groups")) {
            result.addAll(expand(strings(node.get("groups"), where + ".groups"),
                groups, singles, known, category, origin, target));
        } else {
            result.addAll(base);
        }
        if (node.containsKey("add")) {
            result.addAll(expand(strings(node.get("add"), where + ".add"),
                groups, singles, known, category, origin, target));
        }
        if (node.containsKey("remove")) {
            result.removeAll(expand(strings(node.get("remove"), where + ".remove"),
                groups, singles, known, category, origin, target));
        }
        return List.copyOf(result);
    }

    private static void mixin(MutableMixin mixin, Map<String, Object> node, String origin, String target,
                              Set<String> classNames, Map<String, List<String>> groups) {
        String where = origin + " mixin for target '" + target + "'";
        rejectUnknown(node, MIXIN_KEYS, where);
        if (node.containsKey("injectors")) mixin.injectors = bool(node.get("injectors"), false, where + ".injectors");
        if (node.containsKey("refmap")) mixin.refmap = bool(node.get("refmap"), false, where + ".refmap");
        if (node.containsKey("plugin")) mixin.plugin = nullableString(node.get("plugin"), where + ".plugin");

        boolean groupSelection = node.containsKey("groups");
        boolean directSelection = node.containsKey("mixins");
        if (groupSelection && directSelection) {
            throw new IllegalStateException(where + " cannot declare both groups and mixins");
        }
        List<String> result;
        if (groupSelection) {
            result = expand(strings(node.get("groups"), where + ".groups"),
                groups, classNames, classNames, "mixin", origin, target);
        } else if (directSelection) {
            result = expand(strings(node.get("mixins"), where + ".mixins"),
                groups, classNames, classNames, "mixin", origin, target);
        } else {
            result = mixin.mixins;
        }
        if (node.containsKey("add")) {
            LinkedHashSet<String> combined = new LinkedHashSet<>(result);
            combined.addAll(expand(strings(node.get("add"), where + ".add"),
                groups, classNames, classNames, "mixin", origin, target));
            result = List.copyOf(combined);
        }
        if (node.containsKey("remove")) {
            LinkedHashSet<String> combined = new LinkedHashSet<>(result);
            combined.removeAll(expand(strings(node.get("remove"), where + ".remove"),
                groups, classNames, classNames, "mixin", origin, target));
            result = List.copyOf(combined);
        }
        mixin.mixins = result;
    }

    private static List<String> expand(List<String> names, Map<String, List<String>> groups, Set<String> singles,
                                       Set<String> known, String category, String origin, String target) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        expandInto(names, groups, singles, known, category, origin, target, result, new LinkedHashSet<>());
        return List.copyOf(result);
    }

    private static void expandInto(List<String> names, Map<String, List<String>> groups, Set<String> singles,
                                   Set<String> known, String category, String origin, String target,
                                   LinkedHashSet<String> result, Set<String> visiting) {
        for (String name : names) {
            if (singles.contains(name)) {
                result.add(name);
                continue;
            }
            List<String> group = groups.get(name);
            if (group != null) {
                if (!visiting.add(name)) {
                    throw new IllegalStateException("Circular " + category + " group '" + name + "' in "
                        + origin + " for target '" + target + "'");
                }
                expandInto(group, groups, singles, known, category, origin, target, result, visiting);
                visiting.remove(name);
                continue;
            }
            if (!known.contains(name)) {
                throw new IllegalStateException("Unknown " + category + " group or item '" + name + "' in "
                    + origin + " for target '" + target + "'");
            }
            result.add(name);
        }
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

    private static void rejectUnknown(Map<String, Object> node, Set<String> allowed, String where) {
        for (String key : node.keySet()) {
            if (!allowed.contains(key)) throw new IllegalStateException("Unknown key '" + key + "' in " + where);
        }
    }

    private static Map<String, Object> readObject(Path file, String label) {
        if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing " + label + ": " + file);
        try {
            return Json5.parseObject(Files.readString(file, StandardCharsets.UTF_8), true);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + label + ": " + file, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid JSON5 in " + label + ": " + file + ": " + e.getMessage(), e);
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
            if (!(element instanceof String text) || text.isBlank()) {
                throw new IllegalStateException(where + " must contain non-empty strings");
            }
            result.add(text);
        }
        return result;
    }

    private static String string(Object value, String where) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalStateException(where + " must be a non-empty string");
        return text;
    }

    private static String nullableString(Object value, String where) {
        return value == null ? null : string(value, where);
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
            throw new IllegalStateException("No build target '" + loader + "@" + game + "' in ;lists/targets.json5");
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

    private record TargetId(String loader, String game) {}

    private record LayerCatalog(Set<String> names, Map<String, String> layers) {}

    private record MixinCatalog(String template, Set<String> names) {}

    private record RuleEntry(String condition, ConditionalProcessor.Expression expression, Map<String, Object> patch) {}

    private record RuleFile(String label, Map<String, Map<String, List<String>>> groups, List<RuleEntry> rules) {}

    private static final class MutableTarget {
        private List<String> sources;
        private List<String> resources;
        private final MutableMixin mixin;

        private MutableTarget(List<String> sources, List<String> resources, MutableMixin mixin) {
            this.sources = sources;
            this.resources = resources;
            this.mixin = mixin;
        }
    }

    private static final class MutableMixin {
        private boolean injectors;
        private boolean refmap;
        private String plugin;
        private List<String> mixins;

        private MutableMixin(boolean injectors, boolean refmap, String plugin, List<String> mixins) {
            this.injectors = injectors;
            this.refmap = refmap;
            this.plugin = plugin;
            this.mixins = mixins;
        }

        private MixinConfig freeze() {
            return new MixinConfig(injectors, plugin, refmap, List.copyOf(mixins));
        }
    }
}
