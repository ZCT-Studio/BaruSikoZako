// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent.instrument;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 由引导加载程序加载，因此隔离的服务器类加载器共享相同的指标 */
public final class AgentWriteSignals {
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicReference<String> FIRST_FAILURE = new AtomicReference<>();

    private AgentWriteSignals() {}

    public static void failed(String domain) {
        FIRST_FAILURE.compareAndSet(null, domain);
        FAILURES.incrementAndGet();
    }

    public static void fileReplaced(boolean success) {
        if (!success) failed("world or player file replacement");
    }

    public static long failures() {
        return FAILURES.get();
    }

    public static String firstFailure() {
        return FIRST_FAILURE.get();
    }
}
