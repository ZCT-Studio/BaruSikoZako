import io.github.zct_studio.barusikozako.buildlogic.PublishProductJar

plugins {
    java
    id("barusikozako.java17-library") apply false
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

val serverPath = providers.gradleProperty("paper26ServerDir").orNull
    ?: (extra["localTarget"] as? java.util.Properties)?.getProperty("paper26ServerDir")
    ?: error("Set -Ppaper26ServerDir or paper26ServerDir in gradle/local-target.properties")
val server = rootProject.file(serverPath)
val paperLibraries = fileTree(server.resolve("libraries")) { include("**/*.jar") }
evaluationDependsOn(":platforms:spi")
val platformSpiJar = project(":platforms:spi").tasks.named<Jar>("jar")

dependencies {
    compileOnly(paperLibraries)
    implementation(project(":platforms:spi"))
}

tasks.compileJava {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.processResources {
    val versionText = project.version.toString()
    inputs.property("productVersion", versionText)
    filesMatching("plugin.yml") { expand("productVersion" to versionText) }
}

tasks.jar {
    archiveBaseName.set("barusikozako-paper26-compatibility-preview")
    from(platformSpiJar.flatMap { it.archiveFile }.map { zipTree(it) }) { include("io/github/zct_studio/barusikozako/platform/**") }
    from(rootProject.file("LICENSE")) { into("META-INF") }
}

PublishProductJar.register(project, "publishPreviewProduct", "preview", "BaruSikoZakoCompatibilityPreview", "paper26.3", false,
    tasks.jar.flatMap { it.archiveFile })
