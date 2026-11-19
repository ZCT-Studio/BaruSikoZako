plugins {
    java
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

val server = rootProject.file(providers.gradleProperty("paper26ServerDir").get())
val paperLibraries = fileTree(server.resolve("libraries")) { include("**/*.jar") }
evaluationDependsOn(":platform-spi")
val platformSpiJar = project(":platform-spi").tasks.named<Jar>("jar")

dependencies {
    compileOnly(paperLibraries)
    implementation(project(":platform-spi"))
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
    from(platformSpiJar.flatMap { it.archiveFile }.map { zipTree(it) }) { include("barusikozako/platform/**") }
    from(rootProject.file("LICENSE")) { into("META-INF") }
}
