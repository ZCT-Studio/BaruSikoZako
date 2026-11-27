// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.standalone;

import io.github.zct_studio.barusikozako.backend.Consistency;
import io.github.zct_studio.barusikozako.backend.ManagedRepository;
import io.github.zct_studio.barusikozako.backend.Snapshot;
import io.github.zct_studio.barusikozako.core.BackupEngine;
import io.github.zct_studio.barusikozako.core.BackupExclusions;
import io.github.zct_studio.barusikozako.core.RestoreResult;
import io.github.zct_studio.barusikozako.core.StagedDirectoryView;
import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import io.github.zct_studio.barusikozako.zstd.ZstdProvider;
import io.github.zct_studio.barusikozako.zstd.ZstdRepository;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 独立工作站的同步编排：直接复用引擎与 Zstd 后端，语义与游戏内命令一致。
 * 与游戏内共享同一套快照格式、命名与事务逻辑。
 */
public final class Workstation {
    public static final String SOURCE_ID = "world";

    private final Path world;
    private final Path gameRoot;
    private final Path configDir;
    private final StandaloneConfig config;
    private final ZstdProvider provider;

    public Workstation(Path world, Path gameRoot) throws IOException {
        this.world = world.toAbsolutePath().normalize();
        if (this.world.getFileName() == null) throw new IOException("Invalid world directory");
        this.gameRoot = gameRoot.toAbsolutePath().normalize();
        this.configDir = resolveConfigDir(this.gameRoot);
        this.config = StandaloneConfig.load(this.configDir);
        this.provider = new ZstdProvider();
    }

    public Path world() { return world; }
    public Path gameRoot() { return gameRoot; }
    public Path configDir() { return configDir; }
    public StandaloneConfig config() { return config; }

    /** 从存档目录推导游戏根目录：若存档位于 saves 下则取 saves 的父目录，否则取存档上级。 */
    public static Path deriveGameRoot(Path world) {
        var abs = world.toAbsolutePath().normalize();
        var parent = abs.getParent();
        if (parent == null) return abs;
        if ("saves".equalsIgnoreCase(parent.getFileName().toString())) {
            var grand = parent.getParent();
            return grand != null ? grand : parent;
        }
        return parent;
    }

    /** 解析配置目录：优先同步游戏 <游戏根>/config/barusikozako，否则回退到 <游戏根>/standalone。 */
    public static Path resolveConfigDir(Path gameRoot) {
        var game = gameRoot.resolve("config").resolve("barusikozako");
        return Files.isDirectory(game) ? game : gameRoot.resolve("standalone");
    }

    /** 仓库位于 <游戏根>/barusikozako/<世界名>/<后端>，与游戏内一致。 */
    public Path repositoryPath() {
        return gameRoot.resolve("barusikozako").resolve(world.getFileName()).resolve(config.backendKeyword());
    }

    /** 操作进度与消息回调，供 CLI 与 GUI 共用。 */
    public interface Progress {
        Progress NONE = new Progress() {
            @Override public void message(String line) { }
            @Override public void progress(long processed, long total) { }
        };

        void message(String line);
        void progress(long processed, long total);
    }

    public record SnapshotView(UUID id, Instant createdAt, String source, Consistency consistency,
                               String layout, OptionalLong archiveBytes, OptionalLong logicalBytes,
                               Optional<Duration> elapsed) {
        public String folderName() {
            return createdAt.toString().replace(':', '-') + "--" + id;
        }
    }

    public enum BackupMode {
        CONFIGURED, FULL, INCREMENTAL, DIFFERENTIAL, REBUILD, FIXED_CHUNKS
    }

    public List<SnapshotView> list() throws IOException {
        var result = new ArrayList<SnapshotView>();
        if (Files.notExists(repositoryPath(), LinkOption.NOFOLLOW_LINKS)) return result;
        try (var repo = openForRead()) {
            Optional<UUID> after = Optional.empty();
            for (;;) {
                var page = repo.snapshots(after, 256, config.manifestBudget());
                for (var snapshot : page.entries()) result.add(view(snapshot));
                after = page.next();
                if (after.isEmpty()) break;
            }
        }
        // 底层仓库按 UUID 分页（UUID 随机），展示层按创建时间排序，最新在前，避免列表顺序混乱。
        result.sort(Comparator.comparing(SnapshotView::createdAt).reversed());
        return result;
    }

    /** 依据命令行选择参数（时间 / fromuuid / fullname）解析快照。 */
    public SnapshotView select(List<String> args) throws IOException {
        if (args.isEmpty()) throw new IOException("Missing snapshot selector");
        String first = args.get(0);
        if (first.equals("fromuuid") || first.equals("--uuid"))
            return resolveByUuid(requireSelector(args, first));
        if (first.equals("fullname") || first.equals("--fullname"))
            return resolveByFolder(requireSelector(args, first));
        if (first.equals("--time"))
            return resolveByTime(parseTime(requireSelector(args, first)));
        // 依次尝试：ISO 时间 -> UUID -> 目录名
        try {
            return resolveByTime(parseTime(first));
        } catch (java.time.format.DateTimeParseException notTime) {
            try {
                return resolveByUuid(first);
            } catch (IllegalArgumentException notUuid) {
                return resolveByFolder(first);
            }
        }
    }

    private static String requireSelector(List<String> args, String keyword) throws IOException {
        if (args.size() < 2) throw new IOException("Missing value after " + keyword);
        return args.get(1);
    }

    private static Instant parseTime(String text) throws java.time.format.DateTimeParseException {
        return Instant.parse(text);
    }

    private static SnapshotView view(Snapshot snapshot) {
        var stats = snapshot.stats();
        var layout = stats.map(s -> switch (s.layout()) {
            case FULL -> "FULL";
            case INCREMENTAL -> "INCREMENTAL";
            case DIFFERENTIAL -> "DIFFERENTIAL";
        }).orElse("FULL");
        return new SnapshotView(snapshot.id(), snapshot.createdAt(), snapshot.source(), snapshot.consistency(),
            layout, stats.map(Snapshot.Stats::archiveBytes).orElse(OptionalLong.empty()),
            stats.map(Snapshot.Stats::logicalBytes).orElse(OptionalLong.empty()),
            stats.flatMap(Snapshot.Stats::elapsed));
    }

    public SnapshotView resolveByUuid(String text) throws IOException {
        var id = parseId(text);
        for (var snapshot : list()) {
            if (snapshot.id().equals(id)) return snapshot;
        }
        throw new IOException("Snapshot not found: " + id);
    }

    public SnapshotView resolveByTime(Instant time) throws IOException {
        for (var snapshot : list()) {
            if (snapshot.createdAt().equals(time)) return snapshot;
        }
        throw new IOException("Snapshot not found at time: " + time);
    }

    public SnapshotView resolveByFolder(String name) throws IOException {
        for (var snapshot : list()) {
            if (snapshot.folderName().equals(name)) return snapshot;
        }
        throw new IOException("Snapshot folder not found: " + name);
    }

    public SnapshotView backup(BackupMode mode, String note, UUID baseline, Progress progress) throws IOException {
        var listener = progress == null ? Progress.NONE : progress;
        WorldLock.requireClosed(world);
        Files.createDirectories(repositoryPath());
        var canonical = repositoryPath().toRealPath();
        var worldRoot = world.toRealPath();
        if (!Files.isDirectory(worldRoot)) throw new IOException("World directory does not exist: " + world);
        if (canonical.startsWith(worldRoot) || worldRoot.startsWith(canonical))
            throw new IOException("Repository overlaps the world directory");

        var exclusions = BackupExclusions.load(configDir, worldRoot, false,
            config.parseGitignore(), config.parsePteroignore(), gameRoot);
        var staging = gameRoot.resolve("barusikozako").resolve(".staging");
        Files.createDirectories(staging);
        StagedDirectoryView.cleanupAbandoned(staging);
        var budget = new StagedDirectoryView.CopyBudget(config.readLimits().contentBytes(),
            config.readLimits().entries(), 128);
        var operation = UUID.randomUUID();
        var snapshot = new Snapshot(UUID.randomUUID(), Instant.now(), SOURCE_ID, config.consistency(),
            Optional.of(new Snapshot.Trigger("standalone", note == null ? "" : note, java.util.Map.of())));
        long started = System.nanoTime();
        var logical = new java.util.concurrent.atomic.AtomicLong();
        // 引擎在 backup 内部拥有并关闭该视图
        var captured = StagedDirectoryView.copy(worldRoot, staging, budget, () -> {}, (file, target, size) -> false);
        try (var repo = open(mode, baseline)) {
            listener.message("Backing up " + worldRoot + " (" + mode + ")");
            long known = captured.totalBytes();
            new BackupEngine().backup(captured, repo, snapshot, operation, config.readLimits(), () -> {},
                () -> {}, processed -> {
                    logical.set(processed);
                    listener.progress(processed, known > 0 ? known : -1);
                }, config.readBytesPerSecond(), exclusions, lines ->
                    lines.forEach(line -> listener.message("ignore rule: " + line)));
            var elapsed = Duration.ofNanos(System.nanoTime() - started);
            try {
                repo.recordStats(snapshot.id(), elapsed, logical.get());
            } catch (IOException ignored) {
                // 统计仅用于展示，不影响快照可用性
            }
            listener.message("Backup committed: " + snapshotFolderName(snapshot));
        }
        return resolveByUuid(snapshot.id().toString());
    }

    public BackupEngine.Verification verify(UUID id, Progress progress) throws IOException {
        var listener = progress == null ? Progress.NONE : progress;
        try (var repo = openForRead()) {
            listener.message("Verifying snapshot " + id);
            return new BackupEngine().verify(repo, id, config.readLimits(), () -> {},
                bytes -> listener.progress(bytes, -1));
        }
    }

    public RestoreResult rollback(UUID id, Progress progress) throws IOException {
        var listener = progress == null ? Progress.NONE : progress;
        var window = new OfflineRestoreWindow(world);
        try (window) {
            try (var repo = openForRead()) {
                listener.message("Rolling back to snapshot " + id);
                var result = new BackupEngine().restoreWorld(repo, id, UUID.randomUUID(), config.readLimits(),
                    window, false, Workstation::checkWorldStage);
                listener.message("Rollback result: " + result.status());
                return result;
            }
        }
    }

    public void delete(UUID id, Progress progress) throws IOException {
        var listener = progress == null ? Progress.NONE : progress;
        try (var repo = openForRead()) {
            listener.message("Deleting snapshot " + id);
            repo.deleteIndependentSnapshot(id, () -> {}, () -> {});
            listener.message("Snapshot deleted: " + id);
        } catch (io.github.zct_studio.barusikozako.backend.SnapshotInUseException inUse) {
            throw new IOException("Snapshot is a live backup baseline and cannot be deleted", inUse);
        }
    }

    private static void checkWorldStage(Path stage) throws IOException {
        if (!Files.isRegularFile(stage.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Snapshot is missing world level.dat");
    }

    private ManagedRepository openForRead() throws IOException {
        return open(BackupMode.FULL, null);
    }

    private ManagedRepository open(BackupMode mode, UUID baseline) throws IOException {
        var settings = provider.configure(config.backendSettings());
        String operation = switch (mode) {
            case CONFIGURED -> "CONFIGURED";
            case FULL -> "FULL";
            case INCREMENTAL -> "INCREMENTAL";
            case DIFFERENTIAL -> "DIFFERENTIAL";
            case REBUILD -> "REBUILD";
            case FIXED_CHUNKS -> "FIXED_CHUNKS";
        };
        Files.createDirectories(repositoryPath());
        return settings.open(repositoryPath().toRealPath(), NativeSync::forceDirectory, operation, baseline);
    }

    static String snapshotFolderName(Snapshot snapshot) {
        return snapshot.createdAt().toString().replace(':', '-') + "--" + snapshot.id();
    }

    static UUID parseId(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("Noncanonical snapshot ID: " + text);
        return id;
    }
}
