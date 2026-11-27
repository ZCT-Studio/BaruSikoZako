// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.standalone;

/**
 * 独立运行入口。
 *
 * <p>双击（无参数）打开图形界面；带参数则走命令行，语义与游戏内命令一致。
 * 该入口同时作为 standalone fat jar 的 {@code Main-Class}，也被模组 jar 顶层的
 * {@code DoubleClickLauncher} 通过反射调用。
 */
public final class StandaloneMain {
    private StandaloneMain() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            StandaloneGui.launch();
            return;
        }
        System.exit(StandaloneCli.run(args, System.out, System.err));
    }
}
