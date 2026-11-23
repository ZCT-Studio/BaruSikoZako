# 版本表维护指南

`releases.json5` 定义支持范围。每个条目由 **Minecraft 版本、平台和支持标记**组成。一个 Minecraft 版本可以同时有多种标记，每个平台在该版本下只填一种。

`versions.json5` 管理主体、API 和 Zstd 后端的独立版本。覆盖项优先匹配加载器加游戏版本，其次是加载器，再匹配游戏版本，最后用组件默认版本。Quilt 在检查自己的加载器覆盖后，也会检查 Fabric 的加载器覆盖。

## CI 与发布

- 模组运行基线按 Minecraft 版本划分。1.20.1 使用 Java 17，1.21.x 使用 Java 21，26.x 使用 Java 25。CI 检查本体、API 和后端 JAR 内的项目字节码是否超过对应基线。
- 推送或向 `main` 提交拉取请求时，代码与构建输入变更触发 `latest` 构建。纯文档变更不触发产物构建。手动运行工作流会重新构建 `latest`。拉取请求和手动运行均只构建。
- 所有产物构建使用 GitHub 托管的 Ubuntu runner。Forge 和 NeoForge 构建根据 `gradle/compatibility-targets.properties` 下载并校验对应的官方安装器，在该任务的临时目录准备编译输入。
- 推送到主分支时，CI 比较推送前后的有效组件版本。有版本变化且提交标题没有 `[no-release]` 时，符合维护渠道的产物才进入发布计划。正式文件发布到 Modrinth 和 CurseForge。GitHub Actions 保留可下载的构建产物，不创建 Release 或 Git 标签。重新启用仅构建的 JavaAgent 目标时，从 Actions 获取相应产物。
- 默认渠道为 `lts` 允许 RC 和正式版，`nlts` 再允许 Beta，`shift` 再允许 Alpha，`latest` 再允许 Snapshot。`[release-all]` 跳过渠道限制，`[release-temp]` 只选 `nlts` 和 `shift`，`[release-latest]` 只选 `latest`。`[no-build]` 同时跳过构建和发布。
- `[force-release]` 即使版本表未改也生成发布计划。和 `[release-all]` 组合时，发布矩阵中所有可上传至这两个平台的目标都进入发布计划。若平台已有同版本但内容不同的文件，使用当前工作流 ID 区分补发批次。`[no-release]`、`[no-build]` 和仅构建的工作流仍会阻止发布。
- `[no-modrinth]` 跳过 Modrinth 文件发布，`[no-curseforge]` 跳过 CurseForge 文件发布。可以和 `[force-release]` 组合。同时使用两个标记时，仍按常规规则构建。
- CurseForge 先提交主模组文件。若平台返回 1018 并拒绝关联必需的主项目，工作流会告警并暂缓受影响的 API 或 Zstd 文件。主项目可被关联后，可重跑发布作业补发。
- 当前发布工作流运行构建与自动化检查。实际服务端验收需单独进行。
- 所有模组发布目标均标记为可用于独立服务器和单人游戏。Modrinth 使用 `server_only`，CurseForge 同时使用 `Client` 和 `Server`。
- Modrinth 使用主项目 slug `barusikozako` 查找项目 ID。CurseForge 使用 `CURSEFORGE_MOD_ID`，并核对其 slug 同为 `barusikozako`。API 和后端分别使用各自的项目变量。
- 两个平台上，独立 API 和 Zstd 文件都将主模组标记为必需依赖。加载器标签来自发布目标，只有实际共用同一产物的 Fabric 和 Quilt 才共用两个标签。主模组可独立安装，调用公开接口的外部模组需额外安装对应的独立 API JAR。

## 支持标记

| 标记       | 含义                        |
|----------|---------------------------|
| `lts`    | 长期支持，持续更新                 |
| `nlts`   | 非长期支持，后续可能停止更新            |
| `shift`  | 滚动支持，最终停止更新。移除前须对全仓留档     |
| `latest` | 最新版本，更新最频繁，优先安排所有测试       |
| 未标记      | 该版本与平台的组合不再支持，不发布，也不进入 CI |

要取消支持，从 `releases.json5` 中 `versions` 对应的数组移除平台即可。同一版本仍支持其他平台时，保留该版本和其他平台的标记。

## 平台组

| 组名       | 展开后包含                                               |
|----------|-----------------------------------------------------|
| `mod`    | Fabric、Quilt、Forge、NeoForge                         |
| `plugin` | CraftBukkit、Spigot、Paper、Purpur、Folia、SpongeVanilla |
| `agent`  | Vanilla JavaAgent                                    |
| `all`    | `mod`、`plugin` 两组，目前暂停纳入 `agent`            |

单独选择平台时，使用文件中的小写键名，如 `fabric`、`neoforge`、`spongevanilla`。组名可以与平台键名混用。组展开后也要检查是否重复标记。譬如同一个版本已经用 `shift: ["all"]`，就不要再给其中的 `forge` 标 `lts`。

`policy.requireUpstreamBuild` 要求对应的上游构建实际存在，`policy.excludeUpstreamAlpha` 跳过仅有上游 Alpha 构建的组合。填写平台组时仍须应用这两条规则和已有的明确排除项。例如，1.20.1 的 `plugin` 不会把 Folia 1.20.1 纳入支持。SpongeForge 和 SpongeNeo 均不在支持范围内。

## 编辑示例

以当前 1.21.11 为例，在 `versions` 对象内这样填写。

```json5
"1.21.11": {
  nlts: ["forge", "neoforge", "fabric", "quilt"],
  shift: ["agent"], // JavaAgent 按滚动支持维护
},
```

- 新增版本时，在 `versions` 下添加版本键，用引号括住包含小数点的版本号。
- 变更某个平台的标记时，将它移到新的标记数组。若原标记来自平台组，先将该组展开，保留其他平台的原标记。
- JSON5 支持 `//` 单行注释、`/* … */` 块注释和尾逗号。注释适合记录范围调整的理由。

## 滚动淘汰

`policy.maxShiftVersions` 当前为 **5**，按 Minecraft 版本计数。一个版本下有多个 `shift` 平台仍只占一个名额。新增版本会超出上限时，先确定待淘汰项并完成全仓留档，再移除相应的支持标记，最后同步发布和 CI 配置。保留已有验收记录供追溯。
