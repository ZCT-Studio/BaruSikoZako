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

## [1.0.3] - 2026-11-25

### Added / 新增

- Added `/zakobak` and `/zakobackup` aliases, plus a bilingual `/help` command.
<br> 新增 `/zakobak`、`/zakobackup` 别名，以及双语言 `/help` 帮助（超多的杂鱼~）
- `/config` now supports `get` to read a single value.
<br> `/config` 新增 `get`，可读取单项配置。
- The first server start and the first player joining now show the configuration path and backup repository, and note that periodic backups are disabled by default.
<br> 服务器首次启动和首个玩家进入时会显示配置路径与备份仓库，并提示定时备份默认关闭。

### Changed / 变更

- `/status` now shows the backend, repository path, schedule, last backup and configuration path.
<br> `/status` 现在显示后端、仓库路径、定时备份、上次备份与配置路径。
- Failed or rejected periodic backups now notify online administrators and log the concrete reason.
<br> 定时备份失败或被拒绝时会通知在线管理员，并在日志中记录具体原因。

>**_This Changelog isn't AIGC_**
><br>**_本次更新日志为非AI生成内容_**

## [1.0.3-alpha.1] - 2026-11-25

### Changed / 变更

- Configuration files are now created during mod initialization instead of after the server finishes starting.(Thanks for me:( )
<br> 配置文件现在会在模组初始化时创建，不再等到服务器启动完成。(感谢我在自己的专用服务器发现这个问题，它可能会使很多人不知道有备份排除)
- The default world ignore rules now exclude `session.lock`.
<br> 默认世界忽略规则现在增加排除项 `session.lock`。

>**_This Changelog isn't AIGC_**
><br>**_本次更新日志为非AI生成内容_**

## [1.0.2-hotfix] - 2026-11-25

### Fixed / 修复

- Fixed a crash caused by command synchronization when the server context is unavailable as a player joins.
<br> 修复玩家加入时指令同步缺少服务端上下文导致的崩溃。

>**_This Changelog isn't AIGC_**
><br>**_本次更新日志为非AI生成内容_**

## [1.0.2-patch.2] - 2026-11-24

### Changed / 变更

- Change MAX_DEPTH to 2048 and change DEFAULT_DEPTH to 32.
<br> 最大增量备份数量提升至2048并且修改默认增量备份数量为32。

>**_This Changelog isn't AIGC_**
><br>**_本次更新日志为非AI生成内容_**

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
