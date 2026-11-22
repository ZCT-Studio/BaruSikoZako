// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.zstd;

import io.github.zct_studio.barusikozako.backend.Snapshot;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.BackupExclusions;
import io.github.zct_studio.barusikozako.core.StagedDirectoryView;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/** 隔离实服冒烟测试使用的测试夹具生成器 */
public final class StoppedServerFixture {
    public static void main(String[] args) throws IOException {
        var directory = Path.of(args[0]).toRealPath();
        if (!Files.isRegularFile(directory.resolve(".barusikozako-smoke"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Expected an isolated BaruSikoZako smoke test directory");
        var world = directory.resolve("world");
        var location = directory.resolve("barusikozako/world/barusikozako_backendzstd");
        var snapshot = new Snapshot(UUID.randomUUID(), Instant.now(), "stopped-fabric-fixture");
        var limits = new BackupEngine.Limits(2L << 30, 3L << 30, 100_000);
        var budget = new StagedDirectoryView.CopyBudget(limits.contentBytes(), limits.entries(), 64);
        try (var startup = FileChannel.open(directory.resolve(".barusikozako-worlds.lock"), StandardOpenOption.WRITE,
                 LinkOption.NOFOLLOW_LINKS);
             var startupLock = startup.tryLock()) {
            if (startupLock == null) throw new IOException("The smoke test server is still running");
            try (var session = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS);
                 var sessionLock = session.tryLock()) {
                if (sessionLock == null) throw new IOException("The fixture world is still in use");
                try (var repository = ZstdRepository.open(Files.createDirectories(location));
                     var view = StagedDirectoryView.copy(world, directory, budget, () -> {})) {
                    var engine = new BackupEngine();
                    var rules = BackupExclusions.load(directory.resolve("config/barusikozako"), world, false);
                    engine.backup(view, repository, snapshot, UUID.randomUUID(), limits,
                        () -> {}, () -> {}, ignored -> {}, 0, rules);
                    var result = engine.verify(repository, snapshot.id(), limits);
                    System.out.println("Fixture snapshot " + snapshot.id() + " verified (" + result.entries()
                        + " entries, " + result.bytes() + " bytes).");
                }
            }
        }
    }
}
