// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.core;

import io.github.zct_studio.barusikozako.core.RestoreTransaction.Point;
import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class RestoreFaults {
    private static final int HALT = 73;
    private static final int WINDOW_DENIED = 74;
    private static final UUID SNAPSHOT = UUID.fromString("51b0f0b9-aea2-4fba-ad9f-34a10c52eb68");
    private static final UUID OPERATION = UUID.fromString("11d4ba61-eea9-4cc4-9e8b-edffbe3fd0fe");
    private static final String ORIGINAL = "original world";
    private static final String RESTORED = "restored world";
    private static final List<Point> FORWARD = List.of(Point.ACTIVE_CREATED, Point.INTENT_FILE_SYNCED,
        Point.INTENT_PERSISTED, Point.STAGE_CREATED, Point.STAGE_READY, Point.ORIGINAL_MOVED,
        Point.WORLD_INSTALLED, Point.COMMIT_FILE_SYNCED, Point.COMMIT_RECORDED, Point.COMMIT_PERSISTED,
        Point.RECORD_ARCHIVED);
    private static final List<Point> ROLLBACK = List.of(Point.ROLLBACK_UNINSTALLED, Point.ROLLBACK_RESTORED,
        Point.ROLLBACK_RECORDED, Point.ROLLBACK_PERSISTED, Point.STAGE_CLEANUP_STARTED, Point.STAGE_CLEANED,
        Point.RECORD_ARCHIVED);

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            child(args);
            return;
        }
        Path root = Files.createTempDirectory("barusikozako-restore-faults-").toRealPath();
        try {
            boolean nativeSync = directorySyncAvailable(root);
            var sync = directorySync(nativeSync);
            for (var point : FORWARD) {
                var test = world(root, "install-" + point);
                require(runChild(test, "install", point, nativeSync) == HALT, "install did not halt at " + point);
                boolean keptNew = point == Point.COMMIT_RECORDED || point == Point.COMMIT_PERSISTED ||
                    point == Point.RECORD_ARCHIVED;
                boolean noIntent = point == Point.ACTIVE_CREATED || point == Point.INTENT_FILE_SYNCED;
                checkRecovered(test, sync, keptNew, noIntent);
            }
            for (var point : ROLLBACK) {
                var test = world(root, "rollback-" + point);
                require(runChild(test, "install", Point.WORLD_INSTALLED, nativeSync) == HALT, "no interrupted install");
                require(runChild(test, "recover", point, nativeSync) == HALT, "rollback did not halt at " + point);
                checkRecovered(test, sync, false, false);
            }
            checkFailureCases(root, sync, nativeSync);
            checkUnknownState(root, sync, nativeSync);
            checkCorruptRecord(root, sync, nativeSync);
            System.out.printf("Restore fault suite passed: %d forward, %d rollback crash points. "
                + "Directory persistence: %s.%n", FORWARD.size(), ROLLBACK.size(),
                nativeSync ? "native directory force" : "native fail-closed verified; process-crash fixture");
        } finally {
            BackupEngine.deleteStage(root);
        }
    }

    private static void child(String[] args) throws IOException {
        var path = Path.of(args[0]);
        var action = args[1];
        var stopAt = Point.valueOf(args[2]);
        var sync = directorySync(Boolean.parseBoolean(args[3]));
        if (action.equals("window-probe")) {
            try (var window = FixtureWindow.open(path)) {
                window.requireExclusiveAccess();
            } catch (IOException denied) {
                System.exit(WINDOW_DENIED);
            }
            throw new AssertionError("child acquired a window held by its parent");
        }
        try (var window = FixtureWindow.open(path)) {
            var transaction = new RestoreTransaction(window, sync, reached -> {
                if (reached == stopAt) Runtime.getRuntime().halt(HALT);
            });
            if (action.equals("install")) transaction.restore(SNAPSHOT, OPERATION, RestoreFaults::writeStage);
            else if (action.equals("recover")) transaction.recover();
            else throw new AssertionError("unknown child action " + action);
        }
        throw new AssertionError("checkpoint was not reached: " + stopAt);
    }

    private static void checkRecovered(Path test, RestoreTransaction.DirectorySync sync, boolean keptNew,
                                       boolean intentNotCreated) throws IOException {
        try (var window = FixtureWindow.open(test)) {
            var transaction = transaction(window, sync);
            transaction.recover();
            require(new BackupEngine().recoverRestore(window).status() == RestoreResult.Status.NO_PENDING_RESTORE,
                "an active restore remained after recovery");
            require(marker(window.target()).equals(keptNew ? RESTORED : ORIGINAL), "wrong active world");
            if (!intentNotCreated) {
                var saved = transaction.restore(SNAPSHOT, OPERATION,
                    path -> { throw new AssertionError("recorded operation staged a second time"); });
                require(saved.status() == (keptNew ? RestoreResult.Status.RESTORED : RestoreResult.Status.ROLLED_BACK),
                    "wrong recorded outcome");
                if (keptNew)
                    require(marker(saved.previousWorld().orElseThrow()).equals(ORIGINAL), "original world was lost");
            }
        }
    }

    private static void checkFailureCases(Path root, RestoreTransaction.DirectorySync sync, boolean nativeSync)
        throws Exception {
        Path noDisk = world(root, "disk-full");
        try (var window = FixtureWindow.open(noDisk)) {
            require(runChild(noDisk, "window-probe", Point.ACTIVE_CREATED, nativeSync) == WINDOW_DENIED,
                "OS lock allowed a competing process");
            var transaction = transaction(window, sync);
            IOException noSpace = failure(() -> transaction.restore(SNAPSHOT, OPERATION, stage -> {
                writeStage(stage);
                throw new FileSystemException(stage.toString(), null, "injected disk exhaustion");
            }));
            require(noSpace instanceof FileSystemException, "preparation failure was not preserved");
            require(marker(window.target()).equals(ORIGINAL), "disk exhaustion changed the original world");
        }
        checkRecovered(noDisk, sync, false, false);

        Path access = world(root, "access-denied");
        try (var window = FixtureWindow.open(access)) {
            var transaction = new RestoreTransaction(window, sync, point -> {
                if (point == Point.ORIGINAL_MOVED) throw new AccessDeniedException("injected switch access failure");
            });
            require(failure(() -> transaction.restore(SNAPSHOT, OPERATION, RestoreFaults::writeStage))
                instanceof AccessDeniedException, "access failure was not preserved");
        }
        checkRecovered(access, sync, false, false);

        Path unavailable = world(root, "unavailable-window");
        try (var window = FixtureWindow.open(unavailable)) {
            if (!nativeSync) {
                var blocked = failure(() -> RestoreTransaction.open(window).restore(SNAPSHOT, OPERATION, stage -> {
                    throw new AssertionError("unsupported filesystem started restore preparation");
                }));
                require(blocked.getMessage().contains("directory persistence"), "unsupported directory sync was accepted");
            }
            window.close();
            failure(() -> transaction(window, sync).restore(SNAPSHOT, OPERATION, RestoreFaults::writeStage));
            require(marker(window.target()).equals(ORIGINAL), "unavailable capability touched original data");
            require(count(unavailable) == 2, "unavailable capability left restore artifacts");
        }
    }

    private static void checkUnknownState(Path root, RestoreTransaction.DirectorySync sync, boolean nativeSync)
        throws Exception {
        Path test = world(root, "uncertain");
        require(runChild(test, "install", Point.ORIGINAL_MOVED, nativeSync) == HALT, "no interrupted original move");
        try (var window = FixtureWindow.open(test)) {
            // 模拟不遵守协调协议的外部干扰，额外的目标不得被覆盖
            Files.createDirectory(window.target());
            Files.writeString(window.target().resolve("marker"), "unrelated directory");
            failure(() -> transaction(window, sync).recover());
            require(marker(window.target()).equals("unrelated directory"), "ambiguous state overwrote a world");
            var active = activeDirectory(test);
            require(marker(active.resolve("original")).equals(ORIGINAL), "ambiguity lost original data");
            require(marker(active.resolve("staged")).equals(RESTORED), "ambiguity lost the verified stage");
        }
    }

    private static void checkCorruptRecord(Path root, RestoreTransaction.DirectorySync sync, boolean nativeSync)
        throws Exception {
        Path test = world(root, "corrupt-record");
        require(runChild(test, "install", Point.WORLD_INSTALLED, nativeSync) == HALT, "no interrupted world install");
        try (var window = FixtureWindow.open(test)) {
            var active = activeDirectory(test);
            Files.writeString(active.resolve("intent"), "corrupt\n");
            failure(() -> transaction(window, sync).recover());
            require(marker(window.target()).equals(RESTORED), "invalid record overwrote the installed world");
            require(marker(active.resolve("original")).equals(ORIGINAL), "invalid record lost the original");
        }
    }

    private static Path activeDirectory(Path root) throws IOException {
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().startsWith(".barusikozako-recovery-"))
                .findFirst().orElseThrow();
        }
    }

    private static RestoreTransaction transaction(FixtureWindow window, RestoreTransaction.DirectorySync sync)
        throws IOException {
        return new RestoreTransaction(window, sync, ignored -> {});
    }

    private static int runChild(Path test, String action, Point point, boolean nativeSync) throws Exception {
        var executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
            RestoreFaults.class.getName(), test.toString(), action, point.name(), Boolean.toString(nativeSync))
            .inheritIO().start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new IOException("fault child timed out: " + point);
        }
        return process.exitValue();
    }

    private static boolean directorySyncAvailable(Path path) {
        try {
            RestoreTransaction.forceDirectory(path);
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    private static RestoreTransaction.DirectorySync directorySync(boolean nativeSync) {
        // 仅测试夹具绕过不可用的目录刷盘，以验证进程退出语义
        return nativeSync ? RestoreTransaction::forceDirectory : ignored -> {};
    }

    private static Path world(Path root, String name) throws IOException {
        var test = Files.createDirectory(root.resolve(name));
        var world = Files.createDirectory(test.resolve("world"));
        Files.writeString(world.resolve("marker"), ORIGINAL);
        return test;
    }

    private static void writeStage(Path stage) throws IOException {
        Files.writeString(stage.resolve("marker"), RESTORED);
        Files.createDirectory(stage.resolve("empty"));
    }

    private static String marker(Path directory) throws IOException {
        return Files.readString(directory.resolve("marker"));
    }

    private static long count(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    private static IOException failure(Attempt attempt) throws IOException {
        try {
            attempt.run();
        } catch (IOException result) {
            return result;
        }
        throw new AssertionError("expected an I/O failure");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Attempt {
        void run() throws IOException;
    }

    private record FixtureWindow(Path target, FileChannel channel, FileLock lock) implements RestoreWindow {
        static FixtureWindow open(Path root) throws IOException {
            var channel = FileChannel.open(root.resolve(".fixture-platform.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
            try {
                var lock = channel.tryLock();
                if (lock == null) throw new IOException("fixture platform window is already owned");
                return new FixtureWindow(root.resolve("world"), channel, lock);
            } catch (OverlappingFileLockException busy) {
                channel.close();
                throw new IOException("fixture platform window is already owned", busy);
            } catch (IOException failure) {
                channel.close();
                throw failure;
            }
        }

        @Override
        public void requireExclusiveAccess() throws IOException {
            if (!lock.isValid()) throw new IOException("fixture platform window is closed");
        }

        @Override
        public void close() throws IOException {
            try (channel) {
                if (lock.isValid()) lock.release();
            }
        }
    }
}
