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

## [1.2.0] - 2026-11-28

### Added / 新增

- The mod JAR can now be double-clicked (or run with `java -jar`) to open a standalone desktop workstation built with the native Java Swing UI. For a stopped world it supports backup, listing, details, verification, rollback, deletion, status and configuration, and it shares the exact same snapshot format, naming and repository as the in-game commands.
  <br> 模组 JAR 现在可以双击（或用 `java -jar`）打开一个基于 Java 原始 Swing 界面的独立桌面工作站。它能对已停止的存档进行备份、列出、详情、校验、回滚、删除、状态与配置查看，并与游戏内指令共享完全相同的快照格式、命名与仓库。
- The same workstation is available from the command line with semantics matching the in-game commands (`backup`, `list`, `detail`, `verify`, `rollback`, `delete`, `status`, `config`), so backups and rollbacks can be scripted or run from a terminal.
  <br> 该工作站同时提供与游戏内指令语义一致的命令行入口（`backup`、`list`、`detail`、`verify`、`rollback`、`delete`、`status`、`config`），可在终端中脚本化执行备份与回滚。
- Third-party mods can register a backup integration through `META-INF/services/io.github.zct_studio.barusikozako.api.BackupIntegration` (and `...BackupLifecycleHook`) without referencing any loader class; the implementation only needs a public no-argument constructor. Fabric entrypoints and the Forge/NeoForge extension points keep working, take priority when both are present, and every mod still yields exactly one integration identified by its container ID.
<br> 第三方模组现在可以通过 `META-INF/services/io.github.zct_studio.barusikozako.api.BackupIntegration`（以及 `...BackupLifecycleHook`）注册备份集成，无需引用任何加载器类；实现类只需具备 public 无参构造。Fabric entrypoint 与 Forge/NeoForge 扩展点继续可用，两者同时存在时优先，每个模组仍只产生一个以容器 ID 标识的集成。
- `/barusikozako version` now reports registered and rejected integrations, and `/barusikozako list version <backends|mod|extensions>` lists backend, mod or extension versions.
<br> `/barusikozako version` 现在会显示已注册与拒绝的拓展，并新增 `/barusikozako list version <backends|mod|extensions>` 分别列出后端、本体或拓展的版本。
- Integrations that are skipped are logged with their mod ID instead of being dropped silently.
<br> 被跳过的集成会记录模组标识，不再静默丢弃。
- Integrations can write log lines that carry their mod ID, and send messages to all players, administrators, or the player who started the most recent task; arbitrary player targeting is not exposed.
<br> 集成现在可以写带模组标识的日志，并向全部玩家、管理员或最近一次任务的发起者发送消息；不开放任意玩家定向。

### Changed / 变更

- Integration authorization is now an advanced, opt-in feature stored in `api.yml`. It is disabled by default, and while disabled no allowlist is checked: every discovered integration is activated with full rights. Existing `api.*` keys in `server.yml` are migrated on first start.
  <br> 集成授权改为独立的高级功能，配置移到 `api.yml` 并默认关闭；关闭时不检查任何名单，所有被发现的集成都会获得全部权限。`server.yml` 中旧的 `api.*` 键会在首次启动时自动迁移。
- The bundled standalone workstation and the Zstd backend now ship only the native libraries for mainstream platforms (Windows, Linux and macOS on x86_64 and AArch64); the archive-format code unused by tar-based snapshots, and the unused transitive dependencies (`commons-codec`, plus `commons-lang3` replaced by a tiny in-module shim) are no longer packaged. The standalone workstation's own JAR likewise no longer bundles the runtime libraries already shipped in the mod JAR's nested `META-INF/jars`, shrinking it from several megabytes to a few tens of kilobytes. This applies to Fabric, Forge and NeoForge alike and roughly halves each mod JAR (the Forge/NeoForge backend drops from ~10 MB to ~4 MB).
  <br> 内置的独立工作站与 Zstd 后端现在只打包主流平台（Windows、Linux、macOS 的 x86_64 与 AArch64）的原生库，并去除 tar 快照用不到的归档格式代码以及未使用的传递依赖（`commons-codec` 完全移除，`commons-lang3` 以模块内置的迷你替代类取代）。独立工作站自身的 JAR 也不再冗余打包模组 JAR 嵌套 `META-INF/jars` 中已存在的运行时依赖，体积从数 MB 缩减至几十 KB。此优化对 Fabric、Forge 与 NeoForge 一致生效，各模组 JAR 体积约减少一半（Forge/NeoForge 后端由约 10 MB 降至约 4 MB）。
- The standalone workstation now auto-detects the game root from the world directory: it uses the parent of `saves/` when the world sits under `saves/`, otherwise the world's parent. Configuration is synced from the game's `config/barusikozako/` when present, falling back to a `<game root>/standalone/` directory otherwise; snapshots remain under `<game root>/barusikozako/<world>/<backend>/` to match the in-game layout.
  <br> 独立工作站现在会从存档目录自动推导游戏根目录：存档位于 `saves/` 下时取其上级（`saves/` 的父目录），否则取存档上级。配置会优先同步游戏 `<游戏根>/config/barusikozako/`，不存在则改用 `<游戏根>/standalone/`；快照仍位于 `<游戏根>/barusikozako/<存档名>/<后端>/`，与游戏内布局一致。
- Backup progress for tasks the viewer did not start (scheduled, console, other players and third-party API) is now shown to all administrators by default. `progress.yml` can still switch the audience to everyone, operators only, or nobody.
  <br> 非查看者本人发起的备份（定时、控制台、其他玩家及第三方 API）进度现在默认向所有管理员展示；`progress.yml` 仍可将受众改为全部玩家、仅管理员或不展示。

>**_This Changelog is AIGC and verified_**
><br>**_本次更新日志为AI生成内容，并且经过验证_**