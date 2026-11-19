// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package barusikozako.zstd;

import static barusikozako.zstd.ZstdRepository.CHUNK_INDEX;
import static barusikozako.zstd.ZstdRepository.CHUNK_STAGE;
import static barusikozako.zstd.ZstdRepository.MANIFEST;

import barusikozako.backend.Entry;
import barusikozako.backend.Repository;
import barusikozako.backend.Snapshot;
import barusikozako.compression.zstd.ZstdStreams;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** Experimental logical manifests and shared, individually compressed fixed-size blocks. */
final class ZstdChunks {
    private static final int BLOCK_BYTES = 64 * 1024;
    private static final int HASH_BYTES = 32;
    private static final long MAX_COMPRESSED_BLOCK = 128 * 1024;
    private static final String OBJECTS = "blocks-v2";
    private static final long MAX_MANIFEST_BYTES = 128L * 1024 * 1024;

    private ZstdChunks() {}

    @FunctionalInterface
    interface Publish {
        void commit(String manifestHash, String indexHash, Repository.CommitGate gate) throws IOException;
    }

    @FunctionalInterface
    interface Cleanup {
        void discard() throws IOException;
    }

    static Repository.Writer writer(Path root, Path stage, Snapshot snapshot, int level,
                                    ZstdRepository.DirectoryPersistence persistence, Publish publish,
                                    Cleanup cleanup, Runnable release) throws IOException {
        var manifest = digestOutput(stage.resolve(MANIFEST));
        try {
            Manifest.header(manifest, snapshot, Manifest.CHUNK_FORMAT);
            return new ChunkWriter(root, stage, level, persistence, publish, cleanup, release, manifest,
                digestOutput(stage.resolve(CHUNK_INDEX)));
        } catch (IOException | RuntimeException | LinkageError failure) {
            ZstdRepository.closeAfterFailure(manifest, failure);
            throw failure;
        }
    }

    static Repository.Reader reader(Path root, Path folder, UUID id, LimitedInputStream.Budget stored,
                                    LimitedInputStream.Budget expanded,
                                    String manifestHash, String indexHash, Runnable release) throws IOException {
        var manifest = digestInput(folder.resolve(MANIFEST), MAX_MANIFEST_BYTES);
        try {
            Manifest.readHeader(manifest, id, Manifest.CHUNK_FORMAT);
            var indexPath = folder.resolve(CHUNK_INDEX);
            long indexBytes = regularSize(indexPath);
            if (indexBytes % HASH_BYTES != 0) throw new IOException("invalid chunk index length");
            stored.consume(indexBytes);
            return new ChunkReader(root, stored, expanded, manifestHash, indexHash, manifest,
                digestInput(indexPath, indexBytes), release);
        } catch (IOException | RuntimeException | LinkageError failure) {
            ZstdRepository.closeAfterFailure(manifest, failure);
            throw failure;
        }
    }

    static void mark(Path snapshot, UUID id, String manifestHash, String indexHash, long maximumIndexBytes,
                     int maximumReferences, Set<String> live, Repository.CommitGate checkpoint) throws IOException {
        try (var manifest = digestInput(snapshot.resolve(MANIFEST), Math.min(maximumIndexBytes, MAX_MANIFEST_BYTES));
             var index = digestInput(snapshot.resolve(CHUNK_INDEX), maximumIndexBytes)) {
            Manifest.readHeader(manifest, id, Manifest.CHUNK_FORMAT);
            byte[] hash = new byte[HASH_BYTES];
            for (Repository.Item item; (item = Manifest.readItem(manifest)) != null;) {
                checkpoint.enter();
                long remaining = item.entry().size();
                while (remaining > 0) {
                    checkpoint.enter();
                    if (index.readNBytes(hash, 0, hash.length) != hash.length) throw new IOException("truncated chunk index");
                    live.add(HexFormat.of().formatHex(hash));
                    if (live.size() > maximumReferences) throw new IOException("block collection reference budget exceeded");
                    remaining -= Math.min(remaining, BLOCK_BYTES);
                }
            }
            if (index.read() != -1 || !manifestHash.equals(ZstdRepository.hex(manifest.getMessageDigest())) ||
                !indexHash.equals(ZstdRepository.hex(index.getMessageDigest())))
                throw new IOException("cannot collect with invalid chunk metadata");
        }
    }

    static long sweep(Path root, Set<String> live, ZstdRepository.DirectoryPersistence persistence,
                      Repository.CommitGate checkpoint) throws IOException {
        var objects = root.resolve(OBJECTS);
        if (Files.notExists(objects, LinkOption.NOFOLLOW_LINKS)) return 0;
        long reclaimed = 0;
        try (var shards = Files.newDirectoryStream(directory(objects))) {
            for (var shard : shards) {
                var prefix = shard.getFileName().toString();
                if (!prefix.matches("[0-9a-f]{2}")) throw new IOException("unexpected block shard");
                boolean changed = false;
                try (var files = Files.newDirectoryStream(directory(shard))) {
                    for (var file : files) {
                        checkpoint.enter();
                        String name = file.getFileName().toString();
                        if (!name.startsWith(prefix) || !name.matches("[0-9a-f]{64}\\.zst"))
                            throw new IOException("unexpected block object");
                        if (!live.contains(name.substring(0, 64))) {
                            long bytes = regularSize(file);
                            Files.delete(file);
                            changed = true;
                            reclaimed = Math.addExact(reclaimed, bytes);
                        }
                    }
                } finally {
                    if (changed) persistence.force(shard);
                }
            }
        }
        return reclaimed;
    }

    private static DigestOutputStream digestOutput(Path file) throws IOException {
        return new DigestOutputStream(new BufferedOutputStream(ZstdRepository.newFile(file)), ZstdRepository.sha256());
    }

    private static DigestInputStream digestInput(Path file, long maximum) throws IOException {
        if (regularSize(file) > maximum) throw new IOException("chunk metadata exceeds its byte limit");
        return new DigestInputStream(new LimitedInputStream(
            new BufferedInputStream(ZstdRepository.openFile(file)), maximum), ZstdRepository.sha256());
    }

    private static Path directory(Path path) throws IOException {
        var info = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!info.isDirectory() || info.isOther()) throw new IOException("invalid block store directory");
        return path;
    }

    private static void ensureDirectory(Path path) throws IOException {
        try {
            Files.createDirectory(path);
        } catch (java.nio.file.FileAlreadyExistsException existing) {
            directory(path);
        }
    }

    private static long regularSize(Path path) throws IOException {
        var info = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!info.isRegularFile()) throw new IOException("expected a regular chunk storage file");
        return info.size();
    }

    private static Path object(Path root, String hash) {
        return root.resolve(OBJECTS).resolve(hash.substring(0, 2)).resolve(hash + ".zst");
    }

    private static void load(Path path, byte[] output, int length, byte[] wanted, long compressedLength) throws IOException {
        if (compressedLength < 1 || compressedLength > MAX_COMPRESSED_BLOCK) throw new IOException("invalid compressed block size");
        try (var source = ZstdRepository.openFile(path);
             var stream = ZstdStreams.decompressBlock(new LimitedInputStream(source, compressedLength))) {
            if (stream.readNBytes(output, 0, length) != length || stream.read() != -1)
                throw new IOException("chunk content length differs from its index");
            var digest = ZstdRepository.sha256();
            digest.update(output, 0, length);
            if (!MessageDigest.isEqual(wanted, digest.digest())) throw new IOException("chunk SHA-256 verification failed");
        }
    }

    private static final class ChunkWriter implements Repository.Writer {
        private final Path root;
        private final Path stage;
        private final int level;
        private final ZstdRepository.DirectoryPersistence persistence;
        private final Publish publish;
        private final Cleanup cleanup;
        private final Runnable release;
        private final DigestOutputStream manifest;
        private final DigestOutputStream index;
        private final byte[] block = new byte[BLOCK_BYTES];
        private final byte[] verification = new byte[BLOCK_BYTES];
        private final Set<Path> changedDirectories = new HashSet<>();
        private final LinkedHashMap<String, Integer> verified = new LinkedHashMap<>(16, .75f, true);
        private boolean sealed;
        private boolean closed;
        private boolean failed;

        ChunkWriter(Path root, Path stage, int level, ZstdRepository.DirectoryPersistence persistence, Publish publish,
                    Cleanup cleanup, Runnable release, DigestOutputStream manifest, DigestOutputStream index) {
            this.root = root;
            this.stage = stage;
            this.level = level;
            this.persistence = persistence;
            this.publish = publish;
            this.cleanup = cleanup;
            this.release = release;
            this.manifest = manifest;
            this.index = index;
        }

        @Override
        public void add(Entry entry, InputStream content) throws IOException {
            if (sealed || closed || failed) throw new IOException("chunk writer is unavailable");
            failed = true;
            var fileDigest = ZstdRepository.sha256();
            if (!entry.directory()) {
                for (long remaining = entry.size(); remaining > 0;) {
                    int length = (int) Math.min(remaining, BLOCK_BYTES);
                    if (content.readNBytes(block, 0, length) != length) throw new IOException("snapshot file shrank");
                    fileDigest.update(block, 0, length);
                    store(length);
                    remaining -= length;
                }
                if (content.read() != -1) throw new IOException("snapshot file grew");
            }
            Manifest.item(manifest, entry, ZstdRepository.hex(fileDigest));
            failed = false;
        }

        private void store(int length) throws IOException {
            var digest = ZstdRepository.sha256();
            digest.update(block, 0, length);
            byte[] identity = digest.digest();
            String hash = HexFormat.of().formatHex(identity);
            Integer cached = verified.get(hash);
            if (cached != null && cached != length) throw new IOException("chunk hash has conflicting lengths");
            if (cached == null) {
                Path destination = object(root, hash);
                Path parent = destination.getParent();
                // Also force an existing shard before publishing a new reference. An earlier
                // cancelled writer may have left a visible but not durably linked orphan.
                if (changedDirectories.add(parent)) {
                    if (changedDirectories.size() == 1) ensureDirectory(root.resolve(OBJECTS));
                    ensureDirectory(parent);
                }
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    load(destination, verification, length, identity, regularSize(destination));
                    ZstdRepository.force(destination);
                } else {
                    Path temporary = stage.resolve(CHUNK_STAGE);
                    try (var target = ZstdRepository.newFile(temporary);
                         var compressed = ZstdStreams.compressBlock(new BufferedOutputStream(target), level)) {
                        compressed.write(block, 0, length);
                    }
                    ZstdRepository.force(temporary);
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                }
                verified.put(hash, length);
                if (verified.size() > 4096) verified.remove(verified.keySet().iterator().next());
            }
            index.write(identity);
        }

        @Override public void commit() throws IOException { commit(() -> {}); }

        @Override
        public void commit(Repository.CommitGate gate) throws IOException {
            if (sealed || closed || failed) throw new IOException("chunk writer cannot be committed");
            seal();
            if (regularSize(stage.resolve(MANIFEST)) > MAX_MANIFEST_BYTES)
                throw new IOException("chunk manifest exceeds its byte limit");
            ZstdRepository.force(stage.resolve(MANIFEST));
            ZstdRepository.force(stage.resolve(CHUNK_INDEX));
            if (!changedDirectories.isEmpty()) {
                for (var shard : changedDirectories) persistence.force(shard);
                persistence.force(root.resolve(OBJECTS));
            }
            publish.commit(ZstdRepository.hex(manifest.getMessageDigest()), ZstdRepository.hex(index.getMessageDigest()), gate);
        }

        private void seal() throws IOException {
            if (sealed) return;
            sealed = true;
            try (manifest; index) {
                // Close data streams before binding their digests.
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
                    if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) cleanup.discard();
                }
            } finally {
                release.run();
            }
        }
    }

    private static final class ChunkReader implements Repository.Reader {
        private final Path root;
        private final String manifestHash;
        private final String indexHash;
        private final DigestInputStream manifest;
        private final DigestInputStream index;
        private final Runnable release;
        private final byte[] block = new byte[BLOCK_BYTES];
        private byte[] lastHash;
        private int lastLength;
        private final LimitedInputStream.Budget stored;
        private final LimitedInputStream.Budget expanded;
        private long remaining;
        private boolean selected;
        private boolean open;
        private boolean ended;
        private boolean closed;

        ChunkReader(Path root, LimitedInputStream.Budget stored, LimitedInputStream.Budget expanded,
                    String manifestHash, String indexHash, DigestInputStream manifest,
                    DigestInputStream index, Runnable release) {
            this.root = root;
            this.stored = stored;
            this.expanded = expanded;
            this.manifestHash = manifestHash;
            this.indexHash = indexHash;
            this.manifest = manifest;
            this.index = index;
            this.release = release;
        }

        @Override
        public Repository.Item next() throws IOException {
            if (closed || selected || open || remaining != 0) throw new IOException("finish the current chunk file first");
            if (ended) return null;
            var item = Manifest.readItem(manifest);
            if (item == null) {
                if (index.read() != -1 || !manifestHash.equals(ZstdRepository.hex(manifest.getMessageDigest())) ||
                    !indexHash.equals(ZstdRepository.hex(index.getMessageDigest())))
                    throw new IOException("chunk index or manifest differs from its commit");
                ended = true;
                return null;
            }
            if (!item.entry().directory()) {
                selected = true;
                remaining = item.entry().size();
            }
            return item;
        }

        @Override
        public InputStream openContent() throws IOException {
            if (closed || !selected) throw new IOException("no chunk file is selected");
            selected = false;
            open = true;
            return new InputStream() {
                private final byte[] one = new byte[1];
                private int position;
                private int end;
                private boolean released;

                @Override public int read() throws IOException {
                    return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
                }

                @Override public int read(byte[] output, int offset, int length) throws IOException {
                    java.util.Objects.checkFromIndexSize(offset, length, output.length);
                    if (released || closed) throw new IOException("chunk content is closed");
                    if (length == 0) return 0;
                    if (remaining == 0) return -1;
                    if (position == end) {
                        byte[] hash = index.readNBytes(HASH_BYTES);
                        if (hash.length != HASH_BYTES) throw new IOException("truncated chunk index");
                        end = (int) Math.min(remaining, BLOCK_BYTES);
                        loadBlock(hash, end);
                        position = 0;
                    }
                    int copied = Math.min(length, end - position);
                    expanded.consume(copied);
                    System.arraycopy(block, position, output, offset, copied);
                    position += copied;
                    remaining -= copied;
                    return copied;
                }

                @Override public void close() {
                    if (released) return;
                    released = true;
                    open = false;
                }
            };
        }

        private void loadBlock(byte[] hash, int length) throws IOException {
            if (length == lastLength && Arrays.equals(hash, lastHash)) return;
            lastHash = null;
            var path = object(root, HexFormat.of().formatHex(hash));
            directory(root.resolve(OBJECTS));
            directory(path.getParent());
            long compressed = regularSize(path);
            stored.consume(compressed);
            load(path, block, length, hash, compressed);
            lastHash = hash;
            lastLength = length;
        }

        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try (manifest; index) {
                // Releases all snapshot streams before releasing the repository session.
            } finally {
                release.run();
            }
        }
    }
}
