// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.buildlogic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON5 阅读器：支持注释、不带引号的键、尾随逗号、单引号与十六进制字面量。
 * 由 settings、根构建与 build-logic 共用，避免多份实现漂移。
 */
public final class Json5 {
    private final String source;
    private int at;

    private Json5(String text) {
        this.source = stripComments(text);
    }

    public static Object parse(String text) {
        return new Json5(text).read();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("JSON5 root must be an object");
        return (Map<String, Object>) map;
    }

    private static String stripComments(String input) {
        StringBuilder out = new StringBuilder(input.length());
        int index = 0;
        char quote = 0;
        while (index < input.length()) {
            char c = input.charAt(index);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && index + 1 < input.length()) out.append(input.charAt(++index));
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
            } else if (c == '/' && index + 1 < input.length() && input.charAt(index + 1) == '/') {
                while (index < input.length() && input.charAt(index) != '\n') index++;
                out.append('\n');
            } else if (c == '/' && index + 1 < input.length() && input.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < input.length() && !(input.charAt(index) == '*' && input.charAt(index + 1) == '/')) index++;
                index++;
                out.append(' ');
            } else {
                out.append(c);
            }
            index++;
        }
        return out.toString();
    }

    private Object read() {
        skip();
        return value();
    }

    private void skip() {
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
    }

    private Object value() {
        skip();
        if (at >= source.length()) throw new IllegalArgumentException("Unexpected end of JSON5 document");
        return switch (source.charAt(at)) {
            case '{' -> objectValue();
            case '[' -> arrayValue();
            case '"', '\'' -> stringValue();
            default -> literalValue();
        };
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        while (true) {
            skip();
            if (at < source.length() && source.charAt(at) == '}') {
                at++;
                return result;
            }
            String key = source.charAt(at) == '"' || source.charAt(at) == '\'' ? stringValue() : identifierValue();
            skip();
            if (at >= source.length() || source.charAt(at) != ':') throw new IllegalArgumentException("Expected ':' at offset " + at);
            at++;
            result.put(key, value());
            skip();
            if (at < source.length() && source.charAt(at) == ',') {
                at++;
                continue;
            }
            if (at >= source.length() || source.charAt(at) != '}') throw new IllegalArgumentException("Expected '}' at offset " + at);
            at++;
            return result;
        }
    }

    private List<Object> arrayValue() {
        List<Object> result = new ArrayList<>();
        at++;
        while (true) {
            skip();
            if (at < source.length() && source.charAt(at) == ']') {
                at++;
                return result;
            }
            result.add(value());
            skip();
            if (at < source.length() && source.charAt(at) == ',') {
                at++;
                continue;
            }
            if (at >= source.length() || source.charAt(at) != ']') throw new IllegalArgumentException("Expected ']' at offset " + at);
            at++;
            return result;
        }
    }

    private String stringValue() {
        char quote = source.charAt(at++);
        StringBuilder out = new StringBuilder();
        while (at < source.length()) {
            char c = source.charAt(at++);
            if (c == quote) return out.toString();
            if (c == '\\') {
                char escaped = source.charAt(at++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\u000C');
                    case 'u' -> {
                        String hex = source.substring(at, at + 4);
                        at += 4;
                        out.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> out.append(escaped);
                }
            } else {
                out.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated string in JSON5 document");
    }

    private String identifierValue() {
        int start = at;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '$' && c != '.') break;
            at++;
        }
        return source.substring(start, at);
    }

    private Object literalValue() {
        int start = at;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (Character.isWhitespace(c) || c == ',' || c == '}' || c == ']') break;
            at++;
        }
        String token = source.substring(start, at);
        if (token.isEmpty()) throw new IllegalArgumentException("Unexpected token at offset " + start);
        if (token.equals("true")) return Boolean.TRUE;
        if (token.equals("false")) return Boolean.FALSE;
        if (token.equals("null")) return null;
        if (token.startsWith("0x") || token.startsWith("0X")) return Long.valueOf(token.substring(2), 16);
        if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) return Double.valueOf(token);
        return Long.valueOf(token);
    }
}
