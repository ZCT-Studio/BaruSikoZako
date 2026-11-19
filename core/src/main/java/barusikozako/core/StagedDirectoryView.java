// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.core;

import barusikozako.platform.ReadView;
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

/** Owns a private stable copy. The platform must isolate the source before calling copy. */
public final class StagedDirectoryView implements ReadView {
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;
    private static final String PREFIX = ".barusikozako-view-";
    private static final Pattern ABANDONED_NAME = Pattern.compile("\\.barusikozako-view-([0-9]{1,20}|" +
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private final Path root;
    private int sessions;
    private boolean closed;

    private StagedDirectoryView(Path root) {
        this.root = root;
    }

    /**
     * Copies a source already isolated from all writers. Checkpoints may abort an invalidated copy.
     * The destination parent must be trusted and outside the world; the lock file at the world root is excluded.
     */
    public static StagedDirectoryView copy(Path source, Path destinationParent, CopyBudget budget,
                                           BackupEngine.Checkpoint checkpoint) throws IOException {
        return copy(source, destinationParent, budget, checkpoint, (file, target, size) -> false);
    }

    /** The optional cloner runs inside the same isolation window and must leave no target when declining. */
    public static StagedDirectoryView copy(Path source, Path destinationParent, CopyBudget budget,
                                          BackupEngine.Checkpoint checkpoint, FileClone cloner) throws IOException {
        return copy(source, destinationParent, budget, checkpoint, cloner, false, 0, 0, 0);
    }

    /** Makes a best-effort per-file stable copy while the source remains writable. */
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
        if (retries < 0 || retries > 20 || retryDelayMillis < 0 || retryDelayMillis > 1000)
            throw new IllegalArgumentException("Invalid live copy retry policy");
        return copy(source, destinationParent, budget, checkpoint, cloner, true, readBytesPerSecond,
            retries, retryDelayMillis);
    }

    private static StagedDirectoryView copy(Path source, Path destinationParent, CopyBudget budget,
                                            BackupEngine.Checkpoint checkpoint, FileClone cloner, boolean live,
                                            long readBytesPerSecond, int retries, int retryDelayMillis)
        throws IOException {
        var pacing = new BackupEngine.ReadPacing(readBytesPerSecond);
        Path sourceRoot = source.toRealPath();
        Path destination = destinationParent.toRealPath();
        if (destination.startsWith(sourceRoot)) throw new IOException("staging must be outside the world directory");
        if (!Files.isDirectory(sourceRoot)) throw new IOException("snapshot source must be a directory");
        checkpoint.check();
        Path stage = Files.createTempDirectory(destination, PREFIX);
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
                    // The Windows provider can report a directory junction as both a directory and a special file.
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
                    if (relative.toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                    if (relative.getNameCount() > budget.depth)
                        throw new IOException("snapshot exceeds the file budget");
                    Path output = stage.resolve(relative);
                    for (int attempt = 0; ; attempt++) {
                        try {
                            copyFile(file, relative, output);
                            break;
                        } catch (SourceChanged | NoSuchFileException | AccessDeniedException changed) {
                            Files.deleteIfExists(output);
                            checkpoint.check();
                            if (!live || attempt == retries) throw changed;
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
                        // On Windows a directory walk may carry cached attributes from enumeration.
                        // Check size on the same open handle used for all reads.
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
                            throw new SourceChanged("File changed size during capture: " + relative);
                    }
                    var actual = unchanged(relative, initial, length);
                    if (live) {
                        if (!MessageDigest.isEqual(hash(file, length), hash(output, length)))
                            throw new SourceChanged("File contents changed during capture: " + relative);
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
                        throw new SourceChanged("File was replaced or changed during capture: " + relative);
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
                            if (count < 0) throw new SourceChanged("File shrank during verification: " + path);
                            digest.update(hashBuffer, 0, count);
                            remaining -= count;
                            if (count > 0) pacing.consumed(count);
                        }
                        pacing.beforeRead(checkpoint);
                        if (input.read() != -1) throw new SourceChanged("File grew during verification: " + path);
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
            return new StagedDirectoryView(stage);
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
        private SourceChanged(String message) { super(message); }
    }

    @FunctionalInterface
    public interface FileClone {
        boolean tryClone(Path source, Path newTarget, long length) throws IOException;
    }

    /** Requires exclusive, cross-process ownership of the dedicated staging parent. */
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
