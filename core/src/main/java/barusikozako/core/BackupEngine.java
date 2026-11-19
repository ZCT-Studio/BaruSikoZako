// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.core;

import barusikozako.backend.Entry;
import barusikozako.backend.Repository;
import barusikozako.backend.Snapshot;
import barusikozako.platform.ReadView;
import barusikozako.platform.RestoreWindow;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongConsumer;

/** Synchronous engine operations. Callers schedule them off their application thread. */
public final class BackupEngine {
    public void backup(ReadView view, Repository repository, Snapshot snapshot, UUID operationId, Limits limits)
        throws IOException {
        backup(view, repository, snapshot, operationId, limits, () -> {}, () -> {});
    }

    public void backup(ReadView view, Repository repository, Snapshot snapshot, UUID operationId, Limits limits,
                       Checkpoint checkpoint, Repository.CommitGate gate) throws IOException {
        backup(view, repository, snapshot, operationId, limits, checkpoint, gate, ignored -> {});
    }

    public void backup(ReadView view, Repository repository, Snapshot snapshot, UUID operationId, Limits limits,
                       Checkpoint checkpoint, Repository.CommitGate gate, LongConsumer progress) throws IOException {
        backup(view, repository, snapshot, operationId, limits, checkpoint, gate, progress, 0);
    }

    /** Backup reads are paced on the caller's worker. A zero rate disables pacing. */
    public void backup(ReadView view, Repository repository, Snapshot snapshot, UUID operationId, Limits limits,
                       Checkpoint checkpoint, Repository.CommitGate gate, LongConsumer progress, long readBytesPerSecond)
        throws IOException {
        long bytes = 0;
        var measured = new ByteProgress(progress);
        var pacing = new ReadPacing(readBytesPerSecond);
        var seen = new HashMap<List<String>, Boolean>();
        var preparation = new Repository.Preparation(limits.contentBytes(), limits.archiveBytes(), limits.entries(),
            checkpoint::check, input -> paced(input, pacing, checkpoint, null));
        try (view; var session = view.openSession(); var writer = repository.prepare(snapshot, operationId, preparation)) {
            for (;;) {
                checkpoint.check();
                var item = session.next();
                if (item == null) break;
                if (item.size() == ReadView.Item.UNKNOWN_SIZE)
                    throw new IOException("stream length must be known for this development writer");
                var entry = new Entry(item.path(), item.directory(), item.size(), item.modifiedAt());
                checkEntry(entry, seen, limits, bytes);
                try (var content = item.directory() ? InputStream.nullInputStream() : session.openContent()) {
                    writer.add(entry, paced(content, pacing, checkpoint, measured), () ->
                        paced(session.reopenContent(), pacing, checkpoint, null));
                }
                bytes += entry.size();
            }
            checkpoint.check();
            measured.finish();
            writer.commit(gate);
        }
    }

    private static InputStream paced(InputStream content, ReadPacing pacing, Checkpoint checkpoint, ByteProgress measured) {
        return new FilterInputStream(content) {
            @Override public int read() throws IOException {
                pacing.beforeRead(checkpoint);
                int value = super.read();
                if (value >= 0) {
                    pacing.consumed(1);
                    if (measured != null) measured.add(1);
                }
                return value;
            }

            @Override public int read(byte[] data, int offset, int length) throws IOException {
                pacing.beforeRead(checkpoint);
                int count = super.read(data, offset, pacing.requestSize(length));
                if (count > 0) {
                    pacing.consumed(count);
                    if (measured != null) measured.add(count);
                }
                return count;
            }
        };
    }

    /** Applies the same cancellable read control to verified repository-to-repository-point rebuilds. */
    public static Repository.Preparation readPreparation(Limits limits, Checkpoint checkpoint, long readBytesPerSecond) {
        var pacing = new ReadPacing(readBytesPerSecond);
        return new Repository.Preparation(limits.contentBytes(), limits.archiveBytes(), limits.entries(),
            checkpoint::check, input -> paced(input, pacing, checkpoint, null));
    }

    static final class ReadPacing {
        private final long rate;
        private long waitUntil;
        private boolean waiting;

        ReadPacing(long rate) {
            if (rate < 0) throw new IllegalArgumentException("negative backup read rate");
            this.rate = rate;
        }

        int requestSize(int requested) {
            return rate == 0 ? requested : (int) Math.min(requested, Math.max(1, Math.min(64 * 1024, rate / 10)));
        }

        void consumed(int count) {
            if (rate == 0) return;
            waitUntil = System.nanoTime() + (count * 1_000_000_000L - 1) / rate + 1;
            waiting = true;
        }

        void beforeRead(Checkpoint checkpoint) throws IOException {
            checkpoint.check();
            if (!waiting) return;
            for (long remaining; (remaining = waitUntil - System.nanoTime()) > 0;) {
                long slice = Math.min(remaining, 20_000_000L);
                try {
                    Thread.sleep(slice / 1_000_000L, (int) (slice % 1_000_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Backup read pacing interrupted");
                }
                checkpoint.check();
            }
            waiting = false;
        }
    }

    @FunctionalInterface
    public interface Checkpoint {
        void check() throws IOException;
    }

    public Verification verify(Repository repository, UUID snapshotId, Limits limits) throws IOException {
        return readVerified(repository, snapshotId, limits, null);
    }

    public Verification verify(Repository repository, UUID snapshotId, Limits limits, Checkpoint checkpoint)
        throws IOException {
        return readVerified(repository, snapshotId, limits, null, checkpoint);
    }

    public Verification verify(Repository repository, UUID snapshotId, Limits limits, Checkpoint checkpoint,
                               LongConsumer progress) throws IOException {
        return readVerified(repository, snapshotId, limits, null, checkpoint, progress);
    }

    /** The caller owns the window and keeps world loading inhibited until recovery succeeds. */
    public RestoreResult restore(Repository repository, UUID snapshotId, UUID operationId,
                                 Limits limits, RestoreWindow window) throws IOException {
        return restore(repository, snapshotId, operationId, limits, window, stage -> {});
    }

    public RestoreResult restore(Repository repository, UUID snapshotId, UUID operationId,
                                 Limits limits, RestoreWindow window, StageCheck validation) throws IOException {
        synchronized (window) {
            return RestoreTransaction.open(window).restore(snapshotId, operationId, stage -> {
                readVerified(repository, snapshotId, limits, stage);
                validation.check(stage);
            });
        }
    }

    @FunctionalInterface
    public interface StageCheck {
        void check(Path verifiedStage) throws IOException;
    }

    /** Call before loading a world under its offline window. Uncertain states raise an error. */
    public RestoreResult recoverRestore(RestoreWindow window) throws IOException {
        synchronized (window) {
            return RestoreTransaction.open(window).recover();
        }
    }

    /** Allows the startup gate to distinguish a new world from an interrupted switch. */
    public boolean hasPendingRestore(RestoreWindow window) throws IOException {
        synchronized (window) {
            return RestoreTransaction.open(window).hasPending();
        }
    }

    /**
     * Returns verified files in a new, caller-owned staging directory.
     * The caller must supply a trusted staging parent with exclusive access.
     */
    public Path prepareRestore(Repository repository, UUID snapshotId, Limits limits, Path stagingParent)
        throws IOException {
        var stage = Files.createTempDirectory(stagingParent.toRealPath(), ".barusikozako-restore-");
        try {
            readVerified(repository, snapshotId, limits, stage);
            return stage;
        } catch (IOException | RuntimeException e) {
            try {
                deleteStage(stage);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private static Verification readVerified(Repository repository, UUID snapshotId, Limits limits, Path stage)
        throws IOException {
        return readVerified(repository, snapshotId, limits, stage, () -> {});
    }

    private static Verification readVerified(Repository repository, UUID snapshotId, Limits limits, Path stage,
                                             Checkpoint checkpoint) throws IOException {
        return readVerified(repository, snapshotId, limits, stage, checkpoint, ignored -> {});
    }

    private static Verification readVerified(Repository repository, UUID snapshotId, Limits limits, Path stage,
                                             Checkpoint checkpoint, LongConsumer progress) throws IOException {
        long bytes = 0;
        long entries = 0;
        var measured = new ByteProgress(progress);
        byte[] buffer = new byte[64 * 1024];
        var seen = new HashMap<List<String>, Boolean>();
        var directoryTimes = new ArrayList<DirectoryTime>();
        try (var reader = repository.read(snapshotId, limits.archiveBytes(), checkpoint::check)) {
            for (;;) {
                checkpoint.check();
                var item = reader.next();
                if (item == null) break;
                var entry = item.entry();
                checkEntry(entry, seen, limits, bytes);
                entries++;
                Path target = stage == null ? null : resolve(stage, entry);
                if (entry.directory()) {
                    if (target != null) {
                        Files.createDirectory(target);
                        if (entry.modifiedAt() != null)
                            directoryTimes.add(new DirectoryTime(target, FileTime.from(entry.modifiedAt())));
                    }
                    continue;
                }
                var digest = sha256();
                long remaining = entry.size();
                try (var in = reader.openContent();
                     var out = target == null ? OutputStream.nullOutputStream() :
                         Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
                    while (true) {
                        checkpoint.check();
                        int count = in.read(buffer);
                        if (count == -1) break;
                        if (count > remaining) throw new IOException("entry exceeds its declared size");
                        remaining -= count;
                        digest.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                        measured.add(count);
                    }
                }
                if (remaining != 0 || !HexFormat.of().formatHex(digest.digest()).equals(item.sha256()))
                    throw new IOException("content verification failed for " + entry.path());
                bytes += entry.size();
                if (target != null && entry.modifiedAt() != null)
                    Files.setLastModifiedTime(target, FileTime.from(entry.modifiedAt()));
            }
        }
        for (int index = directoryTimes.size() - 1; index >= 0; index--) {
            var directory = directoryTimes.get(index);
            Files.setLastModifiedTime(directory.path(), directory.time());
        }
        measured.finish();
        return new Verification(entries, bytes);
    }

    private static final class ByteProgress {
        private final LongConsumer consumer;
        private long bytes;
        private long lastCheck;
        private long lastSample = System.nanoTime();

        ByteProgress(LongConsumer consumer) { this.consumer = consumer; }

        void add(int count) {
            bytes += count;
            if (bytes - lastCheck < 1_048_576L) return;
            lastCheck = bytes;
            long now = System.nanoTime();
            if (now - lastSample >= 200_000_000L) {
                lastSample = now;
                consumer.accept(bytes);
            }
        }

        void finish() { consumer.accept(bytes); }
    }

    private static void checkEntry(Entry entry, HashMap<List<String>, Boolean> seen, Limits limits, long bytes)
        throws IOException {
        if (seen.size() >= limits.entries() || entry.size() > limits.contentBytes() - bytes)
            throw new IOException("snapshot exceeds the entry or content budget");
        var parent = entry.path().subList(0, entry.path().size() - 1);
        if (!parent.isEmpty() && !Boolean.TRUE.equals(seen.get(parent)))
            throw new IOException("undeclared parent directory: " + parent);
        if (seen.putIfAbsent(entry.path(), entry.directory()) != null)
            throw new IOException("duplicate snapshot path: " + entry.path());
    }

    private static Path resolve(Path stage, Entry entry) throws IOException {
        var target = stage;
        boolean windows = stage.getFileSystem().getSeparator().equals("\\");
        for (var part : entry.path()) {
            if (windows) validateWindowsName(part);
            var name = stage.getFileSystem().getPath(part);
            if (name.isAbsolute() || name.getRoot() != null || name.getNameCount() != 1 || !name.toString().equals(part))
                throw new IOException("invalid destination component");
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("missing or invalid parent directory: " + target);
            target = target.resolve(name);
        }
        return target;
    }

    private static void validateWindowsName(String name) throws IOException {
        String stem = name.split("\\.", 2)[0].stripTrailing().toUpperCase(Locale.ROOT);
        if (name.endsWith(".") || name.endsWith(" ") || name.chars().anyMatch(c -> c < 32 || "<>:\"|?*".indexOf(c) >= 0)
            || stem.matches("CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³]"))
            throw new IOException("unsupported destination name: " + name);
    }

    private record DirectoryTime(Path path, FileTime time) {}

    static void deleteStage(Path stage) throws IOException {
        Files.walkFileTree(stage, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public record Limits(long contentBytes, long archiveBytes, long entries) {
        public Limits {
            if (contentBytes < 0 || archiveBytes < 1 || entries < 1)
                throw new IllegalArgumentException("invalid verification limits");
        }
    }

    public record Verification(long entries, long bytes) {}
}
