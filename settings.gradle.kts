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

val fabricDevelopment = providers.gradleProperty("fabric26").map(String::toBoolean).getOrElse(false)
val fabricLegacy = providers.gradleProperty("fabricLegacyMinecraft").isPresent

dependencyResolutionManagement {
    repositoriesMode.set(if (fabricDevelopment || fabricLegacy) RepositoriesMode.PREFER_PROJECT else RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "BaruSikoZako"
include("public-api", "api-host", "platform-spi", "backend-spi", "core", "compression-zstd", "backend-zstd")
if (fabricDevelopment) include("platform-fabric", "platform-fabric-backend-zstd")
if (fabricLegacy) {
    require(!fabricDevelopment) { "Use separate builds for Fabric 26 and the remapped targets" }
    include("platform-fabric-pre26", "platform-fabric-backend-zstd")
}
if (providers.gradleProperty("fml26").map(String::toBoolean).getOrElse(false)) include("platform-fml26")
if (providers.gradleProperty("paper26ServerDir").isPresent) include("platform-paper26")
