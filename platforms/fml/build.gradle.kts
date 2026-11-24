import groovy.json.JsonOutput
import io.github.zct_studio.barusikozako.buildlogic.CollectNotices
import io.github.zct_studio.barusikozako.buildlogic.MinecraftSourcesPlugin
import io.github.zct_studio.barusikozako.buildlogic.PrepareFmlRuntime
import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties

plugins {
    java
    id("barusikozako.java17-library") apply false
    alias(libs.plugins.shadow) apply false
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

val shared = rootProject.file("platforms/minecraft/src/main")
val compatibility = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("gradle/compatibility-targets.properties")).asText.get().reader())
}
val minecraft = parent!!.name.removePrefix("mc").replace('_', '.')
val gameParts = minecraft.split('.').map(String::toInt)
val supportsNeoBanner = gameParts[0] > 26 || gameParts[0] == 26 && gameParts.getOrElse(1) { 0 } >= 2
val targetLoader = name
val apiOnly = path.startsWith(":api:")
val modVersion = extra["modVersion"].toString()
val apiVersion = extra["apiVersion"].toString()
val zstdVersion = extra["zstdVersion"].toString()
val versions = mapOf(targetLoader to requireNotNull(compatibility.getProperty("fml.runtime.$targetLoader.$minecraft")))
val fmlSource = rootProject.file("platforms/fml")
val legacyMinecraft = minecraft.startsWith("1.")
val forgeSrg = minecraft == "1.20.1"
val runtimeJava = if (forgeSrg) 17 else if (legacyMinecraft) 21 else 25
val synchronousSavedData = forgeSrg || minecraft == "1.21.1"
val isolateBackendCommons = synchronousSavedData || targetLoader == "forge"
val buildTargets = MinecraftSourcesPlugin.load(project)
val ownComponents = listOf(":engine:mod-runtime", ":backends:spi", ":platforms:spi", ":engine:core")
val backendComponents = listOf(":backends:zstd", ":backends:compression-zstd")
val backendLibraries = configurations.create("backendLibraries") { isTransitive = false }
val hostLibraries = configurations.create("hostLibraries") { isTransitive = false }
val isolatedCommons = configurations.create("isolatedCommons") { isTransitive = false }
val legacyAsm = configurations.create("legacyAsm") {
    isCanBeConsumed = false
    isTransitive = false
}
val forgeMappingTools = configurations.create("forgeMappingTools") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    add(hostLibraries.name, libs.snakeyaml)
    for (library in listOf(libs.zstd.jni, libs.jackson.core))
        add(backendLibraries.name, library)
    if (isolateBackendCommons) {
        // Forge 客户端和旧版服务端可能已加载另一份 Commons 模块
        add(isolatedCommons.name, libs.commons.compress)
        add(isolatedCommons.name, "commons-codec:commons-codec:1.19.0")
        add(isolatedCommons.name, "commons-io:commons-io:2.20.0")
        add(isolatedCommons.name, "org.apache.commons:commons-lang3:3.18.0")
    } else {
        add(backendLibraries.name, libs.commons.compress)
        add(backendLibraries.name, "commons-codec:commons-codec:1.19.0")
    }
    if (forgeSrg && !apiOnly) {
        add(forgeMappingTools.name, "net.minecraftforge:srgutils:0.5.6")
        for (artifact in listOf("asm", "asm-analysis", "asm-commons", "asm-tree", "asm-util")) {
            add(legacyAsm.name, "org.ow2.asm:$artifact:9.10.1")
        }
    }
}

fun Jar.copyComponents(components: List<String>) {
    for (component in components) {
        val archive = project(component).tasks.named<Jar>("jar")
        dependsOn(archive)
        from(archive.flatMap { it.archiveFile }.map { zipTree(it) }) {
            include("io/github/zct_studio/barusikozako/**", "META-INF/barusikozako/gitignore/**")
        }
    }
}

fun Jar.loaderManifest(module: String, mixin: Boolean = false) {
    manifest.attributes("Automatic-Module-Name" to module, "Implementation-Version" to archiveVersion.get())
    if (mixin) manifest.attributes("MixinConfigs" to "barusikozako.mixins.json")
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

fun metadata(loader: String, id: String, displayName: String, mixin: Boolean = false): String = buildString {
    val neo = loader == "neoforge"
    val modernNeoArtwork = neo && supportsNeoBanner
    val version = when (id) {
        "barusikozako_api" -> apiVersion
        "barusikozako_backendzstd" -> zstdVersion
        else -> modVersion
    }
    if (!neo || legacyMinecraft || id != "barusikozako_api") {
        appendLine("modLoader=\"${if (id == "barusikozako_api") "lowcodefml" else "javafml"}\"")
        appendLine("loaderVersion=\"[1,)\"")
    }
    val allMetadata = rootProject.extra["productMetadata"] as Map<*, *>
    val info = when (id) {
        "barusikozako_api" -> allMetadata["api"]
        "barusikozako_backendzstd" -> (allMetadata["backend"] as Map<*, *>)["zstd"]
        else -> allMetadata["mod"]
    } as Map<*, *>
    fun quote(value: Any?) = JsonOutput.toJson(value.toString())
    appendLine("issueTrackerURL=${quote(info["issues"])}")
    appendLine("license=\"MPL-2.0\"")
    appendLine("[[mods]]")
    appendLine("modId=\"$id\"")
    appendLine("version=\"$version\"")
    appendLine("displayName=\"$displayName\"")
    appendLine("authors=${quote((info["authors"] as List<*>).joinToString(", "))}")
    appendLine("description=${quote(info["description"])}")
    appendLine("displayURL=${quote(info["homepage"])}")
    appendLine("modUrl=${quote(info["sources"])}")
    val artwork = if (!modernNeoArtwork && id == "barusikozako") "banner.png" else "icon.png"
    appendLine("${if (modernNeoArtwork) "iconFile" else "logoFile"}=\"$artwork\"")
    if (modernNeoArtwork && id == "barusikozako") appendLine("bannerFile=\"banner.png\"")
    if (!neo) appendLine("displayTest=\"IGNORE_SERVER_VERSION\"")
    fun dependency(on: String, range: String) {
        appendLine("[[dependencies.$id]]")
        appendLine("modId=\"$on\"")
        appendLine(if (neo) "type=\"required\"" else "mandatory=true")
        appendLine("versionRange=\"$range\"")
        appendLine("ordering=\"AFTER\"")
        appendLine("side=\"BOTH\"")
    }
    dependency("minecraft", "[$minecraft]")
    dependency(loader, "[${versions.getValue(loader).removePrefix("$minecraft-")},)")
    if (id == "barusikozako_api") dependency("barusikozako", "[0,)")
    if (id == "barusikozako_backendzstd") dependency("barusikozako", "[$modVersion]")
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
    manualNotices.set(rootProject.layout.projectDirectory.dir("platforms/fabric/third-party"))
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
        if (archive != null) coordinates.add(listOf("barusikozako", "barusikozako_backendzstd", zstdVersion, archive.get().asFile.name))
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
    val apiPath = ":api:${parent!!.name}:$loader"
    val runtimeProperty = "${loader}26ServerDir"
    val runtimeOverride = providers.gradleProperty(runtimeProperty).orNull
        ?: (extra["localTarget"] as Properties).getProperty(runtimeProperty)
    val runtimePath = ".gradle/fml-runtime/$minecraft/$loader-$version"
    val libraryDir = rootProject.file(runtimeOverride ?: runtimePath).resolve("libraries")
    val patchedJar = when {
        loader == "neoforge" && minecraft == "1.21.1" ->
            "net/neoforged/neoforge/$version/neoforge-$version-server.jar"
        loader == "neoforge" ->
            "net/neoforged/minecraft-server-patched/$version/minecraft-server-patched-$version.jar"
        else -> "net/minecraftforge/forge/$version/forge-$version-server.jar"
    }
    val patched = libraryDir.resolve(patchedJar)
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    val runtimePreparation = if (runtimeOverride == null) {
        if (apiOnly) {
            val repository = if (loader == "neoforge") "https://maven.neoforged.net/releases/net/neoforged/neoforge"
                else "https://maven.minecraftforge.net/net/minecraftforge/forge"
            val offlineBuild = gradle.startParameter.isOffline
            val runtimeDirectory = rootProject.layout.projectDirectory.dir(runtimePath)
            tasks.register<PrepareFmlRuntime>("prepareFmlRuntime") {
                installerUrl.set("$repository/$version/$loader-$version-installer.jar")
                patchedJarPath.set("libraries/$patchedJar")
                overrideProperty.set(runtimeProperty)
                offline.set(offlineBuild)
                javaLauncher.set(launcher)
                this.runtimeDirectory.set(runtimeDirectory)
            }
        }
        listOf("$apiPath:prepareFmlRuntime")
    } else {
        if (apiOnly) {
            tasks.register("validateFmlRuntime") {
                doLast {
                    if (!patched.isFile) {
                        throw GradleException("Missing $patched. Set -P$runtimeProperty to the official " +
                            "$loader $version server directory or remove the override to let Gradle prepare it.")
                    }
                }
            }
        }
        listOf("$apiPath:validateFmlRuntime")
    }
    val otherLibraries = files(fileTree(libraryDir) {
        include("**/*.jar")
        exclude("**/*-bundled.jar", "**/*-unpacked.jar", "**/*-sources.jar", "**/*-installer.jar", "**/*-client.jar")
        if (forgeSrg) exclude("**/forge-$version-server.jar", "**/*-srg.jar", "**/*-slim.jar")
    }).builtBy(runtimePreparation)
    val srgTools = layout.buildDirectory.dir("forgeSrg")
    val mappings = srgTools.map { it.file("official-to-srg.tsrg") }
    val namedGame = srgTools.map { it.file("server-named.jar") }
    val refmap = srgTools.map { it.file("barusikozako.refmap.json") }
    val fartClasspath = files(legacyAsm,
        libraryDir.resolve("net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar"))
    val mappingLibraries = otherLibraries.filter { library ->
        library.name.startsWith("datafixerupper-") || library.name.startsWith("fastutil-") ||
            library.name.startsWith("guava-") || library.name.startsWith("brigadier-") ||
            library.name.startsWith("authlib-") || library.name.startsWith("joml-") ||
            library.name.startsWith("commons-lang3-") || library.name.startsWith("gson-")
    }
    val namedGameTask = if (forgeSrg && !apiOnly) {
        val mcp = "1.20.1-20230612.114412"
        val official = libraryDir.resolve("net/minecraft/server/$mcp/server-$mcp-mappings.txt")
        val vanilla = libraryDir.resolve("net/minecraft/server/$mcp/server-$mcp-slim.jar")
        val srg = libraryDir.resolve("de/oceanlabs/mcp/mcp_config/$mcp/mcp_config-$mcp-mappings-merged.txt")
        val source = fmlSource.resolve("tools/ForgeMappings.java")
        val compose = tasks.register<Exec>("composeForgeMappings") {
            dependsOn(runtimePreparation)
            inputs.files(source, official, srg)
            inputs.files(forgeMappingTools).withPropertyName("mappingToolClasspath")
            outputs.file(mappings)
            doFirst {
                mappings.get().asFile.parentFile.mkdirs()
                commandLine(launcher.get().executablePath.asFile, "-cp", forgeMappingTools.asPath, source,
                    official, srg, mappings.get().asFile)
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
                args("--input", vanilla, "--output", namedGame.get().asFile,
                    "--names", official, "--reverse", "--threads", "2")
                args(mappingLibraries.map { "--lib=${it.absolutePath}" })
            }
        }
    } else null
    val serverClasspath = (if (namedGameTask != null) files(namedGame).builtBy(namedGameTask) else files()) +
        files(patched) + otherLibraries
    val toml = if (loader == "neoforge") "neoforge.mods.toml" else "mods.toml"
    val forgePack = if (loader == "forge") {
        // 格式范围与各版本 Forge 官方包一致，Forge 会在客户端和服务端读取此元数据
        val formats = when (minecraft) {
            "1.20.1" -> mapOf("pack_format" to 15)
            "1.21.1" -> mapOf("pack_format" to 48)
            "1.21.10" -> mapOf("min_format" to 88, "max_format" to 88)
            "1.21.11" -> mapOf("min_format" to listOf(94, 1), "max_format" to 94)
            "26.1.2" -> mapOf("min_format" to listOf(101, 1), "max_format" to 101)
            "26.2" -> mapOf("min_format" to listOf(107, 1), "max_format" to 107)
            "26.3" -> mapOf("min_format" to 121, "max_format" to 121)
            else -> error("Missing Forge resource pack metadata for Minecraft $minecraft")
        }
        val pack = mapOf("description" to "BaruSikoZako resources") + formats
        metadataFile("forgePackMetadata", "pack.mcmeta", JsonOutput.toJson(mapOf("pack" to pack)))
    } else null
    if (apiOnly) {
        val api = sourceSets.main.get()
        api.java.srcDir(rootProject.file("api/shared/fml/src/${loader}Api/java"))
        api.compileClasspath += serverClasspath
        addCompileDependency(api, project(":api:common"))
        tasks.compileJava {
            options.release.set(runtimeJava)
            options.encoding = "UTF-8"
            options.compilerArgs.add("-proc:none")
        }
        evaluationDependsOn(":api:common")
        val apiMeta = metadataFile("${loader}ApiMetadata", toml, metadata(loader, "barusikozako_api", "ZakoBak API"))
        tasks.jar {
            if (forgePack != null) from(forgePack)
            from(rootProject.file("resource/barusikozako_api/icon.png"))
            archiveBaseName.set("barusikozako_api-$loader-$minecraft")
            from(apiMeta) { into("META-INF") }
            copyComponents(listOf(":api:common"))
            loaderManifest("io.github.zct_studio.barusikozako.api")
        }
        PublishProductJar.register(project, "publishApiProduct", "api", "barusikozako_api", "$loader$minecraft",
            tasks.jar.flatMap { it.archiveFile })
        continue
    }
    (ownComponents + backendComponents).forEach { evaluationDependsOn(it) }
    val main = sourceSets.create(loader) {
        compileClasspath += serverClasspath
    }
    val target = buildTargets.require(loader, minecraft)
    val mixinTask = MinecraftSourcesPlugin.configure(project, main, buildTargets, target, false, true)
    val backend = sourceSets.create("${loader}Backend") {
        java.srcDir(fmlSource.resolve("src/${loader}Backend/java"))
        compileClasspath += serverClasspath + main.output
    }
    for (set in listOf(main, backend)) {
        tasks.named<JavaCompile>(set.compileJavaTaskName) {
            options.release.set(runtimeJava)
            options.encoding = "UTF-8"
            if (forgeSrg && set == main) {
                options.annotationProcessorPath = otherLibraries.filter {
                    it.name == "mixin-0.8.5.jar" || it.name.startsWith("gson-") ||
                        it.name.startsWith("guava-")
                } + legacyAsm
                inputs.file(mappings).withPathSensitivity(PathSensitivity.NONE)
                outputs.file(refmap)
                options.compilerArgs.addAll(listOf("-AdefaultObfuscationEnv=searge", "-AmappingTypes=tsrg",
                    "-AreobfTsrgFile=${mappings.get().asFile}", "-AoutRefMapFile=${refmap.get().asFile}"))
            } else options.compilerArgs.add("-proc:none")
        }
    }
    for (module in ownComponents + ":api:common") addCompileDependency(main, project(module))
    addCompileDependency(main, project(apiPath))
    for (module in backendComponents + ":backends:spi") addCompileDependency(backend, project(module))
    addCompileDependency(main, libs.snakeyaml)
    val backendMeta = metadataFile("${loader}BackendMetadata", toml, metadata(loader, "barusikozako_backendzstd", "ZakoBak Zstd"))
    val backendJarJar = jarJarMetadata("${loader}Backend", backendLibraries)
    val backendJar = tasks.register("${loader}BackendJar",
        if (isolateBackendCommons) ShadowJar::class.java else Jar::class.java) {
        if (forgePack != null) from(forgePack)
        from(rootProject.file("resource/barusikozako_backendzstd/icon.png"))
        archiveBaseName.set("barusikozako_backendzstd-$loader-$minecraft")
        archiveVersion.set(zstdVersion)
        from(backend.output)
        from(backendMeta) { into("META-INF") }
        copyComponents(backendComponents)
        loaderManifest("io.github.zct_studio.barusikozako.zstd")
        attachLibraries(backendLibraries, backendJarJar, null)
        from(backendNotices.flatMap { it.collected }) { into("META-INF/third-party") }
        if (this is ShadowJar) {
            configurations.set(listOf(isolatedCommons))
            for (pkg in listOf("org.apache.commons.compress", "org.apache.commons.codec",
                               "org.apache.commons.io", "org.apache.commons.lang3")) {
                relocate(pkg, pkg.replace("org.apache.commons", "io.github.zct_studio.barusikozako.zstd.internal.commons"))
            }
            exclude("module-info.class", "META-INF/versions/**/module-info.class")
            if (forgeSrg) {
                // 这些 Commons jar 仅具有版本化模块描述符，已在上面删除
                addMultiReleaseAttribute.set(false)
            } else manifest.attributes("Multi-Release" to true)
        }
    }
    val descriptor = metadataFile("${loader}BundledMetadata", toml, metadata(loader, "barusikozako", "BaruSikoZako", true))
    val jarJar = jarJarMetadata("${loader}Bundled", hostLibraries, backendJar)
    val named = tasks.register<Jar>("${loader}BundledJar") {
        if (forgePack != null) from(forgePack)
        from(rootProject.file("resource/barusikozako/icon.png"))
        from(rootProject.file("resource/barusikozako/lang")) { into("assets/barusikozako/lang") }
        from(rootProject.file("resource/barusikozako/banner.png"))
        archiveBaseName.set("barusikozako-$loader-$minecraft")
        if (forgeSrg) {
            destinationDirectory.set(srgTools.map { it.dir("named") })
            from(refmap)
        }
        from(main.output)
        from(shared.resolve("resources")) { exclude("barusikozako.mixins.json") }
        if (mixinTask != null) from(mixinTask)
        from(descriptor) { into("META-INF") }
        copyComponents(ownComponents)
        loaderManifest("barusikozako", mixin = loader == "forge")
        attachLibraries(hostLibraries, jarJar, backendJar)
        from(hostNotices.flatMap { it.collected }) { into("META-INF/third-party") }
    }
    val (source, producer) = if (forgeSrg) {
        val output = layout.buildDirectory.dir("libs").flatMap { dir -> named.flatMap { it.archiveFileName }.map { dir.file(it) } }
        val remap = tasks.register<JavaExec>("${named.name}Srg") {
            javaLauncher.set(launcher)
            classpath = fartClasspath
            mainClass.set("net.minecraftforge.fart.Main")
            val input = named.flatMap { it.archiveFile }
            inputs.files(input, mappings, namedGame, mappingLibraries)
            outputs.file(output)
            doFirst {
                args("--input", input.get().asFile, "--output", output.get().asFile, "--names", mappings.get().asFile,
                    "--lib", namedGame.get().asFile, "--threads", "2")
                args(mappingLibraries.map { "--lib=${it.absolutePath}" })
            }
        }
        output to remap
    } else named.flatMap { it.archiveFile } to named
    val product = PublishProductJar.register(project, "publishBundledProduct", "mod", "barusikozako", "$loader$minecraft", source)
        .also { it.configure { dependsOn(producer) } }
    tasks.register("${loader}Dist") { dependsOn(product) }
}

fun addCompileDependency(sourceSet: SourceSet, dependency: Any) {
    dependencies.add(sourceSet.compileOnlyConfigurationName, dependency)
}

if (!apiOnly) {
    tasks.jar { enabled = false }
    tasks.assemble { dependsOn("${targetLoader}Dist") }
}
