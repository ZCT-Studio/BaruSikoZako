# Changelog / 更新日志

## [Unreleased / 未发布]

### Added / 新增

- Added world backup exclusions from `world.gitignore`, nested `local.gitignore`, and mods, with source details in backup notices.
<br> 新增世界备份排除规则，支持 `world.gitignore`、分目录的 `local.gitignore` 和模组注册，并在备份提示中显示规则来源。

### Changed / 变更

- World backups now group files by world name under `.worlds/`; an existing `save.gitignore` is renamed on first use.
<br> 世界备份现在按存档名归入 `.worlds/`，首次使用时会重命名已有的 `save.gitignore`。

- The main mod runs on its own. Install the standalone API when another mod uses the public integration interfaces.
<br> 主模组可独立运行。其他模组使用公开集成接口时，请额外安装独立 API。

- Installable JAR filenames now prefix the version with `v`, for example `barusikozako-v0.5.0+fabric26.3.jar`.
<br> 可安装 JAR 文件名的版本号现在以 `v` 开头，例如 `barusikozako-v0.5.0+fabric26.3.jar`。

### Fixed / 修复

- Public backup services and lifecycle callbacks now honor the integration allowlists in `server.yml`.
<br> 公开备份服务与生命周期回调现在会遵循 `server.yml` 中的集成授权名单。

## [0.5.0] - 2026-11-22

### Added / 新增

- Added a standalone backup core, public integration API, and Zstd backup backend.
<br> 新增独立备份核心、公共集成 API 和 Zstd 备份后端。

- Added full, incremental, and differential backups, full verification, and offline recovery.
<br> 新增全量、增量和差异备份、完整校验及离线恢复。

- Added Minecraft loader adapters and a JavaAgent entry point.
<br> 新增 Minecraft 加载器适配层和 JavaAgent 入口。

- Added backup configuration, commands, and lifecycle integration points.
<br> 新增备份配置、指令及生命周期集成接口。
