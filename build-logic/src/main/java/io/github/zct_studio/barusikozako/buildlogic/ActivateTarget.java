// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/**
 * 切换活跃构建目标：按目标条件重写带指令的源文件，并更新 {@code gradle/local-target.properties}。
 *
 * <p>它与构建共用 {@link ConditionalProcessor}，因此 IDEA Reload 后看到的分支与实际构建一致。
 * 改写只影响含 {@code //?} 指令的文件，且可重复执行。
 */
public abstract class ActivateTarget extends DefaultTask {
    @Input
    public abstract Property<String> getGame();

    @Input
    public abstract Property<String> getLoader();

    @Internal
    public abstract DirectoryProperty getRootDirectory();

    @TaskAction
    public void run() {
        Path root = getRootDirectory().get().getAsFile().toPath();
        BuildTargets targets = BuildTargets.load(root, root.resolve("gradle/compatibility-targets.properties"));
        BuildTargets.Target target = targets.require(getLoader().get(), getGame().get());
        ConditionalProcessor.Context context = target.conditionContext();

        TreeSet<String> layers = new TreeSet<>();
        targets.targets().values().forEach(entry -> layers.addAll(entry.sources()));
        List<String> changed = new ArrayList<>();
        for (String layer : layers) {
            Path directory = targets.layerDirectory(layer);
            for (String kind : List.of("java", "resources")) {
                Path base = directory.resolve(kind);
                if (!Files.isDirectory(base)) continue;
                try (Stream<Path> files = Files.walk(base)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        if (!ConditionalProcessor.containsDirectives(content)) continue;
                        String processed = ConditionalProcessor.apply(ConditionalProcessor.canonical(content), context);
                        if (!processed.equals(content)) {
                            Files.writeString(file, processed, StandardCharsets.UTF_8);
                            changed.add(root.relativize(file).toString().replace('\\', '/'));
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        writeLocalTarget(root, target);
        getLogger().lifecycle("Activated {} ({}); updated {} file(s)", target.key(), context, changed.size());
        for (String file : changed) getLogger().info("  {}", file);
    }

    private void writeLocalTarget(Path root, BuildTargets.Target target) {
        Path file = root.resolve("gradle/local-target.properties");
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int equals = line.indexOf('=');
                    if (equals <= 0 || line.strip().startsWith("#")) continue;
                    values.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        values.put("gameVersion", target.game());
        values.put("loaders", target.loader());
        if (!values.containsKey("distributionLoader")) values.put("distributionLoader", "fabric");
        StringBuilder out = new StringBuilder("# From ./gradlew activateTarget -PgameVersion=... -Ploaders=... \n");
        values.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        try {
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("Cannot write " + file, e);
        }
    }
}
