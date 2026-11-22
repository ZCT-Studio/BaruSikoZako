# Minecraft 26.3 · 公共 API

选择对应 Gradle 项目。构建规则和平台源码共用，具体路径如下。

| Gradle 项目 | 共享构建规则 |
| --- | --- |
| `:api:mc26_3:fabric` | [`api/shared/fabric/build.gradle.kts`](../../api/shared/fabric/build.gradle.kts) |
| `:api:mc26_3:forge` | [`platforms/fml/build.gradle.kts`](../../platforms/fml/build.gradle.kts) |
| `:api:mc26_3:neoforge` | [`platforms/fml/build.gradle.kts`](../../platforms/fml/build.gradle.kts) |

Fabric 与 Quilt 共用 Fabric 项目，通过 `loaders` 选择发行目标。
