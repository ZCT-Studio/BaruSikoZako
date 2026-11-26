// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.backend;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 配置中带单位的数值，裸数字沿用字段原本的单位 */
public final class ConfigUnits {
    private static final Pattern QUANTITY = Pattern.compile("([+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+))\\s*([A-Za-z]*)");
    private static final Map<String, Long> TIME_MILLIS = Map.ofEntries(
        Map.entry("ms", 1L), Map.entry("s", 1000L), Map.entry("m", 60_000L),
        Map.entry("h", 3_600_000L), Map.entry("d", 86_400_000L),
        Map.entry("w", 604_800_000L), Map.entry("M", 2_592_000_000L),
        Map.entry("month", 2_592_000_000L), Map.entry("y", 31_536_000_000L));
    private static final Map<String, Long> BYTES = Map.ofEntries(
        Map.entry("B", 1L), Map.entry("Byte", 1L), Map.entry("KB", 1_000L),
        Map.entry("MB", 1_000_000L), Map.entry("GB", 1_000_000_000L),
        Map.entry("TB", 1_000_000_000_000L), Map.entry("KiB", 1L << 10),
        Map.entry("MiB", 1L << 20), Map.entry("GiB", 1L << 30), Map.entry("TiB", 1L << 40));
    private static final String[] BINARY_UNITS = {"KiB", "MiB", "GiB", "TiB", "PiB"};
    private static final String[] DECIMAL_UNITS = {"KB", "MB", "GB", "TB", "PB"};

    /** 展示容量时使用的进位方式 */
    public enum SizeUnit { BINARY, DECIMAL }

    private ConfigUnits() {}

    public static long milliseconds(String value) { return parse(value, TIME_MILLIS, 1, 1); }
    public static long seconds(String value) { return parse(value, TIME_MILLIS, 1000, 1000); }
    public static long bytes(String value) { return parse(value, BYTES, 1, 1); }

    /** 按 1024（binary）或 1000（decimal）进位格式化容量，精度限制在 0–4 位 */
    public static String formatBytes(long bytes, SizeUnit unit, int precision) {
        long value = Math.max(0, bytes);
        var units = unit == SizeUnit.DECIMAL ? DECIMAL_UNITS : BINARY_UNITS;
        double size = value;
        int index = -1;
        while (size >= 1024 && index < units.length - 1) {
            size /= 1024;
            index++;
        }
        if (index < 0) return value + " B";
        int digits = Math.max(0, Math.min(4, precision));
        return String.format(Locale.ROOT, "%." + digits + "f %s", size, units[index]);
    }

    /** 使用两者中较大值的单位格式化一对容量，例如 0.2 / 1.2 GiB */
    public static String formatBytesPair(long first, long second, SizeUnit unit, int precision) {
        long left = Math.max(0, first);
        long right = Math.max(0, second);
        var units = unit == SizeUnit.DECIMAL ? DECIMAL_UNITS : BINARY_UNITS;
        double reference = Math.max(left, right);
        int index = -1;
        while (reference >= 1024 && index < units.length - 1) {
            reference /= 1024;
            index++;
        }
        int digits = Math.max(0, Math.min(4, precision));
        if (index < 0) return left + " / " + right + " B";
        double divisor = Math.pow(1024, index + 1);
        return String.format(Locale.ROOT, "%." + digits + "f / %." + digits + "f %s",
            left / divisor, right / divisor, units[index]);
    }

    /** 格式化耗时：不足一分钟显示一位小数秒，随后按分钟、小时展示 */
    public static String formatDuration(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        long seconds = millis / 1000;
        if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        if (seconds < 3600) return String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
    }

    private static long parse(String value, Map<String, Long> units, long defaultMultiplier, long divisor) {
        var match = QUANTITY.matcher(value.strip());
        if (!match.matches()) throw new IllegalArgumentException("Invalid quantity: " + value);
        String unit = match.group(2);
        Long multiplier = unit.isEmpty() ? Long.valueOf(defaultMultiplier) : units.get(unit);
        if (multiplier == null) throw new IllegalArgumentException("Unknown unit: " + unit);
        try {
            return new BigDecimal(match.group(1)).multiply(BigDecimal.valueOf(multiplier))
                .divide(BigDecimal.valueOf(divisor)).longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Quantity overflows or is smaller than the field resolution: " + value, invalid);
        }
    }
}
