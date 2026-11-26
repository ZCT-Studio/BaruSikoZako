import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import io.github.zct_studio.barusikozako.buildlogic.ActivateTarget
import io.github.zct_studio.barusikozako.buildlogic.JarMerge

plugins {
    base
    id("barusikozako.java17-library") apply false
    id("io.github.pacifistmc.forgix") version "2.0.0" apply false
}

tasks.register("buildProducts") {
    group = "build"
    description = "Builds every installable product selected by the current target profile."
}

// 切换 IDEA 活跃构建目标：重写条件分支并更新 gradle/local-target.properties
tasks.register<ActivateTarget>("activateTarget") {
    group = "ide"
    description = "Activates a build target for IDE work: ./gradlew activateTarget -PgameVersion=26.3 -Ploaders=fabric"
    game.set(providers.gradleProperty("gameVersion").orElse("26.3"))
    loader.set(providers.gradleProperty("loaders").orElse("fabric").map { it.substringBefore(',').trim() })
    rootDirectory.set(layout.projectDirectory)
}

tasks.register("checkTargetSources") {
    group = "verification"
    description = "Validates every selected build target against ;lists/targets.json5"
}
subprojects {
    tasks.matching { it.name == "checkTargetSources" }.configureEach {
        rootProject.tasks.named("checkTargetSources").configure { dependsOn(this@configureEach) }
    }
}

val compatibilityTargets = java.util.Properties().apply {
    rootProject.file("gradle/compatibility-targets.properties").inputStream().use { load(it) }
}
val selectableGames = (
    compatibilityTargets.stringPropertyNames().filter {
        it.startsWith("fabric.legacy.api.") || it.startsWith("fabric.runtime.api.")
    }.map { it.substringAfter("api.") } +
        listOfNotNull(compatibilityTargets.getProperty("fabric.development.minecraft"))
    ).distinct().sorted()

val mergedGames: Set<String> = runCatching {
    @Suppress("UNCHECKED_CAST")
    val table = Json5Reader(rootProject.file(";lists/targets.json5").readText()).read() as Map<String, Any?>
    (table["merge"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()
}.getOrElse { emptySet() }

for (game in selectableGames) {
    val loaders = listOf("fabric", "quilt") + listOf("forge", "neoforge").filter {
        compatibilityTargets.containsKey("fml.runtime.$it.$game")
    }
    val targetsProject = project(":targets:$game")
    for (loader in loaders) {
        targetsProject.tasks.register<Exec>(loader) {
            group = "build"
            description = "Builds the $loader products for Minecraft $game"
            workingDir = rootDir
            val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
            commandLine(wrapper, "-PgameVersion=$game", "-Ploaders=$loader", "buildProducts")
        }
        // Quilt 复用 Fabric 的清单目标与条件分支，不单独提供切换任务
        if (loader == "quilt") continue
        targetsProject.tasks.register<ActivateTarget>("activate${loader.replaceFirstChar { it.uppercase() }}") {
            group = "ide"
            description = "Switches the IDE target to $loader on Minecraft $game; reload Gradle projects afterwards"
            getGame().set(game)
            getLoader().set(loader)
            getRootDirectory().set(rootProject.layout.projectDirectory)
        }
    }
    // 合并目标在任务树中始终可见，无需先切换活跃目标
    val mergeLoaders = loaders.filter { it in setOf("fabric", "forge", "neoforge") }
    if (game in mergedGames && mergeLoaders.size >= 2) {
        targetsProject.tasks.register<Exec>("universal") {
            group = "build"
            description = "Builds the universal (fabric+forge+neoforge) product for Minecraft $game"
            workingDir = rootDir
            val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "./gradlew"
            commandLine(wrapper, "-PgameVersion=$game", "-Ploaders=${mergeLoaders.joinToString(",")}",
                "-PmergeJars=true", "mergeJars", "mergeApiJars")
        }
    }
}

// 每个项目的版本和共享设置
// 这在评估子项目构建脚本之前的配置阶段运行
subprojects {
    // 读取本地目标.properties
    val localTarget = java.util.Properties().apply {
        val file = rootProject.projectDir.resolve("gradle/local-target.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
    
    fun local(name: String): String? = localTarget.getProperty(name)
    
    fun property(name: String): String? = project.gradle.startParameter.getProjectProperties()[name] as? String
    
    // 计算选定的游戏和加载程序（与settings.gradle.kts相同的逻辑）
    val selectedGames: List<String> = (property("gameVersion") ?: local("gameVersion"))
        .orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    
    val selectedLoaders: List<String> = (property("loaders") ?: local("loaders"))
        .orEmpty()
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
    
    val distributionLoader: String = property("distributionLoader") ?: local("distributionLoader")
        ?: if (selectedLoaders == listOf("quilt")) "quilt" else "fabric"
    
    
    // 读取 ;lists/version.json5 进行版本解析
    val versionTable: Map<String, Any?> = try {
        @Suppress("UNCHECKED_CAST")
        Json5Reader(rootProject.projectDir.resolve(";lists/version.json5").readText()).read() as Map<String, Any?>
    } catch (e: Exception) {
        emptyMap()
    }
    
    fun Any?.asTable(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
    
    fun versionRule(path: String): Map<String, Any?> {
        var node: Map<String, Any?> = versionTable
        for (key in path.split('.')) node = node[key].asTable()
        return node
    }
    
    fun resolveVersion(component: String, loader: String?, game: String?): String {
        val rule = versionRule(component)
        val overrides = rule["overrides"].asTable()
        val keys = buildList {
            if (loader != null) {
                if (game != null) add("$loader$game")
                add(loader)
            }
            if (loader == "quilt") {
                if (game != null) add("fabric$game")
                add("fabric")
            }
            if (game != null) add(game)
        }
        val chosen = keys.firstNotNullOfOrNull { overrides[it] as? String }
        return chosen ?: (rule["version"] as? String) ?: property("productVersion") ?: "0.1.0-dev"
    }
    
    fun loaderFor(path: String): String? = when {
        path.startsWith(":platforms:javaagent") -> "agent"
        path.endsWith(":fabric") || path.contains(":fabric-") -> distributionLoader
        path.endsWith(":forge") -> "forge"
        path.endsWith(":neoforge") -> "neoforge"
        else -> null
    }
    
    fun gameFor(path: String): String? = when {
        path.startsWith(":platforms:javaagent") -> property("agentGameVersion") ?: local("agentGameVersion") ?: selectedGames.firstOrNull() ?: "26.3"
        else -> path.split(':').firstOrNull { it.startsWith("mc") && it.length > 2 }
            ?.removePrefix("mc")?.replace('_', '.')
    }
    
    val projectPath = project.path
    val loader = loaderFor(projectPath)
    val game = gameFor(projectPath)
    val modVersion = resolveVersion("mod", loader, game)
    val apiVersion = resolveVersion("api", loader, game)
    val zstdVersion = resolveVersion("backend.zstd", loader, game)
    // 最低 API 版本：mod.api_version 显式指定；留空时与当前 API 版本保持一致
    val minimumApiVersion = (versionRule("mod")["api_version"] as? String)?.takeIf { it.isNotBlank() } ?: apiVersion
    
    project.version = when {
        projectPath.startsWith(":api:") -> apiVersion
        projectPath.startsWith(":backends:") || projectPath.endsWith(":fabric-zstd-package") -> zstdVersion
        else -> modVersion
    }
    
    val extra = project.extensions.extraProperties
    extra.set("modVersion", modVersion)
    extra.set("apiVersion", apiVersion)
    extra.set("minimumApiVersion", minimumApiVersion)
    extra.set("zstdVersion", zstdVersion)
    extra.set("fabricPackageLoader", distributionLoader)
    if (game != null) {
        compatibilityTargets.getProperty("fabric.runtime.loader.$game")?.let {
            extra.set("fabricLoaderRequirement", ">=$it")
        }
    }
    extra.set("localTarget", localTarget)
}

// 根项目也需要设置 productMetadata，供所有子项目通过 rootProject.extra 访问
@Suppress("UNCHECKED_CAST")
val rootProductMetadata = Json5Reader(rootProject.file("metadatas.json5").readText()).read() as Map<String, Any?>
rootProject.extra.set("productMetadata", rootProductMetadata)

/** 最小 JSON5 阅读器：注释、不带引号的键、尾随逗号和单引号 */
class Json5Reader(text: String) {
    private val source = stripComments(text)
    private var at = 0

    fun read(): Any? {
        skip()
        return value()
    }

    private fun stripComments(input: String): String {
        val out = StringBuilder(input.length)
        var index = 0
        var quote: Char? = null
        while (index < input.length) {
            val c = input[index]
            if (quote != null) {
                out.append(c)
                if (c == '\\' && index + 1 < input.length) out.append(input[++index])
                else if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
                out.append(c)
            } else if (c == '/' && index + 1 < input.length && input[index + 1] == '/') {
                while (index < input.length && input[index] != '\n') index++
                out.append('\n')
            } else if (c == '/' && index + 1 < input.length && input[index + 1] == '*') {
                index += 2
                while (index + 1 < input.length && !(input[index] == '*' && input[index + 1] == '/')) index++
                index++
                out.append(' ')
            } else out.append(c)
            index++
        }
        return out.toString()
    }

    private fun skip() {
        while (at < source.length && source[at].isWhitespace()) at++
    }

    private fun value(): Any? {
        skip()
        check(at < source.length) { "Unexpected end of JSON5 document" }
        return when (source[at]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"', '\'' -> stringValue()
            else -> literalValue()
        }
    }

    private fun objectValue(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        at++
        while (true) {
            skip()
            if (at < source.length && source[at] == '}') { at++; return result }
            val key = if (source[at] == '"' || source[at] == '\'') stringValue() else identifierValue()
            skip()
            check(at < source.length && source[at] == ':') { "Expected ':' at offset $at" }
            at++
            result[key] = value()
            skip()
            if (at < source.length && source[at] == ',') { at++; continue }
            check(at < source.length && source[at] == '}') { "Expected '}' at offset $at" }
            at++
            return result
        }
    }

    private fun arrayValue(): List<Any?> {
        val result = ArrayList<Any?>()
        at++
        while (true) {
            skip()
            if (at < source.length && source[at] == ']') { at++; return result }
            result.add(value())
            skip()
            if (at < source.length && source[at] == ',') { at++; continue }
            check(at < source.length && source[at] == ']') { "Expected ']' at offset $at" }
            at++
            return result
        }
    }

    private fun stringValue(): String {
        val quote = source[at++]
        val out = StringBuilder()
        while (at < source.length) {
            val c = source[at++]
            when {
                c == quote -> return out.toString()
                c == '\\' -> {
                    val escaped = source[at++]
                    out.append(when (escaped) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> '\u000C'
                        'u' -> {
                            val hex = source.substring(at, at + 4)
                            at += 4
                            hex.toInt(16).toChar()
                        }
                        else -> escaped
                    })
                }
                else -> out.append(c)
            }
        }
        error("Unterminated string in JSON5 document")
    }

    private fun identifierValue(): String {
        val start = at
        while (at < source.length && (source[at].isLetterOrDigit() || source[at] in "_-${'$'}.")) at++
        return source.substring(start, at)
    }

    private fun literalValue(): Any? {
        val start = at
        while (at < source.length && !source[at].isWhitespace() && source[at] !in ",}]") at++
        val token = source.substring(start, at)
        return when {
            token == "true" -> true
            token == "false" -> false
            token == "null" -> null
            token.startsWith("0x") || token.startsWith("0X") -> token.substring(2).toLong(16)
            token.any { it == '.' || it == 'e' || it == 'E' } -> token.toDouble()
            else -> token.toLongOrNull() ?: token
        }
    }
}

// --- 合并 Loader 产物（仅非 LTS 目标） -----------------------------------------

fun rootProperty(name: String): String? = providers.gradleProperty(name).orNull

fun rootLocal(name: String): String? = java.util.Properties().let { properties ->
    val file = rootProject.file("gradle/local-target.properties")
    if (file.isFile) file.inputStream().use { properties.load(it) }
    properties.getProperty(name)
}

val mergeGame = (rootProperty("gameVersion") ?: rootLocal("gameVersion"))
    .orEmpty().split(',').map { it.trim() }.firstOrNull { it in mergedGames }
val mergeLoaders = (rootProperty("loaders") ?: rootLocal("loaders"))
    .orEmpty().split(',').map { it.trim().lowercase() }
    .filter { it in setOf("fabric", "forge", "neoforge") }.distinct()

// 合并构建是可选行为：只有显式传入 -PmergeJars=true 才会注册合并任务
if (mergeGame != null && mergeLoaders.size >= 2 && hasProperty("mergeJars")) {
    extra.set("forgixGame", mergeGame)
    extra.set("forgixLoaders", mergeLoaders)
    apply(from = "gradle/merge-jars.gradle")

    // API 产物类字节在各 Loader 间一致，只有元数据不同，用去重合并即可。
    val root = project
    val node = "mc" + mergeGame.replace('.', '_')
    gradle.projectsEvaluated {
        val apiJars = mergeLoaders.map { loader -> root.project(":api:$node:$loader").tasks.named<Jar>("jar") }
        val apiVersion = root.project(":api:$node:${mergeLoaders.first()}").version
        root.tasks.register<JarMerge>("mergeApiJars") {
            group = "build"
            description = "Merges the $mergeGame API jars of ${mergeLoaders.joinToString()} into one jar"
            dependsOn(apiJars)
            inputJars.from(apiJars.map { jar -> jar.flatMap { it.archiveFile } })
            outputJar.set(root.layout.projectDirectory.file(
                "build_product/api/barusikozako_api-v$apiVersion+universal$mergeGame.jar"))
        }
    }
}
