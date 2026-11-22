// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.bukkit.bridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/** Bukkit 加载器的父链中可能没有 JVM 系统加载器 */
record AgentLink(Method statusMethod, Method backendMethod, Method extensionMethod, Method bindMethod) {
    static AgentLink connect() throws ReflectiveOperationException {
        Class<?> runtime = Class.forName("io.github.zct_studio.barusikozako.agent.AgentRuntime", true, ClassLoader.getSystemClassLoader());
        Class<?> backend = sharedType("io.github.zct_studio.barusikozako.backend.BackendProvider");
        Class<?> extension = sharedType("io.github.zct_studio.barusikozako.api.BackupLifecycleHook");
        sharedType("io.github.zct_studio.barusikozako.api.BackupService");
        return new AgentLink(
            method(runtime, "status", String.class),
            method(runtime, "registerBackend", AutoCloseable.class, backend),
            method(runtime, "registerExtension", AutoCloseable.class, String.class, extension),
            method(runtime, "bindBukkitApi", Map.Entry.class, String.class));
    }

    private static Class<?> sharedType(String name) throws ClassNotFoundException {
        Class<?> type = Class.forName(name, false, null);
        if (Class.forName(name, false, AgentLink.class.getClassLoader()) != type) {
            throw new IllegalStateException("Bukkit loaded a different BaruSikoZako API type: " + name);
        }
        return type;
    }

    private static Method method(Class<?> type, String name, Class<?> result, Class<?>... parameters)
        throws NoSuchMethodException {
        Method method = type.getMethod(name, parameters);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result) {
            throw new NoSuchMethodException("Incompatible BaruSikoZako agent method: " + name);
        }
        return method;
    }

    String status() {
        return (String) invoke(statusMethod);
    }

    AutoCloseable registerBackend(Object provider) {
        return (AutoCloseable) invoke(backendMethod, provider);
    }

    AutoCloseable registerExtension(String id, Object extension) {
        return (AutoCloseable) invoke(extensionMethod, id, extension);
    }

    AutoCloseable registerWorldExclusion(String id, String pattern) {
        try {
            return (AutoCloseable) invoke(method(statusMethod.getDeclaringClass(), "registerBackupExclusion",
                AutoCloseable.class, String.class, String.class, String.class), "WORLD", id, pattern);
        } catch (NoSuchMethodException failure) {
            throw new UnsupportedOperationException("The installed agent does not support world exclusions", failure);
        }
    }

    Map.Entry<?, ?> bindApi(String plugin) {
        return (Map.Entry<?, ?>) invoke(bindMethod, plugin);
    }

    private static Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("BaruSikoZako agent call failed: " + method.getName(), cause);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("BaruSikoZako agent call is inaccessible: " + method.getName(), failure);
        }
    }
}
