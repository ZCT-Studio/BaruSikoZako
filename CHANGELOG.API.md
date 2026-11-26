# API Changelog / API 更新日志

This file documents changes to the public BaruSikoZako API and is used directly for API release notes.
<br> 本文件记录公共 BaruSikoZako API 的改动，内容会直接用于 API 的发布说明。

Changes to the main mod and its built-in backends are documented in [CHANGELOG.md](CHANGELOG.md).
<br> 主模组及其内置后端的改动记录在 [CHANGELOG.md](CHANGELOG.md)。

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
<br> 本更新日志格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
并遵循[语义化版本](https://semver.org/lang/zh-CN/)。

Older API changelogs are archived by major and minor versions in the [Changelog Archives](changelogs/README.md).
<br> 旧版 API 更新日志按主版本号和次版本号归档，详见[更新日志归档](changelogs/README.md)。

Sections appear in this order when applicable: **Added / 新增**, **Changed / 变更**, **Deprecated / 弃用**, **Removed / 移除**, **Fixed / 修复**, **Security / 安全**.
<br> 各分类按以上顺序排列，空分类省略。

## [Unreleased / 未发布]

## [1.1.0] - 2026-11-26

### Added / 新增

- Paged task listing through the API, matching the task list command and including submission time, operator and current state.
<br> API 支持分页查询任务列表，与任务列表指令一致，并返回提交时间、操作人与当前状态。
- Look up a single snapshot by its reference, matching the snapshot detail command.
<br> API 支持按快照标识查询单条信息，与快照详细信息指令一致。
- Query service status and component versions through the API.
<br> API 支持查询服务状态与各组件版本。
- Read and update the configuration of registered backends through the API.
<br> API 支持读取与更新已注册后端的配置。
- ZakoBak API now requires the matching or newer main mod version (`>=` on Fabric, `[version,)` on Forge and NeoForge).
<br> ZakoBak API 现在要求主模组为相同或更高版本（Fabric 使用 `>=`，Forge 与 NeoForge 使用 `[版本,)`）。

### Changed / 变更

- The minimum main MOD version supported is 1.1.0.
<br> 支持最低主模组版本为1.1.0
- Lifecycle hooks now cover verification, deletion and online restore, in addition to backups and offline restore.
<br> 生命周期钩子除备份与离线恢复外，现在也覆盖校验、删除和在线回滚。

>**_This Changelog is M(achine) T(ranslation) and verified_**
><br>**_本次更新日志为机器翻译内容内容，并且经过验证_**