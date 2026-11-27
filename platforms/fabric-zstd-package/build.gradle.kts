import java.util.Properties
import java.io.File
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import io.github.zct_studio.barusikozako.buildlogic.CollectNotices

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
    for (dependency in listOf(libs.zstd.jni, libs.commons.compress, libs.commons.lang3, libs.jackson.core)) include(dependency)
    // codec 不再 include：在 zip/tar 链路中零引用。
    for (dependency in listOf("commons-io:commons-io:2.20.0")) include(dependency)
}

sourceSets.main { resources.srcDir(rootProject.file("platforms/fabric-zstd-package/src/main/resources")) }

tasks.processResources {
    from(rootProject.file("resource/barusikozako_backendzstd/icon.png"))
    val metadata = mapOf("productVersion" to project.version.toString(),
        "loaderRequirement" to project.extra["fabricLoaderRequirement"],
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

// —— 体积精简：重写 included 依赖 jar，去掉冷门平台 native 库与非 tar 归档代码 ——
// 规则与 :platforms:standalone 的 CopySpec exclude 保持一致。
val nativeStripPrefixes = listOf(
    "aix/", "freebsd/i386/",
    "linux/arm/", "linux/i386/", "linux/loongarch64/", "linux/mips64/",
    "linux/ppc64/", "linux/ppc64le/", "linux/riscv64/", "linux/s390x/",
    "win/x86/",
)
val compressStripPrefixes = listOf(
    "org/apache/commons/compress/harmony/",
    "org/apache/commons/compress/archivers/sevenz/",
    "org/apache/commons/compress/archivers/dump/",
    "org/apache/commons/compress/archivers/cpio/",
    "org/apache/commons/compress/archivers/arj/",
    "org/apache/commons/compress/archivers/ar/",
    "org/apache/commons/compress/archivers/jar/",
    "org/apache/commons/compress/archivers/examples/",
    "org/apache/commons/compress/compressors/",
    "org/apache/commons/compress/changes/",
)

fun stripJar(jar: File, prefixes: List<String>) {
    val tmp = File.createTempFile("barusikozako-strip", ".jar")
    ZipFile(jar).use { zin ->
        ZipOutputStream(tmp.outputStream()).use { zout ->
            for (entry in Collections.list(zin.entries())) {
                if (prefixes.any { entry.name.startsWith(it) }) continue
                val copy = ZipEntry(entry.name)
                copy.time = 0L
                zout.putNextEntry(copy)
                zin.getInputStream(entry).copyTo(zout)
                zout.closeEntry()
            }
        }
    }
    tmp.copyTo(jar, overwrite = true)
    tmp.delete()
}

fun stripIncludedJar(f: File) {
    when {
        f.name.startsWith("zstd-jni-") -> stripJar(f, nativeStripPrefixes)
        f.name.startsWith("commons-compress-") -> stripJar(f, compressStripPrefixes)
    }
}

tasks.named("processIncludeJars") {
    doLast {
        outputs.files.files.forEach { f ->
            if (f.isDirectory) f.listFiles()?.forEach { stripIncludedJar(it) }
            else stripIncludedJar(f)
        }
    }
}
