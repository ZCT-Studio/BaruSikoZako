// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

package io.github.zct_studio.barusikozako.tools;

import io.github.zct_studio.barusikozako.ServiceIntegrations;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 独立验证 {@link ServiceIntegrations} 的发现语义，不依赖游戏或加载器。
 *
 * <pre>
 * javac -encoding UTF-8 -d /tmp/si-check \
 *   platforms/minecraft/src/main/java/io/github/zct_studio/barusikozako/ServiceIntegrations.java \
 *   platforms/minecraft/tools/ServiceIntegrationsCheck.java
 * java -cp /tmp/si-check io.github.zct_studio.barusikozako.tools.ServiceIntegrationsCheck
 * </pre>
 */
public final class ServiceIntegrationsCheck {
    private static int failures;

    /** 被服务文件引用的实现；构造次数用于验证延迟与单次实例化。 */
    public static final class Probe implements Runnable {
        static int constructed;

        public Probe() {
            constructed++;
        }

        @Override public void run() {
        }
    }

    /** 只有私有构造，用于验证非公开构造的处理方式。 */
    public static final class Sealed implements Runnable {
        private Sealed() {
        }

        @Override public void run() {
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("si-check");
        Path withProbe = archive(root.resolve("probe.jar"), "io.github.zct_studio.barusikozako.tools.ServiceIntegrationsCheck$Probe");
        Path withSealed = archive(root.resolve("sealed.jar"), "io.github.zct_studio.barusikozako.tools.ServiceIntegrationsCheck$Sealed");
        Path directory = Files.createDirectories(root.resolve("folder"));
        write(directory.resolve("META-INF/services/java.lang.Runnable"),
            "# comment\n\n  io.github.zct_studio.barusikozako.tools.ServiceIntegrationsCheck$Probe  \n");

        var jar = ServiceIntegrations.source("probe", withProbe);
        check("declares() 读取服务文件", ServiceIntegrations.declares(jar, Runnable.class));
        check("未声明的契约返回 false", !ServiceIntegrations.declares(jar, Comparable.class));

        Supplier<Runnable> service = ServiceIntegrations.service("probe", jar, Runnable.class);
        check("首次 get() 之前不实例化", Probe.constructed == 0);
        Runnable first = service.get();
        check("解析出实例", first instanceof Probe);
        check("只实例化一次", Probe.constructed == 1);
        check("重复 get() 返回同一实例", service.get() == first);

        check("目录形态同样被发现",
            ServiceIntegrations.service("folder", ServiceIntegrations.source("folder", directory), Runnable.class)
                .get() instanceof Probe);
        check("空来源不声明也不解析",
            !ServiceIntegrations.declares(ServiceIntegrations.source("empty", path -> Optional.empty()),
                Runnable.class));

        AtomicInteger attempts = new AtomicInteger();
        Supplier<Runnable> throwing = () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        };
        Supplier<Runnable> fallback = () -> () -> { };
        Supplier<Runnable> merged = ServiceIntegrations.firstAvailable("merged", List.of(throwing, fallback));
        check("一个提供方抛错时继续回退", merged.get() != null);
        check("命中后不再尝试后续提供方", attempts.get() == 1);

        check("服务类缺少可访问构造时返回 null",
            ServiceIntegrations.service("sealed", ServiceIntegrations.source("sealed", withSealed),
                Runnable.class).get() == null);

        check("注释、空行与首尾空白被忽略",
            ServiceIntegrations.names("# c\n\n  a.B  \n".getBytes(StandardCharsets.UTF_8)).equals(List.of("a.B")));
        check("去除 BOM",
            ServiceIntegrations.names("\uFEFFa.B\n".getBytes(StandardCharsets.UTF_8)).equals(List.of("a.B")));
        check("拒绝越界路径读取", ServiceIntegrations.read(withProbe, "../" + withProbe.getFileName()).isEmpty());
        check("缺失条目返回空", ServiceIntegrations.read(withProbe, "META-INF/services/nope").isEmpty());

        if (failures > 0) {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("全部检查通过");
    }

    private static Path archive(Path target, String serviceClass) throws IOException {
        try (OutputStream file = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(file)) {
            jar.putNextEntry(new JarEntry("META-INF/services/java.lang.Runnable"));
            jar.write((serviceClass + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return target;
    }

    private static void write(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}
