// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 ZCT Studio

plugins {
    id("barusikozako.java17-library")
}

dependencies {
    api(project(":engine:core"))
    api(project(":backends:zstd"))
    implementation(project(":backends:compression-zstd"))
    implementation(libs.zstd.jni)
    implementation(libs.commons.compress) {
        // codec 零引用，排除以缩减体积；lang3 保留完整实现（运行时由模组 jar 嵌套包提供）。
        exclude(group = "commons-codec", module = "commons-codec")
    }
    implementation(libs.jackson.core)
    implementation(libs.snakeyaml)
}

tasks.jar {
    archiveBaseName.set("barusikozako-standalone")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes(
        "Main-Class" to "io.github.zct_studio.barusikozako.standalone.StandaloneMain",
        "Implementation-Version" to project.version.toString(),
    )
    // 只打包本模块自己的类。运行时依赖（engine:core、backends:zstd、zstd-jni、commons-compress、
    // jackson-core、snakeyaml 等）已由模组 jar 的 META-INF/jars/* 嵌套依赖提供——双击引导器
    // DoubleClickLauncher 会递归收集它们一并加载，因此无需在此重复打平（可省约 3-4MB）。
    from(rootProject.file("LICENSE")) { into("META-INF") }
}
