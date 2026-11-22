// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import io.github.zct_studio.barusikozako.agent.instrument.AgentHooks;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;

public final class Premain {
    private static JarFile contracts;

    private Premain() {}

    public static void premain(String options, Instrumentation instrumentation) throws IOException {
        AgentConfig.load(Path.of(".").toRealPath());
        Path archive = extract("/META-INF/barusikozako/shared-api.jar");
        if (archive == null) throw new IOException("The agent is missing its shared contracts");
        contracts = new JarFile(archive.toFile());
        instrumentation.appendToBootstrapClassLoaderSearch(contracts);
        AgentHooks.install(instrumentation);
        AgentRuntime.initialize(Path.of(".").toRealPath());
    }

    static Path extract(String name) throws IOException {
        try (var input = Premain.class.getResourceAsStream(name)) {
            if (input == null) return null;
            var bytes = input.readAllBytes();
            var directory = Path.of(".").toRealPath().resolve(".barusikozako-agent-cache");
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !directory.toRealPath().equals(directory))
                throw new IOException("Agent JAR cache must be an unaliased directory");
            var target = directory.resolve(digest(bytes) + ".jar");
            if (validCache(target, bytes)) return target;
            if (!Files.notExists(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Agent cache entry failed its digest check: " + target);
            Path file = Files.createTempFile(directory, "extract-", ".tmp");
            try {
                Files.write(file, bytes);
                try {
                    Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException race) {
                    if (!validCache(target, bytes)) throw race;
                }
                return target;
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    private static boolean validCache(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != expected.length) return false;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return java.util.Arrays.equals(input.readNBytes(expected.length + 1), expected);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
