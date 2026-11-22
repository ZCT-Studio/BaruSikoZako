# 版本表维护指南

`releases.json5` 定义杂鱼备份的支持范围。每个条目都由 **Minecraft 版本、平台和支持标记**组成。一个 Minecraft 版本可以同时有多种标记，每个平台在该版本下只填一种。

版本表用于确定发布和 CI 的候选范围。平台是否已有可用产物，以及备份、校验和恢复是否通过实服验收，见[验收矩阵](benchmarks/server-acceptance/matrix.md)。CI 直接读取此表。组件版本、渠道门槛和触发规则见 [版本与发布](versions.README.md)。

## 支持标记

| 标记       | 含义                        |
|----------|---------------------------|
| `lts`    | 长期支持，持续更新                 |
| `nlts`   | 非长期支持，后续可能停止更新            |
| `shift`  | 滚动支持，最终停止更新。移除前须对全仓留档     |
| `latest` | 最新版本，更新最频繁，优先安排所有测试       |
| 未标记      | 该版本与平台的组合不再支持，不发布，也不进入 CI |

要取消支持，从 `versions` 对应的数组移除平台即可。同一版本仍支持其他平台时，保留该版本和其他平台的标记。

## 平台组

| 组名       | 展开后包含                                               |
|----------|-----------------------------------------------------|
| `mod`    | Fabric、Quilt、Forge、NeoForge                         |
| `classic_bukkit` | CraftBukkit、Spigot、Paper、Purpur |
| `bukkit` | `classic_bukkit` 和 Folia |
| `plugin` | `bukkit` 和 SpongeVanilla |
| `agent` | Vanilla |
| `all` | `mod`、`plugin` 和 `agent` |

## Agent 与插件交付

合并同一个 Minecraft 版本的各个支持标记，再展开平台组，按展开后的组合判断交付方式。

| 标记 | 含义 |
| --- | --- |
| `agent`，未选 Bukkit 平台 | Agent 面向原版，并尽力兼容 Bukkit 系插件服 |
| `agent` 和 `bukkit` | 原版用 Agent，适用的 Bukkit 平台用 Agent 加桥，作为正式兼容目标进入发布验收 |
| 仅插件平台 | 该平台使用独立插件交付，例如未来的 SpongeVanilla |

`all` 展开后同时包含 `agent` 和 `bukkit`。若同版本只列出部分 Bukkit 平台，Agent 加桥的目标对应这些平台。实际通过情况以验收矩阵为准。当前 SpongeVanilla 排除在 Agent 路径之外，按独立插件路线处理。

待验收的插件条目使用 `/* … */` 保留在对应版本下。发布候选只包含未被注释的条目。若原先使用 `all`，暂停插件发布时应先展开为 `mod`、`agent` 和待验收的 `plugin`，再注释 `plugin`。验收矩阵继续保留实际测试状态。

当前 Agent 仅维护 **1.21.11、26.1.2、26.2、26.3**。26.3 的 `latest` 标记使其优先安排在线验收。1.20.1、1.21.1 和 1.21.10 的 Agent 实测结果归入历史记录。

单独选择平台时，使用文件中的小写键名，如 `fabric`、`neoforge`、`spongevanilla`。组名可以与平台键名混用。组展开后也要检查是否重复标记。譬如同一个版本已经用 `shift: ["all"]`，就不要再给其中的 `forge` 标 `lts`。

`policy.requireUpstreamBuild` 要求对应的上游构建实际存在，`policy.excludeUpstreamAlpha` 跳过仅有上游 Alpha 构建的组合。填写平台组时仍须应用这两条规则和已有的明确排除项。例如，1.20.1 的 `plugin` 不会把 Folia 1.20.1 纳入支持。SpongeForge 和 SpongeNeo 均不在支持范围内。

## 编辑示例

以当前 1.21.11 为例，在 `versions` 对象内这样填写。

```json5
"1.21.11": {
  nlts: ["forge", "neoforge", "fabric", "quilt"],
  shift: ["agent"], // Agent 路线按滚动支持维护
},
```

- 新增版本时，在 `versions` 下添加版本键，用引号括住包含小数点的版本号。
- 变更某个平台的标记时，将它移到新的标记数组。若原标记来自平台组，先将该组展开，保留其他平台的原标记。
- JSON5 支持 `//` 单行注释、`/* … */` 块注释和尾逗号。注释适合记录范围调整的理由。

## 滚动淘汰

`policy.maxShiftVersions` 当前为 **5**，按 Minecraft 版本计数。一个版本下有多个 `shift` 平台仍只占一个名额。新增版本会超出上限时，先确定待淘汰项并完成全仓留档，再移除相应的支持标记，最后同步发布和 CI 配置。保留已有验收记录供追溯。

