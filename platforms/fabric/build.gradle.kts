import java.util.Properties
import java.util.HexFormat
import java.util.zip.ZipFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import io.github.zct_studio.barusikozako.buildlogic.CollectNotices
import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar

plugins {
    alias(libs.plugins.fabric.loom)
    id("barusikozako.java17-library") apply false
}

val target = Properties().apply {
    rootProject.file("gradle/compatibility-targets.properties").inputStream().use { load(it) }
}
val minecraftVersion = requireNotNull(target.getProperty("fabric.development.minecraft"))
val runtimeMinecraft = parent!!.name.removePrefix("mc").replace('_', '.')
val fabricApiVersion = requireNotNull(target.getProperty("fabric.runtime.api.$minecraftVersion"))
val runtimeApiVersion = requireNotNull(target.getProperty("fabric.runtime.api.$runtimeMinecraft")) {
    "Unsupported Fabric target: $runtimeMinecraft"
}
val supportedMinecraft = target.getProperty("fabric.runtime.minecraft.$runtimeMinecraft", runtimeMinecraft).split(",")
val releaseAlpha = providers.gradleProperty("releaseAlpha").map(String::toBoolean).getOrElse(false)
if (releaseAlpha && !Regex("\\d+\\.\\d+\\.\\d+-alpha\\.\\d+").matches(project.version.toString()))
    throw GradleException("An Alpha build requires a product version such as 0.1.0-alpha.1")
val runtimeProjects = listOf(":engine:mod-runtime", ":platforms:spi", ":backends:spi", ":engine:core")
val backendRuntimeSuffix = if (runtimeMinecraft == minecraftVersion) "" else "-$runtimeMinecraft"
val backendArchive = "barusikozako_backendzstd-fabric$backendRuntimeSuffix-${extra["zstdVersion"]}.jar"
val packageTarget = "${extra["fabricPackageLoader"]}$runtimeMinecraft"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

sourceSets.main {
    java.srcDir(rootProject.file("platforms/fabric/src/main/java"))
    resources.srcDir(rootProject.file("platforms/fabric/src/main/resources"))
    java.srcDir(rootProject.file("platforms/minecraft/src/main/java"))
    resources.srcDir(rootProject.file("platforms/minecraft/src/main/resources"))
}
tasks.processResources {
    from(rootProject.file("resource/barusikozako/icon.png"))
    from(rootProject.file("resource/barusikozako/lang")) { into("assets/barusikozako/lang") }
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
        val dependency = project(module)
        implementation(dependency)
        include(dependency)
    }
    for (module in listOf("fabric-api-base", "fabric-command-api-v2", "fabric-lifecycle-events-v1")) {
        implementation(fabricApi.module(module, fabricApiVersion))
        include(fabricApi.module(module, runtimeApiVersion))
    }
    compileOnly(project(":api:common"))
    implementation(libs.snakeyaml)
    include(libs.snakeyaml)
    include(project("${parent!!.path}:fabric-zstd-package"))

}

tasks.processResources {
    val metadata = mapOf("productVersion" to project.version.toString(),
        "minecraftVersions" to supportedMinecraft.joinToString("\",\""),
        "loaderRequirement" to project.extra["fabricLoaderRequirement"],
        "modDescription" to if (releaseAlpha) "BaruSikoZako backup engine for Fabric dedicated servers (Alpha)."
            else "BaruSikoZako server integration development build.")
    @Suppress("UNCHECKED_CAST")
    val info = (rootProject.extra["productMetadata"] as Map<*, *>)["mod"] as Map<String, Any?>
    val metadataFields = mapOf("descriptionJson" to groovy.json.JsonOutput.toJson(info["description"]).removeSurrounding("\""),
        "authorsJson" to (info["authors"] as List<*>).joinToString("\",\"") { groovy.json.JsonOutput.toJson(it).removeSurrounding("\"") },
        "homepageJson" to groovy.json.JsonOutput.toJson(info["homepage"]).removeSurrounding("\""),
        "sourcesJson" to groovy.json.JsonOutput.toJson(info["sources"]).removeSurrounding("\""),
        "issuesJson" to groovy.json.JsonOutput.toJson(info["issues"]).removeSurrounding("\""))
    inputs.properties(metadata)
    inputs.properties(metadataFields)
    filesMatching("fabric.mod.json") { expand(metadata + metadataFields + ("javaVersion" to 25)) }
}

val collectNotices = tasks.register<CollectNotices>("collectNotices") {
    packagedLibraries.from(tasks.named("processIncludeJars").map { it.outputs.files })
    manualNotices.set(rootProject.layout.projectDirectory.dir("platforms/fabric/third-party"))
    projectJars.set(setOf("barusikozako_mod_runtime-${project(":engine:mod-runtime").version}.jar",
        "barusikozako_backend_spi-${project(":backends:spi").version}.jar",
        "barusikozako_platform_spi-${project(":platforms:spi").version}.jar",
        "barusikozako_core-${project(":engine:core").version}.jar") + backendArchive)
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
PublishProductJar.register(project, "publishBundledProduct", "mod", "barusikozako", packageTarget,
    bundledJar.flatMap { it.archiveFile })
