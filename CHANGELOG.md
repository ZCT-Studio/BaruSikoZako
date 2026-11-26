# Changelog / 更新日志

This file documents **player-facing** changes and is used directly for GitHub Releases and MOD platform release notes.
<br> 本文件记录**面向玩家**的改动，内容会直接用于 GitHub Release 与模组平台的更新说明。

Internal-only changes (such as refactoring, build configuration, and documentation maintenance) are not listed.
<br> 纯内部的调整（重构、构建配置、文档整理等）不在此列出。

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
<br> 本更新日志格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
并遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

Older changelogs are archived by major and minor versions in the [Changelog Archives](changelogs/README.md).
<br> 旧版本更新日志按主版本号和次版本号归档，详见[更新日志归档](changelogs/README.md)。

Changes to the public API are tracked separately in [CHANGELOG.API.md](CHANGELOG.API.md).
<br> 公共 API 的改动单独记录在 [CHANGELOG.API.md](CHANGELOG.API.md)。

Sections appear in this order when applicable: **Added / 新增**, **Changed / 变更**, **Deprecated / 弃用**, **Removed / 移除**, **Fixed / 修复**, **Security / 安全**. Performance improvements are listed under **Changed / 变更**.
<br> 各分类按以上顺序排列，空分类省略；性能优化归入 **Changed / 变更**。

## [Unreleased / 未发布]

## [1.1.0] - 2026-11-26

### Added / 新增

- New separate `progress.yml` displays backup progress in a BossBar, with per-player language, configurable colors, text sections and a result display after the task ends.
<br> 新增独立的 `progress.yml`，可用 BossBar 显示备份进度，支持按玩家语言渲染、自定义颜色与文本项，并在任务结束后展示结果。
- Progress display distinguishes backups started by the viewing player from scheduled, console and third-party backups, with configurable audience for the latter.
<br> 进度显示会区分查看者本人发起的备份与定时、控制台及第三方发起的备份，后者受众可配置。
- Configuration files include English and Chinese guidance and accept human-readable time and size units.
<br> 配置文件提供中英文说明，并支持易读的时间和容量单位。
- New `capture.consistency` option (`strict` by default, or `relaxed`). Strict refuses a backup when files change during capture; relaxed accepts fully copied files that may come from slightly different moments, never truncated or damaged copies.
<br> 新增 `capture.consistency` 配置（默认 `strict`，可选 `relaxed`）。严格模式发现文件在捕获期间变化就拒绝备份；宽松模式接受完整复制但来自不同时间点的文件，绝不接受截断或损坏的副本。
- Snapshot metadata records the consistency level used at capture time, `/list` shows it, and restoring a relaxed snapshot prints a warning first.
<br> 快照元数据永久记录捕获时的一致性等级，`/list` 会显示该等级，恢复宽松模式快照前会先弹出警告。
- Snapshot and task lists show three lines per item (time, UUID and operator; type or progress; actions), with four items per page and previous/next navigation.
<br> 快照与任务列表每项显示三行（时间、UUID 与操作人；类型或进度；操作），每页 4 项并支持上一页/下一页。
- New `/detail` command and a details button on every snapshot, showing capture time, UUID, type, consistency, size, elapsed time, operator and game, mod and backend versions.
<br> 新增 `/detail` 指令和每个快照的“详细信息”按钮，显示备份时间、UUID、类型、一致性、体积、耗时、操作人以及游戏、模组和后端版本。
- Tasks record their operator and can be listed, inspected and canceled by submission time or task ID.
<br> 任务会记录操作人，并支持按提交时间或任务 ID 列出、查看和取消。
- New `ignore.gitignore` and `ignore.pteroignore` options use world-level `.gitignore` files and the game root `.pteroignore` as exclusion rules.
<br> 新增 `ignore.gitignore` 与 `ignore.pteroignore` 配置，可将世界内的 `.gitignore` 和游戏根目录的 `.pteroignore` 作为排除规则。
- Backup and verification completion messages can use separate `template.backup` and `template.verify` layouts, and `progress_simple` shows both sizes in a shared unit.
<br> 备份与校验的完成提示可分别使用 `template.backup` 与 `template.verify`，`progress_simple` 使用统一单位显示两个体积。
- Copyable values are yellow and clickable actions are cyan in brackets; hovering a copyable value shows its original text.
<br> 可复制内容显示为黄色，可点击操作显示为带方括号的青色；悬停可复制内容会显示原始文本。
- Zstd snapshots record archive size, logical size, elapsed time and layout in their metadata, and `/list` and `/detail` display them.
<br> Zstd 快照在元数据中记录压缩体积、逻辑体积、耗时与布局，`/list` 与 `/detail` 会显示这些信息。
- The mod declares a minimum ZakoBak API version (`mod.api_version`); Fabric and NeoForge refuse older API versions at load time, and Forge reports a bilingual `ModLoadingException`.
<br> 主模组声明最低 ZakoBak API 版本（`mod.api_version`）；Fabric 与 NeoForge 会在加载时拒绝更旧的 API，Forge 则输出双语 `ModLoadingException`。

### Changed / 变更

- Online backups retry each changed file more times, and retry the whole capture once when it still fails, greatly improving success on busy servers.
<br> 在线备份会以更多次数重试发生变化的文件，整次捕获仍失败时还会重新捕获一次，显著提升繁忙服务器上的成功率。
- Capture failures now log the concrete reason to the server log instead of only reporting that the source was unavailable.
<br> 捕获失败现在会把具体原因写入服务器日志，不再只报告来源不可用。
- Ignore-rule notices list only the rules that actually matched and dim the source file.
<br> 忽略规则提示只列出实际生效的规则，并淡化显示来源文件。
- Command failures report the concrete rejection or failure reason instead of a generic error.
<br> 指令失败时会报告具体的拒绝或失败原因，不再只显示通用错误。
- The first-run notice is sent only to the first administrator who joins, once per mod version.
<br> 首次启动提示只发送给第一个上线的管理员，每个模组版本仅提示一次。
- The built-in Zstd backend is now listed as `ZakoBak Zstd (Bundled)`, and its version no longer carries a suffix.
<br> 内置 Zstd 后端现在显示为 `ZakoBak Zstd (Bundled)`，版本号不再带后缀。
- Now `server.yml` file is structured instead of flat.
<br> 现在 `server.yml` 结构化而非扁平化

>**_This Changelog is AIGC and verified_**
><br>**_本次更新日志为AI生成内容，并且经过验证_**