# Minecraft 26.3 · 本体

选择对应 Gradle 项目。构建规则和平台源码共用，具体路径如下。

| Gradle 项目 | 共享构建规则 |
| --- | --- |
| `:app:mc26_3:fabric` | [`platforms/fabric/build.gradle.kts`](../../platforms/fabric/build.gradle.kts) |
| `:app:mc26_3:fabric-zstd-package` | [`platforms/fabric-zstd-package/build.gradle.kts`](../../platforms/fabric-zstd-package/build.gradle.kts) |
| `:app:mc26_3:forge` | [`platforms/fml/build.gradle.kts`](../../platforms/fml/build.gradle.kts) |
| `:app:mc26_3:neoforge` | [`platforms/fml/build.gradle.kts`](../../platforms/fml/build.gradle.kts) |

Fabric 与 Quilt 共用 Fabric 项目，通过 `loaders` 选择发行目标。
