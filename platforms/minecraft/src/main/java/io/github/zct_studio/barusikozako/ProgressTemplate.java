// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 文本模板渲染。占位符取值为空时会被省略，相邻分隔符不会重复或残留。
 * 模板只包含字面量与 {@code {name}} 占位符。
 */
final class ProgressTemplate {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private record Token(String text, boolean placeholder) {}

    private ProgressTemplate() {}

    static List<Segment> render(String template, String base, Function<String, Segment> values) {
        var tokens = tokenize(template);
        var segments = new ArrayList<Segment>();
        String pendingSeparator = null;
        boolean hasContent = false;
        for (var token : tokens) {
            if (token.placeholder()) {
                var segment = values.apply(token.text());
                if (segment == null || segment.text().isEmpty()) continue;
                if (hasContent && pendingSeparator != null) segments.add(new Segment(pendingSeparator, base));
                pendingSeparator = null;
                segments.add(segment);
                hasContent = true;
                continue;
            }
            if (token.text().isEmpty()) continue;
            if (separator(token.text())) {
                if (hasContent) pendingSeparator = token.text();
                continue;
            }
            if (hasContent && pendingSeparator != null) segments.add(new Segment(pendingSeparator, base));
            pendingSeparator = null;
            segments.add(new Segment(token.text(), base));
            hasContent = true;
        }
        return segments;
    }

    private static List<Token> tokenize(String template) {
        var tokens = new ArrayList<Token>();
        var matcher = PLACEHOLDER.matcher(template);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) tokens.add(new Token(template.substring(last, matcher.start()), false));
            tokens.add(new Token(matcher.group(1), true));
            last = matcher.end();
        }
        if (last < template.length()) tokens.add(new Token(template.substring(last), false));
        return tokens;
    }

    /** 仅由空白与常见分隔符组成的字面量可以随空占位符一起省略 */
    private static boolean separator(String text) {
        var stripped = text.strip();
        return stripped.isEmpty() || stripped.matches("[·,;|/\\-]+");
    }
}
