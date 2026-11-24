// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小注释式条件编译器。
 *
 * <p>行级指令以注释形式出现在行首：{@code //? if <expr>}、{@code //? else}、
 * {@code //? end}（也接受尾部花括号与 {@code //?}} 写法）。非活跃分支的每一行加上
 * {@link #INACTIVE} 前缀，因此文件始终是合法 Java，IDEA 可以直接索引和编辑原始文件。
 *
 * <p>行内小改动可以使用块注释指令：以 {@code /*?} 开始的注释作为指令，分支内容位于
 * 指令之间，结束指令写作 {@code /*?}}。非活跃片段用 {@code /*?~} 与 {@code *&#47;}
 * 包裹，同样保持文件为合法 Java。块注释分支暂不支持嵌套。
 */
public final class ConditionalProcessor {
    /** 非活跃行前缀；前缀后紧跟原始行内容（含缩进）。 */
    public static final String INACTIVE = "//?~ ";

    /** 非活跃行内片段前缀；用于块注释条件的小范围分支。 */
    private static final String INLINE_INACTIVE = "/*?~ ";

    /** 非活跃行内片段后缀。 */
    private static final String INLINE_INACTIVE_END = " */";

    private ConditionalProcessor() {}

    /** 构建目标的条件上下文。 */
    public record Context(String mc, String loader, int javaRelease) {}

    /** 指令语法错误。 */
    public static final class DirectiveException extends RuntimeException {
        public DirectiveException(String message) {
            super(message);
        }
    }

    public static boolean containsDirectives(String text) {
        return text.contains("//?") || text.contains("/*?");
    }

    /** 去掉非活跃行前缀与行内片段包裹，得到规范形式。 */
    public static String canonical(String text) {
        return canonicalInline(canonicalLines(text));
    }

    /** 判断当前文本是否已经按上下文解析（用于 IDE 活跃目标判断）。 */
    public static boolean isResolved(String text, Context context) {
        if (!containsDirectives(text)) return true;
        return apply(text, context).equals(text);
    }

    /** 按上下文生成带非活跃前缀的文本。 */
    public static String apply(String text, Context context) {
        String base = canonical(text);
        return applyLines(applyInline(base, context), context);
    }

    private static String canonicalLines(String text) {
        String separator = lineSeparator(text);
        List<String> lines = split(text);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(INACTIVE)) line = line.substring(INACTIVE.length());
            else if (line.equals(INACTIVE.strip())) line = "";
            out.append(line);
            if (i + 1 < lines.size()) out.append(separator);
        }
        return out.toString();
    }

    private static String applyLines(String text, Context context) {
        String separator = lineSeparator(text);
        List<String> lines = split(text);
        List<Frame> stack = new ArrayList<>();
        boolean active = true;
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String body = directiveBody(line, i + 1);
            if (body == null) {
                out.append(active ? line : (line.isEmpty() ? INACTIVE.strip() : INACTIVE + line));
            } else if (body.startsWith("if ")) {
                boolean value = Expression.parse(body.substring(3)).evaluate(context);
                stack.add(new Frame(active, value));
                active = active && value;
                out.append(line);
            } else if (body.equals("else")) {
                Frame frame = requireFrame(stack, "else", i + 1);
                frame.current = !frame.taken;
                frame.taken = true;
                active = frame.parentActive && frame.current;
                out.append(line);
            } else if (body.startsWith("elif ")) {
                Frame frame = requireFrame(stack, "elif", i + 1);
                boolean value = !frame.taken && Expression.parse(body.substring(5)).evaluate(context);
                frame.current = value;
                frame.taken = frame.taken || value;
                active = frame.parentActive && value;
                out.append(line);
            } else if (body.equals("end") || body.isEmpty()) {
                requireFrame(stack, "end", i + 1);
                stack.remove(stack.size() - 1);
                active = stack.isEmpty() || (stack.get(stack.size() - 1).parentActive
                    && stack.get(stack.size() - 1).current);
                out.append(line);
            } else {
                throw new DirectiveException("Unknown directive '" + body + "' at line " + (i + 1));
            }
            if (i + 1 < lines.size()) out.append(separator);
        }
        if (!stack.isEmpty()) throw new DirectiveException("Unclosed conditional block");
        return out.toString();
    }

    private static Frame requireFrame(List<Frame> stack, String directive, int line) {
        if (stack.isEmpty()) throw new DirectiveException("'" + directive + "' without 'if' at line " + line);
        return stack.get(stack.size() - 1);
    }

    /**
     * 解析指令行；返回 null 表示普通代码行，返回空串表示 {@code //?}} 结束指令。
     */
    private static String directiveBody(String line, int lineNumber) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("//?") || trimmed.startsWith(INACTIVE.strip())) return null;
        String body = trimmed.substring(3).strip();
        if (body.endsWith("{")) body = body.substring(0, body.length() - 1).strip();
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1).strip();
        if (body.startsWith("if") && !body.equals("if") && !Character.isWhitespace(body.charAt(2))) {
            throw new DirectiveException("Invalid 'if' directive at line " + lineNumber);
        }
        return body;
    }

    /** 校验行级与行内指令结构，返回错误列表。 */
    public static List<String> validate(String text) {
        List<String> errors = new ArrayList<>();
        validateLines(text, errors);
        validateInline(text, errors);
        return errors;
    }

    private static void validateLines(String text, List<String> errors) {
        List<String> lines = split(text);
        int depth = 0;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (!trimmed.startsWith("//?") || trimmed.startsWith(INACTIVE.strip())) continue;
            try {
                String body = directiveBody(trimmed, i + 1);
                if (body == null) continue;
                if (body.startsWith("if ")) {
                    Expression.parse(body.substring(3));
                    depth++;
                } else if (body.equals("else")) {
                    if (depth == 0) errors.add("line " + (i + 1) + ": 'else' without 'if'");
                } else if (body.startsWith("elif ")) {
                    if (depth == 0) errors.add("line " + (i + 1) + ": 'elif' without 'if'");
                    Expression.parse(body.substring(5));
                } else if (body.equals("end") || body.isEmpty()) {
                    if (depth == 0) errors.add("line " + (i + 1) + ": 'end' without 'if'");
                    else depth--;
                } else {
                    errors.add("line " + (i + 1) + ": unknown directive '" + body + "'");
                }
            } catch (DirectiveException e) {
                errors.add("line " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (depth != 0) errors.add("unclosed conditional block");
    }

    private static void validateInline(String text, List<String> errors) {
        if (!text.contains("/*?")) return;
        int depth = 0;
        for (InlineDirective directive : inlineDirectives(text)) {
            try {
                String body = inlineBody(directive.content(), directive.line());
                if (body.startsWith("if ")) {
                    if (depth > 0) errors.add("line " + directive.line() + ": nested inline conditional is not supported");
                    Expression.parse(body.substring(3));
                    depth++;
                } else if (body.equals("else")) {
                    if (depth == 0) errors.add("line " + directive.line() + ": 'else' without inline 'if'");
                } else if (body.startsWith("elif ")) {
                    if (depth == 0) errors.add("line " + directive.line() + ": 'elif' without inline 'if'");
                    Expression.parse(body.substring(5));
                } else if (body.equals("end") || body.isEmpty()) {
                    if (depth == 0) errors.add("line " + directive.line() + ": inline 'end' without 'if'");
                    else depth--;
                } else {
                    errors.add("line " + directive.line() + ": unknown inline directive '" + body + "'");
                }
            } catch (DirectiveException e) {
                errors.add("line " + directive.line() + ": " + e.getMessage());
            }
        }
        if (depth != 0) errors.add("unclosed inline conditional block");
    }

    private static String applyInline(String text, Context context) {
        if (!text.contains("/*?")) return text;
        List<InlineDirective> directives = inlineDirectives(text);
        if (directives.isEmpty()) return text;
        List<Frame> stack = new ArrayList<>();
        boolean active = true;
        int last = 0;
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (InlineDirective directive : directives) {
            emitInline(out, text.substring(last, directive.start()), active, directive.line());
            out.append(text, directive.start(), directive.end());
            String body = inlineBody(directive.content(), directive.line());
            if (body.startsWith("if ")) {
                if (!stack.isEmpty()) {
                    throw new DirectiveException("Nested inline conditional at line "
                        + directive.line() + " is not supported");
                }
                boolean value = Expression.parse(body.substring(3)).evaluate(context);
                stack.add(new Frame(active, value));
                active = active && value;
            } else if (body.equals("else")) {
                Frame frame = requireFrame(stack, "else", directive.line());
                frame.current = !frame.taken;
                frame.taken = true;
                active = frame.parentActive && frame.current;
            } else if (body.startsWith("elif ")) {
                Frame frame = requireFrame(stack, "elif", directive.line());
                boolean value = !frame.taken && Expression.parse(body.substring(5)).evaluate(context);
                frame.current = value;
                frame.taken = frame.taken || value;
                active = frame.parentActive && value;
            } else if (body.equals("end") || body.isEmpty()) {
                requireFrame(stack, "end", directive.line());
                stack.remove(stack.size() - 1);
                active = stack.isEmpty() || (stack.get(stack.size() - 1).parentActive
                    && stack.get(stack.size() - 1).current);
            } else {
                throw new DirectiveException("Unknown inline directive '" + body + "' at line " + directive.line());
            }
            last = directive.end();
        }
        emitInline(out, text.substring(last), active, lineAt(text, last));
        if (!stack.isEmpty()) throw new DirectiveException("Unclosed inline conditional block");
        return out.toString();
    }

    private static void emitInline(StringBuilder out, String region, boolean active, int line) {
        if (active || region.isBlank()) {
            out.append(region);
            return;
        }
        if (!isBlockCommentSafe(region)) {
            throw new DirectiveException("Inline conditional branch at line " + line
                + " cannot be disabled because it contains '*/'");
        }
        out.append(INLINE_INACTIVE).append(region).append(INLINE_INACTIVE_END);
    }

    private static boolean isBlockCommentSafe(String text) {
        return !text.contains("*/");
    }

    private static String canonicalInline(String text) {
        if (!text.contains(INLINE_INACTIVE)) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                int next = text.startsWith("\"\"\"", i) ? skipTextBlock(text, i) : skipQuoted(text, i, '"');
                out.append(text, i, next);
                i = next;
                continue;
            }
            if (c == '\'') {
                int next = skipQuoted(text, i, '\'');
                out.append(text, i, next);
                i = next;
                continue;
            }
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    int end = skipLineComment(text, i);
                    out.append(text, i, end);
                    i = end;
                    continue;
                }
                if (next == '*') {
                    int end = text.indexOf("*/", i + 2);
                    if (end < 0) {
                        out.append(text, i, text.length());
                        break;
                    }
                    if (text.startsWith(INLINE_INACTIVE, i)) {
                        String content = text.substring(i + INLINE_INACTIVE.length(), end);
                        if (content.endsWith(" ")) content = content.substring(0, content.length() - 1);
                        out.append(content);
                    } else {
                        out.append(text, i, end + 2);
                    }
                    i = end + 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String inlineBody(String content, int line) {
        String body = content.strip();
        if (body.startsWith("}")) body = body.substring(1).strip();
        if (body.endsWith("{")) {
            body = body.substring(0, body.length() - 1).strip();
        } else if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1).strip();
        }
        if (body.startsWith("if") && !body.equals("if") && !Character.isWhitespace(body.charAt(2))) {
            throw new DirectiveException("Invalid inline 'if' directive at line " + line);
        }
        return body;
    }

    private static List<InlineDirective> inlineDirectives(String text) {
        List<InlineDirective> result = new ArrayList<>();
        int i = 0;
        int line = 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == '"') {
                i = text.startsWith("\"\"\"", i) ? skipTextBlock(text, i) : skipQuoted(text, i, '"');
                continue;
            }
            if (c == '\'') {
                i = skipQuoted(text, i, '\'');
                continue;
            }
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    i = skipLineComment(text, i);
                    continue;
                }
                if (next == '*') {
                    int end = text.indexOf("*/", i + 2);
                    if (end < 0) break;
                    if (text.startsWith("/*?", i) && !text.startsWith(INLINE_INACTIVE, i)) {
                        result.add(new InlineDirective(i, end + 2, text.substring(i + 3, end), line));
                    }
                    line += newlines(text, i, end + 2);
                    i = end + 2;
                    continue;
                }
            }
            i++;
        }
        return result;
    }

    private static int skipQuoted(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) return i + 1;
            i++;
        }
        return text.length();
    }

    private static int skipTextBlock(String text, int start) {
        int i = start + 3;
        while (i < text.length()) {
            if (text.charAt(i) == '\\') {
                i += 2;
                continue;
            }
            if (text.startsWith("\"\"\"", i)) return i + 3;
            i++;
        }
        return text.length();
    }

    private static int skipLineComment(String text, int start) {
        int end = text.indexOf('\n', start);
        return end < 0 ? text.length() : end;
    }

    private static int newlines(String text, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private record InlineDirective(int start, int end, String content, int line) {}

    private static String lineSeparator(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    private static List<String> split(String text) {
        String normalized = text.replace("\r\n", "\n");
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                lines.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        lines.add(normalized.substring(start));
        return lines;
    }

    private static final class Frame {
        private final boolean parentActive;
        private boolean current;
        private boolean taken;

        private Frame(boolean parentActive, boolean condition) {
            this.parentActive = parentActive;
            this.current = condition;
            this.taken = condition;
        }
    }

    /** 条件表达式。 */
    static final class Expression {
        private enum Kind { OR, AND, NOT, COMPARE, CONSTANT }
        private final Kind kind;
        private final Expression left;
        private final Expression right;
        private final String operator;
        private final Object literal;

        private Expression(Kind kind, Expression left, Expression right, String operator, Object literal) {
            this.kind = kind;
            this.left = left;
            this.right = right;
            this.operator = operator;
            this.literal = literal;
        }

        static Expression parse(String text) {
            Parser parser = new Parser(text);
            Expression result = parser.or();
            parser.skip();
            if (!parser.done()) throw new DirectiveException("Unexpected token in expression: " + text.substring(parser.at));
            return result;
        }

        boolean evaluate(Context context) {
            return switch (kind) {
                case OR -> left.evaluate(context) || right.evaluate(context);
                case AND -> left.evaluate(context) && right.evaluate(context);
                case NOT -> !left.evaluate(context);
                case CONSTANT -> {
                    if (literal instanceof Boolean bool) yield bool;
                    throw new DirectiveException("Not a boolean: " + literal);
                }
                case COMPARE -> compare(context);
            };
        }

        private boolean compare(Context context) {
            Object a = operand(left, context);
            Object b = operand(right, context);
            return switch (operator) {
                case "==" -> equal(a, b);
                case "!=" -> !equal(a, b);
                case "<" -> order(a, b) < 0;
                case "<=" -> order(a, b) <= 0;
                case ">" -> order(a, b) > 0;
                case ">=" -> order(a, b) >= 0;
                default -> throw new DirectiveException("Unknown operator " + operator);
            };
        }

        private static Object operand(Expression expression, Context context) {
            if (expression.kind != Kind.CONSTANT) throw new DirectiveException("Expected operand");
            Object value = expression.literal;
            if (value instanceof Symbol symbol) {
                return switch (symbol.name) {
                    case "mc" -> new Version(context.mc());
                    case "java" -> new Version(Integer.toString(context.javaRelease()));
                    case "loader" -> context.loader();
                    default -> symbol.name;
                };
            }
            return value;
        }

        private static boolean equal(Object a, Object b) {
            if (a instanceof Version va && b instanceof Version vb) return va.compareTo(vb) == 0;
            return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
        }

        private static int order(Object a, Object b) {
            if (a instanceof Version va && b instanceof Version vb) return va.compareTo(vb);
            throw new DirectiveException("Cannot order non-version values: " + a + ", " + b);
        }

        private record Symbol(String name) {}

        private static final class Parser {
            private final String text;
            private int at;

            private Parser(String text) {
                this.text = text;
            }

            private boolean done() {
                return at >= text.length();
            }

            private void skip() {
                while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
            }

            private boolean consume(String token) {
                skip();
                if (text.startsWith(token, at)) {
                    at += token.length();
                    return true;
                }
                return false;
            }

            private Expression or() {
                Expression left = and();
                while (consume("||")) left = new Expression(Kind.OR, left, and(), null, null);
                return left;
            }

            private Expression and() {
                Expression left = not();
                while (consume("&&")) left = new Expression(Kind.AND, left, not(), null, null);
                return left;
            }

            private Expression not() {
                skip();
                if (text.startsWith("!", at) && !text.startsWith("!=", at)) {
                    at++;
                    return new Expression(Kind.NOT, primary(), null, null, null);
                }
                return primary();
            }

            private Expression primary() {
                skip();
                if (consume("(")) {
                    Expression inner = or();
                    if (!consume(")")) throw new DirectiveException("Expected ')'");
                    return inner;
                }
                Expression left = comparisonOperand();
                skip();
                for (String operator : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
                    if (text.startsWith(operator, at)) {
                        at += operator.length();
                        Expression right = comparisonOperand();
                        return new Expression(Kind.COMPARE, left, right, operator, null);
                    }
                }
                return left;
            }

            private Expression comparisonOperand() {
                skip();
                if (done()) throw new DirectiveException("Unexpected end of expression");
                char c = text.charAt(at);
                if (c == '"' || c == '\'') {
                    return new Expression(Kind.CONSTANT, null, null, null, string());
                }
                if (Character.isDigit(c)) {
                    int start = at;
                    while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) at++;
                    return new Expression(Kind.CONSTANT, null, null, null, new Version(text.substring(start, at)));
                }
                int start = at;
                while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) at++;
                String name = text.substring(start, at);
                if (name.isEmpty()) throw new DirectiveException("Unexpected character '" + c + "'");
                return switch (name) {
                    case "true" -> new Expression(Kind.CONSTANT, null, null, null, Boolean.TRUE);
                    case "false" -> new Expression(Kind.CONSTANT, null, null, null, Boolean.FALSE);
                    case "mc", "loader", "java" -> new Expression(Kind.CONSTANT, null, null, null, new Symbol(name));
                    default -> new Expression(Kind.CONSTANT, null, null, null, name);
                };
            }

            private String string() {
                char quote = text.charAt(at++);
                StringBuilder out = new StringBuilder();
                while (at < text.length()) {
                    char c = text.charAt(at++);
                    if (c == quote) return out.toString();
                    out.append(c);
                }
                throw new DirectiveException("Unterminated string");
            }
        }
    }

    /** 版本号，按数字段比较，缺失段按 0 处理。 */
    static final class Version implements Comparable<Version> {
        private final int[] segments;

        Version(String text) {
            String[] parts = text.split("[^0-9]+");
            List<Integer> values = new ArrayList<>();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                try {
                    values.add(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    throw new DirectiveException("Invalid version segment: " + part);
                }
            }
            if (values.isEmpty()) throw new DirectiveException("Invalid version: " + text);
            this.segments = values.stream().mapToInt(Integer::intValue).toArray();
        }

        @Override
        public int compareTo(Version other) {
            int length = Math.max(segments.length, other.segments.length);
            for (int i = 0; i < length; i++) {
                int a = i < segments.length ? segments[i] : 0;
                int b = i < other.segments.length ? other.segments[i] : 0;
                if (a != b) return Integer.compare(a, b);
            }
            return 0;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) out.append('.');
                out.append(segments[i]);
            }
            return out.toString();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Version version && compareTo(version) == 0;
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }
    }
}
