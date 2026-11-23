import java.util.Properties
import io.github.zct_studio.barusikozako.buildlogic.CollectNotices
import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar

plugins {
    alias(libs.plugins.fabric.loom)
    id("barusikozako.java17-library") apply false
}

val targets = Properties().apply {
    rootProject.file("gradle/compatibility-targets.properties").inputStream().use { load(it) }
}
val minecraftVersion = requireNotNull(targets.getProperty("fabric.development.minecraft"))
val runtimeMinecraft = parent!!.name.removePrefix("mc").replace('_', '.')
val runtimeJava = if (runtimeMinecraft == "1.20.1") 17 else if (runtimeMinecraft.startsWith("1.")) 21 else 25
require(runtimeMinecraft == minecraftVersion || targets.containsKey("fabric.runtime.api.$runtimeMinecraft") ||
    targets.containsKey("fabric.legacy.api.$runtimeMinecraft")) {
    "No Fabric backend build target for Minecraft $runtimeMinecraft"
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
    for (module in listOf(":backends:zstd", ":backends:compression-zstd")) {
        implementation(project(module))
        include(project(module))
    }
    for (dependency in listOf(libs.zstd.jni, libs.commons.compress, libs.jackson.core)) include(dependency)
    for (dependency in listOf("commons-codec:commons-codec:1.19.0", "commons-io:commons-io:2.20.0",
        "org.apache.commons:commons-lang3:3.18.0")) include(dependency)
}

sourceSets.main { resources.srcDir(rootProject.file("platforms/fabric-zstd-package/src/main/resources")) }

tasks.processResources {
    from(rootProject.file("resource/barusikozako_backendzstd/icon.png"))
    val metadata = mapOf("productVersion" to project.version.toString(),
        "javaVersion" to runtimeJava,
        "minecraftVersions" to supportedMinecraft.joinToString("\",\""))
    @Suppress("UNCHECKED_CAST")
    val info = ((rootProject.extra["productMetadata"] as Map<*, *>)["backend"] as Map<*, *>)["zstd"] as Map<String, Any?>
    val metadataFields = mapOf("descriptionJson" to groovy.json.JsonOutput.toJson(info["description"]).removeSurrounding("\""),
        "authorsJson" to (info["authors"] as List<*>).joinToString("\",\"") { groovy.json.JsonOutput.toJson(it).removeSurrounding("\"") },
        "homepageJson" to groovy.json.JsonOutput.toJson(info["homepage"]).removeSurrounding("\""),
        "sourcesJson" to groovy.json.JsonOutput.toJson(info["sources"]).removeSurrounding("\""),
        "issuesJson" to groovy.json.JsonOutput.toJson(info["issues"]).removeSurrounding("\""))
    inputs.properties(metadata)
    inputs.properties(metadataFields)
    filesMatching("fabric.mod.json") { expand(metadata + metadataFields) }
}

val collectNotices = tasks.register<CollectNotices>("collectNotices") {
    packagedLibraries.from(tasks.named("processIncludeJars").map { it.outputs.files })
    manualNotices.set(rootProject.layout.projectDirectory.dir("platforms/fabric/third-party"))
    projectJars.set(setOf("backend-zstd-${project.extra["zstdVersion"]}.jar", "compression-zstd-${project.extra["zstdVersion"]}.jar"))
    strict.set(providers.provider {
        providers.gradleProperty("releaseAlpha").orNull == "true" ||
            providers.gradleProperty("strictReleaseNotices").orNull == "true"
    })
    collected.set(layout.buildDirectory.dir("collectedNotices"))
}

tasks.jar {
    from(collectNotices.flatMap { it.collected }) { into("META-INF/third-party") }
    archiveBaseName.set("barusikozako_backendzstd-fabric$runtimeSuffix")
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

PublishProductJar.register(project, "publishBackendProduct", "backend", "barusikozako_backendzstd",
    "${extra["fabricPackageLoader"]}$runtimeMinecraft", false,
    tasks.jar.flatMap { it.archiveFile })
