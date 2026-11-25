// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * 合并多个结构相同、类字节一致、仅元数据不同的 jar（用于 {@code barusikozako_api}）。
 *
 * <p>与 Forgix 不同，这里不做类重定位：调用方保证同名条目内容一致。MANIFEST 主属性取并集，
 * 其余同名但内容不同的条目视为错误，避免静默产出不可用产物。
 */
@CacheableTask
public abstract class JarMerge extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputJars();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @TaskAction
    public void merge() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Manifest manifest = null;
        for (File jar : getInputJars().getFiles()) {
            try (ZipFile zip = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry entry = all.nextElement();
                    String name = entry.getName();
                    byte[] data = entry.isDirectory()
                        ? new byte[0]
                        : zip.getInputStream(entry).readAllBytes();
                    if (!entry.isDirectory() && name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                        Manifest source = new Manifest(new ByteArrayInputStream(data));
                        if (manifest == null) manifest = source;
                        else mergeManifest(manifest, source);
                        continue;
                    }
                    byte[] existing = entries.get(name);
                    if (existing == null) {
                        entries.put(name, data);
                    } else if (!Arrays.equals(existing, data)) {
                        throw new GradleException("Conflicting entry " + name + " while merging " + jar);
                    }
                }
            }
        }
        Path output = getOutputJar().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(output))) {
            if (manifest != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                manifest.write(buffer);
                write(stream, "META-INF/MANIFEST.MF", buffer.toByteArray());
            }
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                write(stream, entry.getKey(), entry.getValue());
            }
        }
    }

    private static void mergeManifest(Manifest target, Manifest source) {
        for (Map.Entry<Object, Object> attribute : source.getMainAttributes().entrySet()) {
            target.getMainAttributes().putIfAbsent(attribute.getKey(), attribute.getValue());
        }
        for (Map.Entry<String, Attributes> section : source.getEntries().entrySet()) {
            target.getEntries().putIfAbsent(section.getKey(), section.getValue());
        }
    }

    private static void write(ZipOutputStream stream, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        stream.putNextEntry(entry);
        stream.write(data);
        stream.closeEntry();
    }
}
