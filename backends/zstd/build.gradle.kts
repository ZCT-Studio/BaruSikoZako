plugins {
    id("barusikozako.java17-library")
}

val staticFixture = sourceSets.create("staticFixture")
configurations.named(staticFixture.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get())
}

dependencies {
    api(project(":backends:spi"))
    implementation(project(":backends:compression-zstd"))
    implementation(libs.commons.lang3)
    implementation(libs.commons.compress) {
        // codec 在 zip/tar 链路中零引用，排除以缩减体积。
        // lang3 是 commons-compress 的传递依赖，必须保留完整实现：不能再用同包名的迷你 shim
        // 替代——那会在游戏类加载器中顶掉 Minecraft 本体依赖的 org.apache.commons.lang3.*。
        exclude(group = "commons-codec", module = "commons-codec")
    }
    implementation(libs.jackson.core)
    add(staticFixture.implementationConfigurationName, project(":engine:core"))
    add(staticFixture.implementationConfigurationName, sourceSets.main.get().output)
}

val staticRoundTrip = tasks.register<JavaExec>("staticRoundTrip") {
    description = "Runs the static-file Zstd backup, verification and recovery fixture."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = (staticFixture.runtimeClasspath - sourceSets.main.get().output) + files(tasks.jar)
    mainClass.set("io.github.zct_studio.barusikozako.zstd.StaticRoundTrip")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(providers.gradleProperty("fixtureJava").map(JavaLanguageVersion::of)
            .orElse(JavaLanguageVersion.of(17)))
    })
}

tasks.register<JavaExec>("stoppedServerFixture") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Archives the dedicated world of an isolated, stopped smoke test."
    classpath = (staticFixture.runtimeClasspath - sourceSets.main.get().output) + files(tasks.jar)
    mainClass.set("io.github.zct_studio.barusikozako.zstd.StoppedServerFixture")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    val runDirectory = providers.gradleProperty("stoppedSmokeDirectory")
    argumentProviders.add(CommandLineArgumentProvider { listOf(runDirectory.get()) })
}

tasks.register<JavaExec>("liveSnapshotEvidence") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Reads player and POI evidence out of an archived live smoke world."
    classpath = (staticFixture.runtimeClasspath - sourceSets.main.get().output) + files(tasks.jar)
    mainClass.set("io.github.zct_studio.barusikozako.zstd.LiveSnapshotEvidence")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    val runDirectory = providers.gradleProperty("stoppedSmokeDirectory")
    val snapshot = providers.gradleProperty("fixtureSnapshot")
    val player = providers.gradleProperty("fixturePlayer")
    val poi = providers.gradleProperty("fixturePoi").orElse("")
    argumentProviders.add(CommandLineArgumentProvider { listOf(runDirectory.get(), snapshot.get(), player.get(), poi.get()) })
}

tasks.named("check") {
    dependsOn(staticRoundTrip)
}

tasks.jar { archiveBaseName.set("backend-zstd") }
