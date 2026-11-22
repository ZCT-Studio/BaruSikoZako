// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.internal.gitignore;

import io.github.zct_studio.barusikozako.internal.gitignore.jgit.ignore.FastIgnoreRule;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 一份 Gitignore 规则，作用范围由调用方传入的相对路径决定 */
public final class GitIgnoreRules {
    public enum Match { NONE, EXCLUDE, INCLUDE }
    private static final int MAX_BYTES = 1024 * 1024;
    private final List<FastIgnoreRule> rules;
    private final List<String> patterns;

    private GitIgnoreRules(List<FastIgnoreRule> rules, List<String> patterns) {
        this.rules = List.copyOf(rules);
        this.patterns = List.copyOf(patterns);
    }

    public static GitIgnoreRules parse(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("Backup ignore rules exceed 1 MiB");
        String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        var rules = new ArrayList<FastIgnoreRule>();
        var patterns = new ArrayList<String>();
        for (String raw : source.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty() || line.startsWith("#") || line.equals("/")) continue;
            var rule = new FastIgnoreRule(matchText(line));
            if (!rule.isEmpty()) {
                rules.add(rule);
                patterns.add(line);
            }
        }
        return new GitIgnoreRules(rules, patterns);
    }

    /** 按父目录到当前条目的顺序判断，已排除目录内的例外规则无法重新包含其子项 */
    public boolean ignores(String relativePath, boolean directory) {
        if (rules.isEmpty()) return false;
        relativePath = matchText(relativePath);
        int slash = relativePath.indexOf('/');
        while (slash >= 0) {
            if (matchesExclusion(relativePath.substring(0, slash), true)) return true;
            slash = relativePath.indexOf('/', slash + 1);
        }
        return matchesExclusion(relativePath, directory);
    }

    public boolean hasInclusions() {
        return rules.stream().anyMatch(FastIgnoreRule::getNegation);
    }

    public List<String> patterns() { return patterns; }

    /** 单条路径的决策，父目录是否可遍历由调用方统一判断 */
    public Match match(String relativePath, boolean directory) {
        return matchEncoded(matchText(relativePath), directory);
    }

    // Git 按字节匹配，非 ASCII 字节映射到私用区以保留顺序和字符类边界
    private static String matchText(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                var encoded = new StringBuilder(bytes.length);
                for (byte b : bytes) encoded.append((char) (b < 0 ? 0xE000 + (b & 0xFF) : b));
                return encoded.toString();
            }
        }
        return value;
    }

    private boolean matchesExclusion(String path, boolean directory) {
        return matchEncoded(path, directory) == Match.EXCLUDE;
    }

    private Match matchEncoded(String path, boolean directory) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            var rule = rules.get(i);
            if (rule.isMatch(path, directory, true)) return rule.getResult() ? Match.EXCLUDE : Match.INCLUDE;
        }
        return Match.NONE;
    }
}
