// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.zstd;

import io.github.zct_studio.barusikozako.backend.Entry;
import io.github.zct_studio.barusikozako.backend.Repository;
import io.github.zct_studio.barusikozako.backend.Snapshot;
import io.github.zct_studio.barusikozako.compression.zstd.ZstdStreams;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;

/** 用于初始受控文件开发路径的本地 Zstd 存储库 */
public final class ZstdRepository implements io.github.zct_studio.barusikozako.backend.ManagedRepository {
    private static final String ARCHIVE = "data.tar.zst";
    static final String MANIFEST = "manifest.jsonl";
    static final String CHUNK_INDEX = "chunks.idx";
    static final String CHUNK_STAGE = "chunk.tmp";
    private static final String COMMIT = "commit";
    private static final String COMMIT_FORMAT = "BaruSikoZako zstd development " + Manifest.FORMAT;
    private static final String CHUNK_COMMIT = "BaruSikoZako zstd development " + Manifest.CHUNK_FORMAT;
    private static final long DELTA_FORMAT = 3;
    private static final String DELTA_COMMIT = "BaruSikoZako zstd development 3";
    private static final String PENDING_PREFIX = ".pending-";
    private static final String DELETED_PREFIX = ".deleted-";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern PENDING_NAME = Pattern.compile("\\.pending-" + UUID_PATTERN +
        "-(" + UUID_PATTERN + "|[0-9]{1,20})");
    private final Path root;
    private final Path snapshots;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final DirectoryPersistence directoryPersistence;
    private final Compression compression;
    private final WriteMode writeMode;
    private final UUID baseline;
    private final Rollover rollover;
    private boolean busy;
    private boolean closed;

    private ZstdRepository(Path root, FileChannel lockChannel, FileLock lock, DirectoryPersistence directoryPersistence,
                           Compression compression, WriteMode writeMode, UUID baseline, Rollover rollover)
        throws IOException {
        this.root = root;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.directoryPersistence = directoryPersistence;
        this.compression = compression;
        this.writeMode = writeMode;
        this.baseline = baseline;
        this.rollover = rollover;
        snapshots = root.resolve("snapshots");
        Files.createDirectories(snapshots);
        ordinaryDirectory(snapshots);
        recoverPending();
    }

    /** 存储库是本地的，其包含的目录由调用者管理 */
    public static ZstdRepository open(Path location) throws IOException {
        return open(location, directory -> {});
    }

    public static ZstdRepository open(Path location, DirectoryPersistence directoryPersistence) throws IOException {
        return open(location, directoryPersistence, new Compression(3, 1));
    }

    public static ZstdRepository open(Path location, DirectoryPersistence directoryPersistence, Compression compression)
        throws IOException {
        return open(location, directoryPersistence, compression, WriteMode.FULL);
    }

    public static ZstdRepository open(Path location, DirectoryPersistence directoryPersistence, Compression compression,
                                      WriteMode writeMode) throws IOException {
        return open(location, directoryPersistence, compression, writeMode, null);
    }

    public static ZstdRepository open(Path location, DirectoryPersistence directoryPersistence, Compression compression,
                                      WriteMode writeMode, UUID baseline) throws IOException {
        return open(location, directoryPersistence, compression, writeMode, baseline, Rollover.DEFAULT);
    }

    public static ZstdRepository open(Path location, DirectoryPersistence directoryPersistence, Compression compression,
                                      WriteMode writeMode, UUID baseline, Rollover rollover) throws IOException {
        java.util.Objects.requireNonNull(rollover);
        if ((writeMode == WriteMode.DIFFERENTIAL) != (baseline != null))
            throw new IllegalArgumentException("a full baseline UUID is required only for differential mode");
        var root = location.toRealPath();
        var channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("repository is already locked");
            return new ZstdRepository(root, channel, lock, java.util.Objects.requireNonNull(directoryPersistence),
                java.util.Objects.requireNonNull(compression), java.util.Objects.requireNonNull(writeMode), baseline, rollover);
        } catch (OverlappingFileLockException e) {
            var failure = new IOException("repository is already locked", e);
            closeAfterFailure(channel, failure);
            throw failure;
        } catch (IOException | RuntimeException | LinkageError e) {
            closeAfterFailure(channel, e);
            throw e;
        }
    }

    /** 选择新快照的提交布局；所有已识别的布局仍然可读 */
    public enum WriteMode { FULL, EXPERIMENTAL_FIXED_CHUNKS, INCREMENTAL, DIFFERENTIAL }

    public record Rollover(int maxIncrementals, int fullThresholdPercent, long fullIntervalSeconds) {
        static final Rollover DEFAULT = new Rollover(ZstdOverlay.DEFAULT_DEPTH, 0, 0);

        public Rollover {
            if (maxIncrementals < 0 || maxIncrementals > ZstdOverlay.MAX_DEPTH ||
                fullThresholdPercent < 0 || fullThresholdPercent > 100 || fullIntervalSeconds < 0)
                throw new IllegalArgumentException("Invalid incremental rollover configuration");
        }
    }


    public record Compression(int level, int threads) {
        public Compression {
            if (level < 1 || level > 9 || threads < 1 || threads > 4)
                throw new IllegalArgumentException("invalid Zstd compression settings");
        }
    }

    @FunctionalInterface
    public interface DirectoryPersistence {
        void force(Path directory) throws IOException;
    }

    public static final class PublicationUncertain extends CommitUncertain {
        private PublicationUncertain(IOException failure) {
            super("Snapshot is visible but publication persistence could not be confirmed", failure);
        }
    }

    public static final class DeletionUncertain extends CommitUncertain {
        private DeletionUncertain(IOException failure) {
            super("Snapshot was moved out of the live catalog but removal persistence could not be confirmed", failure);
        }
    }

    @Override
    public Writer prepare(Snapshot snapshot, UUID operationId) throws IOException {
        return prepare(snapshot, operationId, new Preparation(256L << 30, 320L << 30, 100_000, () -> {}));
    }

    @Override
    public Writer prepare(Snapshot snapshot, UUID operationId, Preparation limits) throws IOException {
        acquire();
        Path staged = null;
        OutputStream archive = null;
        TarArchiveOutputStream tar = null;
        OutputStream manifest = null;
        try {
            migrateSnapshotDirectories(limits.checkpoint());
            boolean delta = writeMode == WriteMode.INCREMENTAL || writeMode == WriteMode.DIFFERENTIAL;
            Snapshot base = delta ? selectBase(snapshot, limits.checkpoint()) : null;
            List<Point> chain = base == null ? List.of() : checkedChain(base.id(), limits.checkpoint());
            if (writeMode == WriteMode.INCREMENTAL) {
                var reason = rolloverReason(chain, snapshot, limits.checkpoint());
                if (reason != null) {
                    System.getLogger(ZstdRepository.class.getName()).log(System.Logger.Level.INFO,
                        "Incremental backup {0} is writing an independent full checkpoint ({1})", snapshot.id(), reason);
                    base = null;
                }
            }
            Commit baseRecord = null;
            java.util.Map<List<String>, Item> baseCatalog = null;
            var stored = new LimitedInputStream.Budget(limits.archiveBytes());
            var expanded = new LimitedInputStream.Budget(limits.archiveBytes());
            if (base != null) {
                baseRecord = chain.get(0).commit();
                try (var reader = openReader(chain, 0, new ZstdOverlay.MemoryBudget(),
                    limits.checkpoint(), stored, expanded)) {
                    baseCatalog = ZstdOverlay.verifiedCatalog(reader, limits);
                }
            }
            var destination = snapshots.resolve(directoryName(snapshot));
            if (!Files.notExists(destination, LinkOption.NOFOLLOW_LINKS) ||
                findSnapshotDirectory(snapshot.id(), limits.checkpoint()) != null ||
                !Files.notExists(root.resolve(DELETED_PREFIX + snapshot.id()), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("snapshot identity is already used or pending removal");
            staged = Files.createTempDirectory(root, PENDING_PREFIX + operationId + "-");
            if (writeMode == WriteMode.EXPERIMENTAL_FIXED_CHUNKS) {
                var stage = staged;
                return ZstdChunks.writer(root, stage, snapshot, compression.level(), directoryPersistence,
                    (catalog, index, gate) -> publish(stage, destination, snapshot.id(), operationId, CHUNK_COMMIT,
                        catalog, index, gate), () -> discard(stage), this::release);
            }
            var archiveHash = sha256();
            var manifestHash = sha256();
            archive = new DigestOutputStream(new BufferedOutputStream(newFile(staged.resolve(ARCHIVE))), archiveHash);
            archive = ZstdStreams.compress(archive, compression.level(), compression.threads());
            if (base != null) archive = new BoundedOutput(archive, expanded);
            tar = new TarArchiveOutputStream(archive, "UTF-8");
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            tar.setAddPaxHeadersForNonAsciiNames(true);
            manifest = new DigestOutputStream(new BufferedOutputStream(newFile(staged.resolve(MANIFEST))), manifestHash);
            Manifest.header(manifest, snapshot);
            var writer = new TarWriter(staged, destination, snapshot.id(), operationId, tar, manifest, archiveHash, manifestHash);
            if (base == null) return writer;
            UUID baseId = base.id();
            String baseHash = baseRecord.identity();
            return new ZstdOverlay.Writer(staged, writer, baseCatalog, limits.checkpoint(), (inherited, removed, gate) -> {
                writer.maximumStored = stored.remaining();
                writer.link = new DeltaLink(baseId, baseHash, inherited, removed, writeMode == WriteMode.DIFFERENTIAL);
                writer.commit(gate);
            });
        } catch (IOException | RuntimeException | LinkageError e) {
            closeAfterFailure(manifest, e);
            closeAfterFailure(tar == null ? archive : tar, e);
            if (staged != null) {
                try {
                    discard(staged);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            release();
            throw e;
        }
    }

    private static final class BoundedOutput extends java.io.FilterOutputStream {
        private final LimitedInputStream.Budget budget;

        BoundedOutput(OutputStream output, LimitedInputStream.Budget budget) {
            super(output);
            this.budget = budget;
        }

        @Override public void write(int value) throws IOException {
            budget.consume(1);
            out.write(value);
        }
        @Override public void write(byte[] data, int offset, int count) throws IOException {
            budget.consume(count);
            out.write(data, offset, count);
        }
    }

    private String rolloverReason(List<Point> chain, Snapshot current, CommitGate checkpoint) throws IOException {
        if (chain.isEmpty()) return "no baseline";
        if (chain.size() > ZstdOverlay.MAX_DEPTH) return "maximum chain depth";
        if (rollover.maxIncrementals() > 0 && chain.size() > rollover.maxIncrementals()) return "incremental count";
        var full = chain.get(chain.size() - 1);
        if (full.commit().format() != Manifest.FORMAT) return "baseline is not an independent tar.zst";
        if (rollover.fullIntervalSeconds() > 0) {
            var previous = readMetadata(full.folder(), full.id(), full.commit(), 128L << 20, checkpoint);
            if (java.time.Duration.between(previous.createdAt(), current.createdAt()).getSeconds()
                >= rollover.fullIntervalSeconds()) return "full checkpoint interval";
        }
        if (rollover.fullThresholdPercent() > 0 && chain.size() > 1) {
            var fullBytes = archiveSize(full);
            // 乘法之前先除以避免溢出，四舍五入为整个字节
            var percent = rollover.fullThresholdPercent();
            long remaining = (fullBytes / 100) * percent + ((fullBytes % 100) * percent + 99) / 100;
            for (int i = 0; i < chain.size() - 1; i++) {
                checkpoint.enter();
                var bytes = archiveSize(chain.get(i));
                if (bytes >= remaining) return "cumulative compressed incrementals";
                remaining -= bytes;
            }
        }
        return null;
    }

    private static long archiveSize(Point point) throws IOException {
        var attributes = Files.readAttributes(point.folder().resolve(ARCHIVE),
            java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Snapshot payload is not a regular file");
        return attributes.size();
    }

    private Snapshot selectBase(Snapshot current, CommitGate checkpoint) throws IOException {
        if (writeMode == WriteMode.DIFFERENTIAL) {
            var folder = snapshotDirectory(baseline, checkpoint);
            var record = readCommit(folder, baseline);
            if (record.format() != Manifest.FORMAT) throw new IOException("differential baseline must be a full tar.zst");
            var base = readMetadata(folder, baseline, record, 128L << 20, checkpoint);
            if (!base.source().equals(current.source())) throw new IOException("baseline belongs to a different source");
            return base;
        }
        Snapshot latest = null;
        try (var folders = Files.newDirectoryStream(snapshots)) {
            for (var path : folders) {
                checkpoint.enter();
                UUID id = snapshotDirectoryId(path);
                var folder = ordinaryDirectory(path);
                var candidate = readMetadata(folder, id, readCommit(folder, id), 128L << 20, checkpoint);
                if (!candidate.source().equals(current.source())) continue;
                if (latest == null || candidate.createdAt().isAfter(latest.createdAt()) ||
                    candidate.createdAt().equals(latest.createdAt()) && candidate.id().compareTo(latest.id()) > 0)
                    latest = candidate;
            }
        } catch (java.nio.file.DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        return latest;
    }

    private static Snapshot readMetadata(Path folder, UUID id, Commit record, long budget, CommitGate checkpoint)
        throws IOException {
        try (var manifest = new DigestInputStream(new LimitedInputStream(
            new BufferedInputStream(openFile(folder.resolve(MANIFEST))), budget), sha256())) {
            var metadata = Manifest.readHeader(manifest, id, record.format() == DELTA_FORMAT ? Manifest.FORMAT : record.format());
            byte[] buffer = new byte[64 * 1024];
            while (manifest.read(buffer) != -1) checkpoint.enter();
            if (!record.manifestHash().equals(hex(manifest.getMessageDigest())))
                throw new IOException("snapshot manifest differs from its commit");
            String name = folder.getFileName().toString();
            if (!canonicalId(name) && !name.equals(directoryName(metadata)))
                throw new IOException("snapshot directory time differs from its manifest");
            return metadata;
        }
    }

    /** 编写一个单独识别、验证的完整点，而不改变原始点或其后代 */
    public void rebuild(UUID source, Snapshot independent, UUID operation, Preparation limits, CommitGate gate)
        throws IOException {
        if (writeMode != WriteMode.FULL) throw new IOException("rebuild requires a full writer");
        var chain = checkedChain(source, limits.checkpoint());
        var first = chain.get(0);
        var origin = readMetadata(first.folder(), first.id(), first.commit(), 128L << 20, limits.checkpoint());
        if (!origin.source().equals(independent.source())) throw new IOException("rebuild source differs from snapshot source");
        if (origin.consistency() != independent.consistency())
            independent = new Snapshot(independent.id(), independent.createdAt(), independent.source(),
                origin.consistency(), independent.trigger());
        try (var writer = prepare(independent, operation, limits)) {
            try (var reader = openReader(chain, 0, limits.archiveBytes(), new ZstdOverlay.MemoryBudget(), limits.checkpoint())) {
                ZstdOverlay.copyVerified(reader, limits, writer);
            }
            limits.checkpoint().enter();
            writer.commit(gate);
        }
    }

    @Override
    public Reader read(UUID snapshotId, long archiveByteBudget) throws IOException {
        return read(snapshotId, archiveByteBudget, () -> {});
    }

    @Override
    public Reader read(UUID snapshotId, long archiveByteBudget, CommitGate checkpoint) throws IOException {
        acquire();
        try {
            var chain = checkedChain(snapshotId, checkpoint);
            var reader = openReader(chain, 0, archiveByteBudget, new ZstdOverlay.MemoryBudget(), checkpoint);
            return new Reader() {
                private boolean closed;
                @Override public Item next() throws IOException { return reader.next(); }
                @Override public InputStream openContent() throws IOException { return reader.openContent(); }
                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try (reader) {
                        // 在修改存储库之前，所有相关流必须关闭
                    } finally {
                        release();
                    }
                }
            };
        } catch (IOException | RuntimeException | LinkageError failure) {
            release();
            throw failure;
        }
    }

    private List<Point> checkedChain(UUID id, CommitGate checkpoint) throws IOException {
        var chain = new ArrayList<Point>();
        var seen = new java.util.HashSet<UUID>();
        String pinned = null;
        String source = null;
        boolean fullRequired = false;
        while (true) {
            checkpoint.enter();
            if (chain.size() > ZstdOverlay.MAX_DEPTH || !seen.add(id)) throw new IOException("invalid delta depth or cycle");
            var folder = snapshotDirectory(id, checkpoint);
            var record = readCommit(folder, id);
            if (pinned != null && !pinned.equals(record.identity())) throw new IOException("baseline commit identity differs");
            if (fullRequired && record.format() != Manifest.FORMAT) throw new IOException("differential baseline is not full");
            if (record.link() != null || source != null) {
                try (var manifest = new BufferedInputStream(openFile(folder.resolve(MANIFEST)))) {
                    var header = Manifest.readHeader(manifest, id, record.format() == DELTA_FORMAT ? Manifest.FORMAT : record.format());
                    if (source != null && !source.equals(header.source())) throw new IOException("baseline source differs");
                    source = header.source();
                }
            }
            chain.add(new Point(id, folder, record));
            if (record.link() == null) return chain;
            var link = record.link();
            id = link.baseline();
            pinned = link.identity();
            fullRequired = link.differential();
        }
    }

    private record Point(UUID id, Path folder, Commit commit) {}

    private Reader openReader(List<Point> chain, int offset, long budget, ZstdOverlay.MemoryBudget memory,
                              CommitGate checkpoint) throws IOException {
        return openReader(chain, offset, memory, checkpoint,
            new LimitedInputStream.Budget(budget), new LimitedInputStream.Budget(budget));
    }

    private Reader openReader(List<Point> chain, int offset, ZstdOverlay.MemoryBudget memory,
                              CommitGate checkpoint, LimitedInputStream.Budget stored, LimitedInputStream.Budget expanded)
        throws IOException {
        var point = chain.get(offset);
        var folder = point.folder();
        var record = point.commit();
        if (record.format() == Manifest.CHUNK_FORMAT)
            return ZstdChunks.reader(root, folder, point.id(), stored, expanded, record.manifestHash(),
                record.archiveHash(), () -> {});
        var payload = openTar(point, stored, expanded);
        if (record.link() == null) return payload;
        Reader parent = null;
        try {
            parent = openReader(chain, offset + 1, memory, checkpoint, stored, expanded);
            return ZstdOverlay.read(folder, record.link().inherited(), record.link().removed(), payload, parent, memory, checkpoint);
        } catch (IOException | RuntimeException | LinkageError failure) {
            closeAfterFailure(parent, failure);
            closeAfterFailure(payload, failure);
            throw failure;
        }
    }

    private Reader openTar(Point point, LimitedInputStream.Budget stored, LimitedInputStream.Budget expanded) throws IOException {
        DigestInputStream manifest = null;
        DigestInputStream archive = null;
        InputStream inflated = null;
        try {
            var folder = point.folder();
            manifest = new DigestInputStream(new BufferedInputStream(openFile(folder.resolve(MANIFEST))), sha256());
            Manifest.readHeader(manifest, point.id());
            if (Files.size(folder.resolve(ARCHIVE)) > stored.remaining()) throw new IOException("archive exceeds budget");
            archive = new DigestInputStream(new LimitedInputStream(
                new BufferedInputStream(openFile(folder.resolve(ARCHIVE))), stored), sha256());
            inflated = new LimitedInputStream(ZstdStreams.decompress(archive), expanded);
            var tar = new GuardedTarInputStream(inflated);
            return new TarReader(point.commit(), manifest, archive, inflated, tar);
        } catch (IOException | RuntimeException | LinkageError e) {
            closeAfterFailure(inflated == null ? archive : inflated, e);
            closeAfterFailure(manifest, e);
            throw e;
        }
    }

    @Override
    public synchronized java.util.OptionalLong archiveBytes(UUID id) throws IOException {
        var folder = snapshotDirectory(id, () -> {});
        var commit = readCommit(folder, id);
        if (commit.format() != Manifest.FORMAT && commit.format() != DELTA_FORMAT)
            return java.util.OptionalLong.empty();
        return java.util.OptionalLong.of(archiveSize(new Point(id, folder, commit)));
    }

    public synchronized boolean isCommitted(UUID snapshotId, UUID operationId) throws IOException {
        if (closed) throw new IOException("repository is closed");
        var folder = findSnapshotDirectory(snapshotId, () -> {});
        if (folder == null) return false;
        ordinaryDirectory(folder);
        return readCommit(ordinaryDirectory(folder), snapshotId).operationId().equals(operationId);
    }

    @Override
    public Optional<Path> snapshotDirectory(UUID id, long metadataBudget) throws IOException {
        return querySnapshot(id, metadataBudget, (folder, metadata) -> folder);
    }

    @Override
    public Optional<Instant> snapshotCreatedAt(UUID id, long metadataBudget) throws IOException {
        return querySnapshot(id, metadataBudget, (folder, metadata) -> metadata.createdAt());
    }

    private <T> Optional<T> querySnapshot(UUID id, long metadataBudget,
            java.util.function.BiFunction<Path, Snapshot, T> result) throws IOException {
        acquire();
        try {
            var folder = findSnapshotDirectory(id, () -> {});
            if (folder == null) return Optional.empty();
            ordinaryDirectory(folder);
            var metadata = readMetadata(folder, id, readCommit(folder, id), metadataBudget, () -> {});
            return Optional.of(result.apply(folder, metadata));
        } finally {
            release();
        }
    }

    /** 按规范 ID 列出已提交记录的有界页面，而不将存储库索引加载到内存中 */
    public SnapshotPage snapshots(Optional<UUID> after, int limit, long manifestBytes) throws IOException {
        if (limit < 1 || limit > 256 || manifestBytes < 1) throw new IllegalArgumentException("invalid query budget");
        acquire();
        try {
            var selected = new TreeMap<String, Path>();
            var lower = after.map(UUID::toString).orElse("");
            try (var entries = Files.newDirectoryStream(snapshots)) {
                for (var folder : entries) {
                    String id = snapshotDirectoryId(folder).toString();
                    if (id.compareTo(lower) <= 0) continue;
                    if (selected.put(id, folder) != null) throw new IOException("duplicate snapshot UUID " + id);
                    if (selected.size() > limit + 1) selected.pollLastEntry();
                }
            } catch (java.nio.file.DirectoryIteratorException failure) {
                throw failure.getCause();
            }
            boolean more = selected.size() > limit;
            if (more) selected.pollLastEntry();
            var page = new ArrayList<Snapshot>(selected.size());
            for (var entry : selected.entrySet()) {
                UUID id = UUID.fromString(entry.getKey());
                Path folder = ordinaryDirectory(entry.getValue());
                var record = readCommit(folder, id);
                page.add(readMetadata(folder, id, record, manifestBytes, () -> {}));
            }
            return new SnapshotPage(page, more ? Optional.of(page.get(page.size() - 1).id()) : Optional.empty());
        } finally {
            release();
        }
    }



    /** 仅在确认没有已知点依赖快照后，才将快照移出目录 */
    public void deleteIndependentSnapshot(UUID id, CommitGate gate) throws IOException {
        deleteIndependentSnapshot(id, () -> {}, gate);
    }

    public void deleteIndependentSnapshot(UUID id, CommitGate checkpoint, CommitGate gate) throws IOException {
        acquire();
        try {
            // 包含未知格式的存储库不能被假定为无依赖性
            try (var folders = Files.newDirectoryStream(snapshots)) {
                for (var folder : folders) {
                    checkpoint.enter();
                    UUID folderId = snapshotDirectoryId(folder);
                    var record = readCommit(ordinaryDirectory(folder), folderId);
                    if (record.link() != null) {
                        checkedChain(folderId, checkpoint);
                        ZstdOverlay.checkSidecars(folder, record.link().inherited(), record.link().removed(), checkpoint);
                        if (record.link().baseline().equals(id)) throw new IOException("snapshot is a live backup baseline");
                    }
                }
            }
            var source = snapshotDirectory(id, checkpoint);
            checkArtifacts(source);
            var tombstone = root.resolve(DELETED_PREFIX + id);
            if (Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) throw new IOException("snapshot removal is pending");
            directoryPersistence.force(root);
            gate.enter();
            Files.move(source, tombstone, StandardCopyOption.ATOMIC_MOVE);
            try {
                forceRemoval();
            } catch (IOException failure) {
                throw new DeletionUncertain(failure);
            }
            // 提交的删除现在独立于回收其文件。镜像的
            // 在维护期间，目录会在同一存储库锁下进行清理
        } finally {
            release();
        }
    }

    /** 在回收任何对象之前成功标记每个提交的块索引 */
    public long collectUnreferencedBlocks(int maximumReferences, long maximumIndexBytes, CommitGate checkpoint)
        throws IOException {
        if (maximumReferences < 1 || maximumReferences > 1_000_000 || maximumIndexBytes < 1)
            throw new IllegalArgumentException("invalid collection budget");
        acquire();
        try {
            forceRemoval();
            var live = new java.util.HashSet<String>();
            try (var folders = Files.newDirectoryStream(snapshots)) {
                for (var folder : folders) {
                    checkpoint.enter();
                    UUID id = snapshotDirectoryId(folder);
                    var record = readCommit(ordinaryDirectory(folder), id);
                    if (record.link() != null) {
                        checkedChain(id, checkpoint);
                        ZstdOverlay.checkSidecars(folder, record.link().inherited(), record.link().removed(), checkpoint);
                    }
                    if (record.format() == Manifest.CHUNK_FORMAT) {
                        ZstdChunks.mark(folder, id, record.manifestHash(), record.archiveHash(), maximumIndexBytes,
                            maximumReferences, live, checkpoint);
                    } else {
                        try (var input = new DigestInputStream(new LimitedInputStream(
                            new BufferedInputStream(openFile(folder.resolve(MANIFEST))), maximumIndexBytes), sha256())) {
                            Manifest.readHeader(input, id);
                            byte[] buffer = new byte[64 * 1024];
                            while (input.read(buffer) != -1) checkpoint.enter();
                            if (!record.manifestHash().equals(hex(input.getMessageDigest())))
                                throw new IOException("legacy manifest differs from its commit");
                        }
                    }
                }
            }
            recoverDeleted(checkpoint);
            return ZstdChunks.sweep(root, live, directoryPersistence, checkpoint);
        } finally {
            release();
        }
    }

    private void forceRemoval() throws IOException {
        directoryPersistence.force(snapshots);
        directoryPersistence.force(root);
    }

    private void recoverDeleted(CommitGate checkpoint) throws IOException {
        try (var folders = Files.newDirectoryStream(root, folder -> {
            String name = folder.getFileName().toString();
            return name.startsWith(DELETED_PREFIX) && canonicalId(name.substring(DELETED_PREFIX.length()));
        })) {
            for (var folder : folders) {
                checkpoint.enter();
                var name = folder.getFileName().toString().substring(DELETED_PREFIX.length());
                if (findSnapshotDirectory(UUID.fromString(name), checkpoint) != null)
                    throw new IOException("ambiguous snapshot removal");
                checkArtifacts(ordinaryDirectory(folder));
                forceRemoval();
                discard(folder);
            }
        }
    }

    private static Path ordinaryDirectory(Path path) throws IOException {
        var info = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!info.isDirectory() || info.isOther()) throw new IOException("invalid repository directory");
        return path;
    }

    static String directoryName(Snapshot snapshot) {
        return snapshot.createdAt().toString().replace(':', '-') + "--" + snapshot.id();
    }

    private static UUID snapshotDirectoryId(Path folder) throws IOException {
        String name = folder.getFileName().toString();
        if (canonicalId(name)) return UUID.fromString(name);
        int separator = name.length() - 38;
        if (separator > 0 && name.startsWith("--", separator)) {
            String uuid = name.substring(separator + 2);
            if (canonicalId(uuid) && directoryTime(name.substring(0, separator)))
                return UUID.fromString(uuid);
        }
        throw new IOException("unexpected snapshot directory " + name);
    }

    private static boolean directoryTime(String text) {
        int start = text.indexOf('T');
        if (start < 0) return false;
        try {
            var time = Instant.parse(text.substring(0, start) + text.substring(start).replace('-', ':'));
            return time.toString().replace(':', '-').equals(text);
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private Path snapshotDirectory(UUID id, CommitGate checkpoint) throws IOException {
        var folder = findSnapshotDirectory(id, checkpoint);
        if (folder == null) throw new java.nio.file.NoSuchFileException(id.toString());
        return ordinaryDirectory(folder);
    }

    private Path findSnapshotDirectory(UUID id, CommitGate checkpoint) throws IOException {
        Path found = null;
        String suffix = id.toString();
        try (var folders = Files.newDirectoryStream(snapshots)) {
            for (var folder : folders) {
                checkpoint.enter();
                if (!folder.getFileName().toString().endsWith(suffix) || !id.equals(snapshotDirectoryId(folder))) continue;
                if (found != null) throw new IOException("duplicate snapshot UUID " + id);
                found = folder;
            }
        } catch (java.nio.file.DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        return found;
    }

    /** 迁移只改目录名，提交、清单及增量依赖仍使用原来的 UUID */
    private void migrateSnapshotDirectories(CommitGate checkpoint) throws IOException {
        while (true) {
            var legacy = new ArrayList<Path>();
            try (var folders = Files.newDirectoryStream(snapshots)) {
                for (var path : folders) {
                    checkpoint.enter();
                    if (canonicalId(path.getFileName().toString())) legacy.add(path);
                    if (legacy.size() == 256) break;
                }
            } catch (java.nio.file.DirectoryIteratorException failure) {
                throw failure.getCause();
            }
            if (legacy.isEmpty()) return;
            for (var path : legacy) {
                checkpoint.enter();
                UUID id = snapshotDirectoryId(path);
                var folder = snapshotDirectory(id, checkpoint);
                var metadata = readMetadata(folder, id, readCommit(folder, id), 128L << 20, checkpoint);
                var destination = snapshots.resolve(directoryName(metadata));
                if (!Files.notExists(destination, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("snapshot migration destination already exists " + id);
                Files.move(folder, destination, StandardCopyOption.ATOMIC_MOVE);
                directoryPersistence.force(snapshots);
            }
        }
    }

    private static boolean canonicalId(String name) {
        if (name.length() != 36) return false;
        try {
            return UUID.fromString(name).toString().equals(name);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private synchronized void acquire() throws IOException {
        if (closed || busy) throw new IOException("repository is unavailable for this operation");
        busy = true;
    }

    private synchronized void release() {
        busy = false;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        if (busy) throw new IOException("close repository readers and writers first");
        closed = true;
        try (lockChannel; lock) {
            // 必须在通道关闭之前释放操作系统锁
        }
    }

    private final class TarWriter implements Writer {
        private final Path staged;
        private final Path destination;
        private final UUID id;
        private final UUID operationId;
        private final TarArchiveOutputStream tar;
        private final OutputStream manifest;
        private final MessageDigest archiveHash;
        private final MessageDigest manifestHash;
        private final byte[] buffer = new byte[64 * 1024];
        private boolean sealed;
        private boolean closed;
        private boolean failedEntry;
        private DeltaLink link;
        private long maximumStored = Long.MAX_VALUE;

        TarWriter(Path staged, Path destination, UUID id, UUID operationId, TarArchiveOutputStream tar,
                  OutputStream manifest, MessageDigest archiveHash, MessageDigest manifestHash) {
            this.staged = staged;
            this.destination = destination;
            this.id = id;
            this.operationId = operationId;
            this.tar = tar;
            this.manifest = manifest;
            this.archiveHash = archiveHash;
            this.manifestHash = manifestHash;
        }

        @Override
        public void add(Entry entry, InputStream content) throws IOException {
            if (sealed || closed || failedEntry) throw new IOException("snapshot writer cannot accept another entry");
            failedEntry = true;
            var header = new TarArchiveEntry(tarName(entry), true);
            header.setSize(entry.size());
            header.setModTime(java.nio.file.attribute.FileTime.from(
                entry.modifiedAt() == null ? java.time.Instant.EPOCH : entry.modifiedAt()));
            tar.putArchiveEntry(header);
            var digest = sha256();
            long remaining = entry.size();
            if (!entry.directory()) {
                for (int count; (count = content.read(buffer)) != -1; ) {
                    if (count > remaining) throw new IOException("source file grew: " + entry.path());
                    tar.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    remaining -= count;
                }
            }
            if (remaining != 0) throw new IOException("source file shrank: " + entry.path());
            tar.closeArchiveEntry();
            Manifest.item(manifest, entry, hex(digest));
            failedEntry = false;
        }

        @Override
        public void commit() throws IOException {
            commit(() -> {});
        }

        @Override
        public void commit(CommitGate gate) throws IOException {
            if (sealed || closed || failedEntry) throw new IOException("snapshot writer cannot be committed");
            seal();
            if (Files.size(staged.resolve(ARCHIVE)) > maximumStored) throw new IOException("delta chain exceeds its stored byte budget");
            force(staged.resolve(ARCHIVE));
            force(staged.resolve(MANIFEST));
            publish(staged, destination, id, operationId, link == null ? COMMIT_FORMAT : DELTA_COMMIT,
                hex(manifestHash), hex(archiveHash), link, gate);
        }

        private void seal() throws IOException {
            if (sealed) return;
            sealed = true;
            try (manifest; tar) {
                // 在使用其摘要之前，存档必须完成并释放其压缩字节
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                try {
                    seal();
                } finally {
                    if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) discard(staged);
                }
            } finally {
                release();
            }
        }
    }

    private final class TarReader implements Reader {
        private final Commit record;
        private final DigestInputStream manifest;
        private final DigestInputStream archive;
        private final InputStream inflated;
        private final TarArchiveInputStream tar;
        private boolean fileSelected;
        private boolean contentOpen;
        private boolean ended;
        private boolean closed;

        TarReader(Commit record, DigestInputStream manifest, DigestInputStream archive, InputStream inflated,
                  TarArchiveInputStream tar) {
            this.record = record;
            this.manifest = manifest;
            this.archive = archive;
            this.inflated = inflated;
            this.tar = tar;
        }

        @Override
        public Item next() throws IOException {
            if (closed) throw new IOException("snapshot reader is closed");
            if (fileSelected || contentOpen) throw new IOException("close the current file before advancing");
            if (ended) return null;
            var item = Manifest.readItem(manifest);
            var header = tar.getNextEntry();
            fileSelected = false;
            if (item == null && header == null) {
                byte[] padding = new byte[8192];
                for (int count; (count = inflated.read(padding)) != -1; ) {
                    for (int i = 0; i < count; i++)
                        if (padding[i] != 0) throw new IOException("unexpected bytes following TAR archive");
                }
                archive.transferTo(OutputStream.nullOutputStream());
                if (!record.manifestHash().equals(hex(manifest.getMessageDigest())) ||
                    !record.archiveHash().equals(hex(archive.getMessageDigest())))
                    throw new IOException("snapshot digest differs from its commit record");
                ended = true;
                return null;
            }
            if (item == null || header == null) throw new IOException("archive and manifest entry counts differ");
            var entry = item.entry();
            byte type = header.getLinkFlag();
            boolean supportedType = entry.directory() ? type == TarConstants.LF_DIR :
                type == TarConstants.LF_NORMAL || type == TarConstants.LF_OLDNORM;
            if (!header.isCheckSumOK() || !tarName(entry).equals(header.getName()) || header.getSize() != entry.size() ||
                !supportedType || header.isSparse())
                throw new IOException("archive entry differs from the manifest");
            fileSelected = !entry.directory();
            return item;
        }

        @Override
        public InputStream openContent() throws IOException {
            if (closed || !fileSelected) throw new IOException("no file is selected");
            fileSelected = false;
            contentOpen = true;
            return new InputStream() {
                private boolean released;

                @Override
                public int read() throws IOException {
                    if (released || TarReader.this.closed) throw new IOException("archive content is closed");
                    return tar.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int count) throws IOException {
                    if (released || TarReader.this.closed) throw new IOException("archive content is closed");
                    return tar.read(bytes, offset, count);
                }

                @Override
                public void close() {
                    if (!released) {
                        released = true;
                        contentOpen = false;
                    }
                }
            };
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try (manifest; tar) { /* TAR owns its decompressor and archive stream. */ }
        }
    }

    private void publish(Path staged, Path destination, UUID id, UUID operationId, String format,
                         String manifestHash, String payloadHash, CommitGate gate) throws IOException {
        publish(staged, destination, id, operationId, format, manifestHash, payloadHash, null, gate);
    }

    private void publish(Path staged, Path destination, UUID id, UUID operationId, String format,
                         String manifestHash, String payloadHash, DeltaLink link, CommitGate gate) throws IOException {
        try (var record = newFile(staged.resolve(COMMIT))) {
            var value = format + "\n" + id + "\n" + operationId + "\n" + manifestHash + "\n" + payloadHash + "\n";
            if (link != null) value += (link.differential() ? "differential" : "incremental") + "\n" +
                link.baseline() + "\n" + link.identity() + "\n" + link.inherited() + "\n" + link.removed() + "\n";
            record.write(value.getBytes(StandardCharsets.US_ASCII));
        }
        force(staged.resolve(COMMIT));
        directoryPersistence.force(staged);
        directoryPersistence.force(root);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("snapshot already exists");
        gate.enter();
        Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
        try {
            directoryPersistence.force(snapshots);
            directoryPersistence.force(root);
        } catch (IOException failure) {
            throw new PublicationUncertain(failure);
        }
    }

    private record DeltaLink(UUID baseline, String identity, String inherited, String removed, boolean differential) {}

    private record Commit(UUID operationId, String manifestHash, String archiveHash, long format, String identity,
                          DeltaLink link) {}

    private static Commit readCommit(Path folder, UUID expected) throws IOException {
        try (var in = openFile(folder.resolve(COMMIT))) {
            var data = in.readNBytes(1024);
            if (data.length == 1024) throw new IOException("oversized snapshot commit");
            var lines = new String(data, StandardCharsets.US_ASCII).split("\n", -1);
            boolean delta = lines[0].equals(DELTA_COMMIT);
            if (lines.length != (delta ? 11 : 6) ||
                !(delta || lines[0].equals(COMMIT_FORMAT) || lines[0].equals(CHUNK_COMMIT)) ||
                !lines[1].equals(expected.toString()) || !isDigest(lines[3]) || !isDigest(lines[4]) ||
                !lines[lines.length - 1].isEmpty())
                throw new IOException("invalid snapshot commit");
            UUID operation = UUID.fromString(lines[2]);
            if (!operation.toString().equals(lines[2])) throw new IOException("noncanonical snapshot operation");
            DeltaLink link = null;
            long format = lines[0].equals(CHUNK_COMMIT) ? Manifest.CHUNK_FORMAT : Manifest.FORMAT;
            if (delta) {
                if (!(lines[5].equals("differential") || lines[5].equals("incremental")) ||
                    !canonicalId(lines[6]) || !isDigest(lines[7]) || !isDigest(lines[8]) || !isDigest(lines[9]))
                    throw new IOException("invalid snapshot baseline reference");
                format = DELTA_FORMAT;
                link = new DeltaLink(UUID.fromString(lines[6]), lines[7], lines[8], lines[9], lines[5].equals("differential"));
            }
            var digest = sha256();
            digest.update(data);
            return new Commit(operation, lines[3], lines[4], format, hex(digest), link);
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid snapshot commit identity", e);
        }
    }

    private static boolean isDigest(String candidate) { return candidate.matches("[0-9a-f]{64}"); }

    private static String tarName(Entry entry) {
        return String.join("/", entry.path()) + (entry.directory() ? "/" : "");
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    static OutputStream newFile(Path path) throws IOException {
        return Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
    }

    static InputStream openFile(Path path) throws IOException {
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
    }

    static void force(Path file) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static void discard(Path staged) throws IOException {
        for (String file : new String[] { COMMIT, MANIFEST, ARCHIVE, CHUNK_INDEX, CHUNK_STAGE,
            ZstdOverlay.INHERITED, ZstdOverlay.REMOVED })
            Files.deleteIfExists(staged.resolve(file));
        Files.delete(staged);
    }

    private void recoverPending() throws IOException {
        try (var candidates = Files.newDirectoryStream(root, path -> PENDING_NAME.matcher(path.getFileName().toString()).matches())) {
            for (var candidate : candidates) {
                checkArtifacts(ordinaryDirectory(candidate));
                // 规范快照是不可变的。一个已完全编写但未发表的阶段
                // 仍处于废弃状态，并且无法在重新打开时替换或删除已提交的快照
                discard(candidate);
            }
        } catch (java.nio.file.DirectoryIteratorException failure) {
            throw failure.getCause();
        }
    }

    private static void checkArtifacts(Path folder) throws IOException {
        try (var files = Files.newDirectoryStream(folder)) {
            for (var file : files) {
                var name = file.getFileName().toString();
                if (!(name.equals(COMMIT) || name.equals(MANIFEST) || name.equals(ARCHIVE) ||
                    name.equals(CHUNK_INDEX) || name.equals(CHUNK_STAGE) || name.equals(ZstdOverlay.INHERITED) ||
                    name.equals(ZstdOverlay.REMOVED)) ||
                    !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("unexpected snapshot artifact");
            }
        }
    }

    static void closeAfterFailure(AutoCloseable resource, Throwable error) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception | LinkageError close) {
            error.addSuppressed(close);
        }
    }
}
