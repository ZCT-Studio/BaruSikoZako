// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.buildlogic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

public abstract class CollectNotices extends DefaultTask {
    private static final Pattern DECLARATION = Pattern.compile("(?i)(LICENSE|COPYING|NOTICE|COPYRIGHT)([._-].*)?");
    private static final Pattern LICENSE = Pattern.compile("(?i)(LICENSE|COPYING)([._-].*)?");

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPackagedLibraries();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getManualNotices();
    @Input public abstract SetProperty<String> getProjectJars();
    @Input public abstract Property<Boolean> getStrict();
    @OutputDirectory public abstract DirectoryProperty getCollected();
    @Inject public abstract FileSystemOperations getFileOperations();

    @TaskAction
    public void gather() throws IOException {
        var target = getCollected().get().getAsFile();
        getFileOperations().delete(spec -> spec.delete(target));
        Files.createDirectories(target.toPath());
        var libraries = getPackagedLibraries().getAsFileTree().matching(spec -> spec.include("**/*.jar"))
            .getFiles().stream().sorted(Comparator.comparing(File::getName)).toList();
        for (var library : libraries) {
            var name = library.getName();
            if (getProjectJars().get().contains(name)) continue;
            var folder = target.toPath().resolve(name);
            Files.createDirectories(folder);
            try (var jar = new ZipFile(library)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    var entryName = entry.getName();
                    if (entry.isDirectory() || !DECLARATION.matcher(entryName.substring(entryName.lastIndexOf('/') + 1)).matches())
                        continue;
                    var output = folder.resolve(entryName).normalize();
                    if (!output.startsWith(folder)) throw new GradleException("Invalid notice path in " + name);
                    Files.createDirectories(output.getParent());
                    try (var input = jar.getInputStream(entry)) {
                        Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            var manual = getManualNotices().get().dir(name).getAsFile();
            if (manual.isDirectory()) getFileOperations().copy(spec -> spec.from(manual).into(folder.toFile()));
            try (var files = Files.walk(folder)) {
                if (files.anyMatch(path -> Files.isRegularFile(path) && LICENSE.matcher(path.getFileName().toString()).matches()))
                    continue;
            }
            var message = "Missing license in packaged dependency " + name;
            if (getStrict().get()) throw new GradleException(message);
            getLogger().warn(message);
        }
    }
}
