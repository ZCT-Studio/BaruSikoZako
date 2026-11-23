# Gradle 模块与 IDEA 开发配置

Gradle JVM 与游戏运行时分别设置。1.20.1 游戏实例使用 Java 17，1.21.x 使用 Java 21，26.x 使用 Java 25。旧版原生适配复用 Minecraft 自带的 JNA，26.x 使用 Java 的 FFM API。

在 IDEA 中打开仓库根目录，使用仓库的 Gradle Wrapper。Gradle 构建需要 Java 17 和 Java 25 toolchain，Gradle JVM 选 Java 25。Gradle 会自动发现标准目录中的 JDK，也可用 `JAVA17_HOME` 和 `JAVA25_HOME` 指定本机路径。

在本地创建 `gradle/local-target.properties`。下面的配置会在 Gradle 工具窗口显示 Minecraft 26.3 的 Fabric、Forge、NeoForge 本体与 API 项目。

```properties
gameVersion=26.3
loaders=fabric,forge,neoforge
```

只开发 Fabric 或 Quilt 时，`loaders=fabric` 或 `loaders=quilt` 即可，两者共享 `fabric` 项目和产物。改完设置后，在 IDEA 中执行 **Reload All Gradle Projects**。未选择版本时，工程只导入公共模块和显式启用的共享入口。命令行可以用 `-PgameVersion` 和 `-Ploaders` 覆盖本地设置。

| 位置 | 用途 |
| --- | --- |
| `app/mc<版本>/<加载器>` | 版本、加载器专属的本体和后端封装构建入口。 |
| `api/mc<版本>/<加载器>` | 版本、加载器专属的独立公共 API 构建入口。 |
| `api/shared` | 跨版本共享的 API 打包入口，例如 Fabric、FML 与 Bukkit API。 |
| `api/common`、`api/host` | 公共 API 契约与服务实现源码。 |
| `engine/mod-runtime` | 从同一套源码生成主模组内部类型，以独立包名随主模组分发。 |
| `engine`、`backends` | Core；后端 SPI、压缩和 Zstd 实现。加载器专用的 Zstd 打包由本体入口管理。 |

项目名将版本点替换为下划线，例如 `26.3` → `mc26_3`。各版本目录的 README 列出对应 Gradle 项目和共享构建脚本。修改游戏适配时，在 `platforms` 目录复用相关源码，只有实际兼容差异需要独立的实现。支持范围由 `releases.json5` 声明，组件版本由 `versions.json5` 提供。详见[版本与发布](../versions.README.md)。

## 常用命令

在 IDEA 的 Gradle 工具窗口刷新项目后，可在 `targets → <游戏版本> → Tasks → build` 中选择加载器，例如 `:targets:26.3:fabric` 或 `:targets:1.20.1:forge`。这些入口通过独立 Gradle 进程构建所选目标。Forge 和 NeoForge 首次编译时会下载官方安装器，校验仓库锁定的 SHA-256，并在 `.gradle/fml-runtime/` 按版本缓存服务端编译依赖。已有匹配版本的官方服务端目录时，可以用 `-Pforge26ServerDir`、`-Pneoforge26ServerDir` 或 `gradle/local-target.properties` 中的同名选项指定路径。

```powershell
.\gradlew.bat '-PgameVersion=26.3' '-Ploaders=fabric' :app:mc26_3:fabric:assemble :api:mc26_3:fabric:assemble
.\gradlew.bat '-PgameVersion=1.21.1' '-Ploaders=forge,neoforge' buildProducts
.\gradlew.bat -PjavaAgent=true :platforms:javaagent:assemble
.\gradlew.bat '-PagentBukkitApiDir=<Paper #196 libraries>' :platforms:bukkit-bridge:assemble :api:shared:bukkit:assemble
.\gradlew.bat :backends:zstd:check :engine:core:recoveryFaults
```

本体和 API 分别导出安装包。Forge 和 NeoForge 编译可选集成入口时使用 API 项目的类型，运行时从服务器安装的 API JAR 加载这些公开类型。根任务 `buildProducts` 导出内置 Zstd 的本体和独立 API。Zstd 保留独立模组 ID 和构建任务。Fabric 的 Zstd 封装位于同一版本下的 `fabric-zstd-package`，构建后的后端 JAR 随本体嵌套打包。

## 安装产物

用根任务 `buildProducts` 构建当前所选目标，或运行单个目标的 `assemble`。可安装 JAR 会移至根目录 `build_product/`，按产品类型分目录。文件名中的版本号以 `v` 开头。导出成功且依赖该 JAR 的任务完成后，Gradle 删除原文件。

| 目录 | 文件名示例 |
| --- | --- |
| `mod` | `barusikozako-v0.5.0+fabric26.3.jar` |
| `api` | `barusikozako_api-v0.5.0+fabric26.3.jar` |
| `agent` | `barusikozako-v0.5.0+agent26.3.jar` |
| `plugin` | `barusikozako-v0.5.0+bukkit26.3.jar`，用于配合 Agent 的可选桥 |
| `preview` | 显式构建的兼容性探针，仍按验收矩阵管理 |

Fabric 与 Quilt 的产物分别使用 `+fabric<游戏版本>` 和 `+quilt<游戏版本>`。Agent 在文件名使用 `-PagentGameVersion`，其次使用命令行的 `-PgameVersion`，缺省 26.3。共享 Agent JAR 的运行兼容性由实际构建和验收决定。Bukkit 桥及 Bukkit API 可独立设置 `-PbukkitGameVersion`，默认沿用 Agent 或命令行所选版本。不同版本和平台的安装包可以并存。

主模组可独立安装。外部模组以对应加载器和 Minecraft 版本的 `barusikozako_api` JAR 编译，声明 `barusikozako_api` 为加载器依赖，并将同一份 JAR 安装到服务器的 `mods` 目录。集成继续使用 `io.github.zct_studio.barusikozako.api` 接口。Zstd 后端 ID 为 `barusikozako_backendzstd`。

```powershell
.\gradlew.bat '-PgameVersion=26.3' '-Ploaders=fabric' buildProducts
.\gradlew.bat -PjavaAgent=true '-PagentGameVersion=26.2' buildProducts
```
