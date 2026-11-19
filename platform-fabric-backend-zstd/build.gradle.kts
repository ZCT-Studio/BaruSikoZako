import java.util.Properties
import barusikozako.buildlogic.CollectNotices

plugins {
    alias(libs.plugins.fabric.loom)
    id("barusikozako.java17-library") apply false
}

val targets = Properties().apply {
    rootProject.file("gradle/compatibility-targets.properties").inputStream().use { load(it) }
}
val minecraftVersion = requireNotNull(targets.getProperty("fabric.development.minecraft"))
val requestedMinecraft = providers.gradleProperty("fabricLegacyMinecraft")
    .orElse(providers.gradleProperty("fabricRuntimeMinecraft")).getOrElse(minecraftVersion)
val runtimeMinecraft = targets.getProperty("fabric.runtime.alias.$requestedMinecraft", requestedMinecraft)
require(runtimeMinecraft == minecraftVersion || targets.containsKey("fabric.runtime.api.$runtimeMinecraft") ||
    targets.containsKey("fabric.legacy.api.$runtimeMinecraft")) {
    "No Fabric backend build target for Minecraft $requestedMinecraft"
}
val supportedMinecraft = targets.getProperty("fabric.runtime.minecraft.$runtimeMinecraft", runtimeMinecraft).split(",")
val runtimeSuffix = if (runtimeMinecraft == minecraftVersion) "" else "-$runtimeMinecraft"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${libs.versions.fabric.loader.get()}")
    for (module in listOf("backend-zstd", "compression-zstd")) {
        implementation(project(":$module"))
        include(project(":$module"))
    }
    for (dependency in listOf(libs.zstd.jni, libs.commons.compress, libs.jackson.core)) include(dependency)
    for (dependency in listOf("commons-codec:commons-codec:1.19.0", "commons-io:commons-io:2.20.0",
        "org.apache.commons:commons-lang3:3.18.0")) include(dependency)
}

tasks.processResources {
    val metadata = mapOf("productVersion" to project.version.toString(),
        "minecraftVersions" to supportedMinecraft.joinToString("\",\""))
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") { expand(metadata) }
}

val collectNotices = tasks.register<CollectNotices>("collectNotices") {
    packagedLibraries.from(tasks.named("processIncludeJars").map { it.outputs.files })
    manualNotices.set(rootProject.layout.projectDirectory.dir("platform-fabric/third-party"))
    projectJars.set(setOf("backend-zstd-${project.version}.jar", "compression-zstd-${project.version}.jar"))
    strict.set(providers.provider {
        providers.gradleProperty("releaseAlpha").orNull == "true" ||
            providers.gradleProperty("strictReleaseNotices").orNull == "true"
    })
    collected.set(layout.buildDirectory.dir("collectedNotices"))
}

tasks.jar {
    from(collectNotices.flatMap { it.collected }) { into("META-INF/third-party") }
    archiveBaseName.set("zakobak_zstd-fabric$runtimeSuffix")
    from(rootProject.file("LICENSE")) { into("META-INF") }
}
