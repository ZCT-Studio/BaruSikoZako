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

Sections appear in this order when applicable: **Added / 新增**, **Changed / 变更**, **Deprecated / 弃用**, **Removed / 移除**, **Fixed / 修复**, **Security / 安全**. Performance improvements are listed under **Changed / 变更**.
<br> 各分类按以上顺序排列，空分类省略；性能优化归入 **Changed / 变更**。

## [Unreleased / 未发布]

## [1.0.2-patch.1] - 2026-11-24

### Fixed / 修复

- Fixed the issue where backups could not be used in single-player mode without cheats.
<br> 修复单人游戏下无作弊无法使用备份的问题。

## [1.0.2] - 2026-11-23

### Added / 新增

- Restore, verify, or delete a snapshot by its exact timestamp, with timestamp and UUID completion for all three commands.
<br> 支持按精确时间回滚、校验或删除快照，三条命令均提供时间和 UUID 补全。
- Select a snapshot by its complete folder name with `fullname <timestamp--uuid>`.
<br> 支持用 `fullname <时间--UUID>` 按完整目录名选取快照，并提供补全。
- Query the actual local path or folder name of a committed snapshot through the public backup API.
<br> 公共备份 API 支持查询已提交快照当前的本地路径或文件夹名。
- List recent tasks or look up and cancel tasks by submission time, with time and task ID completion.
<br> 支持列出近期任务，以及按提交时间查询或取消任务，并补全时间和任务 ID。
- Notify each player once when they first join the restored world during its first session after rollback. The notice names the world and shows the backup time in UTC.
<br> 回滚后的第一次开服期间，每位玩家首次进入时会收到通知，显示存档名和恢复点的 UTC 备份时间。

### Changed / 变更

- The main mod now always includes Zstd. Standalone Zstd downloads and the backend-free main mod are no longer published.
<br> 主模组始终内置 Zstd，停止发布独立 Zstd 后端和无后端版主模组。
- Localize command feedback, including task results, verification, and configuration messages.
<br> 指令反馈按玩家语言显示，涵盖任务结果、校验和配置提示。
- Use `fromuuid <uuid>` in snapshot commands to select by UUID, including pagination and baseline selection. The configuration schema is read-only.
<br> 快照命令统一用 `fromuuid <uuid>` 指定 UUID，涵盖翻页和基线选择。配置中的 schema 为只读。
- Zstd names snapshot directories by UTC time and UUID, and migrates previous UUID-only directories on the next backup.
<br> Zstd 快照目录以 UTC 时间和 UUID 命名，旧 UUID 目录在下一次备份时迁移。
- Lowered the declared Fabric Loader minimum by Minecraft version and aligned the standalone API and Zstd backend with the main mod.
<br> 按 Minecraft 版本降低 Fabric Loader 声明下限，同步主模组、独立 API 和 Zstd 后端的要求。
- Unified the declared Mixin minimum at 0.8.5 across mod loader targets.
<br> 将各模组加载器目标的 Mixin 声明下限统一为 0.8.5。

### Fixed / 修复

- The Minecraft 1.21.1 NeoForge build supports the synchronous and asynchronous world data save paths in NeoForge 21.1.1 through 21.1.267.
  <br> Minecraft 1.21.1 的 NeoForge 构建兼容 NeoForge 21.1.1 至 21.1.267 的同步和异步世界数据保存路径。

## [1.0.1] - 2026-11-23

_元数据修复版本_
The metadata fix Release

## [1.0.0] - 2026-11-23

_首次发布版本_
The first Release
