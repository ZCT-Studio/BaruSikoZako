// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.platform;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** 平台协调保存并向备份工作线程交付稳定视图 */
public interface CaptureSource {
    /** 非阻塞的准入提示，与后续保存是否成功无关 */
    boolean available();

    /** 调用方持有返回的视图，具体实现另行记录保存一致性保证 */
    ReadView acquire(BooleanSupplier mayContinue) throws IOException;
}
