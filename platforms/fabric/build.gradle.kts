import java.util.Properties
import io.github.zct_studio.barusikozako.buildlogic.CollectNotices
import io.github.zct_studio.barusikozako.buildlogic.MinecraftSourcesPlugin
import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar

plugins {
    alias(libs.plugins.fabric.loom)
    id("barusikozako.minecraft-sources")
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
val mixinConfig = MinecraftSourcesPlugin.target(project, MinecraftSourcesPlugin.load(project)).mixinConfigName()

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

tasks.processResources {
    from(rootProject.file("resource/barusikozako/icon.png"))
    from(rootProject.file("resource/barusikozako/lang")) { into("assets/barusikozako/lang") }
}

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
        "mixinConfig" to mixinConfig,
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
