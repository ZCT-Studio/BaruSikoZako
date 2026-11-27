// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * 让模组 jar 支持双击 / 命令行直接运行。
 *
 * <p>模组 jar 的 MANIFEST 声明本类为 {@code Main-Class}。双击（或 {@code java -jar 模组.jar}）时，
 * 本引导器把 {@code META-INF/standalone/standalone.jar}（仅含 standalone 自身的类）与模组 jar 内
 * 嵌套依赖一起交给独立的 {@link URLClassLoader} 加载，再反射调用 {@code StandaloneMain}——从而在不
 * 启动游戏的前提下，提供与游戏内一致、共享同一套快照格式的备份 / 回滚能力。
 *
 * <p>standalone 包不再冗余打包 engine:core、backends:zstd、zstd-jni、commons-compress 等运行时依赖，
 * 它们直接复用模组 jar 的嵌套依赖，以缩减模组体积。Fabric 的依赖位于 {@code META-INF/jars/*}，
 * Forge / NeoForge 的依赖位于 {@code META-INF/jarjar/*}，两者结构等价、都会递归展开。
 *
 * <p>Forge / NeoForge 把 engine 组件（core、mod-runtime、spi 等）打平在模组 jar 顶层（Fabric 则放在
 * 独立的嵌套 jar 里），故本引导器会把自身所在 jar 的顶层也加入 classpath；Fabric 的顶层仅含
 * Minecraft 入口类与资源，不被 standalone 引用，靠 {@link URLClassLoader} 的懒加载保证安全。
 *
 * <p>本类不引用任何 Minecraft 类，因此能在纯 JVM 下运行，且 Loom 重映射对其是空操作。
 */
public final class DoubleClickLauncher {
    private static final String NESTED_STANDALONE_PREFIX = "META-INF/standalone/";
    private static final List<String> NESTED_JARS_PREFIXES = List.of("META-INF/jars/", "META-INF/jarjar/");
    private static final int MAX_NESTING_DEPTH = 5;
    private static final String MAIN_CLASS = "io.github.zct_studio.barusikozako.standalone.StandaloneMain";
    private static final String ORIGIN_PROPERTY = "barusikozako.origin.jar";

    private DoubleClickLauncher() {}

    public static void main(String[] args) throws Exception {
        rememberOriginJar();
        Path standalone = locateStandaloneJar();
        List<URL> urls = new ArrayList<>();
        urls.add(standalone.toUri().toURL());
        urls.addAll(collectRuntimeDependencies());
        // 不关闭 loader：GUI 模式下 Swing 事件线程后续仍需按需加载类；CLI 模式由
        // System.exit 退出，临时文件经 deleteOnExit 清理。
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        Class<?> main = Class.forName(MAIN_CLASS, true, loader);
        Method entry = main.getMethod("main", String[].class);
        entry.invoke(null, (Object) args);
    }

    /** 记录最初被双击 / 运行的模组 jar 位置，供独立界面定位“上级目录”。 */
    private static void rememberOriginJar() {
        try {
            URL location = DoubleClickLauncher.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null && "file".equals(location.getProtocol())) {
                Path self = Path.of(location.toURI());
                if (Files.isRegularFile(self) || Files.isDirectory(self))
                    System.setProperty(ORIGIN_PROPERTY, self.toAbsolutePath().toString());
            }
        } catch (Exception ignored) {
            // 开发态或无法定位时，独立界面回退到工作目录
        }
    }

    /** 优先从自身 jar 提取嵌套 standalone 包；开发态回退到 classpath 中的 standalone jar。 */
    private static Path locateStandaloneJar() throws IOException {
        Optional<Path> nested = extractNestedStandalone();
        if (nested.isPresent()) return nested.get();

        for (String part : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!part.endsWith(".jar")) continue;
            Path candidate = Path.of(part);
            String name = candidate.getFileName().toString();
            if (name.startsWith("barusikozako-standalone")) return candidate;
        }
        throw new IOException("内置的 standalone 运行包缺失 / missing embedded standalone package");
    }

    private static Optional<Path> extractNestedStandalone() {
        try {
            URL location = DoubleClickLauncher.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null || !"file".equals(location.getProtocol())) return Optional.empty();
            Path self = Path.of(location.toURI());
            if (!Files.isRegularFile(self)) return Optional.empty();
            try (JarFile jar = new JarFile(self.toFile())) {
                var entry = jar.stream()
                    .filter(e -> e.getName().startsWith(NESTED_STANDALONE_PREFIX) && e.getName().endsWith(".jar"))
                    .findFirst();
                if (entry.isEmpty()) return Optional.empty();
                Path dir = Files.createTempDirectory("barusikozako-standalone");
                dir.toFile().deleteOnExit();
                Path target = dir.resolve("standalone.jar");
                target.toFile().deleteOnExit();
                try (InputStream in = jar.getInputStream(entry.get())) {
                    Files.copy(in, target);
                }
                return Optional.of(target);
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * 收集模组 jar 内嵌套的运行时依赖。Forge / NeoForge 把 engine 组件打平在模组 jar 顶层，故顶层本身
     * 也加入 classpath；backend 包自身又是更深一层嵌套（{@code backendzstd} 的 {@code META-INF/jars/*}
     * 或 {@code META-INF/jarjar/*}），故递归展开到临时目录后一并加入 classpath。
     */
    private static List<URL> collectRuntimeDependencies() throws IOException {
        Path self;
        try {
            URL location = DoubleClickLauncher.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null || !"file".equals(location.getProtocol())) return List.of();
            self = Path.of(location.toURI());
        } catch (Exception ignored) {
            return List.of();
        }
        if (!Files.isRegularFile(self)) return List.of();

        Path dir = Files.createTempDirectory("barusikozako-deps");
        dir.toFile().deleteOnExit();
        List<URL> urls = new ArrayList<>();
        // 模组 jar 顶层：Fabric 顶层无 standalone 所需类（engine 组件在嵌套 jar 里，懒加载无害）；
        // Forge / NeoForge 顶层含打平的 engine 组件类，必须加入。
        urls.add(self.toUri().toURL());
        expandNestedJars(self, dir, urls, 0);
        return urls;
    }

    /**
     * 递归提取 {@code META-INF/jars/*.jar} 与 {@code META-INF/jarjar/*.jar}，每个嵌套 jar（含中间层的
     * backend 包，其顶层有重映射/重定位后的类）都加入 classpath，并继续展开它内部的下一层嵌套。
     */
    private static void expandNestedJars(Path jar, Path outDir, List<URL> urls, int depth) throws IOException {
        if (depth >= MAX_NESTING_DEPTH) return;
        try (JarFile archive = new JarFile(jar.toFile())) {
            var nested = archive.stream()
                .filter(e -> e.getName().endsWith(".jar")
                    && NESTED_JARS_PREFIXES.stream().anyMatch(p -> e.getName().startsWith(p)))
                .toList();
            for (var entry : nested) {
                String name = nestedName(entry.getName());
                Path target = outDir.resolve(uniqueName(outDir, name));
                try (InputStream in = archive.getInputStream(entry)) {
                    Files.copy(in, target);
                }
                target.toFile().deleteOnExit();
                urls.add(target.toUri().toURL());
                // backend 包内还有一层嵌套（backend-zstd / zstd-jni / commons-compress 等）。
                expandNestedJars(target, outDir, urls, depth + 1);
            }
        }
    }

    private static String nestedName(String entryName) {
        for (String prefix : NESTED_JARS_PREFIXES) {
            if (entryName.startsWith(prefix)) return entryName.substring(prefix.length());
        }
        return entryName;
    }

    private static Path uniqueName(Path dir, String name) {
        Path candidate = dir.resolve(name);
        if (!Files.exists(candidate)) return candidate;
        String stem = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            stem = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 1; ; i++) {
            Path next = dir.resolve(stem + "-" + i + extension);
            if (!Files.exists(next)) return next;
        }
    }
}
