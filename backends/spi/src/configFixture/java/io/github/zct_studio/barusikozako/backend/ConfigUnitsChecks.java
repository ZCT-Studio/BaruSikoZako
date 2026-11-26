// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.backend;

public final class ConfigUnitsChecks {
    public static void main(String[] args) {
        check(ConfigUnits.milliseconds("50ms"), 50);
        check(ConfigUnits.milliseconds("1s"), 1000);
        check(ConfigUnits.seconds("1m"), 60);
        check(ConfigUnits.seconds("0.5h"), 1800);
        check(ConfigUnits.seconds("1d"), 86400);
        check(ConfigUnits.seconds("1w"), 604800);
        check(ConfigUnits.seconds("1M"), 2592000);
        check(ConfigUnits.seconds("1month"), 2592000);
        check(ConfigUnits.seconds("1y"), 31536000);
        check(ConfigUnits.bytes("1B"), 1);
        check(ConfigUnits.bytes("1Byte"), 1);
        check(ConfigUnits.bytes("1KB"), 1000);
        check(ConfigUnits.bytes("1MB"), 1000000);
        check(ConfigUnits.bytes("1GB"), 1000000000);
        check(ConfigUnits.bytes("1TB"), 1000000000000L);
        check(ConfigUnits.bytes("256MiB"), 268435456);
        check(ConfigUnits.seconds("30"), 30);
        check(ConfigUnits.milliseconds("30"), 30);
        reject(() -> ConfigUnits.seconds("50ms"));
        reject(() -> ConfigUnits.bytes("0.1B"));
        reject(() -> ConfigUnits.bytes("999999999999999999999TB"));
        reject(() -> ConfigUnits.seconds("1MB"));
        text(ConfigUnits.formatBytes(0, ConfigUnits.SizeUnit.BINARY, 2), "0 B");
        text(ConfigUnits.formatBytes(1023, ConfigUnits.SizeUnit.BINARY, 2), "1023 B");
        text(ConfigUnits.formatBytes(1024, ConfigUnits.SizeUnit.BINARY, 2), "1.00 KiB");
        text(ConfigUnits.formatBytes(1536, ConfigUnits.SizeUnit.DECIMAL, 1), "1.5 KB");
        text(ConfigUnits.formatBytes(1_073_741_824, ConfigUnits.SizeUnit.BINARY, 0), "1 GiB");
        text(ConfigUnits.formatBytes(-5, ConfigUnits.SizeUnit.BINARY, 2), "0 B");
        text(ConfigUnits.formatDuration(java.time.Duration.ofMillis(2500)), "2.5s");
        text(ConfigUnits.formatDuration(java.time.Duration.ofSeconds(65)), "1m 05s");
        text(ConfigUnits.formatDuration(java.time.Duration.ofSeconds(3672)), "1h 01m");
        text(ConfigUnits.formatDuration(java.time.Duration.ofMillis(-1)), "0.0s");
    }

    private static void text(String actual, String expected) {
        if (!actual.equals(expected)) throw new AssertionError("\"" + actual + "\" != \"" + expected + "\"");
    }

    private static void check(long actual, long expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }

    private static void reject(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid quantity was accepted");
    }
}
