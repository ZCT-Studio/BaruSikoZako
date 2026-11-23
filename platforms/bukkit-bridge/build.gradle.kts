import java.security.MessageDigest
import java.util.HexFormat
import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar

plugins {
    id("barusikozako.java17-library")
}

// 根据审计后的 Paper 1.20.1 #196 发行版中嵌入的 API 进行编译
val apiDigests = mapOf(
    "paper-api-1.20.1-R0.1-SNAPSHOT.jar" to "161ecf24e6ffb325a79eb7eb04904419c2b80f602672e4758785e9389e9038d1",
    "adventure-api-4.14.0.jar" to "1d4cc85babc49917469362fdb4bcd28efbc81c86e52bf473d167ee97c0ec5c76"
)
val bukkitApi = rootProject.fileTree(rootProject.file(providers.gradleProperty("agentBukkitApiDir").get())) {
    include(apiDigests.keys.map { "**/$it" })
}

val verifyBukkitApi = tasks.register("verifyBukkitApi") {
    inputs.files(bukkitApi)
    inputs.property("expectedSha256", apiDigests)
    doLast {
        for ((fileName, expected) in apiDigests) {
            val jar = bukkitApi.files.filter { it.name == fileName }.singleOrNull()
                ?: error("agentBukkitApiDir must contain exactly one $fileName from Paper 1.20.1 #196")
            val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar.readBytes()))
            check(digest == expected) { "$fileName does not match the audited Paper 1.20.1 #196 dependency" }
        }
    }
}

val apiOnly = path.startsWith(":api:")
dependencies {
    compileOnly(bukkitApi)
    compileOnly(project(":api:common"))
    if (apiOnly) compileOnly(project(":platforms:bukkit-bridge"))
    else compileOnly(project(":backends:spi"))
}

tasks.compileJava {
    dependsOn(verifyBukkitApi)
}

tasks.withType<ProcessResources>().configureEach {
    val productVersion = project.version.toString()
    inputs.property("productVersion", productVersion)
    filesMatching("plugin.yml") { expand("productVersion" to productVersion) }
}

tasks.jar {
    archiveBaseName.set(if (apiOnly) "barusikozako_api" else "barusikozako")
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

val bukkitGame = providers.gradleProperty("bukkitGameVersion").orElse(providers.gradleProperty("agentGameVersion"))
    .orElse(providers.gradleProperty("gameVersion")).getOrElse("26.3")
PublishProductJar.register(project, "publishBukkitProduct", if (apiOnly) "api" else "plugin",
    if (apiOnly) "barusikozako_api" else "barusikozako", "bukkit$bukkitGame",
    tasks.jar.flatMap { it.archiveFile })
