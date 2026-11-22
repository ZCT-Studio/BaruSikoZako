import java.util.Properties
import io.github.zct_studio.barusikozako.buildlogic.CollectNotices
import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar
import net.fabricmc.loom.task.RemapJarTask

plugins {
    alias(libs.plugins.fabric.remapping)
    id("barusikozako.java17-library") apply false
}

val compatibility = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("gradle/compatibility-targets.properties"))
        .asText.get().reader())
}
val game = parent!!.name.removePrefix("mc").replace('_', '.')
val packageTarget = "${extra["fabricPackageLoader"]}$game"
val backendArchive = "barusikozako_backendzstd-fabric-$game-${extra["zstdVersion"]}.jar"
val apiVersion = requireNotNull(compatibility.getProperty("fabric.legacy.api.$game")) {
    "No reviewed Fabric API input for Minecraft $game"
}
val shared = rootProject.file("platforms/minecraft/src/main")
val sharedSources = shared.parentFile
val fabric = rootProject.file("platforms/fabric/src/main")
val runtimeProjects = listOf(":engine:mod-runtime", ":platforms:spi", ":backends:spi", ":engine:core")
val structuredPermissions = game == "1.21.11"
val oldAnvil = game == "1.20.1"
val synchronousSavedData = oldAnvil || game == "1.21.1"
val separateStatsHook = !structuredPermissions

base.archivesName.set("barusikozako-fabric-$game-development")

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.compileJava {
    source(fileTree(shared.resolve("java")) {
        exclude("**/SavedDataObservation.java", "**/SavedDataQueue.java", "**/SavedDataBarriers.java",
            "**/ChunkUnloadObservation.java")
        if (synchronousSavedData) exclude("**/EmptyServerCaptureMixin.java", "**/PoiWrites.java")
        if (separateStatsHook) exclude("**/AuxiliaryPlayerObservation.java")
        if (oldAnvil) exclude("**/WorldLevelMetadata.java", "**/DedicatedStartupMixin.java",
            "**/RootFileObservation.java", "**/ServerSaveObservation.java", "**/EntityStorageAccess.java",
            "**/PoiStorageAccess.java", "**/PlayerLanguage.java")
        if (!structuredPermissions) {
            exclude("**/GameCommandAccess.java", "**/ChunkBarrier.java", "**/ReplacementObservation.java")
        }
    })
    source(fileTree(fabric.resolve("java")))
    if (!structuredPermissions) source(fileTree(sharedSources.resolve("beforeStructuredPermissions/java")) {
        if (oldAnvil) exclude("**/ReplacementObservation.java")
    })
    if (synchronousSavedData) {
        source(fileTree(sharedSources.resolve("synchronousSavedData/java")) {
            if (oldAnvil) exclude("**/SavedDataObservation.java")
        })
    } else {
        source(fileTree(sharedSources.resolve("asynchronousSavedData/java")))
    }
    if (oldAnvil) source(fileTree(sharedSources.resolve("minecraft1201/java")))
    if (separateStatsHook) source(fileTree(sharedSources.resolve("separateStatsHook/java")))
}

sourceSets.main {
    java.srcDir(rootProject.file("platforms/fabric-legacy/src/main/java"))
    resources.srcDir(shared.resolve("resources"))
    resources.srcDir(fabric.resolve("resources"))
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
        "minecraftVersions" to game,
        "loaderRequirement" to ">=0.19.5", "modDescription" to "BaruSikoZako remapped server development build.")
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
    filesMatching("barusikozako.mixins.json") {
        filter { line: String -> line.replace("\"minVersion\": \"0.8.7\",",
            "\"minVersion\": \"0.8.7\", \"injectors\": { \"defaultRequire\": 1 },") }
    }
    if (oldAnvil) filesMatching("barusikozako.mixins.json") {
        filter { line: String -> line.replace("\"PlayerFileObservation\",",
            "\"PlayerFileObservation\", \"ClientLanguageObservation\",") }
    }
    if (!structuredPermissions) filesMatching("barusikozako.mixins.json") {
        filter { line: String ->
            line.replace("\"PoiStorageAccess\"", "\"PoiStorageAccess\", \"LegacyChunkStorageAccess\"")
        }
    }
    if (synchronousSavedData) filesMatching("barusikozako.mixins.json") {
        filter { line: String ->
            line.replace("\"EmptyServerCaptureMixin\",", "").replace("\"SavedDataQueue\",", "")
                .replace("\"PoiStorageAccess\"", "\"LegacyPoiWrites\", \"PoiStorageAccess\"")
        }
    }
    if (oldAnvil) filesMatching("barusikozako.mixins.json") {
        filter { line: String -> line.replace("\"ServerSaveObservation\",",
            "\"LegacyRegionWriteFailure\", \"LegacyChunkSerializationFailure\", \"LegacyPoiSerializationFailure\",") }
    }
    if (separateStatsHook) filesMatching("barusikozako.mixins.json") {
        filter { line: String -> line.replace("\"AuxiliaryPlayerObservation\",",
            "\"AuxiliaryPlayerObservation\", \"PlayerStatsObservation\",") }
    }
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
val noBackendJar = tasks.register<Jar>("noBackendJar") {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Packages the remapped server mod without the bundled backend."
    archiveClassifier.set("no-backend")
    manifest.attributes("Implementation-Version" to project.version.toString())
    val nested = "META-INF/jars/$backendArchive"
    inputs.property("backendArchive", nested)
    from(bundledJar.flatMap { it.archiveFile }.map { zipTree(it) }) {
        includeEmptyDirs = false
        exclude(nested)
        filesMatching("fabric.mod.json") {
            filter { line: String ->
                line.replace("\"name\":\"BaruSikoZako\"", "\"name\":\"BaruSikoZako <NO_BACKEND>\"")
                    .replace(",{\"file\":\"$nested\"}", "")
                    .replace("{\"file\":\"$nested\"},", "")
            }
        }
    }
}
PublishProductJar.register(project, "publishBundledProduct", "mod", "barusikozako", packageTarget, false,
    bundledJar.flatMap { it.archiveFile })
PublishProductJar.register(project, "publishLeanProduct", "mod", "barusikozako", packageTarget, true,
    noBackendJar.flatMap { it.archiveFile })
tasks.assemble {
    dependsOn("${parent!!.path}:fabric-zstd-package:assemble")
}
