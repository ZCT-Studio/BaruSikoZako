// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent;

import io.github.zct_studio.barusikozako.backend.Snapshot;
import io.github.zct_studio.barusikozako.backend.BackendProvider;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.BackupExclusions;
import io.github.zct_studio.barusikozako.core.RestoreResult;
import io.github.zct_studio.barusikozako.core.StagedDirectoryView;
import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.zip.GZIPInputStream;

/** 适用于由此启动器管理完整世界目录的服务端的可选离线入口 */
public final class AgentStartup {
    private static final BackupEngine.Limits LIMITS = new BackupEngine.Limits(256L << 30, 300L << 30, 2_000_000);
    private static final String SOURCE = "vanilla-world-container";
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static StartupWindow runningServer;

    private AgentStartup() {}

    public static void run(String[] args, String repositoryName, BackendSelection backend,
                           BackupEngine.Checkpoint beforeRestore, BackupEngine.Checkpoint afterRestore) throws Exception {
        run(args, repositoryName, backend, beforeRestore, afterRestore, () -> {});
    }

    public static void run(String[] args, String repositoryName, BackendSelection backend,
                           BackupEngine.Checkpoint beforeRestore, BackupEngine.Checkpoint afterRestore,
                           BackupEngine.Checkpoint beforeLaunch) throws Exception {
        run(args, repositoryName, backend, beforeRestore, afterRestore, beforeLaunch,
            RestoreWindow::forceLocalDirectory);
    }

    public static void run(String[] args, String repositoryName, BackendSelection backend,
                           BackupEngine.Checkpoint beforeRestore, BackupEngine.Checkpoint afterRestore,
                           BackupEngine.Checkpoint beforeLaunch, DirectoryPersistence persistence) throws Exception {
        boolean backup = (args.length == 1 || args.length == 2) && args[0].equals("backup-offline");
        boolean start = args.length == 2 && args[0].equals("start");
        boolean restore = args.length == 4 && args[0].equals("restore-start");
        if (!backup && !start && !restore)
            throw new IllegalArgumentException("Use backup-offline [full|incremental], start <server.jar>, or restore-start <server.jar> <snapshot UUID> <operation UUID>");
        String mode = backup && args.length == 2 ? args[1].toUpperCase(Locale.ROOT) : "FULL";
        if (!mode.equals("FULL") && !mode.equals("INCREMENTAL"))
            throw new IllegalArgumentException("Offline backup mode must be full or incremental");

        Path home = Path.of(".").toRealPath();
        Path worlds = home.resolve("worlds");
        if (!repositoryName.matches("barusikozako/worlds/[a-z][a-z0-9_-]{1,63}"))
            throw new IOException("Invalid offline repository name");
        Path repositoryPath = home.resolve(repositoryName);
        Path jar = backup ? null : Path.of(args[1]).toRealPath();
        UUID snapshot = restore ? UUID.fromString(args[2]) : null;
        UUID operation = restore ? UUID.fromString(args[3]) : null;
        String entryPoint = backup ? null : entryPoint(jar);
        var core = new BackupEngine();

        var window = new StartupWindow(worlds, persistence);
        try {
            boolean pending = core.hasPendingRestore(window);
            // 安全启动可以在没有目录刷盘的情况下继续，写入和恢复则需要目录刷盘
            if (pending || backup || restore) window.forceDirectory(home);
            if (pending) {
                var recovered = core.recoverRestore(window);
                System.out.println("BaruSikoZako recovered interrupted operation: " + recovered.status());
                if (recovered.status() == RestoreResult.Status.ROLLED_BACK)
                    throw new IOException("Interrupted operation returned the original world. Review it before starting again.");
            }
            if (start && Files.notExists(worlds, NOFOLLOW)) {
                Files.createDirectory(worlds);
            }
            if (!start || !emptyContainer(worlds)) validateContainer(worlds);
            if (backup || restore) {
                if (backup && Files.notExists(repositoryPath, NOFOLLOW)) Files.createDirectories(repositoryPath);
                if (!Files.isDirectory(repositoryPath, NOFOLLOW) || !repositoryPath.toRealPath().equals(repositoryPath))
                    throw new IOException("Expected a local offline repository: " + repositoryPath);
                var settings = backend.settings();
                if (backup && !settings.supports(mode)) throw new IOException("Backend does not support " + mode + " backups");
                try (var repository = settings.open(repositoryPath, persistence::force, mode, null)) {
                    if (backup) {
                        var point = new Snapshot(UUID.randomUUID(), Instant.now(), SOURCE);
                        var budget = new StagedDirectoryView.CopyBudget(LIMITS.contentBytes(), LIMITS.entries(), 64);
                        var exclusions = BackupExclusions.load(home.resolve("config/barusikozako"), worlds, true);
                        var view = StagedDirectoryView.copy(worlds, home, budget, window::requireExclusiveAccess,
                            path -> !(path.getNameCount() == 2 && path.getFileName().toString().equals("session.lock")));
                        core.backup(view, repository, point, UUID.randomUUID(), LIMITS,
                            window::requireExclusiveAccess, () -> {}, ignored -> {}, 0, exclusions);
                        core.verify(repository, point.id(), LIMITS);
                        System.out.println("BaruSikoZako verified offline snapshot: " + point.id());
                    } else {
                        beforeRestore.check();
                        var result = core.restoreWorld(repository, snapshot, operation, LIMITS, window, true,
                            AgentStartup::validateContainer);
                        if (result.status() != RestoreResult.Status.RESTORED)
                            throw new IOException("Restore did not commit: " + result.status());
                        System.out.println("BaruSikoZako restored snapshot " + snapshot + ". Original: "
                            + result.previousWorld().orElseThrow());
                        afterRestore.check();
                    }
                }
            }
            if (!backup) {
                if (restore) validateContainer(worlds);
                window.releaseWorldSessions();
                beforeLaunch.check();
                runningServer = window;
                launch(jar, entryPoint, worlds);
            }
        } finally {
            // 服务端主方法返回后其他线程可能仍在运行，锁需保持到 JVM 退出
            if (runningServer != window) window.close();
        }
    }

    private static boolean emptyContainer(Path directory) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW) || !directory.toRealPath().equals(directory)) return false;
        try (var children = Files.list(directory)) {
            return children.findAny().isEmpty();
        }
    }

    @FunctionalInterface
    public interface BackendSelection {
        BackendProvider.Settings settings() throws IOException;
    }

    @FunctionalInterface
    public interface DirectoryPersistence {
        void force(Path path) throws IOException;

        default boolean sameFileStore(Path first, Path second) throws IOException {
            return Files.getFileStore(first).equals(Files.getFileStore(second));
        }
    }

    private static String entryPoint(Path serverJar) throws IOException {
        try (var jar = new JarFile(serverJar.toFile())) {
            var manifest = jar.getManifest();
            var entry = manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
            if (entry == null || entry.isBlank()) throw new IOException("Server JAR has no Main-Class");
            return entry;
        }
    }

    private static void launch(Path jar, String main, Path worlds) throws Exception {
        var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        Class.forName(main, true, loader).getMethod("main", String[].class)
            .invoke(null, (Object) new String[] {"--universe", worlds.toString(), "--world", "world", "nogui"});
    }

    private static void validateContainer(Path container) throws IOException {
        if (!Files.isDirectory(container, NOFOLLOW) || !container.toRealPath().equals(container))
            throw new IOException("Expected a real managed worlds directory: " + container);
        Path primary = container.resolve("world");
        if (!Files.isDirectory(primary, NOFOLLOW)) throw new IOException("Missing primary world: " + primary);
        try (var children = Files.list(container)) {
            for (Path world : children.toList()) {
                if (userCache(world)) continue;
                if (!Files.isDirectory(world, NOFOLLOW)) throw new IOException("Unexpected world container entry: " + world);
                try (var compressed = Files.newInputStream(world.resolve("level.dat"), NOFOLLOW);
                     var nbt = new GZIPInputStream(compressed)) {
                    if (nbt.read() != 10) throw new IOException("World metadata is not a compressed NBT compound: " + world);
                    long total = 1;
                    byte[] buffer = new byte[8192];
                    for (int size; (size = nbt.read(buffer)) != -1;) {
                        total += size;
                        if (total > 16L << 20) throw new IOException("World metadata exceeds the read limit: " + world);
                    }
                }
            }
        }
    }

    private static boolean userCache(Path path) {
        return path.getFileName().toString().equals("usercache.json") && Files.isRegularFile(path, NOFOLLOW);
    }

    private static final class StartupWindow implements RestoreWindow {
        private final Path target;
        private final FileChannel channel;
        private final FileLock lock;
        private final DirectoryPersistence persistence;
        private final List<FileChannel> sessions = new ArrayList<>();
        private final List<FileLock> sessionLocks = new ArrayList<>();
        private boolean offline = true;

        StartupWindow(Path target, DirectoryPersistence persistence) throws IOException {
            this.target = target;
            this.persistence = persistence;
            channel = FileChannel.open(target.resolveSibling(".barusikozako-bukkit.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW);
            try {
                lock = channel.tryLock();
                if (lock == null) throw new IOException("Another managed server or restore is running.");
                if (Files.isDirectory(target, NOFOLLOW)) {
                    try (var children = Files.list(target)) {
                        for (Path child : children.toList()) {
                            if (userCache(child)) continue;
                            if (!Files.isDirectory(child, NOFOLLOW)) throw new IOException("Untrusted world entry: " + child);
                            Path path = child.resolve("session.lock");
                            if (Files.notExists(path, NOFOLLOW)) continue;
                            var session = FileChannel.open(path, StandardOpenOption.WRITE, NOFOLLOW);
                            sessions.add(session);
                            var sessionLock = session.tryLock();
                            if (sessionLock == null) throw new IOException("World is currently open: " + child);
                            sessionLocks.add(sessionLock);
                        }
                    }
                }
                // 目标目录下的 session.lock 仍被打开时，NTFS 会拒绝替换目录
                // 持续持有受控启动器的锁，并通过原版锁检测已打开的非受控世界
                if (target.getFileSystem().getSeparator().equals("\\")) closeWorldSessions();
            } catch (IOException | RuntimeException failure) {
                try { close(); } catch (IOException closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        }

        @Override public Path target() { return target; }

        @Override public void forceDirectory(Path path) throws IOException { persistence.force(path); }

        @Override public boolean sameFileStore(Path first, Path second) throws IOException {
            return persistence.sameFileStore(first, second);
        }

        @Override public void moveDirectoryAtomically(Path source, Path destination) throws IOException {
            boolean windows = source.getFileSystem().getSeparator().equals("\\");
            for (int attempt = 0; ; attempt++) {
                requireExclusiveAccess();
                if (attempt != 0 && (!Files.isDirectory(source, NOFOLLOW) || !Files.notExists(destination, NOFOLLOW)))
                    throw new IOException("NTFS directory move is no longer safe to retry: " + destination);
                try {
                    RestoreWindow.super.moveDirectoryAtomically(source, destination);
                    if (attempt != 0)
                        System.getLogger(AgentStartup.class.getName()).log(System.Logger.Level.INFO,
                            "NTFS directory move completed after {0} sharing retries", attempt);
                    return;
                } catch (AccessDeniedException denied) {
                    if (!windows || attempt == 6 || !Files.isDirectory(source, NOFOLLOW) ||
                            !Files.notExists(destination, NOFOLLOW)) throw denied;
                    // 扫描程序可能短暂打开刚恢复的文件且不允许共享删除
                    // 仅在能够确认目录移动尚未生效时重试
                    try {
                        Thread.sleep(30L << attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        var failure = new InterruptedIOException("Interrupted while waiting for an NTFS directory move");
                        failure.addSuppressed(denied);
                        throw failure;
                    }
                }
            }
        }

        @Override public void requireExclusiveAccess() throws IOException {
            if (!offline || !lock.isValid()) throw new IOException("The managed world container is not offline.");
        }

        void releaseWorldSessions() throws IOException {
            offline = false;
            closeWorldSessions();
        }

        private void closeWorldSessions() throws IOException {
            IOException error = null;
            for (var session : sessions) {
                try { session.close(); }
                catch (IOException closing) {
                    if (error == null) error = closing;
                    else error.addSuppressed(closing);
                }
            }
            sessions.clear();
            sessionLocks.clear();
            if (error != null) throw error;
        }

        @Override public void close() throws IOException {
            try (channel) { releaseWorldSessions(); }
        }
    }
}
