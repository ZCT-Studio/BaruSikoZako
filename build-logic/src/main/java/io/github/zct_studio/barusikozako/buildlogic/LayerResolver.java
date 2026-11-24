// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 按清单顺序解析目标的有效源码层。
 *
 * <p>后声明的层优先。{@code strict} 模式下出现相同相对路径即报错：IDEA 的模块级排除
 * 只匹配文件名、不匹配路径，无法表达单文件覆盖，因此本仓库要求各目标的有效源码层
 * 互不相交，从结构上避免重复类。非严格模式允许覆盖，并从低优先级层排除被覆盖文件，
 * 同时标记该布局不适合 IDE。
 */
public final class LayerResolver {
    /** 单个源码层。 */
    public record LayerDir(String name, Path directory, Path java, Path resources,
                           List<String> javaExcludes, List<String> resourceExcludes) {}

    /** 解析结果。 */
    public record Resolved(BuildTargets.Target target, List<LayerDir> layers,
                           Map<String, Path> javaFiles, Map<String, Path> resourceFiles,
                           List<String> warnings, boolean ideSafe) {}

    private LayerResolver() {}

    public static Resolved resolve(BuildTargets targets, BuildTargets.Target target) {
        List<Map<String, Path>> perLayerJava = new ArrayList<>();
        List<Map<String, Path>> perLayerResources = new ArrayList<>();
        for (String name : target.sources()) {
            Path directory = targets.layerDirectory(name);
            perLayerJava.add(files(directory.resolve("java")));
            perLayerResources.add(files(directory.resolve("resources")));
        }

        List<String> warnings = new ArrayList<>();
        Map<String, Path> javaFiles = effective(perLayerJava, target.sources(), "source", targets.strict(), warnings);
        Map<String, Path> resourceFiles = effective(perLayerResources, target.sources(), "resource", targets.strict(), warnings);

        List<LayerDir> layers = new ArrayList<>();
        for (int i = 0; i < target.sources().size(); i++) {
            String name = target.sources().get(i);
            Path directory = targets.layerDirectory(name);
            layers.add(new LayerDir(name, directory, directory.resolve("java"), directory.resolve("resources"),
                shadowed(perLayerJava, i), shadowed(perLayerResources, i)));
        }
        return new Resolved(target, List.copyOf(layers), Map.copyOf(javaFiles), Map.copyOf(resourceFiles),
            List.copyOf(warnings), warnings.isEmpty());
    }

    private static Map<String, Path> effective(List<Map<String, Path>> perLayer, List<String> names, String kind,
                                               boolean strict, List<String> warnings) {
        Map<String, Path> result = new TreeMap<>();
        Map<String, String> owner = new LinkedHashMap<>();
        for (int i = 0; i < perLayer.size(); i++) {
            String layer = names.get(i);
            for (Map.Entry<String, Path> entry : perLayer.get(i).entrySet()) {
                String previous = owner.put(entry.getKey(), layer);
                if (previous != null) {
                    String message = "Layer '" + layer + "' overrides " + kind + " '" + entry.getKey() + "' from layer '" + previous + "'";
                    if (strict && kind.equals("source")) {
                        throw new IllegalStateException(message + "。IDEA 无法表达单文件覆盖；请把被覆盖文件移入独占层，或用条件编译合并差异。");
                    }
                    warnings.add(message);
                }
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** 第 index 层中会被后续层覆盖的文件。 */
    private static List<String> shadowed(List<Map<String, Path>> perLayer, int index) {
        if (index + 1 >= perLayer.size()) return List.of();
        List<String> result = new ArrayList<>();
        for (String relative : perLayer.get(index).keySet()) {
            for (int later = index + 1; later < perLayer.size(); later++) {
                if (perLayer.get(later).containsKey(relative)) {
                    result.add(relative);
                    break;
                }
            }
        }
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private static Map<String, Path> files(Path directory) {
        Map<String, Path> result = new TreeMap<>();
        if (!Files.isDirectory(directory)) return result;
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile).forEach(file ->
                result.put(directory.relativize(file).toString().replace('\\', '/'), file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }
}
