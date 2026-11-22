// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.agent.instrument;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** 独立游戏加载器和托管代理之间的引导边界 */
public final class AgentServerSignals {
    private static volatile Consumer<Object> tick;
    private static volatile Runnable stop;
    private static volatile Predicate<String> console;

    private AgentServerSignals() {}

    public static synchronized void bind(Consumer<Object> onTick, Runnable onStop, Predicate<String> onConsole) {
        if (tick != null) throw new IllegalStateException("The Agent already owns a server lifecycle");
        stop = onStop;
        console = onConsole;
        tick = onTick;
    }

    public static boolean console(String command) {
        var handler = console;
        return handler != null && handler.test(command);
    }

    public static void tick(Object server) {
        var action = tick;
        if (action != null) action.accept(server);
    }

    public static void stopping() {
        var action = stop;
        if (action != null) action.run();
    }
}
