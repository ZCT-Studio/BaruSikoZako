// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.zstd;

import io.github.zct_studio.barusikozako.backend.Entry;
import io.github.zct_studio.barusikozako.backend.Repository;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 版本 3 存档携带更改的有效负载，并明确对继承和删除的路径进行分类 */
final class ZstdOverlay {
    static final String INHERITED = "inherited.jsonl";
    static final String REMOVED = "removed.jsonl";
    static final int MAX_DEPTH = 8;
    private static final long META_LIMIT = 16L << 20;

    private ZstdOverlay() {}

    interface Finish {
        void publish(String inherited, String removed, Repository.CommitGate gate) throws IOException;
    }

    static Map<List<String>, Repository.Item> verifiedCatalog(Repository.Reader base, Repository.Preparation limits)
        throws IOException {
        return verifiedCatalog(base, limits, null);
    }

    static void copyVerified(Repository.Reader source, Repository.Preparation limits, Repository.Writer destination)
        throws IOException {
        verifiedCatalog(source, limits, destination);
    }

    private static Map<List<String>, Repository.Item> verifiedCatalog(Repository.Reader base,
        Repository.Preparation limits, Repository.Writer destination) throws IOException {
        var entries = new LinkedHashMap<List<String>, Repository.Item>();
        var metadata = new MemoryBudget();
        byte[] buffer = new byte[64 * 1024];
        long bytes = 0;
        for (;;) {
            limits.checkpoint().enter();
            var item = base.next();
            if (item == null) return entries;
            var entry = item.entry();
            if (entries.size() >= limits.entries() || entry.size() > limits.contentBytes() - bytes)
                throw new IOException("baseline exceeds the backup budget");
            var parent = entry.path().subList(0, entry.path().size() - 1);
            if (!parent.isEmpty() && (!entries.containsKey(parent) || !entries.get(parent).entry().directory()))
                throw new IOException("baseline has an undeclared parent");
            metadata.add(entry.path());
            if (entries.putIfAbsent(entry.path(), item) != null) throw new IOException("duplicate baseline path");
            if (entry.directory()) {
                if (destination != null) destination.add(entry, InputStream.nullInputStream());
            } else {
                try (var content = limits.pace().apply(base.openContent())) {
                    String observed;
                    if (destination == null) {
                        observed = hash(entry, content, buffer, limits.checkpoint());
                    } else {
                        var digest = ZstdRepository.sha256();
                        destination.add(entry, new DigestInputStream(content, digest));
                        observed = ZstdRepository.hex(digest);
                    }
                    if (!item.sha256().equals(observed))
                        throw new IOException("baseline content digest mismatch");
                }
            }
            bytes += entry.size();
        }
    }

    private static String hash(Entry entry, InputStream input, byte[] buffer, Repository.CommitGate checkpoint)
        throws IOException {
        var digest = ZstdRepository.sha256();
        long remaining = entry.size();
        for (;;) {
            checkpoint.enter();
            int count = input.read(buffer);
            if (count == -1) break;
            if (count > remaining) throw new IOException("file length changed");
            digest.update(buffer, 0, count);
            remaining -= count;
        }
        if (remaining != 0) throw new IOException("file length changed");
        return ZstdRepository.hex(digest);
    }

    static final class Writer implements Repository.Writer {
        private final Path stage;
        private final Repository.Writer payload;
        private final Map<List<String>, Repository.Item> base;
        private final Repository.CommitGate checkpoint;
        private final Finish publish;
        private final DigestOutputStream inherited;
        private final byte[] buffer = new byte[64 * 1024];
        private boolean failed;
        private boolean sealed;

        Writer(Path stage, Repository.Writer payload, Map<List<String>, Repository.Item> base,
               Repository.CommitGate checkpoint, Finish publish) throws IOException {
            this.stage = stage;
            this.payload = payload;
            this.base = base;
            this.checkpoint = checkpoint;
            this.publish = publish;
            inherited = output(stage.resolve(INHERITED));
        }

        @Override public void add(Entry entry, InputStream input) throws IOException {
            add(entry, input, () -> { throw new IOException("comparison requires stable file rereading"); });
        }

        @Override public void add(Entry entry, InputStream input, Repository.ContentSource reopen) throws IOException {
            if (failed || sealed) throw new IOException("delta writer is unavailable");
            failed = true;
            var prior = base.remove(entry.path());
            if (entry.directory() || prior == null || prior.entry().directory() || prior.entry().size() != entry.size()) {
                payload.add(entry, input);
            } else {
                String digest = hash(entry, input, buffer, checkpoint);
                if (digest.equals(prior.sha256())) {
                    Manifest.item(inherited, entry, digest);
                } else {
                    var replayDigest = ZstdRepository.sha256();
                    try (var replay = new DigestInputStream(reopen.open(), replayDigest)) {
                        payload.add(entry, replay);
                    }
                    if (!digest.equals(ZstdRepository.hex(replayDigest)))
                        throw new IOException("the stable view changed between comparison and writing");
                }
            }
            failed = false;
        }

        @Override public void commit() throws IOException { commit(() -> {}); }

        @Override public void commit(Repository.CommitGate gate) throws IOException {
            if (failed || sealed) throw new IOException("delta writer cannot commit");
            sealed = true;
            inherited.close();
            var removed = output(stage.resolve(REMOVED));
            try (removed) {
                for (var item : base.values()) {
                    checkpoint.enter();
                    Manifest.item(removed, item.entry(), item.sha256());
                }
            }
            for (var path : List.of(stage.resolve(INHERITED), stage.resolve(REMOVED))) {
                if (java.nio.file.Files.size(path) > META_LIMIT) throw new IOException("delta metadata exceeds its byte limit");
                ZstdRepository.force(path);
            }
            publish.publish(ZstdRepository.hex(inherited.getMessageDigest()), ZstdRepository.hex(removed.getMessageDigest()), gate);
        }

        @Override public void close() throws IOException {
            try (payload; inherited) {
                sealed = true;
            }
        }
    }

    private static DigestOutputStream output(Path file) throws IOException {
        return new DigestOutputStream(new BufferedOutputStream(ZstdRepository.newFile(file)), ZstdRepository.sha256());
    }

    static final class MemoryBudget {
        private long used;
        void add(List<String> path) throws IOException {
            used += 256L + path.stream().mapToLong(part -> 64L + 2L * part.length()).sum();
            if (used > 64L << 20) throw new IOException("delta catalog exceeds its memory budget");
        }
    }

    private static Map<List<String>, Repository.Item> sidecar(Path path, String hash, MemoryBudget budget,
                                                              Repository.CommitGate checkpoint) throws IOException {
        var result = new LinkedHashMap<List<String>, Repository.Item>();
        try (var input = new DigestInputStream(new LimitedInputStream(
            new BufferedInputStream(ZstdRepository.openFile(path)), META_LIMIT), ZstdRepository.sha256())) {
            for (Repository.Item item; (item = Manifest.readItem(input)) != null;) {
                checkpoint.enter();
                budget.add(item.entry().path());
                if (result.putIfAbsent(item.entry().path(), item) != null) throw new IOException("duplicate delta path");
            }
            if (!hash.equals(ZstdRepository.hex(input.getMessageDigest()))) throw new IOException("delta metadata digest mismatch");
        }
        return result;
    }

    static Reader read(Path folder, String inheritedHash, String removedHash, Repository.Reader payload,
                       Repository.Reader parent, MemoryBudget memory, Repository.CommitGate checkpoint) throws IOException {
        var inherited = sidecar(folder.resolve(INHERITED), inheritedHash, memory, checkpoint);
        var removed = sidecar(folder.resolve(REMOVED), removedHash, memory, checkpoint);
        for (var item : inherited.values()) {
            if (item.entry().directory() || removed.containsKey(item.entry().path()))
                throw new IOException("inherited delta records must be distinct files");
        }
        return new Reader(payload, parent, inherited, removed, memory, checkpoint);
    }

    static void checkSidecars(Path folder, String inheritedHash, String removedHash, Repository.CommitGate checkpoint)
        throws IOException {
        var memory = new MemoryBudget();
        sidecar(folder.resolve(INHERITED), inheritedHash, memory, checkpoint);
        sidecar(folder.resolve(REMOVED), removedHash, memory, checkpoint);
    }

    static final class Reader implements Repository.Reader {
        private final Repository.Reader payload;
        private final Repository.Reader parent;
        private final Map<List<String>, Repository.Item> inherited;
        private final Map<List<String>, Repository.Item> removed;
        private final MemoryBudget memory;
        private final Repository.CommitGate checkpoint;
        private final HashSet<List<String>> local = new HashSet<>();
        private Repository.Reader selected;
        private final byte[] discard = new byte[64 * 1024];
        private boolean localDone;

        Reader(Repository.Reader payload, Repository.Reader parent, Map<List<String>, Repository.Item> inherited,
               Map<List<String>, Repository.Item> removed, MemoryBudget memory, Repository.CommitGate checkpoint) {
            this.payload = payload;
            this.parent = parent;
            this.inherited = inherited;
            this.removed = removed;
            this.memory = memory;
            this.checkpoint = checkpoint;
        }

        @Override public Repository.Item next() throws IOException {
            if (!localDone) {
                var item = payload.next();
                if (item != null) {
                    var path = item.entry().path();
                    memory.add(path);
                    if (inherited.containsKey(path) || removed.containsKey(path) || !local.add(path))
                        throw new IOException("conflicting local delta path");
                    selected = payload;
                    return item;
                }
                localDone = true;
            }
            for (;;) {
                checkpoint.enter();
                var item = parent.next();
                if (item == null) {
                    if (!inherited.isEmpty() || !removed.isEmpty()) throw new IOException("delta references missing baseline paths");
                    selected = null;
                    return null;
                }
                var path = item.entry().path();
                var retained = inherited.remove(path);
                if (retained != null) {
                    if (item.entry().directory() || item.entry().size() != retained.entry().size() ||
                        !retained.sha256().equals(item.sha256())) throw new IOException("inherited content identity differs");
                    selected = parent;
                    return retained;
                }
                var deleted = removed.remove(path);
                if (deleted == null && !local.contains(path)) throw new IOException("unclassified baseline path");
                if (deleted != null && !deleted.equals(item)) throw new IOException("removed content identity differs");
                if (!item.entry().directory()) {
                    try (var ignored = parent.openContent()) {
                        while (ignored.read(discard) != -1) checkpoint.enter();
                    }
                }
            }
        }

        @Override public InputStream openContent() throws IOException {
            if (selected == null) throw new IOException("no delta entry selected");
            return selected.openContent();
        }

        @Override public void close() throws IOException {
            try (parent; payload) {
                // 存储库锁属于外部会话
            }
        }
    }
}
