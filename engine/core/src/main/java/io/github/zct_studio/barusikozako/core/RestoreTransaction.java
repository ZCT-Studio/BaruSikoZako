// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.core;

import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 对平台保持离线的世界执行同一文件系统内的事务 */
public final class RestoreTransaction {
    private static final String INTENT = "intent";
    private static final String READY = "ready";
    private static final String COMMITTED = "committed";
    private static final String ROLLED_BACK = "rolled-back";
    private static final String STAGED = "staged";
    private static final String ORIGINAL = "original";
    private static final String VERSION = "barusikozako-restore/1";
    private static final String ACTIVE_PREFIX = ".barusikozako-recovery-";
    private static final Pattern ACTIVE_NAME = Pattern.compile(ACTIVE_PREFIX.replace(".", "\\.") + "[0-9a-f]{64}",
        Pattern.CASE_INSENSITIVE);
    private static final List<String> RECORDS = List.of(INTENT, READY, COMMITTED, ROLLED_BACK);
    private static final Set<String> MEMBERS = Set.of(INTENT, READY, COMMITTED, ROLLED_BACK, STAGED, ORIGINAL,
        INTENT + ".pending", READY + ".pending", COMMITTED + ".pending", ROLLED_BACK + ".pending");

    private final RestoreWindow window;
    private final Path parent;
    private final Path target;
    private final Path active;
    private final Path stage;
    private final Path original;
    private final String key;
    private final DirectorySync sync;
    private final Fault fault;

    static RestoreTransaction open(RestoreWindow window) throws IOException {
        return new RestoreTransaction(window, window::forceDirectory, point -> {});
    }

    boolean hasPending() throws IOException {
        window.requireExclusiveAccess();
        try (var records = Files.newDirectoryStream(parent, path -> ACTIVE_NAME.matcher(path.getFileName().toString()).matches())) {
            for (var record : records) {
                if (!record.equals(active)) throw new IOException("Unresolved recovery in world parent: " + record);
            }
        } catch (java.nio.file.DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        return attributes(active) != null;
    }

    RestoreTransaction(RestoreWindow window, DirectorySync sync, Fault fault) throws IOException {
        this.window = Objects.requireNonNull(window);
        this.sync = Objects.requireNonNull(sync);
        this.fault = Objects.requireNonNull(fault);
        window.requireExclusiveAccess();
        var supplied = window.target();
        if (!supplied.isAbsolute() || !supplied.equals(supplied.normalize()) || supplied.getParent() == null)
            throw new IOException("restore requires a stable absolute target path");
        parent = supplied.getParent().toRealPath();
        if (!supplied.getParent().equals(parent)) throw new IOException("restore parent must be canonical");
        target = parent.resolve(supplied.getFileName());
        var name = target.getFileName().toString();
        if (name.regionMatches(true, 0, ACTIVE_PREFIX, 0, ACTIVE_PREFIX.length()))
            throw new IOException("reserved restore target name");
        key = HexFormat.of().formatHex(BackupEngine.sha256().digest(name.getBytes(StandardCharsets.UTF_8)));
        active = parent.resolve(ACTIVE_PREFIX + key);
        stage = active.resolve(STAGED);
        original = active.resolve(ORIGINAL);
    }

    RestoreResult restore(UUID snapshotId, UUID operationId, Preparation preparation) throws IOException {
        var request = new Intent(Objects.requireNonNull(operationId), Objects.requireNonNull(snapshotId));
        window.requireExclusiveAccess();
        if (attributes(active) != null) throw new IOException("a restore already requires recovery: " + active);
        var history = history(request);
        if (directoryExists(history)) return recordedResult(history, request);
        if (!sameStoreDirectory(target)) throw new IOException("offline world is absent: " + target);
        // 文件系统无法同步目录元数据时，在创建操作意图前失败
        sync.force(parent);
        Files.createDirectory(active);
        try {
            fault.at(Point.ACTIVE_CREATED);
            writeRecord(INTENT, request.data(key));
            sync.force(parent);
            fault.at(Point.INTENT_PERSISTED);

            Files.createDirectory(stage);
            fault.at(Point.STAGE_CREATED);
            preparation.write(stage);
            forceTree(stage);
            if (!sameStoreDirectory(stage) || !sameStoreDirectory(target))
                throw uncertain("the verified stage or original world is absent");
            writeRecord(READY, operationId.toString());
            fault.at(Point.STAGE_READY);
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("restore cancelled before switching");

            move(target, original, Point.ORIGINAL_MOVED);
            move(stage, target, Point.WORLD_INSTALLED);
            writeRecord(COMMITTED, operationId.toString());
            fault.at(Point.COMMIT_PERSISTED);
            return finish(request, true);
        } catch (IOException | RuntimeException failure) {
            try {
                recover();
            } catch (IOException | RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    RestoreResult recover() throws IOException {
        window.requireExclusiveAccess();
        if (!sameStoreDirectory(active)) {
            if (!sameStoreDirectory(target)) throw uncertain("world is absent without a restore intent");
            return new RestoreResult(RestoreResult.Status.NO_PENDING_RESTORE, Optional.empty(), Optional.empty());
        }
        sync.force(parent);
        verifyMembers();
        boolean targetPresent = sameStoreDirectory(target);
        boolean stagePresent = sameStoreDirectory(stage);
        boolean originalPresent = sameStoreDirectory(original);
        var contents = record(active, INTENT);
        if (contents == null) {
            if (!targetPresent || stagePresent || originalPresent ||
                record(active, READY) != null || record(active, COMMITTED) != null ||
                record(active, ROLLED_BACK) != null || attributes(active.resolve(READY + ".pending")) != null ||
                attributes(active.resolve(COMMITTED + ".pending")) != null ||
                attributes(active.resolve(ROLLED_BACK + ".pending")) != null)
                throw uncertain("restore intent is missing");
            Files.deleteIfExists(active.resolve(INTENT + ".pending"));
            Files.delete(active);
            sync.force(parent);
            return new RestoreResult(RestoreResult.Status.ROLLED_BACK, Optional.empty(), Optional.empty());
        }

        var intent = parseIntent(contents);
        boolean ready = marker(active, READY, intent);
        boolean committed = marker(active, COMMITTED, intent);
        boolean rolledBack = marker(active, ROLLED_BACK, intent);
        if (committed) {
            if (!ready || rolledBack || !targetPresent || !originalPresent || stagePresent)
                throw uncertain("directories do not match the committed restore");
            return finish(intent, true);
        }
        if (rolledBack) {
            if (!targetPresent || originalPresent) throw uncertain("directories do not match the rollback");
            return finish(intent, false);
        }
        if (ready && targetPresent && originalPresent && !stagePresent) {
            move(target, stage, Point.ROLLBACK_UNINSTALLED);
            targetPresent = false;
            stagePresent = true;
        }
        if (ready && !targetPresent && originalPresent && stagePresent) {
            move(original, target, Point.ROLLBACK_RESTORED);
            targetPresent = true;
            originalPresent = false;
        }
        if (!targetPresent || originalPresent || (ready && !stagePresent))
            throw uncertain("directories do not match an interrupted restore");

        writeRecord(ROLLED_BACK, intent.operationId().toString());
        fault.at(Point.ROLLBACK_PERSISTED);
        return finish(intent, false);
    }

    private RestoreResult finish(Intent intent, boolean committed) throws IOException {
        window.requireExclusiveAccess();
        // 重新启动的进程可能会在其目录刷新之前看到安装的标记
        sync.force(active);
        if (!committed && directoryExists(stage)) {
            fault.at(Point.STAGE_CLEANUP_STARTED);
            BackupEngine.deleteStage(stage);
            sync.force(active);
            fault.at(Point.STAGE_CLEANED);
        }
        boolean removedPending = false;
        for (String name : RECORDS) {
            Path pending = active.resolve(name + ".pending");
            var attrs = attributes(pending);
            if (attrs == null) continue;
            if (!attrs.isRegularFile()) throw uncertain("invalid pending restore record");
            Files.delete(pending);
            removedPending = true;
        }
        if (removedPending) sync.force(active);
        var history = history(intent);
        move(active, history, Point.RECORD_ARCHIVED);
        return result(intent, committed, history);
    }

    private RestoreResult recordedResult(Path history, Intent intent) throws IOException {
        if (!intent.equals(parseIntent(record(history, INTENT))))
            throw new IOException("restore operation identifier was already used for another snapshot");
        boolean committed = marker(history, COMMITTED, intent);
        boolean rolledBack = marker(history, ROLLED_BACK, intent);
        if (committed == rolledBack || directoryExists(history.resolve(ORIGINAL)) != committed ||
            attributes(history.resolve(STAGED)) != null || (committed && !marker(history, READY, intent)))
            throw uncertain("invalid archived restore state");
        if (!sameStoreDirectory(target)) throw uncertain("the current world is absent");
        return result(intent, committed, history);
    }

    private static RestoreResult result(Intent intent, boolean committed, Path history) {
        return new RestoreResult(committed ? RestoreResult.Status.RESTORED : RestoreResult.Status.ROLLED_BACK,
            Optional.of(intent.operationId()), committed ? Optional.of(history.resolve(ORIGINAL)) : Optional.empty());
    }

    private Path history(Intent intent) {
        return parent.resolve(active.getFileName() + "-" + intent.operationId());
    }

    private void move(Path from, Path to, Point point) throws IOException {
        window.requireExclusiveAccess();
        if (attributes(to) != null) throw new IOException("restore path already exists: " + to);
        window.moveDirectoryAtomically(from, to);
        // 此处测试检查点模型进程甚至在目录刷新运行之前就退出
        fault.at(point);
        sync.force(to.getParent());
        if (!from.getParent().equals(to.getParent())) sync.force(from.getParent());
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        if (attributes(to) != null) throw new IOException("restore path already exists: " + to);
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeRecord(String name, String value) throws IOException {
        window.requireExclusiveAccess();
        var pending = active.resolve(name + ".pending");
        var attrs = attributes(pending);
        if (attrs != null) {
            if (!attrs.isRegularFile()) throw uncertain("invalid pending restore record");
            Files.delete(pending);
        }
        try (var file = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
            var data = ByteBuffer.wrap((value + "\n").getBytes(StandardCharsets.US_ASCII));
            while (data.hasRemaining()) file.write(data);
            file.force(true);
        }
        if (name.equals(INTENT)) fault.at(Point.INTENT_FILE_SYNCED);
        if (name.equals(COMMITTED)) fault.at(Point.COMMIT_FILE_SYNCED);
        atomicMove(pending, active.resolve(name));
        if (name.equals(COMMITTED)) fault.at(Point.COMMIT_RECORDED);
        if (name.equals(ROLLED_BACK)) fault.at(Point.ROLLBACK_RECORDED);
        sync.force(active);
    }

    private static String record(Path home, String name) throws IOException {
        var path = home.resolve(name);
        var attrs = attributes(path);
        if (attrs == null) return null;
        if (!attrs.isRegularFile()) throw new IOException("invalid restore record: " + path);
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(256);
            if (bytes.length == 0 || bytes.length == 256 || bytes[bytes.length - 1] != '\n')
                throw new IOException("invalid restore record boundary: " + path);
            return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
        }
    }

    private static boolean marker(Path home, String name, Intent intent) throws IOException {
        var value = record(home, name);
        if (value == null) return false;
        if (!value.equals(intent.operationId().toString()))
            throw new IOException("restore marker does not match the intent: " + home.resolve(name));
        return true;
    }

    private Intent parseIntent(String value) throws IOException {
        if (value == null) throw uncertain("restore intent is absent");
        String[] fields = value.split("\n", -1);
        if (fields.length != 4 || !fields[0].equals(VERSION) || !fields[1].equals(key))
            throw uncertain("restore intent version or target does not match");
        try {
            var id = UUID.fromString(fields[2]);
            var snapshot = UUID.fromString(fields[3]);
            if (!id.toString().equals(fields[2]) || !snapshot.toString().equals(fields[3]))
                throw new IllegalArgumentException("non-canonical identifier");
            return new Intent(id, snapshot);
        } catch (IllegalArgumentException failure) {
            throw new IOException("invalid restore intent identifier: " + active, failure);
        }
    }

    private void verifyMembers() throws IOException {
        try (var members = Files.newDirectoryStream(active)) {
            for (var member : members)
                if (!MEMBERS.contains(member.getFileName().toString()))
                    throw uncertain("unrecognized restore transaction data");
        }
    }

    private boolean sameStoreDirectory(Path path) throws IOException {
        if (!directoryExists(path)) return false;
        if (!window.sameFileStore(path, parent))
            throw new IOException("restore directories must share a filesystem: " + path);
        return true;
    }

    private static boolean directoryExists(Path path) throws IOException {
        var attrs = attributes(path);
        if (attrs == null) return false;
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther())
            throw new IOException("restore path must be an ordinary directory: " + path);
        return true;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return null;
        }
    }

    private void forceTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("invalid restore stage file: " + file);
                try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    channel.force(true);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                sync.force(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void forceDirectory(Path path) throws IOException {
        RestoreWindow.forceLocalDirectory(path);
    }

    private IOException uncertain(String detail) {
        return new IOException(detail + ". Keep world offline and inspect " + active);
    }

    private record Intent(UUID operationId, UUID snapshotId) {
        String data(String key) {
            return VERSION + "\n" + key + "\n" + operationId + "\n" + snapshotId;
        }
    }

    enum Point {
        ACTIVE_CREATED, INTENT_FILE_SYNCED, INTENT_PERSISTED, STAGE_CREATED, STAGE_READY,
        ORIGINAL_MOVED, WORLD_INSTALLED, COMMIT_FILE_SYNCED, COMMIT_RECORDED, COMMIT_PERSISTED,
        ROLLBACK_UNINSTALLED, ROLLBACK_RESTORED, ROLLBACK_RECORDED, ROLLBACK_PERSISTED, STAGE_CLEANUP_STARTED,
        STAGE_CLEANED, RECORD_ARCHIVED
    }

    @FunctionalInterface
    interface Preparation {
        void write(Path stage) throws IOException;
    }

    @FunctionalInterface
    interface DirectorySync {
        void force(Path path) throws IOException;
    }

    @FunctionalInterface
    interface Fault {
        void at(Point point) throws IOException;
    }

}
