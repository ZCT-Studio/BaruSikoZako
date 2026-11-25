// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.core;

import io.github.zct_studio.barusikozako.backend.Consistency;
import io.github.zct_studio.barusikozako.platform.ReadView;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 持有私有稳定副本，平台必须在调用复制前隔离源数据 */
public final class StagedDirectoryView implements ReadView {
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;
    private static final String PREFIX = ".barusikozako-view-";
    private static final Pattern ABANDONED_NAME = Pattern.compile("\\.barusikozako-view-([0-9]{1,20}|" +
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private final Path root;
    private final List<String> tolerated;
    private int sessions;
    private boolean closed;

    private StagedDirectoryView(Path root) {
        this(root, List.of());
    }

    private StagedDirectoryView(Path root, List<String> tolerated) {
        this.root = root;
        this.tolerated = List.copyOf(tolerated);
    }

    /** 宽松捕获中被接受的时间点差异文件；严格捕获始终为空 */
    public List<String> relaxedChanges() {
        return tolerated;
    }

    /**
     * 复制已与所有作者隔离的来源。检查点可能会中止无效的副本
     * 目的地父母必须是值得信赖的、与外界隔绝的；世界根目录下的锁定文件被排除
     */
    public static StagedDirectoryView copy(Path source, Path destinationParent, CopyBudget budget,
                                           BackupEngine.Checkpoint checkpoint) throws IOException {
        return copy(source, destinationParent, budget, checkpoint, (file, target, size) -> false);
    }

    /** 可选的克隆器在同一隔离窗口内运行，拒绝操作时不得留下目标 */
    public static StagedDirectoryView copy(Path source, Path destinationParent, CopyBudget budget,
                                          BackupEngine.Checkpoint checkpoint, FileClone cloner) throws IOException {
        return copy(source, destinationParent, budget, checkpoint, cloner, false, 0, 0, 0, path -> true);
    }

    public static StagedDirectoryView copy(Path source, Path destinationParent, CopyBudget budget,
                                          BackupEngine.Checkpoint checkpoint, java.util.function.Predicate<Path> includeFile)
        throws IOException {
        return copy(source, destinationParent, budget, checkpoint, (file, target, size) -> false, false, 0, 0, 0,
            java.util.Objects.requireNonNull(includeFile));
    }

    /** 在源数据保持可写时尽力为每个文件创建稳定副本 */
    public static StagedDirectoryView copyLive(Path source, Path destinationParent, CopyBudget budget,
                                              BackupEngine.Checkpoint checkpoint, FileClone cloner) throws IOException {
        return copyLive(source, destinationParent, budget, checkpoint, cloner, 0);
    }

    public static StagedDirectoryView copyLive(Path source, Path destinationParent, CopyBudget budget,
                                              BackupEngine.Checkpoint checkpoint, FileClone cloner,
                                              long readBytesPerSecond) throws IOException {
        return copyLive(source, destinationParent, budget, checkpoint, cloner, readBytesPerSecond, 2, 10);
    }

    public static StagedDirectoryView copyLive(Path source, Path destinationParent, CopyBudget budget,
                                              BackupEngine.Checkpoint checkpoint, FileClone cloner,
                                              long readBytesPerSecond, int retries, int retryDelayMillis) throws IOException {
        return copyLive(source, destinationParent, budget, checkpoint, cloner, readBytesPerSecond, retries,
            retryDelayMillis, path -> true);
    }

    /** 允许受管理的容器排除已知的相对运行时锁路径 */
    public static StagedDirectoryView copyLive(Path source, Path destinationParent, CopyBudget budget,
                                              BackupEngine.Checkpoint checkpoint, FileClone cloner,
                                              long readBytesPerSecond, int retries, int retryDelayMillis,
                                              java.util.function.Predicate<Path> includeFile) throws IOException {
        return copyLive(source, destinationParent, budget, checkpoint, cloner, readBytesPerSecond, retries,
            retryDelayMillis, includeFile, Consistency.STRICT);
    }

    /** 只接受已完整复制的文件，宽松模式下允许其内容对应不同时间点 */
    public static StagedDirectoryView copyLive(Path source, Path destinationParent, CopyBudget budget,
                                              BackupEngine.Checkpoint checkpoint, FileClone cloner,
                                              long readBytesPerSecond, int retries, int retryDelayMillis,
                                              java.util.function.Predicate<Path> includeFile,
                                              Consistency consistency) throws IOException {
        if (retries < 0 || retries > 20 || retryDelayMillis < 0 || retryDelayMillis > 1000)
            throw new IllegalArgumentException("Invalid live copy retry policy");
        return copy(source, destinationParent, budget, checkpoint, cloner, true, readBytesPerSecond,
            retries, retryDelayMillis, java.util.Objects.requireNonNull(includeFile),
            java.util.Objects.requireNonNull(consistency));
    }

    private static StagedDirectoryView copy(Path source, Path destinationParent, CopyBudget budget,
                                            BackupEngine.Checkpoint checkpoint, FileClone cloner, boolean live,
                                            long readBytesPerSecond, int retries, int retryDelayMillis,
                                            java.util.function.Predicate<Path> includeFile)
        throws IOException {
        return copy(source, destinationParent, budget, checkpoint, cloner, live, readBytesPerSecond, retries,
            retryDelayMillis, includeFile, Consistency.STRICT);
    }

    private static StagedDirectoryView copy(Path source, Path destinationParent, CopyBudget budget,
                                            BackupEngine.Checkpoint checkpoint, FileClone cloner, boolean live,
                                            long readBytesPerSecond, int retries, int retryDelayMillis,
                                            java.util.function.Predicate<Path> includeFile, Consistency consistency)
        throws IOException {
        var pacing = new BackupEngine.ReadPacing(readBytesPerSecond);
        Path sourceRoot = source.toRealPath();
        Path destination = destinationParent.toRealPath();
        if (destination.startsWith(sourceRoot)) throw new IOException("staging must be outside the world directory");
        if (!Files.isDirectory(sourceRoot)) throw new IOException("snapshot source must be a directory");
        checkpoint.check();
        Path stage = Files.createTempDirectory(destination, PREFIX);
        var tolerated = new ArrayList<String>();
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                private ByteBuffer copyBuffer;
                private byte[] hashBuffer;
                private long entries;
                private long bytes;

                private void count() throws IOException {
                    checkpoint.check();
                    if (++entries > budget.entries) throw new IOException("snapshot exceeds the entry budget");
                }

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    // Windows 提供程序可以将目录连接报告为目录和特殊文件
                    if (attrs.isOther() || attrs.isSymbolicLink()) throw new IOException("Unsupported world directory entry");
                    count();
                    var relative = sourceRoot.relativize(directory);
                    if (relative.getNameCount() > budget.depth) throw new IOException("snapshot exceeds path depth");
                    if (!directory.equals(sourceRoot)) {
                        if (relative.toString().equals("session.lock"))
                            throw new IOException("invalid world lock directory");
                        Files.createDirectory(stage.resolve(relative));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    count();
                    Path relative = sourceRoot.relativize(file);
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("unsupported world entry");
                    if (relative.toString().equals("session.lock") || !includeFile.test(relative))
                        return FileVisitResult.CONTINUE;
                    if (relative.getNameCount() > budget.depth)
                        throw new IOException("snapshot exceeds the file budget");
                    Path output = stage.resolve(relative);
                    for (int attempt = 0; ; attempt++) {
                        try {
                            copyFile(file, relative, output);
                            break;
                        } catch (SourceChanged | NoSuchFileException | AccessDeniedException changed) {
                            boolean complete = changed instanceof SourceChanged source && source.complete();
                            boolean exhausted = !live || attempt == retries;
                            checkpoint.check();
                            if (complete && exhausted && consistency == Consistency.RELAXED) {
                                // 只接受完整复制自身内容的文件，绝不保留截断或未完成的副本
                                tolerated.add(relative.toString());
                                bytes += Files.size(output);
                                break;
                            }
                            Files.deleteIfExists(output);
                            if (exhausted) throw changed;
                            try {
                                Thread.sleep((long) retryDelayMillis * (attempt + 1));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new InterruptedIOException("Live copy retry interrupted");
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                private void copyFile(Path file, Path relative, Path output) throws IOException {
                    var initial = Files.readAttributes(sourceRoot.resolve(relative.toString()), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                    if (!initial.isRegularFile()) throw new SourceChanged("File type changed: " + relative);
                    long length;
                    try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        // 在 Windows 上，目录遍历可能携带枚举中的缓存属性
                        // 检查用于所有读取的同一打开句柄的大小
                        length = channel.size();
                        if (length != initial.size()) throw new SourceChanged("File changed before reading: " + relative);
                        if (length > budget.bytes - bytes) throw new IOException("snapshot exceeds the file budget");
                        if (cloner.tryClone(file, output, length)) {
                            checkpoint.check();
                            var cloned = Files.readAttributes(output, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                            if (!cloned.isRegularFile() || cloned.size() != length ||
                                (initial.fileKey() != null && initial.fileKey().equals(cloned.fileKey())))
                                throw new IOException("Invalid cloned file: " + relative);
                        } else {
                            checkpoint.check();
                            copyPayload(channel, output, length, relative);
                        }
                        if (channel.size() != length)
                            throw new SourceChanged("File changed size during capture: " + relative, true);
                    }
                    var actual = unchanged(relative, initial, length);
                    if (live) {
                        if (!MessageDigest.isEqual(hash(file, length), hash(output, length)))
                            throw new SourceChanged("File contents changed during capture: " + relative, true);
                        actual = unchanged(relative, initial, length);
                    }
                    Files.setLastModifiedTime(output, actual.lastModifiedTime());
                    bytes += length;
                }

                private BasicFileAttributes unchanged(Path relative, BasicFileAttributes initial, long length)
                    throws IOException {
                    var actual = Files.readAttributes(sourceRoot.resolve(relative.toString()), BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                    if (!actual.isRegularFile() || actual.size() != length ||
                        !initial.lastModifiedTime().equals(actual.lastModifiedTime()) ||
                        !java.util.Objects.equals(initial.fileKey(), actual.fileKey()))
                        throw new SourceChanged("File was replaced or changed during capture: " + relative, true);
                    return actual;
                }

                private byte[] hash(Path path, long expected) throws IOException {
                    var digest = sha256();
                    if (hashBuffer == null) hashBuffer = new byte[64 * 1024];
                    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        long remaining = expected;
                        while (remaining > 0) {
                            pacing.beforeRead(checkpoint);
                            int request = pacing.requestSize((int) Math.min(remaining, hashBuffer.length));
                            int count = input.read(hashBuffer, 0, request);
                            if (count < 0) throw new SourceChanged("File shrank during verification: " + path, true);
                            digest.update(hashBuffer, 0, count);
                            remaining -= count;
                            if (count > 0) pacing.consumed(count);
                        }
                        pacing.beforeRead(checkpoint);
                        if (input.read() != -1) throw new SourceChanged("File grew during verification: " + path, true);
                    }
                    return digest.digest();
                }

                private MessageDigest sha256() {
                    try {
                        return MessageDigest.getInstance("SHA-256");
                    } catch (NoSuchAlgorithmException unavailable) {
                        throw new IllegalStateException("SHA-256 unavailable", unavailable);
                    }
                }

                private void copyPayload(FileChannel channel, Path output, long expected, Path relative) throws IOException {
                    if (copyBuffer == null) copyBuffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES);
                    try (var target = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        for (;;) {
                            pacing.beforeRead(checkpoint);
                            copyBuffer.clear();
                            copyBuffer.limit(pacing.requestSize(copyBuffer.capacity()));
                            int read = channel.read(copyBuffer);
                            if (read < 0) break;
                            if (read > 0) pacing.consumed(read);
                            if (read > expected) throw new SourceChanged("File grew during capture: " + relative);
                            copyBuffer.flip();
                            while (copyBuffer.hasRemaining()) {
                                checkpoint.check();
                                target.write(copyBuffer);
                            }
                            expected -= read;
                        }
                    }
                    if (expected != 0) throw new SourceChanged("File shrank during capture: " + relative);
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    checkpoint.check();
                    if (!directory.equals(sourceRoot)) {
                        Files.setLastModifiedTime(stage.resolve(sourceRoot.relativize(directory)),
                            Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            checkpoint.check();
            return new StagedDirectoryView(stage, tolerated);
        } catch (IOException | RuntimeException failure) {
            try {
                BackupEngine.deleteStage(stage);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static final class SourceChanged extends IOException {
        private final boolean complete;
        private SourceChanged(String message) { this(message, false); }
        private SourceChanged(String message, boolean complete) {
            super(message);
            this.complete = complete;
        }
        boolean complete() { return complete; }
    }

    @FunctionalInterface
    public interface FileClone {
        boolean tryClone(Path source, Path newTarget, long length) throws IOException;
    }

    /** 要求跨进程独占专用暂存父目录 */
    public static void cleanupAbandoned(Path destinationParent) throws IOException {
        try (var entries = Files.newDirectoryStream(destinationParent,
            entry -> ABANDONED_NAME.matcher(entry.getFileName().toString()).matches())) {
            for (var entry : entries) {
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Unexpected capture staging entry");
                BackupEngine.deleteStage(entry);
            }
        } catch (java.nio.file.DirectoryIteratorException failure) {
            throw failure.getCause();
        }
    }

    @Override
    public synchronized Session openSession() throws IOException {
        if (closed) throw new IOException("staged view is closed");
        var paths = Files.walk(root);
        try {
            var session = new ViewSession(paths);
            sessions++;
            return session;
        } catch (RuntimeException failure) {
            paths.close();
            throw failure;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (sessions != 0) throw new IOException("staged view has open sessions");
        BackupEngine.deleteStage(root);
        closed = true;
    }

    private final class ViewSession implements Session {
        private final Stream<Path> paths;
        private final Iterator<Path> entries;
        private Path current;
        private boolean currentFile;
        private boolean sessionClosed;

        private ViewSession(Stream<Path> paths) {
            this.paths = paths;
            entries = paths.iterator();
            entries.next(); // The backup engine consumes entries relative to the root.
        }

        @Override
        public Item next() throws IOException {
            if (sessionClosed) throw new IOException("staged view session is closed");
            try {
                if (!entries.hasNext()) {
                    currentFile = false;
                    return null;
                }
                current = entries.next();
                var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                currentFile = attrs.isRegularFile();
                if (!attrs.isDirectory() && !currentFile) throw new IOException("unexpected staged entry");
                List<String> names = new ArrayList<>();
                for (Path segment : root.relativize(current)) names.add(segment.toString());
                return new Item(names, attrs.isDirectory(), currentFile ? attrs.size() : 0,
                    attrs.lastModifiedTime().toInstant());
            } catch (UncheckedIOException error) {
                throw error.getCause();
            }
        }

        @Override
        public InputStream openContent() throws IOException {
            if (sessionClosed || !currentFile) throw new IOException("no staged file selected");
            return Files.newInputStream(current, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public InputStream reopenContent() throws IOException {
            return openContent();
        }

        @Override
        public void close() {
            synchronized (StagedDirectoryView.this) {
                if (sessionClosed) return;
                try {
                    paths.close();
                } finally {
                    sessionClosed = true;
                    sessions--;
                }
            }
        }
    }

    public record CopyBudget(long bytes, long entries, int depth) {
        public CopyBudget {
            if (bytes < 0 || entries < 1 || depth < 1) throw new IllegalArgumentException("invalid snapshot budget");
        }
    }
}
