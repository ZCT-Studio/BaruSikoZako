// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.standalone;

import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.nio.file.Path;

/** 平台相关的目录持久化与锁探测。 */
final class NativeSync {
    private NativeSync() {}

    /** 目录元数据刷新：Windows 不支持目录 fsync，交由引擎的文件级 fsync 兜底。 */
    static void forceDirectory(Path directory) throws IOException {
        if (directory.getFileSystem().getSeparator().equals("\\")) return;
        RestoreWindow.forceLocalDirectory(directory);
    }
}
