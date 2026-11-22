// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit;

import io.github.zct_studio.barusikozako.platform.RestoreWindow;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reflection-based bridge to {@code io.github.zct_studio.barusikozako.core.RestoreTransaction} to avoid
 * platform-b​​ukkit 和核心模块之间的模块可见性问题
 */
final class RestoreTransactionBridge {
    private static final Class<?> RESTORE_TRANSACTION_CLASS;
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    static {
        Class<?> clazz;
        try {
            clazz = Class.forName("io.github.zct_studio.barusikozako.core.RestoreTransaction");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
        RESTORE_TRANSACTION_CLASS = clazz;
    }

    private RestoreTransactionBridge() {}

    /**
     * 打开给定窗口的恢复事务
     */
    static Object open(RestoreWindow window) throws IOException {
        try {
            Method openMethod = RESTORE_TRANSACTION_CLASS.getDeclaredMethod("open", RestoreWindow.class);
            openMethod.setAccessible(true);
            return openMethod.invoke(null, window);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to open restore transaction", e);
        }
    }

    /**
     * 使用给定事务恢复快照
     */
    static void restore(Object transaction, UUID snapshotId, UUID operationId,
                        Consumer<Path> preparation) throws IOException {
        try {
            Method restoreMethod = RESTORE_TRANSACTION_CLASS.getDeclaredMethod("restore", UUID.class, UUID.class, Object.class);
            restoreMethod.setAccessible(true);
            // 第三个参数是Preparation功能接口
            // 我们需要创建 Preparation 接口的实例
            Class<?> preparationClass = Class.forName("io.github.zct_studio.barusikozako.core.RestoreTransaction$Preparation");
            Object preparationInstance = java.lang.reflect.Proxy.newProxyInstance(
                    preparationClass.getClassLoader(),
                    new Class<?>[]{preparationClass},
                    (proxy, method, args) -> {
                        preparation.accept((Path) args[0]);
                        return null;
                    });
            restoreMethod.invoke(null, null, null, preparationInstance);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Failed to restore snapshot", e);
        }
    }
}