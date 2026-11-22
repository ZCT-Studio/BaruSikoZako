// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.work.DisableCachingByDefault;

/** 导出一个可安装的 JAR，即使多个任务共用目录，每个任务也拥有自己的输出 */
@DisableCachingByDefault(because = "Publishing a local JAR costs less than fetching it from a build cache")
public abstract class PublishProductJar extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSourceJar();

    @OutputFile
    public abstract RegularFileProperty getProductJar();

    @TaskAction
    public void publish() throws IOException {
        var destination = getProductJar().get().getAsFile().toPath();
        Files.createDirectories(destination.getParent());
        var staging = Files.createTempFile(destination.getParent(), ".product-", ".tmp");
        try {
            Files.copy(getSourceJar().get().getAsFile().toPath(), staging, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    @DisableCachingByDefault(because = "This task removes an already published archive")
    public abstract static class RemovePublishedSource extends DefaultTask {
        @Internal
        public abstract RegularFileProperty getSourceJar();

        @Internal
        public abstract RegularFileProperty getProductJar();

        @TaskAction
        public void removeSource() throws IOException {
            var source = getSourceJar().get().getAsFile().toPath();
            var product = getProductJar().get().getAsFile().toPath();
            if (Files.isRegularFile(source) && Files.isRegularFile(product)
                    && !Files.isSameFile(source, product) && Files.mismatch(source, product) == -1) {
                Files.delete(source);
            }
        }
    }

    public static TaskProvider<PublishProductJar> register(Project project, String task, String directory,
            String id, String target, boolean lean, Provider<RegularFile> source) {
        return register(project, task, directory, id, target, lean, project.getVersion().toString(), source);
    }

    public static TaskProvider<PublishProductJar> register(Project project, String task, String directory,
            String id, String target, boolean lean, String version, Provider<RegularFile> source) {
        if (!directory.matches("[a-z]+") || !id.matches("[a-zA-Z0-9_-]+")
                || !version.matches("[a-zA-Z0-9][a-zA-Z0-9.+-]*")
                || !target.matches("[a-z]+[0-9]+(\\.[0-9]+)+")) {
            throw new IllegalArgumentException("Invalid product coordinate: " + id + "-" + version + "+" + target);
        }
        String file = id + "-v" + version + (lean ? "_L" : "") + "+" + target + ".jar";
        var destination = project.getRootProject().getLayout().getProjectDirectory()
                .file("build_product/" + directory + "/" + file);
        var publish = project.getTasks().register(task, PublishProductJar.class, export -> {
            export.setGroup("build");
            export.setDescription("Publishes the installable JAR to build_product/" + directory + "/" + file);
            export.getSourceJar().set(source);
            export.getProductJar().set(destination);
        });
        var cleanup = project.getTasks().register(task + "SourceCleanup", RemovePublishedSource.class, remove -> {
            remove.getSourceJar().set(source);
            remove.getProductJar().set(destination);
            // Gradle waits for other consumers, such as the JAR that embeds this archive.
            remove.getDestroyables().register(source);
        });
        publish.configure(export -> export.finalizedBy(cleanup));
        project.getTasks().named("assemble").configure(assemble -> assemble.dependsOn(publish));
        project.getRootProject().getTasks().named("buildProducts").configure(all -> all.dependsOn(publish));
        return publish;
    }
}
