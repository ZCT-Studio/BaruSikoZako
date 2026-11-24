// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.zstd;

import io.github.zct_studio.barusikozako.backend.Snapshot;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.BackupExclusions;
import io.github.zct_studio.barusikozako.core.RestoreResult;
import io.github.zct_studio.barusikozako.core.StagedDirectoryView;
import io.github.zct_studio.barusikozako.platform.ReadView;
import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public final class StaticRoundTrip {
    private static final BackupEngine.Limits LIMITS = new BackupEngine.Limits(8L << 20, 10L << 20, 100);

    public static void main(String[] args) throws IOException {
        if (args.length == 7 && args[0].equals("--halt-delta-publication")) {
            crashDelta(args);
            throw new AssertionError("the publication crash hook was not reached");
        }
        exerciseTarMetadataGuard();
        var work = Files.createTempDirectory("barusikozako-static-test-");
        try {
            exercise(work);
            exerciseExclusions(work);
            exerciseChunks(work);
            exerciseFileModes(work);
            exerciseChainLimit(work);
            exerciseEarlyRollover(work, "size", new ZstdRepository.Rollover(0, 75, 0), 2);
            exerciseEarlyRollover(work, "time", new ZstdRepository.Rollover(8, 0, 60), 61);
            exerciseLiveCopy(work);
        } finally {
            try (var paths = Files.walk(work)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Static Zstd backup, full verification, staged recovery and failure checks passed on Java "
                + Runtime.version().feature() + ".");
    }

    private static void exerciseExclusions(Path work) throws IOException {
        var game = Files.createDirectory(work.resolve("ignore-game"));
        var config = Files.createDirectories(game.resolve("config/barusikozako"));
        var world = Files.createDirectories(game.resolve("worlds/world"));
        var stages = Files.createDirectory(work.resolve("ignore-stages"));
        var repo = Files.createDirectory(work.resolve("ignore-repository"));
        Files.writeString(config.resolve("save.gitignore"), "*.log\n!keep.log\n!forced.tmp\ncache/\n!cache/keep\ndatapacks/\n");
        Files.writeString(world.resolve("forced.tmp"), "excluded by mod");
        Files.writeString(world.resolve("omit.log"), "excluded by world rules");
        Files.writeString(world.resolve("keep.log"), "included by world exception");
        Files.createDirectory(world.resolve("cache"));
        Files.writeString(world.resolve("cache/keep"), "excluded with its parent");
        Files.writeString(world.resolve("cache/local.gitignore"), "!keep\n");
        Files.createDirectory(world.resolve("datapacks"));
        Files.writeString(world.resolve("datapacks/local.gitignore"), "!keep-local.log\n");
        Files.writeString(world.resolve("datapacks/keep-local.log"), "nested override");
        Files.writeString(world.resolve("datapacks/omit-local.log"), "world rule still applies");
        Files.writeString(world.resolve("local.gitignore"), "!datapacks/\n");
        var dimension = Files.createDirectories(world.resolveSibling("world_nether").resolve("DIM-1"));
        Files.writeString(dimension.resolve("level.dat"), "separate dimension");
        var engine = new BackupEngine();
        for (boolean container : new boolean[] {false, true}) {
            var source = container ? world.getParent() : world;
            BackupExclusions rules;
            try (var registration = BackupExclusions.register("WORLD", "fixture.mod", "forced.tmp")) {
                rules = BackupExclusions.load(config, source, container);
            } catch (Exception failure) {
                throw new IOException(failure);
            }
            var point = new Snapshot(UUID.randomUUID(), Instant.now(), "ignore");
            try (var repository = ZstdRepository.open(repo)) {
                var view = StagedDirectoryView.copy(source, stages,
                        new StagedDirectoryView.CopyBudget(1L << 20, 100, 16), () -> {});
                var notices = new java.util.ArrayList<String>();
                engine.backup(view, repository, point, UUID.randomUUID(), LIMITS,
                        () -> {}, () -> {}, ignored -> {}, 0, rules, notices::addAll);
                require(engine.verify(repository, point.id(), LIMITS).entries() == (container ? 10 : 7),
                        "exclusions changed the expected snapshot entries");
                require(notices.contains("mod fixture.mod [WORLD]: forced.tmp") &&
                                notices.contains(".worlds/world/datapacks/local.gitignore: !keep-local.log") &&
                                notices.stream().noneMatch(line -> line.contains("cache/local.gitignore")),
                        "missing expected rule sources in the notice");
                var archive = engine.prepareRestore(repository, point.id(), LIMITS, stages);
                require(Files.isRegularFile(archive.resolve(".worlds/world/keep.log")), "missing world archive namespace");
                var restored = Files.createTempDirectory(stages, "scoped-restore-");
                engine.readWorld(repository, point.id(), LIMITS, restored, "world", container);
                var data = container ? restored.resolve("world") : restored;
                require(Files.readString(data.resolve("keep.log")).equals("included by world exception"),
                        "re-included content did not survive restore");
                require(Files.readString(data.resolve("datapacks/keep-local.log")).equals("nested override"),
                        "nested rule failed to override ordinary global rules");
                require(Files.notExists(data.resolve("forced.tmp")) && Files.notExists(data.resolve("omit.log"))
                                && Files.notExists(data.resolve("cache")) && Files.notExists(data.resolve("datapacks/omit-local.log")),
                        "excluded data was restored");
                if (container) require(Files.readString(restored.resolve("world_nether/DIM-1/level.dat"))
                        .equals("separate dimension"), "separate dimension collided with another world");
                var target = Files.createDirectories(stages.resolve(container ? "restored-container" : "restored-one")
                        .resolve(container ? "worlds" : "world")).toRealPath();
                var original = container ? Files.createDirectory(target.resolve("world")) : target;
                Files.writeString(original.resolve("forced.tmp"), "retain in the original world");
                try (var window = new RestoreWindow() {
                    @Override public Path target() { return target; }
                    @Override public void requireExclusiveAccess() {}
                    @Override public void forceDirectory(Path directory) {}
                    @Override public void close() {}
                }) {
                    var result = engine.restoreWorld(repository, point.id(), UUID.randomUUID(), LIMITS, window, container,
                            stage -> require(Files.isRegularFile((container ? stage.resolve("world") : stage).resolve("keep.log")),
                                    "restore validation did not see the projected world path"));
                    require(result.status() == RestoreResult.Status.RESTORED, "namespaced restore did not commit");
                    var previous = result.previousWorld().orElseThrow();
                    if (container) previous = previous.resolve("world");
                    require(Files.readString(previous.resolve("forced.tmp")).equals("retain in the original world"),
                            "restore did not protect the excluded file in the original world");
                    require(Files.notExists(original.resolve("forced.tmp")), "excluded file leaked into the restored world");
                }
            }
        }
        require(Files.isRegularFile(config.resolve("world.gitignore")) && Files.notExists(config.resolve("save.gitignore")),
                "old world rule file was not renamed");
        System.out.println("World namespaces, scoped rules, notifications and restored data verified.");
    }

    private static void exerciseLiveCopy(Path work) throws IOException {
        var source = Files.createDirectory(work.resolve("live-copy-source"));
        var stages = Files.createDirectory(work.resolve("live-copy-stages"));
        var file = source.resolve("region.mca");
        byte[] data = new byte[2 * 1024 * 1024];
        new Random(73).nextBytes(data);
        Files.write(file, data);
        var timestamp = Files.getLastModifiedTime(file);
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var budget = new StagedDirectoryView.CopyBudget(8L << 20, 100, 32);
        try (var view = StagedDirectoryView.copyLive(source, stages, budget, () -> {
            if (changed.get()) return;
            try (var candidates = Files.newDirectoryStream(stages, ".barusikozako-view-*")) {
                for (var stage : candidates) {
                    var partial = stage.resolve("region.mca");
                    if (Files.exists(partial) && Files.size(partial) > 0) {
                        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
                            channel.write(java.nio.ByteBuffer.wrap(new byte[] { (byte) (data[0] ^ 1) }), 0);
                        }
                        Files.setLastModifiedTime(file, timestamp);
                        changed.set(true);
                        break;
                    }
                }
            }
        }, (input, target, length) -> false); var session = view.openSession()) {
            require(changed.get(), "live copy fixture did not modify the source");
            require(session.next() != null, "live copy did not contain the file");
            try (var content = session.openContent()) {
                require(java.util.Arrays.equals(content.readAllBytes(), Files.readAllBytes(file)),
                        "live copy did not retry same-size, same-time modification");
            }
        }
        var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var modifications = new java.util.concurrent.atomic.AtomicInteger();
        expectFailure(() -> StagedDirectoryView.copyLive(source, stages, budget, () -> {
            try (var candidates = Files.newDirectoryStream(stages, ".barusikozako-view-*")) {
                for (var stage : candidates) {
                    var partial = stage.resolve("region.mca");
                    if (!Files.exists(partial)) {
                        armed.set(true);
                    } else if (armed.get() && Files.size(partial) > 0) {
                        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                            var first = java.nio.ByteBuffer.allocate(1);
                            channel.read(first, 0);
                            first.flip();
                            channel.write(java.nio.ByteBuffer.wrap(new byte[] { (byte) (first.get() ^ 1) }), 0);
                        }
                        Files.setLastModifiedTime(file, timestamp);
                        modifications.incrementAndGet();
                        armed.set(false);
                    }
                }
            }
        }, (input, target, length) -> false));
        require(modifications.get() == 3, "unstable file was not retried exactly three times");
        expectFailure(() -> StagedDirectoryView.copyLive(source, stages, budget, () -> {
            try (var candidates = Files.newDirectoryStream(stages, ".barusikozako-view-*")) {
                for (var stage : candidates) {
                    if (Files.exists(stage.resolve("region.mca")))
                        throw new InterruptedIOException("cancel live capture");
                }
            }
        }, (input, target, length) -> false));
        var writeAttempt = new java.util.concurrent.atomic.AtomicBoolean();
        expectFailure(() -> StagedDirectoryView.copyLive(source, stages, budget, () -> {}, (input, target, length) -> {
            Files.writeString(target, "partial");
            writeAttempt.set(true);
            throw new java.nio.file.FileSystemException(target.toString(), null, "No space left on device");
        }));
        require(writeAttempt.get(), "space exhaustion fixture did not reach capture write");
        try (var candidates = Files.newDirectoryStream(stages, ".barusikozako-view-*")) {
            require(!candidates.iterator().hasNext(), "failed live copy left staging files");
        }
        System.out.println("Live copy retries changed content and cleans up after cancellation.");
    }

    private static void crashDelta(String[] args) throws IOException {
        var root = Path.of(args[1]);
        require(Files.isRegularFile(root.resolve(".isolated-crash-fixture")), "wrong crash fixture");
        var snapshot = new Snapshot(UUID.fromString(args[5]), Instant.now(), "delta");
        var stop = args[6];
        var compression = new ZstdRepository.Compression(3, 1);
        try (var repository = ZstdRepository.open(root, directory -> {
            var name = directory.getFileName().toString();
            if (stop.equals("before") && name.startsWith(".pending-") ||
                    stop.equals("after") && name.equals("snapshots")) Runtime.getRuntime().halt(86);
        }, compression, ZstdRepository.WriteMode.DIFFERENTIAL, UUID.fromString(args[4]))) {
            var view = StagedDirectoryView.copy(Path.of(args[2]), Path.of(args[3]),
                    new StagedDirectoryView.CopyBudget(8L << 20, 100, 32), () -> {});
            new BackupEngine().backup(view, repository, snapshot, UUID.randomUUID(), LIMITS);
        }
    }

    private static void exerciseDeltaPublicationCrash(Path root, Path source, Path stage, Snapshot full)
            throws IOException {
        Files.writeString(root.resolve(".isolated-crash-fixture"), "fixture");
        var engine = new BackupEngine();
        var javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        for (String location : java.util.List.of("before", "after")) {
            UUID snapshot = UUID.randomUUID();
            var process = new ProcessBuilder(javaExecutable.toString(), "-cp", System.getProperty("java.class.path"),
                    StaticRoundTrip.class.getName(), "--halt-delta-publication", root.toString(), source.toString(),
                    stage.toString(), full.id().toString(), snapshot.toString(), location).inheritIO().start();
            try {
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("child publication crash timed out");
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("publication crash fixture interrupted");
            }
            require(process.exitValue() == 86, "child did not halt at the intended boundary");
            try (var repository = ZstdRepository.open(root)) {
                require(engine.verify(repository, full.id(), LIMITS).entries() == 4, "process crash damaged full baseline");
                if (location.equals("before")) expectFailure(() -> engine.verify(repository, snapshot, LIMITS));
                else require(engine.verify(repository, snapshot, LIMITS).entries() == 3, "visible delta lost verified content");
                try (var files = Files.newDirectoryStream(root, ".pending-*")) {
                    require(!files.iterator().hasNext(), "crashed publication left a pending snapshot");
                }
            }
        }
        StagedDirectoryView.cleanupAbandoned(stage);
    }

    private static void exerciseEarlyRollover(Path work, String policy, ZstdRepository.Rollover rollover, int seconds)
            throws IOException {
        var root = Files.createDirectory(work.resolve(policy + "-rollover-repo"));
        var source = Files.createDirectory(work.resolve(policy + "-rollover-source"));
        var stage = Files.createDirectory(work.resolve(policy + "-rollover-stage"));
        var file = source.resolve("content");
        var engine = new BackupEngine();
        var epoch = Instant.parse("2026-01-01T00:00:00Z");
        var budget = new StagedDirectoryView.CopyBudget(8L << 20, 100, 32);
        try (var repository = ZstdRepository.open(root, ignored -> {}, new ZstdRepository.Compression(3, 1),
                ZstdRepository.WriteMode.INCREMENTAL, null, rollover)) {
            var random = new java.util.Random(42);
            for (int i = 0; i < 3; i++) {
                byte[] contents = new byte[32 * 1024];
                random.nextBytes(contents);
                Files.write(file, contents);
                var point = new Snapshot(UUID.randomUUID(), epoch.plusSeconds(i == 2 ? seconds : i), policy);
                engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository,
                        point, UUID.randomUUID(), LIMITS);
                var metadata = root.resolve("snapshots").resolve(ZstdRepository.directoryName(point)).resolve(ZstdOverlay.INHERITED);
                require(Files.exists(metadata) == (i == 1), "wrong archive type for " + policy + " rollover");
                require(engine.verify(repository, point.id(), LIMITS).bytes() == contents.length, "rollover verification failed");
            }
        }
    }

    private static void exerciseChainLimit(Path work) throws IOException {
        final int chainLimit = 8;
        var root = Files.createDirectory(work.resolve("chain-repository"));
        var source = Files.createDirectory(work.resolve("chain-world"));
        var stage = Files.createDirectory(work.resolve("chain-stage"));
        var file = Files.writeString(source.resolve("stable"), "0");
        var full = new Snapshot(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"), "chain");
        var engine = new BackupEngine();
        var budget = new StagedDirectoryView.CopyBudget(8L << 20, 100, 32);
        try (var repository = ZstdRepository.open(root)) {
            engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository, full, UUID.randomUUID(), LIMITS);
        }
        var compression = new ZstdRepository.Compression(3, 1);
        try (var repository = ZstdRepository.open(root, ignored -> {}, compression, ZstdRepository.WriteMode.INCREMENTAL, null,
                new ZstdRepository.Rollover(chainLimit, 0, 0))) {
            for (int depth = 1; depth <= chainLimit; depth++) {
                Files.writeString(file, Integer.toString(depth));
                var snapshot = new Snapshot(UUID.randomUUID(), full.createdAt().plusSeconds(depth), full.source());
                engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository, snapshot,
                        UUID.randomUUID(), LIMITS);
                require(Files.isRegularFile(root.resolve("snapshots").resolve(ZstdRepository.directoryName(snapshot))
                        .resolve(ZstdOverlay.INHERITED)), "premature rollover at depth " + depth);
                require(engine.verify(repository, snapshot.id(), LIMITS).entries() == 1, "allowed chain depth did not verify");
            }
            var excess = new Snapshot(UUID.randomUUID(), full.createdAt().plusSeconds(chainLimit + 1L), full.source());
            var operation = UUID.randomUUID();
            engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository, excess,
                    operation, LIMITS);
            require(repository.isCommitted(excess.id(), operation), "independent checkpoint was not committed");
            require(!Files.exists(root.resolve("snapshots").resolve(ZstdRepository.directoryName(excess)).resolve(ZstdOverlay.INHERITED)),
                    "chain rollover wrote another delta");
            require(engine.verify(repository, excess.id(), LIMITS).entries() == 1, "checkpoint did not verify");
            var followup = new Snapshot(UUID.randomUUID(), excess.createdAt().plusSeconds(1), full.source());
            engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository, followup,
                    UUID.randomUUID(), LIMITS);
            require(Files.isRegularFile(root.resolve("snapshots").resolve(ZstdRepository.directoryName(followup)).resolve(ZstdOverlay.INHERITED)),
                    "incremental backup did not resume after rollover");
            require(engine.verify(repository, followup.id(), LIMITS).entries() == 1, "new chain did not verify");
        }
    }

    private static void exerciseFileModes(Path work) throws IOException {
        var repositoryPath = Files.createDirectory(work.resolve("delta-repository"));
        var source = Files.createDirectory(work.resolve("delta-source"));
        var stage = Files.createDirectory(work.resolve("delta-stage"));
        var dir = Files.createDirectories(source.resolve("region"));
        var retained = Files.writeString(dir.resolve("retained.mca"), "unchanged");
        var change = Files.writeString(dir.resolve("edit.mca"), "AAAA");
        var initialTime = Files.getLastModifiedTime(change);
        Files.writeString(dir.resolve("removed.mca"), "gone");
        var full = new Snapshot(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"), "delta");
        var first = new Snapshot(UUID.randomUUID(), full.createdAt().plusSeconds(1), full.source());
        var second = new Snapshot(UUID.randomUUID(), full.createdAt().plusSeconds(2), full.source());
        var differential = new Snapshot(UUID.randomUUID(), full.createdAt().plusSeconds(3), full.source());
        var engine = new BackupEngine();
        var budget = new StagedDirectoryView.CopyBudget(8L << 20, 100, 32);
        var compression = new ZstdRepository.Compression(3, 1);
        try (var repository = ZstdRepository.open(repositoryPath)) {
            engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository, full, UUID.randomUUID(), LIMITS);
        }
        Files.writeString(change, "BBBB");
        Files.setLastModifiedTime(change, initialTime);
        Files.delete(dir.resolve("removed.mca"));
        try (var repository = ZstdRepository.open(repositoryPath, ignored -> {}, compression, ZstdRepository.WriteMode.INCREMENTAL, null,
                new ZstdRepository.Rollover(8, 0, 0))) {
            engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository, first, UUID.randomUUID(), LIMITS);
            Files.writeString(change, "CCCC");
            engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository, second, UUID.randomUUID(), LIMITS);
        }
        try (var repository = ZstdRepository.open(repositoryPath, ignored -> {}, compression,
                ZstdRepository.WriteMode.DIFFERENTIAL, full.id())) {
            engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository, differential,
                    UUID.randomUUID(), LIMITS);
            var rejected = new Snapshot(UUID.randomUUID(), differential.createdAt().plusSeconds(1), full.source());
            expectFailure(() -> engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}), repository,
                    rejected, UUID.randomUUID(), LIMITS, () -> {}, () -> { throw new IOException("injected cancellation"); }));
            expectFailure(() -> engine.verify(repository, rejected.id(), LIMITS));
        }
        var noSpace = new Snapshot(UUID.randomUUID(), differential.createdAt().plusSeconds(1), full.source());
        try (var repository = ZstdRepository.open(repositoryPath, directory -> {
            if (directory.getFileName().toString().startsWith(".pending-"))
                throw new java.nio.file.FileSystemException(directory.toString(), null, "injected ENOSPC before publication");
        }, compression, ZstdRepository.WriteMode.DIFFERENTIAL, full.id())) {
            expectFailure(() -> engine.backup(StagedDirectoryView.copy(source, stage, budget, () -> {}),
                    repository, noSpace, UUID.randomUUID(), LIMITS));
            expectFailure(() -> engine.verify(repository, noSpace.id(), LIMITS));
            require(engine.verify(repository, differential.id(), LIMITS).entries() == 3, "disk fault damaged prior delta");
        }
        try (var repository = ZstdRepository.open(repositoryPath)) {
            require(repository.snapshots(Optional.empty(), 8, 1 << 20).entries().size() == 4, "deltas were not listed");
            for (var point : java.util.List.of(first, second, differential)) {
                require(engine.verify(repository, point.id(), LIMITS).entries() == 3, "wrong logical delta entry count");
                var restored = engine.prepareRestore(repository, point.id(), LIMITS, stage);
                require(Files.mismatch(retained, restored.resolve("region/retained.mca")) == -1, "inherited file changed");
                require(Files.readString(restored.resolve("region/edit.mca")).equals(point == first ? "BBBB" : "CCCC"),
                        "same-length file change was missed");
                require(Files.notExists(restored.resolve("region/removed.mca")), "removed entry returned");
                var container = repositoryPath.resolve("snapshots").resolve(ZstdRepository.directoryName(point));
                require(!Files.readString(container.resolve("manifest.jsonl")).contains("retained.mca"),
                        "unchanged file was added to delta payload");
            }
            expectFailure(() -> repository.deleteIndependentSnapshot(full.id(), () -> {}));
            expectFailure(() -> repository.deleteIndependentSnapshot(first.id(), () -> {}));
            var rebuilt = new Snapshot(UUID.randomUUID(), differential.createdAt().plusSeconds(2), full.source());
            repository.rebuild(second.id(), rebuilt, UUID.randomUUID(), new io.github.zct_studio.barusikozako.backend.Repository.Preparation(
                    LIMITS.contentBytes(), LIMITS.archiveBytes(), LIMITS.entries(), () -> {}), () -> {});
            repository.deleteIndependentSnapshot(second.id(), () -> {});
            repository.deleteIndependentSnapshot(first.id(), () -> {});
            repository.collectUnreferencedBlocks(100, 1 << 20, () -> {});
            require(engine.verify(repository, differential.id(), LIMITS).entries() == 3, "differential lost an independent point");
            var standalone = engine.prepareRestore(repository, rebuilt.id(), LIMITS, stage);
            require(Files.readString(standalone.resolve("region/edit.mca")).equals("CCCC"), "rebuilt point depended on deleted delta");
        }
        exerciseDeltaPublicationCrash(repositoryPath, source, stage, full);
        var reference = repositoryPath.resolve("snapshots").resolve(ZstdRepository.directoryName(differential)).resolve("inherited.jsonl");
        Files.writeString(reference, "\n", StandardOpenOption.APPEND);
        try (var repository = ZstdRepository.open(repositoryPath)) {
            expectFailure(() -> engine.verify(repository, differential.id(), LIMITS));
            expectFailure(() -> repository.collectUnreferencedBlocks(100, 1 << 20, () -> {}));
        }
        System.out.println("File delta, differential, same-length changes, removals and protected lineage passed.");
    }

    private static void exerciseChunks(Path work) throws IOException {
        Path repositoryPath = Files.createDirectory(work.resolve("chunk-repository"));
        Path source = Files.createDirectory(work.resolve("chunk-source"));
        Path staging = Files.createDirectory(work.resolve("chunk-staging"));
        byte[] original = new byte[192 * 1024 + 31];
        new Random(263).nextBytes(original);
        Path file = Files.write(source.resolve("region.mca"), original);
        Files.write(source.resolve("duplicate.mca"), original);
        Files.createFile(source.resolve("empty-file"));
        var first = new Snapshot(UUID.randomUUID(), Instant.now(), "chunk-fixture");
        var second = new Snapshot(UUID.randomUUID(), first.createdAt().plusSeconds(1), first.source());
        var full = new Snapshot(UUID.randomUUID(), first.createdAt().minusSeconds(1), first.source());
        var engine = new BackupEngine();
        var budget = new StagedDirectoryView.CopyBudget(8L << 20, 100, 32);
        var compression = new ZstdRepository.Compression(3, 1);
        var mode = ZstdRepository.WriteMode.EXPERIMENTAL_FIXED_CHUNKS;
        try (var repository = ZstdRepository.open(repositoryPath)) {
            engine.backup(StagedDirectoryView.copy(source, staging, budget, () -> {}), repository, full, UUID.randomUUID(), LIMITS);
        }
        try (var repository = ZstdRepository.open(repositoryPath, ignored -> {}, compression, mode)) {
            engine.backup(StagedDirectoryView.copy(source, staging, budget, () -> {}), repository, first, UUID.randomUUID(), LIMITS);
            require(blocks(repositoryPath).size() == 4, "equal files did not reuse their four chunks");
            byte[] changed = original.clone();
            changed[100] ^= 1;
            Files.write(file, changed);
            Files.delete(source.resolve("duplicate.mca"));
            engine.backup(StagedDirectoryView.copy(source, staging, budget, () -> {}), repository, second, UUID.randomUUID(), LIMITS);
            require(blocks(repositoryPath).size() == 5, "changed file stored more than one new chunk");
            require(repository.snapshots(Optional.empty(), 4, 1 << 20).entries().size() == 3, "mixed snapshots not listed");
            require(engine.verify(repository, first.id(), LIMITS).entries() == 3, "base verification failed");
            require(engine.verify(repository, second.id(), LIMITS).entries() == 2, "latest verification failed");
            Path restored = engine.prepareRestore(repository, second.id(), LIMITS, staging);
            require(Files.mismatch(file, restored.resolve("region.mca")) == -1, "chunk restore did not preserve bytes");
            require(Files.notExists(restored.resolve("duplicate.mca")), "removed file returned");

            var refused = new Snapshot(UUID.randomUUID(), second.createdAt().plusSeconds(1), first.source());
            changed[70_000] ^= 1;
            Files.write(file, changed);
            expectFailure(() -> engine.backup(StagedDirectoryView.copy(source, staging, budget, () -> {}), repository,
                    refused, UUID.randomUUID(), LIMITS, () -> {}, () -> { throw new IOException("commit denied"); }));
            require(blocks(repositoryPath).size() == 6, "failure injection did not leave a collectible orphan");
            expectFailure(() -> engine.verify(repository, refused.id(), LIMITS));
            Path index = repositoryPath.resolve("snapshots").resolve(ZstdRepository.directoryName(second)).resolve("chunks.idx");
            byte[] validIndex = Files.readAllBytes(index);
            byte[] invalidIndex = validIndex.clone();
            invalidIndex[0] ^= 1;
            Files.write(index, invalidIndex);
            expectFailure(() -> repository.collectUnreferencedBlocks(100, 1 << 20, () -> {}));
            require(blocks(repositoryPath).size() == 6, "invalid mark phase reclaimed blocks");
            Files.write(index, validIndex);
            expectFailure(() -> repository.collectUnreferencedBlocks(1, 1 << 20, () -> {}));
            require(blocks(repositoryPath).size() == 6, "incomplete mark phase reclaimed blocks");
            repository.deleteIndependentSnapshot(first.id(), () -> {});
            expectFailure(() -> repository.collectUnreferencedBlocks(100, 1 << 20, () -> {
                if (blocks(repositoryPath).size() < 6) throw new InterruptedIOException("interrupt after first orphan unlink");
            }));
            require(blocks(repositoryPath).size() == 5, "GC interruption did not leave one orphan for later cleanup");
            require(engine.verify(repository, second.id(), LIMITS).bytes() == original.length, "interrupted GC damaged a live point");
            require(repository.collectUnreferencedBlocks(100, 1 << 20, () -> {}) > 0, "remaining orphan not reclaimed");
            require(blocks(repositoryPath).size() == 4, "reclaim did not preserve exactly the live chunks");
        }
        // 默认打开仍然读取分块点并保持其正常写入器格式 1
        try (var repository = ZstdRepository.open(repositoryPath)) {
            require(engine.verify(repository, second.id(), LIMITS).bytes() == original.length, "delete damaged surviving snapshot");
            require(engine.verify(repository, full.id(), LIMITS).bytes() == original.length * 2L, "chunk activity damaged full TAR");
            expectFailure(() -> engine.verify(repository, first.id(), LIMITS));
            Path target = blocks(repositoryPath).get(0);
            byte[] block = Files.readAllBytes(target);
            Files.write(target, new byte[] {1, 2, 3});
            expectFailure(() -> engine.verify(repository, second.id(), LIMITS));
            Files.write(target, block);
            require(engine.verify(repository, second.id(), LIMITS).bytes() == original.length, "repair did not re-enable verification");
        }
        boolean[] entered = {false};
        try (var repository = ZstdRepository.open(repositoryPath, directory -> {
            if (entered[0] && directory.getFileName().toString().equals("snapshots"))
                throw new IOException("injected directory force failure after removal");
        })) {
            try {
                repository.deleteIndependentSnapshot(full.id(), () -> entered[0] = true);
                throw new AssertionError("removal durability failure was reported as success");
            } catch (ZstdRepository.DeletionUncertain expected) {
                // 维护人员必须在回收之前解决发布持久性问题
            }
        }
        Path tombstone = repositoryPath.resolve(".deleted-" + full.id());
        try (var repository = ZstdRepository.open(repositoryPath)) {
            require(Files.isRegularFile(tombstone.resolve("data.tar.zst")), "query open destroyed an uncertain removal");
            expectFailure(() -> repository.prepare(full, UUID.randomUUID()));
            require(engine.verify(repository, second.id(), LIMITS).bytes() == original.length, "removal failure damaged another point");
        }
        try (var repository = ZstdRepository.open(repositoryPath, ignored -> {})) {
            repository.collectUnreferencedBlocks(100, 1 << 20, () -> {});
            require(Files.notExists(tombstone), "maintenance did not clean the reconciled removal");
            require(engine.verify(repository, second.id(), LIMITS).bytes() == original.length, "reconciled removal lost live blocks");
        }
        System.out.println("Experimental chunk sharing, complete verification, restore, cancellation and collection checks passed.");
    }

    private static java.util.List<Path> blocks(Path repository) throws IOException {
        try (var files = Files.walk(repository.resolve("blocks-v2"))) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    private static void exerciseTarMetadataGuard() throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var archive = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bytes, "UTF-8")) {
            var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("regular.txt");
            entry.addPaxHeader("comment", "field outside the snapshot TAR profile");
            archive.putArchiveEntry(entry);
            archive.closeArchiveEntry();
        }
        try (var guarded = new GuardedTarInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            expectFailure(guarded::getNextEntry);
        }
    }

    private static void exercise(Path work) throws IOException {
        var source = Files.createDirectory(work.resolve("source"));
        var directory = Files.createDirectory(source.resolve("世界"));
        Files.createDirectory(source.resolve("empty-directory"));
        Files.createFile(source.resolve("empty-file"));
        var file = directory.resolve("区".repeat(42) + ".bin");
        var content = new byte[(1 << 20) + 31];
        new Random(42).nextBytes(content);
        Files.write(file, content);
        var snapshots = Files.createDirectory(work.resolve("repository"));
        var restoreParent = Files.createDirectory(work.resolve("restore"));
        var deadCapture = Files.createDirectory(restoreParent.resolve(".barusikozako-view-1"));
        Files.writeString(deadCapture.resolve("partial"), "abandoned capture");
        StagedDirectoryView.cleanupAbandoned(restoreParent);
        require(countChildren(restoreParent) == 0, "exclusive staging recovery did not remove the abandoned capture");
        interruptStagedCopy(source, restoreParent, file, content);
        var snapshot = new Snapshot(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"), "static-fixture",
                Optional.of(new Snapshot.Trigger("test-integration", "before edit", java.util.Map.of("area", "overworld"))));
        var operation = UUID.randomUUID();
        var engine = new BackupEngine();
        try (var repository = ZstdRepository.open(snapshots)) {
            expectFailure(() -> {
                try (var second = ZstdRepository.open(snapshots)) {
                    throw new AssertionError("a second repository lock was acquired");
                }
            });
            Files.writeString(source.resolve("session.lock"), "fixture world lock");
            var staged = StagedDirectoryView.copy(source, restoreParent,
                    new StagedDirectoryView.CopyBudget(8L << 20, 100, 32), () -> {});
            Files.writeString(file, "live source changed after copy");
            engine.backup(staged, repository, snapshot, operation, LIMITS);
            Files.write(file, content);
            Files.delete(source.resolve("session.lock"));
            require(countChildren(restoreParent) == 0, "staged view leaked after backup");
            expectFailure(() -> engine.backup(new FixtureView(source, false), repository, snapshot,
                    UUID.randomUUID(), LIMITS));
            var interrupted = new Snapshot(UUID.randomUUID(), snapshot.createdAt(), snapshot.source());
            var interruptedOperation = UUID.randomUUID();
            expectFailure(() -> engine.backup(new FixtureView(source, true), repository, interrupted,
                    interruptedOperation, LIMITS));
            require(!repository.isCommitted(interrupted.id(), interruptedOperation), "partial write was committed");
            require(countChildren(snapshots) == 2, "partial write leaked a staging directory");
            var denied = new Snapshot(UUID.randomUUID(), snapshot.createdAt(), snapshot.source());
            var deniedOperation = UUID.randomUUID();
            expectFailure(() -> engine.backup(new FixtureView(source, false), repository, denied, deniedOperation,
                    LIMITS, () -> {}, () -> { throw new IOException("commit forbidden"); }));
            require(!repository.isCommitted(denied.id(), deniedOperation), "denied operation published a snapshot");
            require(countChildren(snapshots) == 2, "denied operation leaked staging");
            var older = new Snapshot(UUID.randomUUID(), snapshot.createdAt().minusSeconds(60), snapshot.source());
            engine.backup(new FixtureView(source, false), repository, older, UUID.randomUUID(), LIMITS);
            var firstPage = repository.snapshots(Optional.empty(), 1, 1 << 20);
            var secondPage = repository.snapshots(firstPage.next(), 1, 1 << 20);
            require(firstPage.entries().size() == 1 && firstPage.next().isPresent() &&
                    secondPage.entries().size() == 1 && secondPage.next().isEmpty() &&
                    !firstPage.entries().equals(secondPage.entries()), "committed snapshot pages are inconsistent");
            require(firstPage.entries().contains(snapshot) || secondPage.entries().contains(snapshot),
                    "history lost original snapshot metadata");
        }
        exerciseUncertainPublication(work, source);
        var published = snapshots.resolve("snapshots").resolve(ZstdRepository.directoryName(snapshot));
        var abandoned = Files.createDirectory(snapshots.resolve(".pending-" + UUID.randomUUID() + "-1"));
        Files.writeString(abandoned.resolve("manifest.jsonl"), "interrupted stage");
        try (var repository = ZstdRepository.open(snapshots)) {
            require(!Files.exists(abandoned), "exclusive reopen did not clear an abandoned pending backup");
            require(repository.isCommitted(snapshot.id(), operation), "published commit was not found on reopen");
            var verified = engine.verify(repository, snapshot.id(), LIMITS);
            require(verified.entries() == 4 && verified.bytes() == content.length, "unexpected verification counts");
            var output = engine.prepareRestore(repository, snapshot.id(), LIMITS, restoreParent);
            require(Files.mismatch(file, output.resolve(source.relativize(file))) == -1, "restored content differs");
            require(Files.isDirectory(output.resolve("empty-directory")), "empty directory was lost");
            require(Files.size(output.resolve("empty-file")) == 0, "empty file was lost");
            exerciseSwitch(engine, repository, snapshot, work, file, source.relativize(file));

            expectFailure(() -> engine.prepareRestore(repository, snapshot.id(),
                    new BackupEngine.Limits(32, LIMITS.archiveBytes(), LIMITS.entries()), restoreParent));
            require(countChildren(restoreParent) == 1, "failed budget check leaked a stage");
            var manifest = published.resolve("manifest.jsonl");
            var original = Files.readAllBytes(manifest);
            byte[] changed = new byte[original.length + 1];
            changed[0] = ' ';
            System.arraycopy(original, 0, changed, 1, original.length);
            Files.write(manifest, changed);
            expectFailure(() -> engine.prepareRestore(repository, snapshot.id(), LIMITS, restoreParent));
            require(countChildren(restoreParent) == 1, "failed manifest check leaked a stage");
            Files.write(manifest, original);

            Files.write(published.resolve("data.tar.zst"), new byte[] {42}, StandardOpenOption.APPEND);
            expectFailure(() -> engine.verify(repository, snapshot.id(), LIMITS));
            require(Files.mismatch(file, output.resolve(source.relativize(file))) == -1,
                    "failed verification altered an earlier output");
        }
    }

    private static void interruptStagedCopy(Path source, Path staging, Path file, byte[] content) throws IOException {
        try (var unexpected = StagedDirectoryView.copy(source, staging, new StagedDirectoryView.CopyBudget(8L << 20, 100, 32),
                () -> {
                    try (var candidates = Files.newDirectoryStream(staging)) {
                        for (var stage : candidates) {
                            var target = stage.resolve(source.relativize(file));
                            if (Files.isRegularFile(target)) {
                                long written = Files.size(target);
                                if (written > 0 && written < content.length) throw new InterruptedIOException("fixture cancelled a partial copy");
                            }
                        }
                    }
                })) {
            throw new AssertionError("the checkpoint did not stop a partial staged copy");
        } catch (InterruptedIOException stopped) {
            require(countChildren(staging) == 0, "partial copy remained after cancellation");
            require(java.util.Arrays.equals(content, Files.readAllBytes(file)), "partial stage cleanup modified the source");
        }
    }

    private static void exerciseUncertainPublication(Path work, Path source) throws IOException {
        var path = Files.createDirectory(work.resolve("publication-durability"));
        var snapshot = new Snapshot(UUID.randomUUID(), Instant.now(), "durability-fixture");
        var operation = UUID.randomUUID();
        try (var repository = ZstdRepository.open(path, directory -> {
            if (directory.getFileName().toString().equals("snapshots")) throw new IOException("directory flush failed");
        })) {
            var engine = new BackupEngine();
            try {
                engine.backup(new FixtureView(source, false), repository, snapshot, operation, LIMITS);
                throw new AssertionError("post-move flush failure was reported as a confirmed commit");
            } catch (ZstdRepository.PublicationUncertain expected) {
                require(repository.isCommitted(snapshot.id(), operation), "the visible record could not be reconciled");
                require(engine.verify(repository, snapshot.id(), LIMITS).entries() == 4,
                        "post-move flush failure altered the published data");
            }
        }
    }

    private static void exerciseSwitch(BackupEngine engine, ZstdRepository repository, Snapshot snapshot, Path work,
                                       Path sourceFile, Path relativeFile) throws IOException {
        var exclusiveTestRoot = Files.createDirectory(work.resolve("controlled-world-parent")).toRealPath();
        var world = Files.createDirectory(exclusiveTestRoot.resolve("world"));
        Files.writeString(world.resolve("old-marker"), "original");
        try (var window = new RestoreWindow() {
            @Override
            public Path target() {
                return world;
            }

            @Override
            public void requireExclusiveAccess() {
                // 该装置拥有临时父级并且没有竞争的世界加载器
            }

            @Override
            public void close() {}
        }) {
            RestoreResult outcome;
            try {
                outcome = engine.restore(repository, snapshot.id(), UUID.randomUUID(), LIMITS, window);
            } catch (IOException unavailable) {
                if (!unavailable.getMessage().startsWith("filesystem cannot confirm directory persistence: " +
                        exclusiveTestRoot)) throw unavailable;
                require(Files.readString(world.resolve("old-marker")).equals("original"),
                        "unsupported filesystem changed the original world");
                require(countChildren(exclusiveTestRoot) == 1, "unsupported filesystem opened a restore transaction");
                System.out.println("Static Zstd switch refused before any modification: directory persistence unavailable.");
                return;
            }
            require(outcome.status() == RestoreResult.Status.RESTORED, "verified world was not installed");
            require(Files.mismatch(sourceFile, world.resolve(relativeFile)) == -1, "installed world bytes differ");
            require(Files.readString(outcome.previousWorld().orElseThrow().resolve("old-marker")).equals("original"),
                    "restore discarded the original world");
            require(engine.recoverRestore(window).status() == RestoreResult.Status.NO_PENDING_RESTORE,
                    "completed restore remains pending");
            System.out.println("Static Zstd archive verified and installed; previous world retained.");
        }
    }

    private static long countChildren(Path root) throws IOException {
        try (var paths = Files.list(root)) {
            return paths.count();
        }
    }

    private static void expectFailure(Checked operation) throws IOException {
        try {
            operation.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("operation unexpectedly succeeded");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws IOException;
    }

    private record FixtureView(Path root, boolean wrongLength) implements ReadView {
        @Override
        public Session openSession() throws IOException {
            try (var paths = Files.walk(root)) {
                var files = paths.filter(path -> !path.equals(root)).sorted().toList().iterator();
                return new Session() {
                    private Path current;

                    @Override
                    public Item next() throws IOException {
                        if (!files.hasNext()) return null;
                        current = files.next();
                        boolean directory = Files.isDirectory(current);
                        var names = new java.util.ArrayList<String>();
                        for (var part : root.relativize(current)) names.add(part.toString());
                        return new Item(names, directory, directory ? 0 : Files.size(current) + (wrongLength ? 1 : 0),
                                Files.getLastModifiedTime(current).toInstant());
                    }

                    @Override
                    public InputStream openContent() throws IOException {
                        return Files.newInputStream(current);
                    }

                    @Override
                    public void close() {}
                };
            }
        }

        @Override
        public void close() {}
    }
}
