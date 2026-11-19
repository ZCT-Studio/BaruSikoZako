import java.util.Properties
import barusikozako.buildlogic.CollectNotices
import net.fabricmc.loom.task.RemapJarTask

plugins {
    alias(libs.plugins.fabric.remapping)
    id("barusikozako.java17-library") apply false
}

val compatibility = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("gradle/compatibility-targets.properties"))
        .asText.get().reader())
}
val game = providers.gradleProperty("fabricLegacyMinecraft").get()
val apiVersion = requireNotNull(compatibility.getProperty("fabric.legacy.api.$game")) {
    "No reviewed Fabric API input for Minecraft $game"
}
val shared = rootProject.file("platform-minecraft26/src/main")
val sharedSources = shared.parentFile
val fabric = rootProject.file("platform-fabric/src/main")
val runtimeProjects = listOf("api-host", "platform-spi", "backend-spi", "core")
val structuredPermissions = game == "1.21.11"
val oldAnvil = game == "1.20.1"
val synchronousSavedData = oldAnvil || game == "1.21.1"
val separateStatsHook = !structuredPermissions && !oldAnvil

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
            "**/PoiStorageAccess.java")
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
    resources.srcDir(shared.resolve("resources"))
    resources.srcDir(fabric.resolve("resources"))
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
        implementation(project(":$component"))
        include(project(":$component"))
    }
    implementation(project(":public-api"))
    implementation(libs.snakeyaml)
    include(libs.snakeyaml)
    include(project(":platform-fabric-backend-zstd"))
}

tasks.processResources {
    val metadata = mapOf("productVersion" to project.version.toString(), "minecraftVersions" to game,
        "loaderRequirement" to ">=0.19.5", "modDescription" to "BaruSikoZako remapped server development build.")
    inputs.properties(metadata)
    filesMatching("fabric.mod.json") { expand(metadata) }
    filesMatching("barusikozako.mixins.json") {
        filter { line: String -> line.replace("\"minVersion\": \"0.8.7\",",
            "\"minVersion\": \"0.8.7\", \"injectors\": { \"defaultRequire\": 1 },") }
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
    manualNotices.set(rootProject.layout.projectDirectory.dir("platform-fabric/third-party"))
    projectJars.set(runtimeProjects.map { "$it-${project.version}.jar" }.toSet() +
        "zakobak_zstd-fabric-$game-${project.version}.jar")
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
    val nested = "META-INF/jars/zakobak_zstd-fabric-$game-${project.version}.jar"
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
tasks.assemble { dependsOn(noBackendJar) }
