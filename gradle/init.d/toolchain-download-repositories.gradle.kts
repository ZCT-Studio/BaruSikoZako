import org.gradle.jvm.toolchain.JavaToolchainSpec

// 配置全局工具链下载仓库，解决 IDE 同步找不到 Java 17 的问题
// 此 init 脚本在所有构建脚本之前运行
gradle.javaToolchain {
    downloadRepositories.setFrom(
        listOf(
            "https://github.com/gradle/gradle-jdks/releases/download/v1/jdks.xml",
            "https://api.adoptium.net/v3/assets/feature_releases/"
        )
    )
}