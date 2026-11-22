// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.core;

import io.github.zct_studio.barusikozako.backend.Entry;
import io.github.zct_studio.barusikozako.internal.gitignore.GitIgnoreRules;
import io.github.zct_studio.barusikozako.internal.gitignore.GitIgnoreRules.Match;
import io.github.zct_studio.barusikozako.platform.ReadView;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 从平台已捕获的读取视图固定局部规则，再生成统一的归档路径 */
final class WorldBackupView implements ReadView {
    static final String PREFIX = ".worlds";
    private static final String LOCAL_RULES = "local.gitignore";
    private static final int RULE_FILES = 1024;
    private static final int RULE_BYTES = 16 * 1024 * 1024;
    private final ReadView source;
    private final BackupExclusions rules;
    private final BackupEngine.Checkpoint checkpoint;
    private final Map<String, Map<String, GitIgnoreRules>> local = new HashMap<>();
    private final List<String> descriptions;
    private record RuleFile(String world, String parent, byte[] content) {}

    WorldBackupView(ReadView source, BackupExclusions rules, BackupEngine.Limits limits,
                    BackupEngine.Checkpoint checkpoint) throws IOException {
        this.source = source;
        this.rules = rules;
        this.checkpoint = checkpoint;
        descriptions = rules.descriptions();
        int count = 0;
        int bytes = 0;
        int entries = 0;
        var worldNames = new HashSet<String>();
        var ruleFiles = new ArrayList<RuleFile>();
        try (var session = source.openSession()) {
            for (var item = session.next(); item != null; item = session.next()) {
                checkpoint.check();
                if (++entries > limits.entries()) throw new IOException("World rule scan exceeds the entry budget");
                var parts = item.path();
                validate(parts);
                if (rules.worldContainer && parts.size() == 1) {
                    if (!item.directory()) {
                        if (parts.get(0).equals("usercache.json")) continue;
                        throw new IOException("Unexpected file at the root of the world container: " + parts.get(0));
                    }
                    String key = Normalizer.normalize(parts.get(0), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                    if (!worldNames.add(key)) throw new IOException("Conflicting world names: " + parts.get(0));
                }
                if (item.directory() || !parts.get(parts.size() - 1).equals(LOCAL_RULES)) continue;
                if (++count > RULE_FILES || item.size() > RULE_BYTES - bytes)
                    throw new IOException("World rule scan exceeds the local rule budget");
                String worldName = worldName(parts);
                String parent = String.join("/", worldParts(parts).subList(0, worldParts(parts).size() - 1));
                byte[] content;
                try (var in = session.openContent()) {
                    content = in.readNBytes(Math.min(1024 * 1024, RULE_BYTES - bytes) + 1);
                }
                if (content.length > RULE_BYTES - bytes) throw new IOException("World rule scan exceeds the local rule budget");
                bytes += content.length;
                ruleFiles.add(new RuleFile(worldName, parent, content));
            }
        }
        if (rules.worldContainer && worldNames.isEmpty()) throw new IOException("No worlds in the captured container");
        if (!rules.worldContainer) validate(List.of(rules.worldName));
        // 文件枚举顺序不能决定继承优先级，所有父目录规则先于后代加载
        ruleFiles.sort(java.util.Comparator.comparing(RuleFile::world).thenComparing(RuleFile::parent));
        for (var file : ruleFiles) {
            checkpoint.check();
            var worldRules = local.computeIfAbsent(file.world(), ignored -> new HashMap<>());
            var parent = file.parent();
            if (!parent.isEmpty() && ignored(worldRules, parent, true)) continue;
            var parsed = GitIgnoreRules.parse(new ByteArrayInputStream(file.content()));
            if (worldRules.putIfAbsent(parent, parsed) != null)
                throw new IOException("Duplicate local rule file: " + file.world() + "/" + parent);
            BackupExclusions.describe(descriptions, PREFIX + "/" + file.world() + "/" +
                (parent.isEmpty() ? "" : parent + "/") + LOCAL_RULES, parsed);
        }
    }

    List<String> descriptions() { return List.copyOf(descriptions); }

    @Override public Session openSession() throws IOException {
        var session = source.openSession();
        return new Session() {
            private boolean rootPending = true;
            private boolean singleWorldPending = !rules.worldContainer;

            @Override public Item next() throws IOException {
                if (rootPending) {
                    rootPending = false;
                    return new Item(List.of(PREFIX), true, 0, null);
                }
                if (singleWorldPending) {
                    singleWorldPending = false;
                    return new Item(List.of(PREFIX, rules.worldName), true, 0, null);
                }
                for (var item = session.next(); item != null; item = session.next()) {
                    checkpoint.check();
                    var path = item.path();
                    validate(path);
                    if (rules.worldContainer && path.size() == 1 && !item.directory()) continue;
                    String worldName = worldName(path);
                    String relative = String.join("/", worldParts(path));
                    if (!relative.isEmpty() && ignored(local.getOrDefault(worldName, Map.of()), relative, item.directory()))
                        continue;
                    var output = new ArrayList<String>(path.size() + 2);
                    output.add(PREFIX);
                    if (!rules.worldContainer) output.add(worldName);
                    output.addAll(path);
                    return new Item(output, item.directory(), item.size(), item.modifiedAt());
                }
                return null;
            }

            @Override public InputStream openContent() throws IOException { return session.openContent(); }
            @Override public InputStream reopenContent() throws IOException { return session.reopenContent(); }
            @Override public void close() throws IOException { session.close(); }
        };
    }

    private boolean ignored(Map<String, GitIgnoreRules> localRules, String path, boolean directory) {
        if (rules.forced(path, directory)) return true;
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            if (BackupExclusions.match(rules.world, localRules, path.substring(0, slash), true) == Match.EXCLUDE)
                return true;
        }
        return BackupExclusions.match(rules.world, localRules, path, directory) == Match.EXCLUDE;
    }

    private String worldName(List<String> path) { return rules.worldContainer ? path.get(0) : rules.worldName; }
    private List<String> worldParts(List<String> path) { return rules.worldContainer ? path.subList(1, path.size()) : path; }

    private static void validate(List<String> parts) throws IOException {
        try {
            new Entry(parts, true, 0, null);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid world backup path", invalid);
        }
    }

    @Override public void close() throws IOException { source.close(); }
}
