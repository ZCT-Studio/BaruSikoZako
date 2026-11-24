// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import io.github.zct_studio.barusikozako.platform.windows.WindowsDirectorySync;

import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.RestoreResult;
import io.github.zct_studio.barusikozako.platform.RestoreWindow;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 在世界加载期间保持合作锁，包括其名称可能不存在的时间间隔 */
public final class WorldStartupGate implements RestoreWindow {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private static WorldStartupGate lifetime;
    private static WorldStartupGate clientLock;
    private static final Map<Path, RestoreNotice> pendingNotices = new HashMap<>();
    private final Path target;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean offline = true;

    private WorldStartupGate(Path target, FileChannel channel, FileLock lock) {
        this.target = target;
        this.channel = channel;
        this.lock = lock;
    }

    /** 在专用启动器提供通向香草的世界路径之前调用 */
    public static synchronized void beforeWorldAccess(Path requestedTarget) throws IOException {
        if (lifetime != null) throw new IOException("Repeated world access in dedicated startup");
        var path = requestedTarget.toAbsolutePath().normalize();
        if (path.getParent() == null) throw new IOException("Missing world parent");
        var parent = path.getParent().toRealPath();
        var gate = acquire(parent.resolve(path.getFileName()), parent.resolve(".barusikozako-worlds.lock"));
        try {
            gate.restoreBeforeAccess(true);
            lifetime = gate;
        } catch (IOException | RuntimeException failure) {
            try {
                gate.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw new IOException("BaruSikoZako could not establish safe world startup", failure);
        }
    }

    public static synchronized void beforeClientWorldAccess(Path saves, String name) throws IOException {
        var configuredSaves = LoaderBridge.configDirectory().toAbsolutePath().normalize().resolveSibling("saves");
        if (!saves.toAbsolutePath().normalize().equals(configuredSaves)) return;
        var relative = saves.getFileSystem().getPath(name);
        if (relative.getRoot() != null || relative.getNameCount() != 1 || name.isEmpty() || name.equals(".") || name.equals(".."))
            throw new IOException("Invalid singleplayer world name");
        var parent = saves.toRealPath();
        if (clientLock == null) {
            clientLock = acquire(parent, parent.resolve(".barusikozako-worlds.lock"));
        }
        var target = parent.resolve(relative);
        clientWindow(target).restoreBeforeAccess(false);
    }

    static synchronized boolean canRestoreClientWorld(Path world) {
        return clientLock != null && clientLock.lock.isValid() && clientLock.target.equals(world.getParent());
    }

    record RestoreNotice(Optional<Instant> snapshotTime) {}

    static synchronized RestoreNotice takeRestoreNotice(Path world) {
        if (pendingNotices.isEmpty()) return null;
        var notice = pendingNotices.remove(world.toAbsolutePath().normalize());
        if (notice != null) return notice;
        try {
            return pendingNotices.remove(world.toRealPath());
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "Could not resolve world path for the restore notice", failure);
            return null;
        }
    }

    private static WorldStartupGate clientWindow(Path world) throws IOException {
        if (!canRestoreClientWorld(world)) throw new IOException("Singleplayer save directory is not locked");
        return new WorldStartupGate(world, clientLock.channel, clientLock.lock);
    }

    private void restoreBeforeAccess(boolean dedicated) throws IOException {
        requireUnaliasedWorld(target);
        var engine = new BackupEngine();
        var request = dedicated ? RestoreRequest.read(target) : RestoreRequest.readQueued(target);
        boolean pending = engine.hasPendingRestore(this);
        RestoreNotice notice = null;
        UUID recoveredOperation = null;
        if (pending || request != null) {
            // Vanilla 的补充锁可以检测已经运行的未修改的进程
            // 当世界存在时。在整个操作过程中，未经修改的服务器仍必须保留在外面
            try (var session = windows() ? null : existingSessionLock(target)) {
                if (pending) {
                    var result = engine.recoverRestore(this);
                    if (result.status() == RestoreResult.Status.RESTORED) {
                        notice = new RestoreNotice(Optional.empty());
                        recoveredOperation = result.operationId().orElse(null);
                    }
                    LOG.log(System.Logger.Level.INFO, "Startup restore recovery: {0}", result.status());
                }
                if (request != null) {
                    var config = ServerConfig.load(false);
                    var keyword = request.backend == null ? config.backendKeyword() : request.backend;
                    var location = ServerRepository.checkPath(ServerRepository.forBackend(keyword, target), target);
                    var backend = BackendRegistry.configure(keyword, false);
                    var snapshotRef = new io.github.zct_studio.barusikozako.runtime.SnapshotRef(FabricOperations.REPOSITORY_ID, request.snapshot.toString());
                    var hookContext = new io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Context(
                        io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Operation.OFFLINE_RESTORE,
                        FabricOperations.REPOSITORY_ID, java.util.Optional.of(snapshotRef));
                    LifecycleHooks.beforeStartupRestore(config.lifecycleIntegrations(), hookContext);
                    try (var repository = backend.open(location, ServerRepository::forceDirectory, "FULL", null)) {
                        long restoreStarted = System.nanoTime();
                        var prepared = new java.util.concurrent.atomic.AtomicBoolean();
                        var result = engine.restoreWorld(repository, request.snapshot, request.operation,
                            config.readLimits(), this, false, stage -> {
                                checkWorldStage(stage);
                                prepared.set(true);
                            });
                        if (result.status() != RestoreResult.Status.RESTORED)
                            throw new IOException("Restore request was previously rolled back: " + request.operation);
                        // 已完成的 JVM 启动参数可能在后续启动仍存在
                        if (request.backend != null || prepared.get() || request.operation.equals(recoveredOperation)) {
                            Optional<Instant> snapshotTime = Optional.empty();
                            try {
                                snapshotTime = repository.snapshotCreatedAt(request.snapshot, config.manifestBudget());
                            } catch (IOException | RuntimeException failure) {
                                LOG.log(System.Logger.Level.WARNING, "Could not read restored snapshot creation time", failure);
                            }
                            notice = new RestoreNotice(snapshotTime);
                        }
                        if (request.backend != null) {
                            Files.delete(queuedRequest(target));
                            ServerRepository.forceDirectory(target.getParent());
                        }
                        LifecycleHooks.after(config.lifecycleIntegrations(), hookContext,
                            new io.github.zct_studio.barusikozako.runtime.TaskResult.Restored(snapshotRef, FabricOperations.SOURCE_ID));
                        LOG.log(System.Logger.Level.INFO, "Offline restore committed for {0}; original world: {1}",
                            request.snapshot, result.previousWorld().orElseThrow());
                        LOG.log(System.Logger.Level.INFO, "Offline verification and switch elapsed {0} ms",
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - restoreStarted));
                    }
                }
            }
        }
        if (notice != null) pendingNotices.put(target, notice);
        offline = false;
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
            // 新世界或中断的切换可能还没有目标目录
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
            // 当 session.lock 文件打开时，NTFS 无法重命名世界。父锁
            // 始终保持保留状态；普通锁是非合作实例的预检
            try (var session = existingSessionLock(target)) {
                // 在事务的目录操作中不保留子句柄
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
                                          io.github.zct_studio.barusikozako.backend.Repository.CommitGate commit) throws IOException {
        if ((lifetime == null || lifetime.offline || !lifetime.lock.isValid() || !lifetime.target.equals(world))
            && !canRestoreClientWorld(world))
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
                throw new io.github.zct_studio.barusikozako.backend.ManagedRepository.CommitUncertain("Restore request is visible; durability is uncertain", failure);
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
