import io.github.zct_studio.barusikozako.buildlogic.CollectNotices
import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar

plugins {
    id("barusikozako.java17-library")
}

val bundledBackend = configurations.create("bundledBackend") {
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":backends:spi"))
    implementation(project(":api:common"))
    implementation(project(":api:host"))
    implementation(project(":engine:core"))
    implementation(project(":platforms:spi"))
    implementation("org.ow2.asm:asm:9.10.1")
    implementation(libs.snakeyaml)
    add(bundledBackend.name, project(":backends:zstd"))
}

val sharedContracts = configurations.create("sharedContracts") { isTransitive = false }
dependencies {
    add(sharedContracts.name, project(":backends:spi"))
    add(sharedContracts.name, project(":api:common"))
}
val declaredComponents = setOf("barusikozako_backend_spi", "public-api")

val sharedApi = tasks.register<Jar>("sharedApi") {
    archiveFileName.set("shared-api.jar")
    dependsOn(sharedContracts)
    from(sharedContracts.map { archive -> zipTree(archive) }) {
        include("io/github/zct_studio/barusikozako/**")
    }
    from(sourceSets.main.get().output) {
        include("io/github/zct_studio/barusikozako/agent/instrument/AgentWriteSignals.class")
        include("io/github/zct_studio/barusikozako/agent/instrument/AgentCaptureSignals*.class")
        include("io/github/zct_studio/barusikozako/agent/instrument/AgentServerSignals.class")
        include("io/github/zct_studio/barusikozako/agent/instrument/MoonriseSaveSignals*.class")
    }
}

val prepareManualNotices = tasks.register<Sync>("prepareManualNotices") {
    from(layout.projectDirectory.dir("third-party"))
    from(rootProject.layout.projectDirectory.dir("platforms/fabric/third-party"))
    into(layout.buildDirectory.dir("manualNotices"))
}

fun notices(name: String, dependencies: Any) = tasks.register<CollectNotices>(name) {
    packagedLibraries.from(dependencies)
    dependsOn(prepareManualNotices)
    manualNotices.set(layout.buildDirectory.dir("manualNotices"))
    projectJars.set(
        mapOf(
            "barusikozako_core" to ":engine:core",
            "barusikozako_platform_spi" to ":platforms:spi",
            "barusikozako_api_host" to ":api:host",
            "public-api" to ":api:common",
            "barusikozako_backend_spi" to ":backends:spi",
            "backend-zstd" to ":backends:zstd",
            "compression-zstd" to ":backends:compression-zstd",
        ).map { (name, path) -> "$name-${project(path).version}.jar" }.toSet(),
    )
    strict.set(providers.gradleProperty("strictReleaseNotices").map(String::toBoolean).orElse(false))
    collected.set(layout.buildDirectory.dir(name))
}
val agentNotices = notices("collectAgentNotices", configurations.runtimeClasspath)
val backendNotices = notices("collectBackendNotices", bundledBackend)

fun CopySpec.excludeJarMetadata() {
    exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
        "module-info.class", "META-INF/versions/**/module-info.class")
}

val zstdComponent = tasks.register<Jar>("zstdComponent") {
    archiveBaseName.set("barusikozako-agent-zstd")
    archiveVersion.set(project.extra["zstdVersion"].toString())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(bundledBackend)
    from(bundledBackend.elements.map { entries ->
        entries.filterNot { it.asFile.name.startsWith("barusikozako_backend_spi-") }.map { zipTree(it.asFile) }
    }) { excludeJarMetadata() }
    manifest.attributes("Multi-Release" to "true")
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(backendNotices.flatMap { it.collected }) { into("META-INF/third-party") }
}

val runtimeInputs = configurations.runtimeClasspath
val compileWindowsPersistence = tasks.register<JavaCompile>("compileWindowsPersistence") {
    source(rootProject.file("platforms/minecraft/src/main/java/io/github/zct_studio/barusikozako/platform/windows/WindowsDirectorySync.java"))
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("classes/windowsPersistence"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    options.release.set(22)
}
val windowsPersistence = tasks.register<Jar>("windowsPersistence") {
    archiveFileName.set("windows-persistence.jar")
    from(compileWindowsPersistence)
}
val agentManifest = mapOf("Premain-Class" to "io.github.zct_studio.barusikozako.agent.Premain", "Main-Class" to "io.github.zct_studio.barusikozako.agent.AgentMain",
    "Multi-Release" to "true")

tasks.jar {
    from(rootProject.file("resource/barusikozako/agent")) { into("assets/barusikozako/agent") }
    archiveBaseName.set("barusikozako-javaagent")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes(agentManifest)
    dependsOn(runtimeInputs)
    from(runtimeInputs.flatMap { it.elements }.map { entries ->
        entries.filterNot { entry -> declaredComponents.any { entry.asFile.name.startsWith("$it-") } }
            .map { zipTree(it.asFile) }
    }) { excludeJarMetadata() }
    from(sharedApi.flatMap { it.archiveFile }) { into("META-INF/barusikozako") }
    from(windowsPersistence.flatMap { it.archiveFile }) { into("META-INF/barusikozako") }
    from(zstdComponent.flatMap { it.archiveFile }) {
        into("META-INF/barusikozako")
        rename { "backend.jar" }
    }
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(agentNotices.flatMap { it.collected }) { into("META-INF/third-party") }
}

val agentGame = providers.gradleProperty("agentGameVersion").orElse(providers.gradleProperty("gameVersion"))
    .getOrElse("26.3")
require(agentGame in setOf("1.21.11", "26.1.2", "26.2", "26.3")) { "Unsupported Agent product target: $agentGame" }
PublishProductJar.register(project, "publishBundledProduct", "agent", "barusikozako", "agent$agentGame",
    tasks.jar.flatMap { it.archiveFile })
val contracts = configurations.create("publicApiContract") { isTransitive = false }
dependencies.add(contracts.name, project(":api:common"))
val apiJar = tasks.register<Jar>("agentApiJar") {
    archiveBaseName.set("barusikozako-agent-api")
    archiveVersion.set(project.extra["apiVersion"].toString())
    dependsOn(contracts)
    from(contracts.map { zipTree(it) }) { include("io/github/zct_studio/barusikozako/api/**") }
    from(rootProject.file("LICENSE")) { into("META-INF") }
}
PublishProductJar.register(project, "publishApiProduct", "api", "barusikozako_api", "agent$agentGame",
    extra["apiVersion"].toString(), apiJar.flatMap { it.archiveFile })

val captureFixture = sourceSets.create("captureFixture")
dependencies {
    add(captureFixture.implementationConfigurationName, sourceSets.main.get().output)
}
val checkCaptureSignals = tasks.register<JavaExec>("checkCaptureSignals") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = captureFixture.runtimeClasspath
    mainClass.set("io.github.zct_studio.barusikozako.agent.instrument.CaptureSignalsChecks")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}
tasks.check { dependsOn(checkCaptureSignals) }
