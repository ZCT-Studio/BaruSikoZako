# Changelog / 更新日志

This file documents **user-facing** changes and is used directly for GitHub Releases and release notes.
<br> 本文件记录**面向用户**的改动，内容会直接用于 GitHub Release 与发布说明。

Internal-only changes (such as refactoring, build configuration, and documentation maintenance) are not listed.
<br> 纯内部的调整（重构、构建配置、文档整理等）不在此列出。

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
<br> 本更新日志格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
并遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

Older changelogs are archived by major and minor versions in the [Changelog Archives](changelogs/README.md).
<br> 旧版本更新日志按主版本号和次版本号归档，详见[更新日志归档](changelogs/README.md)。

See [AGENTS.md#changelog](AGENTS.md#changelog) for details.
<br> 详见 [AGENTS.md#changelog](AGENTS.md#changelog)。

Sections appear in this order when applicable: **Added / 新增**, **Changed / 变更**, **Deprecated / 弃用**, **Removed / 移除**, **Fixed / 修复**, **Security / 安全**. Performance improvements are listed under **Changed / 变更**.
<br> 各分类按以上顺序排列，空分类省略；性能优化归入 **Changed / 变更**。

## [Unreleased / 未发布]

### Added / 新增

- Added dedicated server development builds for Fabric and Quilt on Minecraft 1.20.1, 1.21.1, 1.21.10, and 1.21.11. These builds require Java 25.
<br> 新增 Minecraft 1.20.1、1.21.1、1.21.10 和 1.21.11 的 Fabric／Quilt 独立服务端开发构建，需要 Java 25。

- Added NeoForge dedicated server development builds for Minecraft 1.21.1, 1.21.10, and 1.21.11.
<br> 新增 Minecraft 1.21.1、1.21.10 和 1.21.11 的 NeoForge 独立服务端开发构建。

- Added Forge dedicated server development builds for Minecraft 1.20.1, 1.21.1, 1.21.10, and 1.21.11. These builds require Java 25.
<br> 新增 Minecraft 1.20.1、1.21.1、1.21.10 和 1.21.11 的 Forge 独立服务端开发构建，需要 Java 25。

- Added Fabric and Quilt dedicated server builds for Minecraft 26.1.2 and 26.2.
<br> 新增 Minecraft 26.1.2 和 26.2 的 Fabric／Quilt 独立服务端构建。

- Added Minecraft 26.1.2, 26.2, and 26.3 dedicated server development builds for NeoForge and Forge.
<br> 新增 Minecraft 26.1.2、26.2 和 26.3 的 NeoForge 和 Forge 独立服务端开发构建。

- Added a Fabric `<NO_BACKEND>` build, a standalone Zstd backend, and a separate public API mod.
<br> 新增 Fabric `<NO_BACKEND>` 版本、独立 Zstd 后端和单独安装的公共 API 模组。

- Administrators can edit YAML settings via commands and queue an offline restore for the next server startup.
<br> 管理员可以通过指令修改 YAML 配置，并登记在下次开服前执行的离线回滚。

### Changed / 变更

- Windows backup and restore try NTFS directory synchronization under Java 25's default native access policy. Failures report troubleshooting steps to the requester and server log.
<br> Windows 备份和回滚可在 Java 25 的默认原生访问策略下尝试 NTFS 目录刷盘。失败时会向请求者和服务端日志报告处理办法。

- The save coordination threshold defaults to zero, which disables duration-based rejection. Positive limits are opt-in, and the capture preparation timeout still applies.
<br> 保存协调耗时阈值默认为 0，默认不因协调耗时拒绝备份。配置正数可启用阈值，捕获准备阶段的超时继续生效。

- Online backup uses best-effort capture. The server keeps ticking and saving while files are copied in the background. Changed files use bounded retries, and the source and staged SHA-256 digests are compared. See [Online Capture](private/agents/docs/online-capture.md).
<br> 在线备份采用 best-effort 捕获。后台复制时服务器继续 tick 和保存。逐文件有限重试并核对源与暂存 SHA-256。详见[在线捕获记录](private/agents/docs/online-capture.md)。

- Incremental mode writes a full backup on first run and rotates after the configured count, cumulative archive size, or full-backup interval threshold is reached.
<br> 增量模式首次写入全量，在达到已配置的增量次数、累计归档大小或全量时间间隔阈值后，下一次备份重新写入全量。

- Full Zstd backup supports configurable compression level, threads, and stable view read rate; continues writing legacy v1 tar.zst.
<br> 全量 Zstd 可配置压缩等级、压缩线程和稳定视图读取速率，继续写入原有 v1 tar.zst。

- Added fixed 64 KiB chunking with content deduplication, file-level incremental, and differential backup against a specified full baseline. Retains v1 tar.zst and v2 fixed-block reads.
<br> 增加固定 64 KiB 分块及内容去重、文件级增量和指定全量基线的差异备份。保留 v1 tar.zst 和 v2 固定块读取。

- Added deletion with baseline protection, standalone rebuild, and empty-server background reclamation. Dev build recovered 15 points across three Windows NTFS server states and re-validated Linux ext4 differential recovery and XFS clone paths.
<br> 增加带基线保护的删除、独立重建和空服后台回收。开发版在 Windows NTFS 三种服务端状态累计恢复 15 个点，并复验 Linux ext4 差异恢复与 XFS 克隆路径。

- Three real Minecraft region file sets benchmarked against 16, 32, 64, 128 KiB and FastCDC-style chunking. Dev validation status see [Zstd Modes](private/agents/docs/zstd-modes.md).
<br> 三组真实 Minecraft 区域文件扩展了 16、32、64、128 KiB 与 FastCDC 风格分块对照。开发验收状态见[Zstd 模式](private/agents/docs/zstd-modes.md)。

## [0.1.0-alpha.3] - 2026-11-19

### Changed / 变更

- Optimized Windows NTFS bounded staging copy; rejects directory junctions and improves isolation timeout diagnostics. Retains validated Linux XFS capture.
<br> 优化 Windows NTFS 有界暂存复制，拒绝目录联接并改进隔离超时诊断。保留已验收的 Linux XFS 捕获。
