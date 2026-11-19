import groovy.json.JsonOutput
import barusikozako.buildlogic.CollectNotices
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

plugins {
    java
    id("barusikozako.java17-library") apply false
    alias(libs.plugins.shadow) apply false
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

val shared = rootProject.file("platform-minecraft26/src/main")
val compatibility = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("gradle/compatibility-targets.properties")).asText.get().reader())
}
val minecraft = providers.gradleProperty("fmlRuntimeMinecraft")
    .orElse(compatibility.getProperty("fml.development.minecraft")).get()
val versions = listOf("neoforge", "forge").mapNotNull { loader ->
    compatibility.getProperty("fml.runtime.$loader.$minecraft")?.let { loader to it }
}.toMap()
require(versions.isNotEmpty()) { "No FML runtime configured for Minecraft $minecraft" }
val legacyMinecraft = minecraft.startsWith("1.")
val legacyChunkWorker = legacyMinecraft && minecraft != "1.21.11"
val forgeSrg = minecraft == "1.20.1"
val synchronousSavedData = forgeSrg || minecraft == "1.21.1"
val sharedSources = shared.parentFile
val ownComponents = listOf("api-host", "backend-spi", "platform-spi", "core")
val backendComponents = listOf("backend-zstd", "compression-zstd")
val backendLibraries = configurations.create("backendLibraries") { isTransitive = false }
val hostLibraries = configurations.create("hostLibraries") { isTransitive = false }
val isolatedCommons = configurations.create("isolatedCommons") { isTransitive = false }

dependencies {
    add(hostLibraries.name, libs.snakeyaml)
    for (library in listOf(libs.zstd.jni, libs.jackson.core))
        add(backendLibraries.name, library)
    if (synchronousSavedData) {
        // Older servers put incompatible Commons types in their module layer.
        add(isolatedCommons.name, libs.commons.compress)
        add(isolatedCommons.name, "commons-codec:commons-codec:1.19.0")
        add(isolatedCommons.name, "commons-io:commons-io:2.20.0")
        add(isolatedCommons.name, "org.apache.commons:commons-lang3:3.18.0")
    } else {
        add(backendLibraries.name, libs.commons.compress)
        add(backendLibraries.name, "commons-codec:commons-codec:1.19.0")
    }
}

fun Jar.copyComponents(components: List<String>) {
    for (component in components) {
        val archive = project(":$component").tasks.named<Jar>("jar")
        dependsOn(archive)
        from(archive.flatMap { it.archiveFile }.map { zipTree(it) }) { include("barusikozako/**") }
    }
}

fun Jar.loaderManifest(module: String, mixin: Boolean = false) {
    manifest.attributes("Automatic-Module-Name" to module, "Implementation-Version" to project.version.toString())
    if (mixin) manifest.attributes("MixinConfigs" to "barusikozako.mixins.json")
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

fun metadata(loader: String, id: String, displayName: String, mixin: Boolean = false): String = buildString {
    val neo = loader == "neoforge"
    val version = project.version.toString()
    if (!neo || legacyMinecraft || id != "zakobak_api") {
        appendLine("modLoader=\"${if (id == "zakobak_api") "lowcodefml" else "javafml"}\"")
        appendLine("loaderVersion=\"[1,)\"")
    }
    appendLine("license=\"MPL-2.0\"")
    appendLine("[[mods]]")
    appendLine("modId=\"$id\"")
    appendLine("version=\"$version\"")
    appendLine("displayName=\"$displayName\"")
    if (!neo) appendLine("displayTest=\"IGNORE_SERVER_VERSION\"")
    fun dependency(on: String, range: String) {
        appendLine("[[dependencies.$id]]")
        appendLine("modId=\"$on\"")
        appendLine(if (neo) "type=\"required\"" else "mandatory=true")
        appendLine("versionRange=\"$range\"")
        appendLine("ordering=\"AFTER\"")
        appendLine("side=\"SERVER\"")
    }
    dependency("minecraft", "[$minecraft]")
    dependency(loader, "[${versions.getValue(loader).removePrefix("$minecraft-")},)")
    if (id == "barusikozako") dependency("zakobak_api", "[$version]")
    if (id == "zakobak_zstd") dependency("barusikozako", "[$version]")
    if (neo && mixin) {
        appendLine("[[mixins]]")
        appendLine("config=\"barusikozako.mixins.json\"")
    }
}

fun metadataFile(task: String, filename: String, content: String): TaskProvider<Task> {
    val output = layout.buildDirectory.file("metadata/$task/$filename")
    return tasks.register(task) {
        inputs.property("content", content)
        outputs.file(output)
        doLast {
            output.get().asFile.apply { parentFile.mkdirs(); writeText(content, Charsets.UTF_8) }
        }
    }
}

fun notices(task: String, vararg libraries: Configuration) = tasks.register<CollectNotices>(task) {
    packagedLibraries.from(*libraries)
    manualNotices.set(rootProject.layout.projectDirectory.dir("platform-fabric/third-party"))
    projectJars.set(emptySet())
    strict.set(true)
    collected.set(layout.buildDirectory.dir("collectedNotices/$task"))
}

val hostNotices = notices("collectHostNotices", hostLibraries)
val backendNotices = notices("collectBackendNotices", backendLibraries, isolatedCommons)

fun jarJarMetadata(name: String, libraries: Configuration, nestedBackend: TaskProvider<out Jar>? = null): TaskProvider<Task> {
    val archive = nestedBackend?.flatMap { it.archiveFile }
    val descriptors = libraries.incoming.artifacts.resolvedArtifacts
    val output = layout.buildDirectory.file("metadata/$name/jarjar/metadata.json")
    val content = descriptors.map { results ->
        val coordinates = results.map {
            val id = it.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier
            listOf(id.group, id.module, id.version, it.file.name)
        }.sortedBy { it[3] }.toMutableList()
        if (archive != null) coordinates.add(listOf("barusikozako", "zakobak_zstd", project.version.toString(), archive.get().asFile.name))
        JsonOutput.toJson(mapOf("jars" to coordinates.map { (group, id, version, jar) ->
            mapOf("identifier" to mapOf("group" to group, "artifact" to id),
                "version" to mapOf("range" to "[$version]", "artifactVersion" to version),
                "path" to "META-INF/jarjar/$jar", "isObfuscated" to false)
        }))
    }
    return tasks.register("${name}JarJar") {
        inputs.property("metadata", content)
        outputs.file(output)
        doLast { output.get().asFile.apply { parentFile.mkdirs(); writeText(content.get(), Charsets.UTF_8) } }
    }
}

fun Jar.attachLibraries(libraries: Configuration, descriptor: TaskProvider<Task>, nestedBackend: TaskProvider<out Jar>?) {
    val archive = nestedBackend?.flatMap { it.archiveFile }
    from(descriptor) { into("META-INF/jarjar") }
    from(libraries) { into("META-INF/jarjar") }
    if (archive != null) {
        dependsOn(nestedBackend)
        from(archive) { into("META-INF/jarjar") }
    }
}

for ((loader, version) in versions) {
    val neoSavedData = loader == "neoforge" && synchronousSavedData
    val runtimeDir = providers.gradleProperty("${loader}26ServerDir").map { rootProject.file(it) }
    val libraryDir = runtimeDir.map { it.resolve("libraries") }
    val patched = libraryDir.map {
        if (loader == "neoforge" && minecraft == "1.21.1")
            it.resolve("net/neoforged/neoforge/$version/neoforge-$version-server.jar")
        else if (loader == "neoforge") it.resolve("net/neoforged/minecraft-server-patched/$version/minecraft-server-patched-$version.jar")
        else it.resolve("net/minecraftforge/forge/$version/forge-$version-server.jar")
    }
    val otherLibraries = files(libraryDir.map { dir ->
        fileTree(dir) {
            include("**/*.jar")
            exclude("**/*-bundled.jar", "**/*-unpacked.jar", "**/*-sources.jar", "**/*-installer.jar", "**/*-client.jar")
            if (forgeSrg) exclude("**/forge-$version-server.jar", "**/*-srg.jar", "**/*-slim.jar")
        }
    })
    val srgTools = layout.buildDirectory.dir("forgeSrg")
    val mappings = srgTools.map { it.file("official-to-srg.tsrg") }
    val namedGame = srgTools.map { it.file("server-named.jar") }
    val refmap = srgTools.map { it.file("barusikozako.refmap.json") }
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    val fartClasspath = files(libraryDir.map { dir ->
        fileTree(dir.resolve("org/ow2/asm")) { include("**/*-9.9.1.jar") }
    }, libraryDir.map { it.resolve("net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar") })
    val mappingLibraries = otherLibraries.filter { library ->
        library.name.startsWith("datafixerupper-") || library.name.startsWith("fastutil-") ||
            library.name.startsWith("guava-") || library.name.startsWith("brigadier-") ||
            library.name.startsWith("authlib-") || library.name.startsWith("joml-") ||
            library.name.startsWith("commons-lang3-") || library.name.startsWith("gson-")
    }
    val namedGameTask = if (forgeSrg) {
        val mcp = "1.20.1-20230612.114412"
        val official = libraryDir.map { it.resolve("net/minecraft/server/$mcp/server-$mcp-mappings.txt") }
        val vanilla = libraryDir.map { it.resolve("net/minecraft/server/$mcp/server-$mcp-slim.jar") }
        val srg = libraryDir.map { it.resolve("de/oceanlabs/mcp/mcp_config/$mcp/mcp_config-$mcp-mappings-merged.txt") }
        val source = layout.projectDirectory.file("tools/ForgeMappings.java")
        val compose = tasks.register<Exec>("composeForgeMappings") {
            inputs.files(source, official, srg)
            val utils = libraryDir.map { it.resolve("net/minecraftforge/srgutils/0.5.6/srgutils-0.5.6.jar") }
            inputs.file(utils)
            outputs.file(mappings)
            doFirst {
                mappings.get().asFile.parentFile.mkdirs()
                commandLine(launcher.get().executablePath.asFile, "-cp", utils.get(), source.asFile,
                    official.get(), srg.get(), mappings.get().asFile)
            }
        }
        tasks.register<JavaExec>("prepareNamedForgeCompileInput") {
            dependsOn(compose)
            javaLauncher.set(launcher)
            classpath = fartClasspath
            mainClass.set("net.minecraftforge.fart.Main")
            inputs.files(official, vanilla, mappingLibraries)
            outputs.file(namedGame)
            doFirst {
                args("--input", vanilla.get(), "--output", namedGame.get().asFile,
                    "--names", official.get(), "--reverse", "--threads", "2")
                args(mappingLibraries.map { "--lib=${it.absolutePath}" })
            }
        }
    } else null
    val serverClasspath = (if (forgeSrg) files(namedGame).builtBy(namedGameTask!!) else files()) +
        files(patched) + otherLibraries
    val api = sourceSets.create("${loader}Api") { compileClasspath += serverClasspath }
    addCompileDependency(api, project(":public-api"))
    val main = sourceSets.create(loader) {
        if (!legacyMinecraft) java.srcDir(shared.resolve("java"))
        if (legacyMinecraft && loader == "forge") java.exclude("**/MainMod.java")
        compileClasspath += serverClasspath + api.output
    }
    if (legacyMinecraft) {
        tasks.named<JavaCompile>(main.compileJavaTaskName) {
            source(fileTree(shared.resolve("java")) {
                exclude("**/SavedDataBarriers.java", "**/SavedDataQueue.java", "**/SavedDataObservation.java",
                    "**/ChunkUnloadObservation.java")
                if (legacyChunkWorker) exclude("**/GameCommandAccess.java", "**/ChunkBarrier.java",
                    "**/ReplacementObservation.java", "**/AuxiliaryPlayerObservation.java")
                if (synchronousSavedData) exclude("**/EmptyServerCaptureMixin.java", "**/PoiWrites.java")
                if (forgeSrg) exclude("**/WorldLevelMetadata.java", "**/DedicatedStartupMixin.java",
                    "**/RootFileObservation.java", "**/ServerSaveObservation.java", "**/EntityStorageAccess.java",
                    "**/PoiStorageAccess.java")
            })
            if (synchronousSavedData) {
                source(fileTree(sharedSources.resolve("synchronousSavedData/java")) {
                    if (neoSavedData) exclude("**/SavedDataObservation.java", "**/SavedDataBarriers.java")
                    if (forgeSrg) exclude("**/SavedDataObservation.java")
                })
                if (neoSavedData) source(fileTree("src/neoforge1211/java"))
            } else {
                source(fileTree(sharedSources.resolve("asynchronousSavedData/java")))
            }
            if (legacyChunkWorker) {
                source(fileTree(sharedSources.resolve("beforeStructuredPermissions/java")) {
                    if (forgeSrg) exclude("**/ReplacementObservation.java")
                })
                source(fileTree(sharedSources.resolve("separateStatsHook/java")))
            }
            if (forgeSrg) {
                source(fileTree(sharedSources.resolve("minecraft1201/java")))
                source(fileTree("src/forgeSrg/java"))
            } else source(fileTree("src/officialLegacy/java"))
            if (loader == "forge") {
                source(fileTree("src/forgeLegacy/java"))
                source(fileTree(if (synchronousSavedData) "src/forgeClassicBus/java" else "src/forgeTypedBus/java"))
            }
        }
    }
    val backend = sourceSets.create("${loader}Backend") { compileClasspath += serverClasspath + main.output }
    for (set in listOf(api, main, backend)) {
        tasks.named<JavaCompile>(set.compileJavaTaskName) {
            options.release.set(25)
            options.encoding = "UTF-8"
            if (forgeSrg && set == main) {
                options.annotationProcessorPath = otherLibraries.filter {
                    it.name == "mixin-0.8.5.jar" || it.name.startsWith("gson-") ||
                        it.name.startsWith("guava-") || it.name.matches(Regex("asm.*-9\\.9\\.1\\.jar"))
                }
                inputs.file(mappings).withPathSensitivity(PathSensitivity.NONE)
                outputs.file(refmap)
                options.compilerArgs.addAll(listOf("-AdefaultObfuscationEnv=searge", "-AmappingTypes=tsrg",
                    "-AreobfTsrgFile=${mappings.get().asFile}", "-AoutRefMapFile=${refmap.get().asFile}"))
            } else options.compilerArgs.add("-proc:none")
            doFirst {
                if (!patched.orNull.let { it != null && it.isFile })
                    throw GradleException("Install the official $loader $version server and set -P${loader}26ServerDir=<path>")
            }
        }
    }
    for (module in ownComponents + "public-api") addCompileDependency(main, project(":$module"))
    for (module in backendComponents + "backend-spi") addCompileDependency(backend, project(":$module"))
    addCompileDependency(main, libs.snakeyaml)
    val toml = if (loader == "neoforge") "neoforge.mods.toml" else "mods.toml"
    val apiMeta = metadataFile("${loader}ApiMetadata", toml, metadata(loader, "zakobak_api", "ZakoBak API"))
    val backendMeta = metadataFile("${loader}BackendMetadata", toml, metadata(loader, "zakobak_zstd", "ZakoBak Zstd"))
    val apiJar = tasks.register<Jar>("${loader}ApiJar") {
        archiveBaseName.set("zakobak_api-$loader-$minecraft")
        from(apiMeta) { into("META-INF") }
        from(api.output)
        copyComponents(listOf("public-api"))
        loaderManifest("zakobak.api")
    }
    val backendJarJar = jarJarMetadata("${loader}Backend", backendLibraries)
    val backendJar = tasks.register("${loader}BackendJar",
        if (synchronousSavedData) ShadowJar::class.java else Jar::class.java) {
        archiveBaseName.set("zakobak_zstd-$loader-$minecraft")
        from(backend.output)
        from(backendMeta) { into("META-INF") }
        copyComponents(backendComponents)
        loaderManifest("zakobak.zstd")
        attachLibraries(backendLibraries, backendJarJar, null)
        from(backendNotices.flatMap { it.collected }) { into("META-INF/third-party") }
        if (this is ShadowJar) {
            configurations.set(listOf(isolatedCommons))
            for (pkg in listOf("org.apache.commons.compress", "org.apache.commons.codec",
                               "org.apache.commons.io", "org.apache.commons.lang3")) {
                relocate(pkg, pkg.replace("org.apache.commons", "barusikozako.zstd.internal.commons"))
            }
            exclude("module-info.class", "META-INF/versions/**/module-info.class")
            if (forgeSrg) {
                // These Commons jars only have versioned module descriptors, removed above.
                addMultiReleaseAttribute.set(false)
            } else manifest.attributes("Multi-Release" to true)
        }
    }
    val variants = listOf(false, true).map { lean ->
        val kind = if (lean) "NoBackend" else "Bundled"
        val title = "BaruSikoZako" + if (lean) " <NO_BACKEND>" else ""
        val descriptor = metadataFile("$loader${kind}Metadata", toml, metadata(loader, "barusikozako", title, true))
        val includedBackend = if (lean) null else backendJar
        val jarJar = jarJarMetadata("$loader$kind", hostLibraries, includedBackend)
        tasks.register<Jar>("$loader${kind}Jar") {
            archiveBaseName.set("barusikozako-$loader-$minecraft")
            if (lean) archiveClassifier.set("no-backend")
            if (forgeSrg) {
                destinationDirectory.set(srgTools.map { it.dir("named") })
                from(refmap)
            }
            from(main.output)
            from(shared.resolve("resources")) {
                if (legacyMinecraft) filesMatching("barusikozako.mixins.json") {
                    filter { line: String ->
                        var value = line.replace("\"minVersion\": \"0.8.7\",",
                            "\"minVersion\": \"0.8.7\", \"injectors\": { \"defaultRequire\": 1 },")
                        if (legacyChunkWorker) value = value
                            .replace("\"PoiStorageAccess\"", "\"PoiStorageAccess\", \"LegacyChunkStorageAccess\"")
                        if (legacyChunkWorker) value = value.replace("\"AuxiliaryPlayerObservation\",",
                                "\"AuxiliaryPlayerObservation\", \"PlayerStatsObservation\",")
                        if (synchronousSavedData) value = value.replace("\"EmptyServerCaptureMixin\",", "")
                            .replace("\"SavedDataQueue\",", if (neoSavedData) "\"NeoForgeSaveQueue\"," else "")
                            .replace("\"PoiStorageAccess\"", "\"LegacyPoiWrites\", \"PoiStorageAccess\"")
                        if (forgeSrg) value = value.replace("\"0.8.7\"", "\"0.8.5\", \"refmap\": \"barusikozako.refmap.json\"")
                            .replace("\"ServerSaveObservation\",",
                                "\"LegacyRegionWriteFailure\", \"LegacyChunkSerializationFailure\", \"LegacyPoiSerializationFailure\",")
                        value
                    }
                }
            }
            from(descriptor) { into("META-INF") }
            copyComponents(ownComponents)
            loaderManifest("barusikozako", mixin = loader == "forge")
            attachLibraries(hostLibraries, jarJar, includedBackend)
            from(hostNotices.flatMap { it.collected }) { into("META-INF/third-party") }
        }
    }
    val distributable = if (forgeSrg) variants.map { named ->
        tasks.register<JavaExec>("${named.name}Srg") {
            javaLauncher.set(launcher)
            classpath = fartClasspath
            mainClass.set("net.minecraftforge.fart.Main")
            val input = named.flatMap { it.archiveFile }
            val output = layout.buildDirectory.dir("libs").flatMap { dir -> named.flatMap { it.archiveFileName }.map { dir.file(it) } }
            inputs.files(input, mappings, namedGame, mappingLibraries)
            outputs.file(output)
            doFirst {
                args("--input", input.get().asFile, "--output", output.get().asFile, "--names", mappings.get().asFile,
                    "--lib", namedGame.get().asFile, "--threads", "2")
                args(mappingLibraries.map { "--lib=${it.absolutePath}" })
            }
        }
    } else variants
    tasks.register("${loader}Dist") { dependsOn(apiJar, backendJar, distributable) }
}

fun addCompileDependency(sourceSet: SourceSet, dependency: Any) {
    dependencies.add(sourceSet.compileOnlyConfigurationName, dependency)
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(versions.keys.map { "${it}Dist" }) }
