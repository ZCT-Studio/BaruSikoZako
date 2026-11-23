<p align="center"><img src="resource/.public/banner.jpg" alt="BaruSikoZako 项目横幅 / Project banner" width="100%"></p>

<details>
<summary>简体中文</summary>

## 简介

BaruSikoZako 是 Minecraft Java 版的服务端世界备份模组。服务器运行时可以手动或定时创建备份，并在需要恢复时校验快照。恢复请求在服务器下次启动、世界加载前执行。

## 功能

- **世界备份**支持全量、增量和差异备份。默认版内置 Zstd 后端，将压缩快照保存到服务端本地。
- **定时与手动备份**共用一个备份仓库。管理员可以列出快照、校验归档和查看任务状态。
- **存档排除规则**支持全局 `world.gitignore` 和世界子目录中的 `local.gitignore`。
- **独立 API**供外部模组接入备份服务和生命周期回调。服务端管理员通过 `server.yml` 为集成授权。

## 安装

1. 从发布文件中选择与你的 Minecraft 版本和加载器匹配的 JAR。模组构建目标包括 Fabric、Quilt、Forge 和 NeoForge。请以下载文件标注的版本和加载器为准。
2. 将默认版 JAR 放入服务器的 `mods` 目录。请选择与构建匹配的 Java，1.20.1 使用 Java 17，1.21.x 使用 Java 21，26.x 使用 Java 25。默认版已包含 Zstd 后端，联机玩家无需安装。单人备份在客户端实例的 `mods` 目录安装，单人备份与回滚已在 Forge 1.20.1／1.21.10／1.21.11／26.1.2／26.2、NeoForge 1.21.1、Fabric／Quilt 1.21.11，以及 26.3 的 Fabric、Quilt、Forge、NeoForge 验证。
3. 运行 `/barusikozako backup` 创建首个备份，再用 `/barusikozako list` 查看快照 ID。

默认版和标有 `_L` 的无后端版二选一。`_L` 版需要安装兼容的后端才能备份。如有外部模组调用公开接口，再安装对应 Minecraft 版本及加载器的 `barusikozako_api`。

## 常用命令

| 命令 | 用途 |
| --- | --- |
| `/barusikozako status` | 查看备份服务状态 |
| `/barusikozako backup` | 按后端配置创建备份 |
| `/barusikozako full` | 使用 Zstd 后端手动创建全量备份 |
| `/barusikozako list` | 列出已提交的快照 |
| `/barusikozako verify <快照 ID>` | 完整校验指定快照 |
| `/barusikozako restore <快照 ID>` | 登记恢复请求，重启时在世界加载前执行 |

这些命令需要管理员权限，也可从服务器控制台执行。恢复会替换正在使用的世界数据，请先确认快照 ID 后再重启。

## 配置与数据

| 路径 | 用途 |
| --- | --- |
| `config/barusikozako/server.yml` | 定时备份、后端选择、世界捕获和 API 授权 |
| `config/barusikozako/barusikozako_backendzstd.yml` | Zstd 压缩、备份模式和全量轮换 |
| `config/barusikozako/world.gitignore` | 世界备份的全局排除规则 |
| `config/barusikozako/repository/` | 默认本地快照仓库，位于世界目录之外 |

## 开发与链接

使用仓库自带的 Gradle Wrapper，并为构建配置 Java 17 和 Java 25 工具链。参见[构建指南](gradle/README.md)、[发布目标](releases.README.md)、[更新记录](CHANGELOG.md)和[许可证](LICENSE)。[GitHub Actions](https://github.com/ZCT-Studio/BaruSikoZako/actions/workflows/release.yml) · [问题反馈](https://github.com/ZCT-Studio/BaruSikoZako/issues)

</details>

## About

BaruSikoZako is a server-side world backup mod for Minecraft Java Edition. Create backups on demand or on a schedule while the server is running, and verify snapshots before you need them. A queued restore runs before the world loads on the next server startup.

## Features

- **World backups** support full, incremental, and differential modes. The standard build includes a Zstd backend that stores compressed snapshots on the server.
- **Scheduled and manual backups** use the same repository. Admins can list snapshots, verify their contents, and check task status.
- **File exclusions** use a global `world.gitignore` and per-directory `local.gitignore` files inside the world.
- **Optional integration API** lets other mods use backup services and lifecycle hooks. Admins grant permissions in `server.yml`.

## Installation

1. Pick the JAR for your Minecraft version and loader. Build targets include Fabric, Quilt, Forge, and NeoForge. Check the release file for its exact compatibility.
2. Place the standard JAR in the server's `mods` directory and use the Java version for your build (17 for Minecraft 1.20.1, 21 for 1.21.x, or 25 for 26.x). The JAR includes the Zstd backend, and multiplayer guests can join without installing it. For singleplayer backups, install it in the client instance's `mods` directory. Singleplayer backups and restores have been tested on Forge (1.20.1, 1.21.10, 1.21.11, 26.1.2 and 26.2), NeoForge 1.21.1, Fabric and Quilt 1.21.11, and Fabric, Quilt, Forge and NeoForge 26.3.
3. Run `/barusikozako backup` to create your first snapshot, then find its ID with `/barusikozako list`.

Install either the standard build or the `_L` build. The `_L` build needs a compatible backend to create backups. Install the matching `barusikozako_api` only when another mod uses the public integration API.

## Common commands

| Command | Purpose |
| --- | --- |
| `/barusikozako status` | Show the state of the backup service |
| `/barusikozako backup` | Create a backup using the configured mode |
| `/barusikozako full` | Create a full backup with the Zstd backend |
| `/barusikozako list` | List committed snapshots |
| `/barusikozako verify <snapshot ID>` | Fully verify a snapshot |
| `/barusikozako restore <snapshot ID>` | Queue a restore before the world loads on the next startup |

Run these commands as an administrator or from the server console. A restore replaces the active world data, so confirm the snapshot ID before restarting.

## Configuration and data

| Path | Purpose |
| --- | --- |
| `config/barusikozako/server.yml` | Backup schedule, backend selection, world capture, and API permissions |
| `config/barusikozako/barusikozako_backendzstd.yml` | Zstd compression, backup mode, and full-backup rotation |
| `config/barusikozako/world.gitignore` | Global world backup exclusions |
| `config/barusikozako/repository/` | Default local snapshot repository, outside the world directory |

## Development and links

Use the Gradle Wrapper with Java 17 and Java 25 toolchains. See the [build guide](gradle/README.md), [release targets](releases.README.md), [changelog](CHANGELOG.md), and [MPL-2.0 license](LICENSE). [GitHub Actions](https://github.com/ZCT-Studio/BaruSikoZako/actions/workflows/release.yml) · [Issue tracker](https://github.com/ZCT-Studio/BaruSikoZako/issues)
