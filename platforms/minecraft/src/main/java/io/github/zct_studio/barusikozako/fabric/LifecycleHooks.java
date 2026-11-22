// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.fabric;

import io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook;
import io.github.zct_studio.barusikozako.runtime.BackupLifecycleHook.Context;
import io.github.zct_studio.barusikozako.runtime.TaskResult;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class LifecycleHooks {
    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private static final ThreadPoolExecutor NOTIFICATIONS = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), action -> {
            var thread = new Thread(action, "BaruSikoZako lifecycle notifications");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    static { NOTIFICATIONS.allowCoreThreadTimeOut(true); }

    private LifecycleHooks() {}

    static void before(Set<String> allowed, Context context) {
        for (var entry : entries(allowed)) {
            var hook = entry.value().get();
            if (hook != null) hook.before(context);
        }
    }

    static void beforeStartupRestore(Set<String> allowed, Context context) throws IOException {
        var callback = new java.util.concurrent.FutureTask<Void>(() -> {
            before(allowed, context);
            return null;
        });
        var thread = new Thread(callback, "BaruSikoZako restore preparation");
        thread.setDaemon(true);
        thread.start();
        try {
            callback.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            callback.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Restore preparation interrupted", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            callback.cancel(true);
            throw new IOException("Restore preparation callback failed", failure);
        }
    }

    static void after(Set<String> allowed, Context context, TaskResult result) {
        try {
            NOTIFICATIONS.execute(() -> {
                for (var entry : entries(allowed)) {
                    try {
                        var hook = entry.value().get();
                        if (hook != null) hook.after(context, result);
                    } catch (RuntimeException | LinkageError failure) {
                        LOG.log(System.Logger.Level.WARNING, "Lifecycle listener failed: " + entry.owner(), failure);
                    }
                }
            });
        } catch (RejectedExecutionException full) {
            LOG.log(System.Logger.Level.WARNING, "Lifecycle notification dropped because its queue is full: {0}", context.operation());
        }
    }

    private static java.util.List<LoaderBridge.Entry<BackupLifecycleHook>>
    entries(Set<String> allowed) {
        if (allowed.isEmpty()) return java.util.List.of();
        return LoaderBridge.entries("barusikozako:lifecycle", BackupLifecycleHook.class).stream()
            .filter(entry -> allowed.contains(entry.owner())).toList();
    }
}
