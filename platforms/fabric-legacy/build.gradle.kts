import java.util.Properties
import io.github.zct_studio.barusikozako.buildlogic.CollectNotices
import io.github.zct_studio.barusikozako.buildlogic.MinecraftSourcesPlugin
import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar
import net.fabricmc.loom.task.RemapJarTask

plugins {
    alias(libs.plugins.fabric.remapping)
    id("barusikozako.minecraft-sources")
    id("barusikozako.java17-library") apply false
}

val compatibility = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("gradle/compatibility-targets.properties"))
        .asText.get().reader())
}
val game = parent!!.name.removePrefix("mc").replace('_', '.')
val packageTarget = "${extra["fabricPackageLoader"]}$game"
val mixinConfig = MinecraftSourcesPlugin.target(project, MinecraftSourcesPlugin.load(project)).mixinConfigName()
val backendArchive = "barusikozako_backendzstd-fabric-$game-${extra["zstdVersion"]}.jar"
val apiVersion = requireNotNull(compatibility.getProperty("fabric.legacy.api.$game")) {
    "No reviewed Fabric API input for Minecraft $game"
}
val runtimeProjects = listOf(":engine:mod-runtime", ":platforms:spi", ":backends:spi", ":engine:core")
val runtimeJava = if (game == "1.20.1") 17 else 21

base.archivesName.set("barusikozako-fabric-$game-development")

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

tasks.processResources {
    from(rootProject.file("resource/barusikozako/icon.png"))
    from(rootProject.file("resource/barusikozako/lang")) { into("assets/barusikozako/lang") }
}

dependencies {
    minecraft("com.mojang:minecraft:$game")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${libs.versions.fabric.loader.get()}")
    for (name in listOf("fabric-api-base", "fabric-command-api-v2", "fabric-lifecycle-events-v1")) {
        val module = fabricApi.module(name, apiVersion)
        modImplementation(module)
        include(module)
    }
    for (component in runtimeProjects) {
        implementation(project(component))
        include(project(component))
    }
    compileOnly(project(":api:common"))
    implementation(libs.snakeyaml)
    include(libs.snakeyaml)
    include(project("${parent!!.path}:fabric-zstd-package"))
}

tasks.processResources {
    val metadata = mapOf("productVersion" to project.version.toString(),
        "minecraftVersions" to game, "javaVersion" to runtimeJava, "mixinConfig" to mixinConfig,
        "loaderRequirement" to project.extra["fabricLoaderRequirement"], "modDescription" to "BaruSikoZako remapped server development build.")
    @Suppress("UNCHECKED_CAST")
    val info = (rootProject.extra["productMetadata"] as Map<*, *>)["mod"] as Map<String, Any?>
    val metadataFields = mapOf("descriptionJson" to groovy.json.JsonOutput.toJson(info["description"]).removeSurrounding("\""),
        "authorsJson" to (info["authors"] as List<*>).joinToString("\",\"") { groovy.json.JsonOutput.toJson(it).removeSurrounding("\"") },
        "homepageJson" to groovy.json.JsonOutput.toJson(info["homepage"]).removeSurrounding("\""),
        "sourcesJson" to groovy.json.JsonOutput.toJson(info["sources"]).removeSurrounding("\""),
        "issuesJson" to groovy.json.JsonOutput.toJson(info["issues"]).removeSurrounding("\""))
    inputs.properties(metadata)
    inputs.properties(metadataFields)
    filesMatching("fabric.mod.json") { expand(metadata + metadataFields) }
}

val notices = tasks.register<CollectNotices>("collectNotices") {
    packagedLibraries.from(tasks.named("processIncludeJars").map { it.outputs.files })
    manualNotices.set(rootProject.layout.projectDirectory.dir("platforms/fabric/third-party"))
    projectJars.set(setOf("barusikozako_mod_runtime-${project(":engine:mod-runtime").version}.jar",
        "barusikozako_backend_spi-${project(":backends:spi").version}.jar",
        "barusikozako_platform_spi-${project(":platforms:spi").version}.jar",
        "barusikozako_core-${project(":engine:core").version}.jar") +
        backendArchive)
    strict.set(true)
    collected.set(layout.buildDirectory.dir("collectedNotices"))
}

tasks.jar {
    manifest.attributes("Implementation-Version" to project.version.toString())
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(notices.flatMap { it.collected }) { into("META-INF/third-party") }
}

val bundledJar = tasks.named<RemapJarTask>("remapJar")
PublishProductJar.register(project, "publishBundledProduct", "mod", "barusikozako", packageTarget,
    bundledJar.flatMap { it.archiveFile })
