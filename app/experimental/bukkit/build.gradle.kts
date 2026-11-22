plugins {
    java
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }

dependencies {
    // Paper API 1.20.1 - 与 CraftBukkit、Spigot、Paper、Purpur 1.20.1 兼容
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    implementation(project(":platforms:spi"))
    implementation(project(":engine:core"))
    implementation(project(":api:host"))
    implementation(project(":backends:zstd"))
    // 运行时需要 backend-zstd 的传递依赖
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.0")
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

tasks {
    // 将所有外部依赖项提取到目录中的任务
    val extractDependencies by registering(Copy::class) {
        val externalDeps = configurations.runtimeClasspath.get().filter { file ->
            !file.name.startsWith("platform-") && !file.name.startsWith("api-") &&
            !file.name.startsWith("backend-") && !file.name.startsWith("core-") &&
            !file.name.startsWith("compression-")
        }
        from(externalDeps.flatMap { zipTree(it) })
        into(layout.buildDirectory.dir("extracted-deps"))
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }

    jar {
        archiveBaseName.set("barusikozako-bukkit-best-effort")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        // 包括提取的依赖类
        from(extractDependencies.map { it.destinationDir }) {
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        }
        // 包括项目模块
        from(project(":platforms:spi").tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { zipTree(it) }) {
            include("io/github/zct_studio/barusikozako/platform/**")
        }
        from(project(":api:host").tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { zipTree(it) }) {
            include("io/github/zct_studio/barusikozako/apihost/**")
        }
        from(project(":api:common").tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { zipTree(it) }) {
            include("io/github/zct_studio/barusikozako/api/**")
        }
        from(project(":backends:spi").tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { zipTree(it) }) {
            include("io/github/zct_studio/barusikozako/backend/**")
        }
        from(project(":engine:core").tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { zipTree(it) }) {
            include("io/github/zct_studio/barusikozako/core/**", "io/github/zct_studio/barusikozako/internal/gitignore/**",
                "META-INF/barusikozako/gitignore/**")
        }
        from(project(":backends:zstd").tasks.named<Jar>("jar").flatMap { it.archiveFile }.map { zipTree(it) }) {
            include("io/github/zct_studio/barusikozako/zstd/**")
            include("META-INF/services/**")
        }
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
}
