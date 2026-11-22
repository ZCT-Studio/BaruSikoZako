// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import io.github.zct_studio.barusikozako.api.BackupLifecycleHook;
import io.github.zct_studio.barusikozako.api.SnapshotRef;
import io.github.zct_studio.barusikozako.api.TaskResult;
import io.github.zct_studio.barusikozako.bukkit.startup.BukkitStartup;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;

public final class AgentMain {
    private static final Set<String> AUDITED_VANILLA = Set.of(
        "f83b8e093865806f931c7e34aae41b177d4c076335263dd124c75d6d65dd1726",
        "cd47e7c38328f64768fd17af8fcd8b22496b40b63d4ffee81e71ae059fedcb42",
        "cdacdfb25898de5e4b4b0e5ddcc2722f77067e46605709c2d886c000ebb63ec5",
        "d052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64"
    );
    private AgentMain() {}

    public static void main(String[] args) throws Exception {
        AgentRuntime.requireReady();
        if (args.length == 1 && args[0].equals("status")) {
            System.out.println(AgentRuntime.status());
            return;
        }
        if (args.length >= 2 && (args[0].equals("start") || args[0].equals("restore-start")))
            requireSupportedLauncher(Path.of(args[1]));
        String zstd = "barusikozako_backendzstd";
        String configured = System.getProperty("barusikozako.agent.backend", zstd);
        String backend = configured;
        if (!backend.matches("[a-z][a-z0-9_-]{1,63}"))
            throw new IOException("Invalid backend keyword");
        String repository = "barusikozako/worlds/" + backend;
        var path = Path.of(".").toRealPath().resolve(repository);
        boolean auditedVanilla = args.length >= 2 && !args[0].equals("backup-offline") &&
            isAuditedVanilla(Path.of(args[1]));
        io.github.zct_studio.barusikozako.core.BackupEngine.Checkpoint startApi =
            () -> AgentRuntime.startApi(path, backend, auditedVanilla);
        if (args.length == 4 && args[0].equals("restore-start")) {
            var point = new SnapshotRef("agent", args[2]);
            var context = new BackupLifecycleHook.Context(BackupLifecycleHook.Operation.OFFLINE_RESTORE,
                "agent", Optional.of(point));
            BukkitStartup.run(args, repository, () -> AgentRuntime.backend(backend), () -> AgentRuntime.beforeRestore(context),
                () -> AgentRuntime.afterRestore(context, new TaskResult.Restored(point, "worlds")), startApi,
                AgentDirectoryPersistence.INSTANCE);
        } else {
            BukkitStartup.run(args, repository, () -> AgentRuntime.backend(backend), () -> {}, () -> {}, startApi,
                AgentDirectoryPersistence.INSTANCE);
        }
    }

    private static boolean isAuditedVanilla(Path launcher) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(launcher), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return AUDITED_VANILLA.contains(HexFormat.of().formatHex(digest.digest()));
    }

    private static void requireSupportedLauncher(Path serverJar) throws IOException {
        try (var archive = new JarFile(serverJar.toFile())) {
            var manifest = archive.getManifest();
            if (manifest == null) return;
            var attributes = manifest.getMainAttributes();
            var entry = attributes.getValue("Main-Class");
            if ("SpongeVanilla".equalsIgnoreCase(attributes.getValue("Implementation-Title")) ||
                    (entry != null && entry.startsWith("org.spongepowered.")))
                throw new IOException("UNSUPPORTED: SpongeVanilla is outside the JavaAgent support scope");
        }
    }
}
