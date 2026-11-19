import java.util.Properties
import java.util.HexFormat
import java.util.zip.ZipFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import barusikozako.buildlogic.CollectNotices

plugins {
    alias(libs.plugins.fabric.loom)
    id("barusikozako.java17-library") apply false
}

val target = Properties().apply {
    rootProject.file("gradle/compatibility-targets.properties").inputStream().use { load(it) }
}
val minecraftVersion = requireNotNull(target.getProperty("fabric.development.minecraft"))
val requestedMinecraft = providers.gradleProperty("fabricRuntimeMinecraft").getOrElse(minecraftVersion)
val runtimeMinecraft = target.getProperty("fabric.runtime.alias.$requestedMinecraft", requestedMinecraft)
val fabricApiVersion = libs.versions.fabric.api.get()
val runtimeApiVersion = if (runtimeMinecraft == minecraftVersion) fabricApiVersion else
    requireNotNull(target.getProperty("fabric.runtime.api.$runtimeMinecraft")) { "Unsupported Fabric target: $runtimeMinecraft" }
val supportedMinecraft = target.getProperty("fabric.runtime.minecraft.$runtimeMinecraft", runtimeMinecraft).split(",")
val releaseAlpha = providers.gradleProperty("releaseAlpha").map(String::toBoolean).getOrElse(false)
if (releaseAlpha && !Regex("\\d+\\.\\d+\\.\\d+-alpha\\.\\d+").matches(project.version.toString()))
    throw GradleException("An Alpha build requires a product version such as 0.1.0-alpha.1")
val runtimeProjects = listOf("api-host", "platform-spi", "backend-spi", "core")
val backendRuntimeSuffix = if (runtimeMinecraft == minecraftVersion) "" else "-$runtimeMinecraft"
val backendArchive = "zakobak_zstd-fabric$backendRuntimeSuffix-${project.version}.jar"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

sourceSets.main {
    java.srcDir(rootProject.file("platform-minecraft26/src/main/java"))
    resources.srcDir(rootProject.file("platform-minecraft26/src/main/resources"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

abstract class VerifyMinecraftCompileInput : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val minecraftJars: ConfigurableFileCollection

    @get:Input
    abstract val expectedSha256: Property<String>

    @TaskAction
    fun verify() {
        val input = minecraftJars.singleFile
        val overall = MessageDigest.getInstance("SHA-256")
        val entryDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        ZipFile(input).use { jar ->
            val files = jar.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.toList()
            var previous: String? = null
            for (entry in files) {
                if (entry.name == previous) throw GradleException("Duplicate entry in Minecraft compile input")
                previous = entry.name
                val name = entry.name.toByteArray(Charsets.UTF_8)
                overall.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(name.size).array())
                overall.update(name)
                jar.getInputStream(entry).use { contents ->
                    while (true) {
                        val count = contents.read(buffer)
                        if (count < 0) break
                        entryDigest.update(buffer, 0, count)
                    }
                }
                overall.update(entryDigest.digest())
            }
        }
        val actual = HexFormat.of().formatHex(overall.digest())
        if (expectedSha256.get() != actual)
            throw GradleException("Minecraft compile input payload checksum differs from the reviewed 26.3 build: $actual")
    }
}

val verifyMinecraftCompileInput = tasks.register<VerifyMinecraftCompileInput>("verifyMinecraftCompileInput") {
    minecraftJars.from(loom.namedMinecraftJars)
    expectedSha256.set(requireNotNull(target.getProperty("fabric.development.minecraftContentSha256")))
}

tasks.named("compileJava") { dependsOn(verifyMinecraftCompileInput) }

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${libs.versions.fabric.loader.get()}")

    for (module in runtimeProjects) {
        val dependency = project(":$module")
        implementation(dependency)
        include(dependency)
    }
    for (module in listOf("fabric-api-base", "fabric-command-api-v2", "fabric-lifecycle-events-v1")) {
        implementation(fabricApi.module(module, fabricApiVersion))
        include(fabricApi.module(module, runtimeApiVersion))
    }
    implementation(project(":public-api"))
    implementation(libs.snakeyaml)
    include(libs.snakeyaml)
    include(project(":platform-fabric-backend-zstd"))

}

tasks.processResources {
    val metadata = mapOf("productVersion" to project.version.toString(),
        "minecraftVersions" to supportedMinecraft.joinToString("\",\""),
        "loaderRequirement" to ">=0.19.5",
        "modDescription" to if (releaseAlpha) "BaruSikoZako backup engine for Fabric dedicated servers (Alpha)."
            else "BaruSikoZako server integration development build.")
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") { expand(metadata) }
}

val collectNotices = tasks.register<CollectNotices>("collectNotices") {
    packagedLibraries.from(tasks.named("processIncludeJars").map { it.outputs.files })
    manualNotices.set(layout.projectDirectory.dir("third-party"))
    projectJars.set(runtimeProjects.map { "$it-${project.version}.jar" }.toSet() + backendArchive)
    strict.set(if (releaseAlpha) providers.provider { true }
        else providers.gradleProperty("strictReleaseNotices").map(String::toBoolean).orElse(false))
    collected.set(layout.buildDirectory.dir("collectedNotices"))
}

tasks.jar {
    manifest.attributes("Implementation-Version" to project.version.toString())
    archiveBaseName.set("barusikozako-fabric-$runtimeMinecraft" + if (releaseAlpha) "" else "-development")
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(collectNotices.flatMap { it.collected }) { into("META-INF/third-party") }
}


val bundledJar = tasks.jar
val noBackendJar = tasks.register<Jar>("noBackendJar") {
    description = "Packages BaruSikoZako without a bundled backup backend."
    group = LifecycleBasePlugin.BUILD_GROUP
    archiveBaseName.set(bundledJar.flatMap { it.archiveBaseName })
    archiveClassifier.set("no-backend")
    manifest.attributes("Implementation-Version" to project.version.toString())
    val nested = "META-INF/jars/$backendArchive"
    inputs.property("backendArchive", backendArchive)
    dependsOn(bundledJar)
    from(bundledJar.flatMap { it.archiveFile }.map { zipTree(it) }) {
        includeEmptyDirs = false
        exclude(nested)
        filesMatching("fabric.mod.json") {
            filter { line ->
                line.replace("\"name\":\"BaruSikoZako\"", "\"name\":\"BaruSikoZako <NO_BACKEND>\"")
                    .replace(",{\"file\":\"$nested\"}", "")
                    .replace("{\"file\":\"$nested\"},", "")
            }
        }
    }
}
tasks.assemble { dependsOn(noBackendJar) }
