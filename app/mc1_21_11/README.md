# Minecraft 1.21.11 · 本体

选择对应 Gradle 项目。构建规则和平台源码共用，具体路径如下。

| Gradle 项目 | 共享构建规则 |
| --- | --- |
| `:app:mc1_21_11:fabric` | [`platforms/fabric-legacy/build.gradle.kts`](../../platforms/fabric-legacy/build.gradle.kts) |
| `:app:mc1_21_11:fabric-zstd-package` | [`platforms/fabric-zstd-package/build.gradle.kts`](../../platforms/fabric-zstd-package/build.gradle.kts) |
| `:app:mc1_21_11:forge` | [`platforms/fml/build.gradle.kts`](../../platforms/fml/build.gradle.kts) |
| `:app:mc1_21_11:neoforge` | [`platforms/fml/build.gradle.kts`](../../platforms/fml/build.gradle.kts) |

Fabric 与 Quilt 共用 Fabric 项目，通过 `loaders` 选择发行目标。
