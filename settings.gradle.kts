import java.util.Properties
import org.gradle.api.Project

pluginManagement {
    includeBuild("build-logic")
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
    resolutionStrategy.eachPlugin {
        if (requested.id.id in setOf("net.fabricmc.fabric-loom", "net.fabricmc.fabric-loom-remap")) {
            useModule("net.fabricmc:fabric-loom:${requested.version}")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://maven.minecraftforge.net/") {
            content { includeModule("net.minecraftforge", "srgutils") }
        }
    }
}

rootProject.name = "BaruSikoZako"

// --- local development profile -------------------------------------------------

val localTarget = Properties().apply {
    val file = rootDir.resolve("gradle/local-target.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun local(name: String): String? = localTarget.getProperty(name)

fun property(name: String): String? = providers.gradleProperty(name).orNull

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

val compatibility = Properties().apply {
    rootDir.resolve("gradle/compatibility-targets.properties").inputStream().use { load(it) }
}

val buildTargetsDir = rootDir.resolve(".gradle/build-targets").apply { mkdirs() }
include(":targets")
project(":targets").projectDir = buildTargetsDir
val targetGames = (compatibility.stringPropertyNames().filter {
    it.startsWith("fabric.legacy.api.") || it.startsWith("fabric.runtime.api.")
}.map { it.substringAfter("api.") } +
    listOfNotNull(compatibility.getProperty("fabric.development.minecraft"))).distinct()
for (game in targetGames) {
    val directory = buildTargetsDir.resolve(game).apply { mkdirs() }
    include(":targets:$game")
    project(":targets:$game").projectDir = directory
}

fun fabricKind(game: String): String? = when {
    compatibility.getProperty("fabric.development.minecraft") == game -> "modern"
    compatibility.containsKey("fabric.runtime.api.$game") -> "modern"
    compatibility.containsKey("fabric.legacy.api.$game") -> "legacy"
    else -> null
}

fun fmlSupported(loader: String, game: String): Boolean =
    compatibility.containsKey("fml.runtime.$loader.$game")

fun directoryOf(game: String): String = "mc" + game.replace('.', '_')

// --- component versions --------------------------------------------------------

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

val versionTable: Map<String, Any?> = runCatching {
    @Suppress("UNCHECKED_CAST")
    Json5Reader(rootDir.resolve(";lists/version.json5").readText()).read() as Map<String, Any?>
}.getOrElse { emptyMap() }

@Suppress("UNCHECKED_CAST")
fun Any?.asTable(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

fun versionRule(path: String): Map<String, Any?> {
    var node: Map<String, Any?> = versionTable
    for (key in path.split('.')) node = node[key].asTable()
    return node
}

fun resolveVersion(component: String, loader: String?, game: String?): String {
    val rule = versionRule(component)
    val overrides = rule["overrides"].asTable()
    val keys = listOfNotNull(loader?.let { game?.let { g -> "$it$g" } }, loader, game)
    val chosen = keys.firstNotNullOfOrNull { overrides[it] as? String }
    return chosen ?: (rule["version"] as? String) ?: property("productVersion") ?: "0.1.0-dev"
}

// --- optional shared entry points ----------------------------------------------


include(":api:common", ":api:host")
include(":backends:spi", ":backends:compression-zstd", ":backends:zstd")
include(":engine:core")
include(":engine:gitignore")
include(":engine:mod-runtime")
include(":platforms:spi")

if ((property("javaAgent") ?: local("javaAgent"))?.toBoolean() == true) {
    include(":platforms:javaagent")
}

fun includeVersioned(game: String, loader: String, script: String) {
    val node = directoryOf(game)
    val appPath = ":app:$node:$loader"
    include(appPath)
    project(appPath).apply {
        projectDir = file("app/$node/$loader")
        buildFileName = "../../../$script"
    }
    val apiPath = ":api:$node:$loader"
    include(apiPath)
    project(apiPath).apply {
        projectDir = file("api/$node/$loader")
        buildFileName = "../../../$script"
    }
}

for (game in selectedGames) {
    val node = directoryOf(game)
    val fabric = fabricKind(game)
    val wantsFabric = selectedLoaders.isEmpty() || selectedLoaders.any { it == "fabric" || it == "quilt" }
    if (fabric != null && wantsFabric) {
        includeVersioned(game, "fabric",
            if (fabric == "legacy") "platforms/fabric-legacy/build.gradle.kts" else "platforms/fabric/build.gradle.kts")
        val apiPath = ":api:$node:fabric"
        project(apiPath).buildFileName = "../../../api/shared/fabric/build.gradle.kts"
        val packagePath = ":app:$node:fabric-zstd-package"
        include(packagePath)
        project(packagePath).apply {
            projectDir = file("app/$node/fabric-zstd-package")
            buildFileName = "../../../platforms/fabric-zstd-package/build.gradle.kts"
        }
    }
    for (loader in listOf("forge", "neoforge")) {
        if (!fmlSupported(loader, game)) continue
        if (selectedLoaders.isNotEmpty() && loader !in selectedLoaders) continue
        includeVersioned(game, loader, "platforms/fml/build.gradle.kts")
    }
}


// --- per-project versions and shared settings ----------------------------------

fun loaderFor(path: String): String? = when {
    path.endsWith(":fabric") || path.contains(":fabric-") -> distributionLoader
    path.endsWith(":forge") -> "forge"
    path.endsWith(":neoforge") -> "neoforge"
    else -> null
}


