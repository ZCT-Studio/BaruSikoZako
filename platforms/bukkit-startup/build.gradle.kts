import io.github.zct_studio.barusikozako.buildlogic.CollectNotices

plugins {
    application
    id("barusikozako.java17-library")
}

dependencies {
    implementation(project(":engine:core"))
    implementation(project(":platforms:spi"))
    implementation(project(":backends:zstd"))
}

application {
    applicationName = "platform-bukkit-startup"
    mainClass.set("io.github.zct_studio.barusikozako.bukkit.startup.BukkitStartup")
}

val collectNotices = tasks.register<CollectNotices>("collectNotices") {
    packagedLibraries.from(configurations.runtimeClasspath)
    manualNotices.set(rootProject.layout.projectDirectory.dir("platforms/fabric/third-party"))
    projectJars.set(setOf("barusikozako_core", "barusikozako_platform_spi").map { "$it-${rootProject.version}.jar" }.toSet() +
        setOf("barusikozako_backend_spi", "backend-zstd", "compression-zstd").map { "$it-${rootProject.extra["zstdVersion"]}.jar" })
    strict.set(providers.gradleProperty("strictReleaseNotices").map(String::toBoolean).orElse(false))
    collected.set(layout.buildDirectory.dir("collectedNotices"))
}

distributions.named("main") {
    contents {
        from(rootProject.file("LICENSE"))
        from(collectNotices.flatMap { it.collected }) { into("third-party") }
    }
}

tasks.jar { archiveBaseName.set("platform-bukkit-startup") }
