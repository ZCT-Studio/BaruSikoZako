// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.core;

import io.github.zct_studio.barusikozako.internal.gitignore.GitIgnoreRules;
import io.github.zct_studio.barusikozako.internal.gitignore.GitIgnoreRules.Match;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在任务开始时固定全局规则和模组排除规则 */
public final class BackupExclusions {
    private static final Map<Registration, GitIgnoreRules> REGISTERED = new LinkedHashMap<>();
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    final GitIgnoreRules world;
    /** 游戏根目录的 .pteroignore，未启用或不存在时为 null */
    final GitIgnoreRules ptero;
    /** 世界读取路径相对游戏根目录的前缀，游戏根本身时为空串 */
    final String pteroPrefix;
    final String worldName;
    final boolean worldContainer;
    final boolean parseGitignore;
    private final Map<Registration, GitIgnoreRules> registered;

    private BackupExclusions(String worldName, boolean container, GitIgnoreRules world, GitIgnoreRules ptero,
                             String pteroPrefix, boolean parseGitignore,
                             Map<Registration, GitIgnoreRules> registered) {
        this.worldName = worldName;
        this.worldContainer = container;
        this.world = world;
        this.ptero = ptero;
        this.pteroPrefix = pteroPrefix;
        this.parseGitignore = parseGitignore;
        this.registered = registered;
    }

    private record Registration(String scope, String source, String pattern) {}

    /** 为可选集成提供只依赖 JDK 类型的入口，句柄关闭后撤销注册 */
    public static synchronized AutoCloseable register(String scope, String source, String pattern) {
        if (!"WORLD".equals(scope) || source == null || source.isBlank() || source.length() > 128
            || pattern == null || pattern.isBlank() || pattern.length() > 4096 || pattern.startsWith("!")
            || pattern.indexOf('\r') >= 0 || pattern.indexOf('\n') >= 0 || pattern.indexOf(0) >= 0)
            throw new IllegalArgumentException("Expected WORLD, a source, and a single exclusion pattern");
        var key = new Registration(scope, source, pattern);
        if (REGISTERED.containsKey(key)) throw new IllegalStateException("Exclusion already registered: " + source);
        if (REGISTERED.size() >= 256) throw new IllegalStateException("Backup exclusion registry is full");
        final GitIgnoreRules rule;
        try {
            rule = GitIgnoreRules.parse(new ByteArrayInputStream(pattern.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid backup exclusion", invalid);
        }
        if (rule.patterns().isEmpty() || rule.hasInclusions())
            throw new IllegalArgumentException("Backup exclusion must contain a valid exclusion pattern");
        REGISTERED.put(key, rule);
        return () -> {
            synchronized (BackupExclusions.class) {
                REGISTERED.remove(key, rule);
            }
        };
    }

    public static BackupExclusions load(Path configDirectory, Path captureRoot, boolean worldContainer)
        throws IOException {
        return load(configDirectory, captureRoot, worldContainer, false, false, null);
    }

    /**
     * @param parseGitignore  是否把各目录的 .gitignore 作为局部规则
     * @param parsePteroignore 是否读取游戏根目录的 .pteroignore
     * @param gameRoot        游戏根目录；.pteroignore 的规则路径相对于它
     */
    public static BackupExclusions load(Path configDirectory, Path captureRoot, boolean worldContainer,
                                        boolean parseGitignore, boolean parsePteroignore, Path gameRoot)
        throws IOException {
        Path source = captureRoot.toAbsolutePath().normalize();
        if (source.getFileName() == null) throw new IOException("Backup source has no world name");
        var world = prepareConfig(configDirectory);
        GitIgnoreRules ptero = null;
        String pteroPrefix = "";
        if (parsePteroignore && gameRoot != null) {
            Path root = gameRoot.toAbsolutePath().normalize();
            Path file = root.resolve(".pteroignore");
            if (source.startsWith(root) && Files.isRegularFile(file, NOFOLLOW)) {
                pteroPrefix = root.relativize(source).toString().replace('\\', '/');
                ptero = readRules(file);
            } else if (!source.startsWith(root)) {
                System.getLogger("BaruSikoZako").log(System.Logger.Level.WARNING,
                    "Game root " + root + " does not contain the backup source; .pteroignore is ignored");
            }
        }
        synchronized (BackupExclusions.class) {
            return new BackupExclusions(source.getFileName().toString(), worldContainer, world, ptero, pteroPrefix,
                parseGitignore, Map.copyOf(REGISTERED));
        }
    }

    /** 启动阶段创建忽略规则模板，不读取世界。 */
    public static void ensureConfig(Path configDirectory) throws IOException {
        prepareConfig(configDirectory);
    }

    private static GitIgnoreRules prepareConfig(Path configDirectory) throws IOException {
        Files.createDirectories(configDirectory);
        Path legacy = configDirectory.resolve("save.gitignore");
        Path current = configDirectory.resolve("world.gitignore");
        if (Files.notExists(current, NOFOLLOW) && Files.isRegularFile(legacy, NOFOLLOW)) {
            try {
                Files.move(legacy, current);
            } catch (FileAlreadyExistsException concurrentCreation) {
                // 另一项任务已经建立新的配置
            }
        }
        return readConfig(configDirectory, "world.gitignore", "world directory");
    }

    private static GitIgnoreRules readConfig(Path directory, String fileName, String scope) throws IOException {
        Path path = directory.resolve(fileName);
        if (Files.notExists(path, NOFOLLOW)) {
            try {
                Files.writeString(path, "# Gitignore rules relative to the " + scope + "\nsession.lock\n",
                    StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException concurrentCreation) {
                // 并发任务已经创建配置
            }
        }
        return readRules(path);
    }

    private static GitIgnoreRules readRules(Path path) throws IOException {
        try {
            var before = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
            if (!before.isRegularFile()) throw new IOException("Expected a regular backup ignore file: " + path);
            GitIgnoreRules parsed;
            try (var in = Files.newInputStream(path, NOFOLLOW)) {
                parsed = GitIgnoreRules.parse(in);
            }
            var after = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
            if (!after.isRegularFile() || before.size() != after.size() ||
                !before.lastModifiedTime().equals(after.lastModifiedTime()) ||
                !java.util.Objects.equals(before.fileKey(), after.fileKey()))
                throw new IOException("Backup ignore rules changed during read");
            return parsed;
        } catch (IOException failure) {
            throw new IOException("Could not read backup ignore file: " + path, failure);
        }
    }

    boolean forced(String relative, boolean directory) {
        for (var entry : registered.entrySet()) {
            if (entry.getValue().ignores(relative, directory)) return true;
        }
        return false;
    }

    List<String> descriptions() {
        var lines = new ArrayList<String>();
        describe(lines, "[world.gitignore]", world);
        if (ptero != null) describe(lines, "[.pteroignore]", ptero);
        registered.entrySet().stream()
            .sorted(java.util.Comparator.comparing(entry -> entry.getKey().source()))
            .forEach(entry -> describe(lines, "mod " + display(entry.getKey().source()) + " [WORLD]",
                entry.getValue()));
        return lines;
    }

    /** 只列出真正参与过匹配的规则 */
    static void describe(List<String> lines, String source, GitIgnoreRules rules) {
        for (String pattern : rules.usedPatterns()) lines.add(display(source) + ": " + display(pattern));
    }

    private static String display(String text) {
        var printable = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT)
                printable.append(String.format("\\u%04x", cp));
            else printable.appendCodePoint(cp);
        });
        return printable.toString();
    }

    /** .pteroignore 的决策，路径按游戏根目录展开 */
    Match ptero(String world, String path, boolean directory) {
        if (ptero == null) return Match.NONE;
        var relative = new StringBuilder(pteroPrefix);
        if (worldContainer) {
            if (relative.length() > 0) relative.append('/');
            relative.append(world);
        }
        if (!path.isEmpty()) {
            if (relative.length() > 0) relative.append('/');
            relative.append(path);
        }
        return ptero.match(relative.toString(), directory);
    }

    /** .pteroignore 最底层，world.gitignore 覆盖它，各级局部规则再覆盖两者 */
    Match match(String world, Map<String, List<GitIgnoreRules>> local, String path, boolean directory) {
        Match decision = ptero(world, path, directory);
        Match base = this.world.match(path, directory);
        if (base != Match.NONE) decision = base;
        int slash = -1;
        do {
            String parent = slash < 0 ? "" : path.substring(0, slash);
            for (var rules : local.getOrDefault(parent, List.of())) {
                Match found = rules.match(path.substring(slash + 1), directory);
                if (found != Match.NONE) decision = found;
            }
            slash = path.indexOf('/', slash + 1);
        } while (slash >= 0);
        return decision;
    }
}
