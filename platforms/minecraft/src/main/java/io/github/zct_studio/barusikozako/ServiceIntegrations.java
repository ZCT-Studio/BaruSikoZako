// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 {@code META-INF/services} 发现集成实现，使集成方不必引用任何加载器类型。
 *
 * <p>集成 mod 只需在 JAR 内放置 {@code META-INF/services/io.github.zct_studio.barusikozako.api.BackupIntegration}
 * 并写入实现类名；实现类需为 public、具备 public 无参构造。发现身份仍是 mod 容器 ID，
 * 因此 {@code api.integrations} 等授权规则不变。
 *
 * <p>本类不引用 Minecraft、加载器或本项目的任何类型，便于独立验证；资源读取只依赖 JDK。
 * 加载与实例化都延迟到 {@link Supplier#get()}，因此未声明服务的 mod 不产生类加载开销。
 */
public final class ServiceIntegrations {
    /** 服务文件所在目录，符合 {@link java.util.ServiceLoader} 约定 */
    public static final String DIRECTORY = "META-INF/services/";

    private static final System.Logger LOG = System.getLogger("BaruSikoZako");
    private static final int MAX_CLASS_NAME = 256;

    private ServiceIntegrations() {}

    /** 一个 mod 文件对服务资源的只读视图 */
    public interface Source {
        /** mod 容器 ID，即授权使用的集成标识 */
        String id();

        /** 该 mod 文件内的资源内容；不存在、不可读或不是资源文件时为空 */
        Optional<byte[]> resource(String path);
    }

    /** 从磁盘文件或目录读取；路径为 null 时静默返回空 */
    public static Source source(String id, Path file) {
        return source(id, () -> file);
    }

    /** 延迟解析文件路径，避免在 mod 构造阶段触碰加载器元数据 */
    public static Source source(String id, Supplier<Path> file) {
        return new Source() {
            @Override public String id() { return id; }

            @Override public Optional<byte[]> resource(String path) {
                Path resolved;
                try {
                    resolved = file.get();
                } catch (RuntimeException failure) {
                    LOG.log(System.Logger.Level.DEBUG, "Unable to locate mod file for " + id, failure);
                    return Optional.empty();
                }
                return read(resolved, path);
            }

            @Override public String toString() { return "Source[" + id + "]"; }
        };
    }

    /** 由平台提供的资源读取函数构造，例如 Fabric 的 {@code ModContainer#findPath} */
    public static Source source(String id, Function<String, Optional<byte[]>> reader) {
        return new Source() {
            @Override public String id() { return id; }

            @Override public Optional<byte[]> resource(String path) {
                try {
                    return reader.apply(path);
                } catch (RuntimeException failure) {
                    LOG.log(System.Logger.Level.DEBUG, "Unable to read " + path + " from " + id, failure);
                    return Optional.empty();
                }
            }

            @Override public String toString() { return "Source[" + id + "]"; }
        };
    }

    /** 读取目录或归档中的单个资源；越界路径和不可读归档返回空 */
    public static Optional<byte[]> read(Path file, String entry) {
        if (file == null || entry == null || entry.isEmpty()) return Optional.empty();
        try {
            if (Files.isDirectory(file)) {
                Path root = file.toAbsolutePath().normalize();
                Path target = root.resolve(entry).normalize();
                if (!target.startsWith(root)) return Optional.empty();
                return Files.isRegularFile(target) ? Optional.of(Files.readAllBytes(target)) : Optional.empty();
            }
            if (Files.isRegularFile(file)) {
                try (ZipFile archive = new ZipFile(file.toFile())) {
                    ZipEntry found = archive.getEntry(entry);
                    if (found == null) return Optional.empty();
                    try (InputStream in = archive.getInputStream(found)) {
                        return Optional.of(in.readAllBytes());
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            // 嵌套归档或虚拟文件系统可能无法按普通文件读取；发现机制据此跳过该 mod。
            LOG.log(System.Logger.Level.DEBUG, "Unable to read " + entry + " from " + file, failure);
        }
        return Optional.empty();
    }

    /** 解析服务文件：UTF-8、忽略注释与空行、去重并保持声明顺序 */
    public static List<String> names(byte[] content) {
        if (content == null || content.length == 0) return List.of();
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String line : text.split("\r\n|\n|\r")) {
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            String name = line.strip();
            if (name.isEmpty() || name.length() > MAX_CLASS_NAME) continue;
            names.add(name);
        }
        return List.copyOf(names);
    }

    /** 该 mod 是否声明了指定契约的服务（只读文件，不加载类） */
    public static boolean declares(Source source, Class<?> contract) {
        if (source == null || contract == null) return false;
        return source.resource(DIRECTORY + contract.getName())
            .map(ServiceIntegrations::names)
            .map(names -> !names.isEmpty())
            .orElse(false);
    }

    /**
     * 延迟实例化服务声明的第一个实现；未声明或全部失败时返回 null。
     * 结果被缓存，重复获取不会重复实例化。
     */
    public static <T> Supplier<T> service(String owner, Source source, Class<T> contract) {
        return memoize(() -> resolve(owner, source, contract));
    }

    /** 按顺序尝试多个来源，跳过返回 null 或抛异常的项，结果缓存 */
    public static <T> Supplier<T> firstAvailable(String owner, List<Supplier<T>> candidates) {
        return memoize(() -> {
            for (Supplier<T> candidate : candidates) {
                if (candidate == null) continue;
                try {
                    T value = candidate.get();
                    if (value != null) return value;
                } catch (Throwable failure) {
                    LOG.log(System.Logger.Level.WARNING,
                        "Integration " + owner + " failed while resolving a provider", failure);
                }
            }
            return null;
        });
    }

    private static <T> T resolve(String owner, Source source, Class<T> contract) {
        if (source == null || contract == null) return null;
        byte[] content = source.resource(DIRECTORY + contract.getName()).orElse(null);
        if (content == null) return null;
        for (String name : names(content)) {
            try {
                Class<?> type = Class.forName(name, true, ServiceIntegrations.class.getClassLoader());
                if (!contract.isAssignableFrom(type)) {
                    LOG.log(System.Logger.Level.WARNING, "Mod " + owner + " declares " + name
                        + " as " + contract.getSimpleName() + " but it does not implement it");
                    continue;
                }
                Object instance = instantiate(type);
                if (instance != null) {
                    LOG.log(System.Logger.Level.DEBUG, "Discovered " + name + " from mod " + owner);
                    return contract.cast(instance);
                }
            } catch (ClassNotFoundException | LinkageError failure) {
                LOG.log(System.Logger.Level.WARNING, "Mod " + owner + " declares " + name
                    + " but the class is not loadable", failure);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "Mod " + owner + " failed to provide " + name, failure);
            }
        }
        return null;
    }

    /**
     * 与 {@link java.util.ServiceLoader} 保持一致：只接受 public 无参构造，避免通过反射
     * 强行实例化刻意封闭的实现。
     */
    private static Object instantiate(Class<?> type) {
        try {
            return type.getConstructor().newInstance();
        } catch (NoSuchMethodException missingConstructor) {
            LOG.log(System.Logger.Level.WARNING, type.getName()
                + " needs a public no-argument constructor to be discovered as an integration");
            return null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Unable to instantiate " + type.getName(), failure);
            return null;
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private T value;
            private boolean resolved;

            @Override public synchronized T get() {
                if (!resolved) {
                    value = delegate.get();
                    resolved = true;
                }
                return value;
            }

            @Override public String toString() { return "memoized"; }
        };
    }
}
