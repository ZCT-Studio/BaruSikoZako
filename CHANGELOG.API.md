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

## [1.2.0] - 2026-11-28

### Added / 新增

- Integrations can write log lines through `BackupService.log(level, message)`. The host prefixes every line with the integration's container ID, so an extension cannot forge its origin, and ignores lines silently after its binding is revoked.
<br> 集成可以通过 `BackupService.log(level, message)` 写日志。宿主会为每行自动附加集成的容器 ID 前缀，扩展无法伪造来源；绑定被撤销后调用会静默忽略。
- Integrations can notify players through `BackupService.sendMessage(target, message)`. The target is limited to the presets `ALL`, `EXECUTOR` and `ADMINS`; the API never accepts a player name or UUID. `EXECUTOR` resolves to the operator carried by the most recent task the same binding submitted, and the returned stage reports how many players were queued.
<br> 集成可以通过 `BackupService.sendMessage(target, message)` 通知玩家。目标只允许 `ALL`、`EXECUTOR`、`ADMINS` 三类预设值，API 从不接受玩家名或 UUID。`EXECUTOR` 解析为同一绑定最近一次提交任务时携带的操作人，返回的阶段给出已排队投递的玩家数。

### Changed / 变更

- Integration authorization is now opt-in: `api.yml` holds `authorization.enabled` and defaults to `false`. While disabled, integrations are activated without allowlist checks and receive full rights; when enabled, the previous allowlists and grants apply unchanged.
<br> 集成授权改为可选：`api.yml` 的 `authorization.enabled` 默认为 `false`。关闭时集成不需要名单即可激活并获得全部权限；开启时沿用原有名单与授权范围。

>**_This Changelog is AIGC and verified_**
><br>**_本次更新日志为AI生成内容，并且经过验证_**