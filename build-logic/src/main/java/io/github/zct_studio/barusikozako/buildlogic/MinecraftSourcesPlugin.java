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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.language.jvm.tasks.ProcessResources;

/**
 * 把 {@code ;lists/targets.json5} 应用到单个 Minecraft 构建目标：源码层、资源层、
 * Java release、条件编译与 Mixin 配置。
 *
 * <p>源码直接作为源码集目录加入，IDEA 因此可以正常补全、跳转和重构。只有当前工作区
 * 条件分支与目标不一致时（非活跃目标）才生成临时副本并排除原文件，此时以构建正确性
 * 为准。FML 等使用自定义源码集的脚本可以调用 {@link #configure} 复用同一套规则。
 */
public final class MinecraftSourcesPlugin implements Plugin<Project> {
    public static final String GENERATE_SOURCES_TASK = "generateTargetSources";
    public static final String MIXIN_TASK = "generateMixinConfig";
    public static final String CHECK_TASK = "checkTargetSources";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        BuildTargets targets = load(project);
        BuildTargets.Target target = target(project, targets);
        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class)
            .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        TaskProvider<?> mixin = configure(project, main, targets, target, true, true);
        if (mixin != null) {
            ProcessResources processResources = (ProcessResources) project.getTasks()
                .getByName(JavaPlugin.PROCESS_RESOURCES_TASK_NAME);
            processResources.from(mixin);
        }
        registerCheckTask(project, targets, target);
    }

    /** 供脚本复用的清单入口。 */
    public static BuildTargets load(Project project) {
        Path root = project.getRootDir().toPath();
        return BuildTargets.load(root, root.resolve("gradle/compatibility-targets.properties"));
    }

    /** 由项目路径推导目标：{@code :app:mc26_3:fabric} -> fabric@26.3。 */
    public static BuildTargets.Target target(Project project, BuildTargets targets) {
        String path = project.getPath();
        if (!path.startsWith(":app:")) {
            throw new GradleException("Build targets are only defined for :app:<mc>:<loader> projects, not " + path);
        }
        String node = project.getParent().getName();
        if (!node.startsWith("mc")) throw new GradleException("Unexpected Minecraft project name: " + node);
        String game = node.substring(2).replace('_', '.');
        return targets.require(project.getName(), game);
    }

    /**
     * 按清单配置源码集。
     *
     * @return Mixin 生成任务；目标未声明 mixin 配置时为 {@code null}
     */
    public static TaskProvider<?> configure(Project project, SourceSet sourceSet, BuildTargets targets,
                                            BuildTargets.Target target, boolean withResources, boolean withMixin) {
        LayerResolver.Resolved resolved = LayerResolver.resolve(targets, target);
        ConditionalProcessor.Context context = target.conditionContext();

        project.getExtensions().getByType(JavaPluginExtension.class).getToolchain().getLanguageVersion()
            .set(JavaLanguageVersion.of(25));
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getRelease().set(target.javaRelease());
            task.getOptions().setEncoding("UTF-8");
        });

        Map<String, String> allSources = new LinkedHashMap<>();
        Map<String, Path> directiveFiles = new LinkedHashMap<>();
        boolean needsView = false;
        for (Map.Entry<String, Path> entry : resolved.javaFiles().entrySet()) {
            Path file = entry.getValue();
            String content = read(file);
            allSources.put(entry.getKey(), file.toString());
            if (!ConditionalProcessor.containsDirectives(content)) continue;
            List<String> errors = ConditionalProcessor.validate(content);
            if (!errors.isEmpty()) {
                throw new GradleException("Invalid conditional directives in " + file + ": " + String.join("; ", errors));
            }
            directiveFiles.put(entry.getKey(), file);
            if (!ConditionalProcessor.isResolved(content, context)) needsView = true;
        }

        if (needsView) {
            // 工作区条件分支与目标不一致：生成该目标的完整源码视图，避免原文件混入错误分支。
            TaskProvider<GenerateTargetSources> generate = project.getTasks().register(GENERATE_SOURCES_TASK,
                GenerateTargetSources.class, task -> {
                    task.setGroup("build");
                    task.setDescription("Generates the " + target.key() + " source view for conditional processing");
                    task.getMc().set(target.game());
                    task.getLoader().set(target.loader());
                    task.getJavaRelease().set(target.javaRelease());
                    task.getSources().set(allSources);
                    task.getSourceFiles().from(allSources.values());
                    task.getOutputDirectory().set(project.getLayout().getBuildDirectory()
                        .dir("target-sources/" + target.key() + "/java"));
                    // 输出目录里出现源码视图中不存在的文件时（源码移动或删除后的残留）必须重跑并清理
                    task.getOutputs().upToDateWhen(actual ->
                        !(actual instanceof GenerateTargetSources sources) || !sources.hasOrphanedOutputs());
                });
            sourceSet.getJava().srcDir(generate.flatMap(GenerateTargetSources::getOutputDirectory));
            project.getTasks().named(sourceSet.getCompileJavaTaskName()).configure(task -> task.dependsOn(generate));
        } else {
            for (LayerResolver.LayerDir layer : resolved.layers()) {
                if (Files.isDirectory(layer.java())) sourceSet.getJava().srcDir(layer.java().toFile());
            }
        }

        if (withResources) {
            for (LayerResolver.LayerDir layer : resolved.layers()) {
                if (Files.isDirectory(layer.resources())) sourceSet.getResources().srcDir(layer.resources().toFile());
            }
        }

        if (!withMixin || target.mixin() == null) return null;
        validateMixins(project, targets, target, resolved);
        return project.getTasks().register(MIXIN_TASK, GenerateMixinConfig.class, task -> {
            task.setGroup("build");
            task.setDescription("Generates " + target.mixinConfigName() + " for " + target.key());
            task.getTemplate().set(project.getRootProject().getLayout().getProjectDirectory()
                .file(targets.mixinTemplate()).getAsFile());
            task.getInjectors().set(target.mixin().injectors());
            task.getRefmap().set(target.mixin().refmap());
            task.getPluginClass().set(target.mixin().plugin() == null ? "" : target.mixin().plugin());
            task.getMixins().set(target.mixin().mixins());
            task.getOutputFile().set(project.getLayout().getBuildDirectory()
                .file("generated/mixin/" + target.key() + "/" + target.mixinConfigName()));
        });
    }

    private static void registerCheckTask(Project project, BuildTargets targets, BuildTargets.Target target) {
        LayerResolver.Resolved resolved = LayerResolver.resolve(targets, target);
        ConditionalProcessor.Context context = target.conditionContext();
        project.getTasks().register(CHECK_TASK, task -> {
            task.setGroup("verification");
            task.setDescription("Validates the build target manifest, source layers and conditionals");
            task.doLast(action -> {
                List<String> problems = new ArrayList<>(resolved.warnings());
                for (Map.Entry<String, Path> entry : resolved.javaFiles().entrySet()) {
                    Path file = entry.getValue();
                    String content = read(file);
                    if (!ConditionalProcessor.containsDirectives(content)) continue;
                    problems.addAll(ConditionalProcessor.validate(content).stream()
                        .map(error -> file + ": " + error).toList());
                    if (!ConditionalProcessor.isResolved(content, context)) {
                        problems.add("Active target " + target.key() + " does not match conditional branches in "
                            + file + "; run activateTarget");
                    }
                }
                if (!problems.isEmpty()) throw new GradleException(String.join(System.lineSeparator(), problems));
                project.getLogger().lifecycle("Target {}: {} source files, {} resource files, {} layers",
                    target.key(), resolved.javaFiles().size(), resolved.resourceFiles().size(), resolved.layers().size());
            });
        });
    }

    private static void validateMixins(Project project, BuildTargets targets, BuildTargets.Target target,
                                       LayerResolver.Resolved resolved) {
        Map<String, Object> template;
        try {
            template = Json5.parseObject(Files.readString(
                project.getRootDir().toPath().resolve(targets.mixinTemplate()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read mixin template", e);
        }
        if (!(template.get("package") instanceof String pkg)) {
            throw new GradleException("Mixin template needs a package field");
        }
        String prefix = pkg.replace('.', '/') + "/";
        List<String> missing = new ArrayList<>();
        for (String mixin : target.mixin().mixins()) {
            if (!resolved.javaFiles().containsKey(prefix + mixin + ".java")) missing.add(mixin);
        }
        if (!missing.isEmpty()) {
            throw new GradleException("Target " + target.key() + " lists mixins without matching sources: " + missing
                + ". Mixin 类必须存在于该目标的有效源码层中。");
        }
    }

    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** 为非活跃目标生成完整源码视图（含条件处理）。 */
    @CacheableTask
    public abstract static class GenerateTargetSources extends DefaultTask {
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        public abstract ConfigurableFileCollection getSourceFiles();

        @Internal
        public abstract MapProperty<String, String> getSources();

        @Input
        public abstract Property<String> getMc();

        @Input
        public abstract Property<String> getLoader();

        @Input
        public abstract Property<Integer> getJavaRelease();

        @OutputDirectory
        public abstract DirectoryProperty getOutputDirectory();

        @TaskAction
        public void run() {
            ConditionalProcessor.Context context = new ConditionalProcessor.Context(
                getMc().get(), getLoader().get(), getJavaRelease().get());
            Path output = getOutputDirectory().get().getAsFile().toPath();
            // 源码移动或改名后旧视图必须消失，否则残留副本会继续被编译
            deleteRecursively(output);
            for (Map.Entry<String, String> entry : new java.util.TreeMap<>(getSources().get()).entrySet()) {
                Path file = Path.of(entry.getValue());
                String processed = ConditionalProcessor.apply(ConditionalProcessor.canonical(read(file)), context);
                Path destination = output.resolve(entry.getKey());
                try {
                    Files.createDirectories(destination.getParent());
                    Files.writeString(destination, processed, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        /** 输出目录中是否存在当前源码视图不含的文件，用于让增量检查发现残留副本。 */
        public boolean hasOrphanedOutputs() {
            Path output = getOutputDirectory().get().getAsFile().toPath();
            if (Files.notExists(output)) return false;
            Set<String> expected = getSources().get().keySet();
            try (java.util.stream.Stream<Path> walk = Files.walk(output)) {
                return walk.filter(Files::isRegularFile)
                    .map(file -> output.relativize(file).toString().replace('\\', '/'))
                    .anyMatch(relative -> !expected.contains(relative));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static void deleteRecursively(Path directory) {
            if (Files.notExists(directory)) return;
            try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** 由模板与清单生成 Mixin 配置。 */
    @CacheableTask
    public abstract static class GenerateMixinConfig extends DefaultTask {
        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public abstract RegularFileProperty getTemplate();

        @Input
        public abstract Property<Boolean> getInjectors();

        @Input
        public abstract Property<Boolean> getRefmap();

        @Input
        public abstract Property<String> getPluginClass();

        @Input
        public abstract ListProperty<String> getMixins();

        @OutputFile
        public abstract RegularFileProperty getOutputFile();

        @TaskAction
        public void run() {
            Map<String, Object> template;
            try {
                template = Json5.parseObject(Files.readString(getTemplate().get().getAsFile().toPath(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            StringBuilder json = new StringBuilder("{\n");
            json.append("  \"required\": ").append(template.getOrDefault("required", true)).append(",\n");
            json.append("  \"minVersion\": \"").append(escape(String.valueOf(template.getOrDefault("minVersion", "0.8.5")))).append("\",\n");
            if (getInjectors().get()) json.append("  \"injectors\": { \"defaultRequire\": 1 },\n");
            json.append("  \"package\": \"").append(escape(String.valueOf(template.get("package")))).append("\",\n");
            if (!getPluginClass().get().isEmpty()) {
                json.append("  \"plugin\": \"").append(escape(getPluginClass().get())).append("\",\n");
            }
            if (getRefmap().get()) json.append("  \"refmap\": \"barusikozako.refmap.json\",\n");
            appendArray(json, "server", template.get("server"));
            appendArray(json, "client", template.get("client"));
            json.append("  \"mixins\": [");
            List<String> mixins = getMixins().get();
            for (int i = 0; i < mixins.size(); i++) {
                json.append(i == 0 ? "\n" : ",\n").append("    \"").append(escape(mixins.get(i))).append("\"");
            }
            json.append("\n  ]\n}\n");
            Path output = getOutputFile().get().getAsFile().toPath();
            try {
                Files.createDirectories(output.getParent());
                Files.writeString(output, json.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static void appendArray(StringBuilder json, String name, Object value) {
            if (!(value instanceof List<?> list) || list.isEmpty()) return;
            json.append("  \"").append(name).append("\": [");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) json.append(", ");
                json.append("\"").append(escape(String.valueOf(list.get(i)))).append("\"");
            }
            json.append("],\n");
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
