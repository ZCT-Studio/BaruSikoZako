// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

/** 用官方安装器生成 FML 服务端编译依赖。 */
@DisableCachingByDefault(because = "The installer produces a local server distribution")
public abstract class PrepareFmlRuntime extends DefaultTask {
    @Input public abstract Property<String> getInstallerUrl();
    @Input public abstract Property<String> getInstallerSha256();
    @Input public abstract Property<String> getPatchedJarPath();
    @Input public abstract Property<String> getOverrideProperty();
    @Internal public abstract Property<Boolean> getOffline();
    @Nested public abstract Property<JavaLauncher> getJavaLauncher();
    @OutputDirectory public abstract DirectoryProperty getRuntimeDirectory();

    @Inject protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void prepare() throws IOException, NoSuchAlgorithmException {
        var directory = getRuntimeDirectory().get().getAsFile().toPath();
        var url = URI.create(getInstallerUrl().get());
        if (getOffline().get()) {
            throw new GradleException("FML compile inputs need setup. Run Gradle online or set -P"
                    + getOverrideProperty().get() + "=<installed server directory>.");
        }
        Files.createDirectories(directory);
        var lockPath = directory.resolveSibling(directory.getFileName() + ".lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            var installer = directory.resolve(url.getPath().substring(url.getPath().lastIndexOf('/') + 1));
            if (!Files.isRegularFile(installer) || !checksum(installer).equals(getInstallerSha256().get())) {
                downloadInstaller(url, installer);
            }
            var patchedJar = directory.resolve(getPatchedJarPath().get());
            for (int attempt = 1; attempt <= 3; attempt++) {
                var result = getExecOperations().exec(spec -> {
                    spec.setWorkingDir(directory.toFile());
                    spec.commandLine(getJavaLauncher().get().getExecutablePath().getAsFile(),
                            "-Djava.awt.headless=true", "-jar", installer.toFile(), "--installServer");
                    spec.setIgnoreExitValue(true);
                });
                if (result.getExitValue() == 0 && Files.isRegularFile(patchedJar)) {
                    return;
                }
                if (attempt < 3) {
                    getLogger().warn("FML installer did not prepare {} on attempt {}. Retrying.", patchedJar, attempt);
                }
            }
            throw new GradleException("FML installer did not prepare " + patchedJar
                    + ". See " + directory.resolve("installer.log") + " or set -P"
                    + getOverrideProperty().get() + "=<installed server directory>.");
        }
    }

    private void downloadInstaller(URI url, Path installer) throws IOException, NoSuchAlgorithmException {
        var staging = Files.createTempFile(installer.getParent(), "installer-", ".tmp");
        try {
            var connection = url.toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (BaruSikoZako Gradle build)");
            try (var stream = connection.getInputStream()) {
                Files.copy(stream, staging, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!checksum(staging).equals(getInstallerSha256().get())) {
                throw new GradleException("SHA-256 mismatch for " + url);
            }
            Files.move(staging, installer, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static String checksum(Path file) throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) != -1;) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
