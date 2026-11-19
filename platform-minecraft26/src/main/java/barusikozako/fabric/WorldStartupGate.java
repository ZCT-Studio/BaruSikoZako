// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.fabric;

import barusikozako.core.BackupEngine;
import barusikozako.core.RestoreResult;
import barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** Holds the cooperation lock across world loading, including the interval where its name may be absent. */
public final class WorldStartupGate implements RestoreWindow {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private static WorldStartupGate lifetime;
    private final Path target;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean offline = true;

    private WorldStartupGate(Path target, FileChannel channel, FileLock lock) {
        this.target = target;
        this.channel = channel;
        this.lock = lock;
    }

    /** Invoked before the dedicated launcher gives the world path to vanilla. */
    public static synchronized void beforeWorldAccess(Path requestedTarget) throws IOException {
        if (lifetime != null) throw new IOException("Repeated world access in dedicated startup");
        var path = requestedTarget.toAbsolutePath().normalize();
        if (path.getParent() == null) throw new IOException("Missing world parent");
        var parent = path.getParent().toRealPath();
        var gate = acquire(parent.resolve(path.getFileName()), parent.resolve(".barusikozako-worlds.lock"));
        try {
            requireUnaliasedWorld(gate.target);
            var engine = new BackupEngine();
            var request = RestoreRequest.read(gate.target);
            boolean pending = engine.hasPendingRestore(gate);
            if (pending || request != null) {
                // Vanilla's supplementary lock can detect an already running unmodified process
                // while the world exists. Unmodified servers must still be kept out for the whole operation.
                try (var session = gate.windows() ? null : existingSessionLock(gate.target)) {
                    if (pending) {
                        var result = engine.recoverRestore(gate);
                        LOG.log(System.Logger.Level.INFO, "Startup restore recovery: {0}", result.status());
                    }
                    if (request != null) {
                        var config = ServerConfig.load(false);
                        var keyword = request.backend == null ? config.backendKeyword() : request.backend;
                        var location = ServerRepository.checkPath(ServerRepository.forBackend(keyword), gate.target);
                        var backend = BackendRegistry.configure(keyword, false);
                        var snapshotRef = new barusikozako.api.SnapshotRef(FabricOperations.REPOSITORY_ID, request.snapshot.toString());
                        var hookContext = new barusikozako.api.BackupLifecycleHook.Context(
                            barusikozako.api.BackupLifecycleHook.Operation.OFFLINE_RESTORE,
                            FabricOperations.REPOSITORY_ID, java.util.Optional.of(snapshotRef));
                        LifecycleHooks.beforeStartupRestore(config.lifecycleIntegrations(), hookContext);
                        try (var repository = backend.open(location, ServerRepository::forceDirectory, "FULL", null)) {
                            long restoreStarted = System.nanoTime();
                            var result = engine.restore(repository, request.snapshot, request.operation,
                                config.readLimits(), gate, WorldStartupGate::checkWorldStage);
                            if (result.status() != RestoreResult.Status.RESTORED)
                                throw new IOException("Restore request was previously rolled back: " + request.operation);
                            if (request.backend != null) {
                                Files.delete(queuedRequest(gate.target));
                                ServerRepository.forceDirectory(gate.target.getParent());
                            }
                            LifecycleHooks.after(config.lifecycleIntegrations(), hookContext,
                                new barusikozako.api.TaskResult.Restored(snapshotRef, FabricOperations.SOURCE_ID));
                            LOG.log(System.Logger.Level.INFO, "Offline restore committed for {0}; original world: {1}",
                                request.snapshot, result.previousWorld().orElseThrow());
                            LOG.log(System.Logger.Level.INFO, "Offline verification and switch elapsed {0} ms",
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - restoreStarted));
                        }
                    }
                }
            }
            gate.offline = false;
            lifetime = gate; // Keep the OS lock until this dedicated process exits.
        } catch (IOException | RuntimeException failure) {
            try {
                gate.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw new IOException("BaruSikoZako could not establish safe world startup", failure);
        }
    }

    private static void checkWorldStage(Path stage) throws IOException {
        var levelDat = stage.resolve("level.dat");
        if (!Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Snapshot is missing world level.dat");
        try (var input = Files.newInputStream(levelDat, LinkOption.NOFOLLOW_LINKS)) {
            WorldLevelMetadata.check(input);
        } catch (RuntimeException failure) {
            throw new IOException("Snapshot world metadata could not be validated", failure);
        }
    }

    private static void requireUnaliasedWorld(Path target) throws IOException {
        try {
            var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || !target.toRealPath().equals(target))
                throw new IOException("World startup requires a real world directory: " + target);
        } catch (NoSuchFileException absent) {
            // A new world or an interrupted switch may legitimately have no target directory yet.
        }
    }

    private static WorldStartupGate existingSessionLock(Path world) throws IOException {
        try {
            var path = world.resolve("session.lock");
            return acquire(world, path, false);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    private static WorldStartupGate acquire(Path target, Path lockPath) throws IOException {
        return acquire(target, lockPath, true);
    }

    private static WorldStartupGate acquire(Path target, Path lockPath, boolean create) throws IOException {
        var channel = create
            ? FileChannel.open(lockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE, LinkOption.NOFOLLOW_LINKS)
            : FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("World storage is already in use: " + lockPath);
            return new WorldStartupGate(target, channel, lock);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            if (failure instanceof OverlappingFileLockException)
                throw new IOException("World storage is already in use: " + lockPath, failure);
            throw failure;
        }
    }

    @Override
    public Path target() {
        return target;
    }

    @Override
    public void requireExclusiveAccess() throws IOException {
        if (!offline || !lock.isValid()) throw new IOException("World is not in an exclusive offline startup window");
        if (windows()) {
            // NTFS cannot rename the world while its session.lock file is open. The parent lock
            // remains held throughout; the vanilla lock is a preflight for non-cooperating instances.
            try (var session = existingSessionLock(target)) {
                // Hold no child handle across the transaction's directory operations.
            }
        }
    }

    @Override
    public boolean sameFileStore(Path first, Path second) throws IOException {
        if (windows()) return WindowsDirectorySync.volume(first).equalsIgnoreCase(WindowsDirectorySync.volume(second));
        return RestoreWindow.super.sameFileStore(first, second);
    }

    @Override
    public void forceDirectory(Path directory) throws IOException {
        ServerRepository.forceDirectory(directory);
    }

    private boolean windows() {
        return target.getFileSystem().getSeparator().equals("\\");
    }

    @Override
    public void close() throws IOException {
        try (channel; lock) {
            offline = false;
        }
    }

    private static Path queuedRequest(Path world) {
        return world.resolveSibling("." + world.getFileName() + ".barusikozako-restore");
    }

    static boolean hasQueuedRestore(Path world, String backend, UUID snapshot) throws IOException {
        var request = RestoreRequest.readQueued(world);
        return request != null && request.backend.equals(backend) && request.snapshot.equals(snapshot);
    }

    static synchronized void queueRestore(Path world, String backend, UUID snapshot,
                                          barusikozako.backend.Repository.CommitGate commit) throws IOException {
        if (lifetime == null || lifetime.offline || !lifetime.lock.isValid() || !lifetime.target.equals(world))
            throw new IOException("Restore scheduling requires the active world's cooperation lock");
        var file = queuedRequest(world);
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("A restore is already queued for this world");
        ServerRepository.forceDirectory(file.getParent());
        var stage = Files.createTempFile(file.getParent(), ".barusikozako-restore-", ".tmp");
        try {
            var data = java.nio.charset.StandardCharsets.UTF_8.encode(backend + ":" + snapshot + ":" + UUID.randomUUID());
            try (var output = FileChannel.open(stage, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                while (data.hasRemaining()) output.write(data);
                output.force(true);
            }
            commit.enter();
            Files.move(stage, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            try {
                ServerRepository.forceDirectory(file.getParent());
            } catch (IOException failure) {
                throw new barusikozako.backend.ManagedRepository.CommitUncertain("Restore request is visible; durability is uncertain", failure);
            }
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private record RestoreRequest(UUID snapshot, UUID operation, String backend) {
        private static RestoreRequest read(Path world) throws IOException {
            var queued = readQueued(world);
            var input = System.getProperty("barusikozako.restore");
            if (input == null) return queued;
            if (queued != null) throw new IOException("A restore is already queued; remove the JVM restore override");
            return parse(input, false);
        }

        private static RestoreRequest readQueued(Path world) throws IOException {
            try (var input = Files.newInputStream(queuedRequest(world), LinkOption.NOFOLLOW_LINKS)) {
                var bytes = input.readNBytes(257);
                if (bytes.length > 256) throw new IOException("Invalid queued restore length");
                return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), true);
            } catch (NoSuchFileException absent) {
                return null;
            }
        }

        private static RestoreRequest parse(String input, boolean queued) throws IOException {
            var parts = input.split(":", -1);
            try {
                int offset = queued ? 1 : 0;
                if (parts.length != offset + 2 || (queued && !parts[0].matches("[a-z][a-z0-9_-]{1,63}")))
                    throw new IllegalArgumentException("invalid restore request");
                return new RestoreRequest(FabricOperations.parseId(parts[offset]), FabricOperations.parseId(parts[offset + 1]),
                    queued ? parts[0] : null);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Restore requires canonical snapshot and operation UUIDs", invalid);
            }
        }
    }
}
