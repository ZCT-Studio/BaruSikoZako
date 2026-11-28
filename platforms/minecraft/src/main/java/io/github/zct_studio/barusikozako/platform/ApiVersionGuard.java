// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.platform;

import java.util.ArrayList;
import java.util.List;

/** 校验已安装的 BaruSikoZako API 是否满足主模组声明的最低版本。 */
public final class ApiVersionGuard {
    private ApiVersionGuard() {}

    /** 版本过低时返回双语提示，满足要求或无法判断版本时返回 {@code null}。 */
    public static String rejection(String minimum, String installed) {
        if (minimum == null || minimum.isBlank() || installed == null || installed.isBlank()
            || "unknown".equalsIgnoreCase(installed)) return null;
        if (compare(installed, minimum) >= 0) return null;
        return "BaruSikoZako requires BaruSikoZako API " + minimum + " or newer, but " + installed + " is installed.\n"
            + "BaruSikoZako 需要 BaruSikoZako API " + minimum + " 或更高版本，当前安装的是 " + installed + "。";
    }

    static int compare(String left, String right) {
        var a = Version.parse(left);
        var b = Version.parse(right);
        for (int index = 0; index < Math.max(a.core().size(), b.core().size()); index++) {
            int result = Integer.compare(part(a.core(), index), part(b.core(), index));
            if (result != 0) return result;
        }
        if (a.pre().isEmpty() || b.pre().isEmpty())
            return Boolean.compare(a.pre().isEmpty(), b.pre().isEmpty());
        for (int index = 0; index < Math.max(a.pre().size(), b.pre().size()); index++) {
            if (index >= a.pre().size()) return -1;
            if (index >= b.pre().size()) return 1;
            int result = identifier(a.pre().get(index), b.pre().get(index));
            if (result != 0) return result;
        }
        return 0;
    }

    private static int identifier(String left, String right) {
        boolean leftNumber = left.chars().allMatch(Character::isDigit);
        boolean rightNumber = right.chars().allMatch(Character::isDigit);
        if (leftNumber && rightNumber) return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        if (leftNumber != rightNumber) return leftNumber ? -1 : 1;
        return left.compareTo(right);
    }

    private static int part(List<Integer> parts, int index) {
        return index < parts.size() ? parts.get(index) : 0;
    }

    private record Version(List<Integer> core, List<String> pre) {
        static Version parse(String value) {
            String text = value.trim();
            int build = text.indexOf('+');
            if (build >= 0) text = text.substring(0, build);
            int dash = text.indexOf('-');
            String coreText = dash >= 0 ? text.substring(0, dash) : text;
            var core = new ArrayList<Integer>();
            for (String part : coreText.split("\\.")) core.add(number(part));
            var pre = dash >= 0 ? List.of(text.substring(dash + 1).split("\\.")) : List.<String>of();
            return new Version(List.copyOf(core), pre);
        }

        private static int number(String part) {
            int end = 0;
            while (end < part.length() && Character.isDigit(part.charAt(end))) end++;
            return end == 0 ? 0 : Integer.parseInt(part.substring(0, end));
        }
    }
}
