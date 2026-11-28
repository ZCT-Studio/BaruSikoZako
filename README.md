<p align="center"><img src="resource/.public/banner.jpg" alt="BaruSikoZako 项目横幅 / Project banner" width="100%"></p>

An extensible backup framework for Minecraft: Java Edition, both inside and outside the game.

<details>
<summary>简体中文</summary>

## 简介

BaruSikoZako（杂鱼备份）是一个使用起来简单、具有丰富扩展能力的 Minecraft Java 版备份框架，支持游戏内外的备份与回滚。

与直接复制并压缩存档文件的传统备份方式不同，本模组通过多个 Mixin 注入点与 Minecraft 世界保存机制协作，获取具有一致性的存档视图，并将后续备份处理交由后台任务执行，以减少对正常游戏的影响。

## 功能

- **在线备份：** 无需关闭游戏或服务器即可创建备份。
- **一致性捕获：** 协调世界保存，检测文件变化，并提供严格与宽松两种一致性模式。
- **异步处理：** 在后台执行复制、压缩等耗时操作。
- **多种备份模式：** 内置 Zstd 后端支持全量、增量与差异备份。
- **自动备份：** 支持手动触发与定时备份。
- **完整校验：** 在恢复前检查快照及归档的完整性。
- **存档回滚：** 在世界加载前执行恢复，并在恢复后通知玩家。
- **文件排除：** 支持 `world.gitignore`、`local.gitignore`，以及可选的 `.gitignore` 和 `.pteroignore` 规则。
- **多种操作入口：** 支持游戏内指令、桌面 GUI、WebUI 与 CLI。
- **独立运行：** 无需启动 Minecraft 即可管理已停止的存档。
- **异地部署：** 支持在其他机器上管理可访问的存档及备份仓库。
- **可扩展架构：** 支持第三方存储后端、集成 API 与生命周期钩子。

## 一致性与可靠性

备份框架与 Minecraft 世界保存机制协作，在捕获过程中检查文件状态及内容完整性。

`capture.consistency` 提供两种模式：

- `strict`：默认模式，要求严格的一致性捕获。无法满足一致性要求时拒绝备份。
- `relaxed`：允许完整复制的文件来自不同时间点，适用于无法稳定完成严格捕获的场景。

一致性等级会记录在快照元数据中，恢复宽松快照前会发出警告。

快照在仓库锁内原子发布。回滚请求会持久化，并在世界加载前执行；回滚失败时会阻止世界打开。

## 兼容性

- Fabric
- Quilt（通过 Fabric 兼容层）
- Forge
- NeoForge

支持 Minecraft 1.20.1、1.21.1、1.21.10、1.21.11、26.1.2、26.2 和 26.3，具体版本与加载器组合以实际发行文件为准。

| Minecraft 版本 | Java 版本 |
| --- | --- |
| 1.20.1 | Java 17 |
| 1.21.x | Java 21 |
| 26.x | Java 25 |

支持独立服务器与单人游戏。多人游戏仅需服务端安装，联机玩家无需安装本模组。

**C2ME：** 已在启用 C2ME 的服务器环境中进行在线备份测试。不同版本组合的兼容性可能存在差异。

## 下载与安装

- [GitHub Releases](https://github.com/ZCT-Studio/BaruSikoZako/releases)
- [GitHub 构建产物](https://github.com/ZCT-Studio/BaruSikoZako/actions/workflows/release.yml)

选择与你的 Minecraft 版本及加载器匹配的发行文件，将 JAR 放入服务器或客户端实例的 `mods` 目录。

也可以使用支持图形化安装的发行包，双击 JAR 或通过 `java -jar` 启动安装界面。

默认发行版已内置 Zstd 后端。仅当其他模组使用集成 API 时，才需要安装对应版本的 `barusikozako_api`。

## 快速开始

1. 运行 `/barusikozako backup` 创建备份。
2. 运行 `/barusikozako list` 查看快照。
3. 运行 `/barusikozako verify <UTC 时间>` 校验快照。
4. 运行 `/barusikozako rollback <UTC 时间>` 请求回滚。
5. 重启独立服务器，或退出并重新进入单人存档，以完成恢复。

服务端命令可由管理员或服务器控制台执行。在控制台输入命令时省略开头的 `/`。

### 常用指令

| 指令 | 用途 |
| --- | --- |
| `/barusikozako backup` | 创建备份 |
| `/barusikozako full` | 创建全量备份 |
| `/barusikozako list` | 列出快照 |
| `/barusikozako detail <选择>` | 查看快照详情 |
| `/barusikozako verify <选择>` | 校验快照 |
| `/barusikozako rollback <选择>` | 回滚存档 |
| `/barusikozako delete <选择>` | 删除快照 |
| `/barusikozako task` | 查看近期任务 |
| `/barusikozako cancel <时间>` | 取消任务 |
| `/barusikozako status` | 查看服务状态 |
| `/barusikozako config` | 查看配置 |
| `/barusikozako version` | 查看版本及集成信息 |

快照可通过 UTC 时间、`fromuuid <uuid>` 或 `fullname <时间--UUID>` 选择。

任务可通过时间或 `fromid <任务 ID>` 选择。

## 运行方式

### 游戏内指令

在 Minecraft 运行期间，通过 `/barusikozako` 管理备份、快照、任务与配置。

### 桌面 GUI

双击模组 JAR 或运行：

```sh
java -jar mod.jar
```

通过独立桌面工作站管理已停止的存档，无需启动 Minecraft。

### WebUI

通过浏览器管理备份、快照及相关任务，无需进入游戏。

网络访问范围取决于实际运行模式与部署配置。

### CLI

通过命令行管理已停止的存档，适用于自动化脚本、计划任务及无图形界面的服务器。

```sh
java -jar mod.jar [选项] <命令> [参数]
```

例如：

```sh
java -jar mod.jar -w world backup
java -jar mod.jar -w world list
java -jar mod.jar -w world status
```

| 命令 | 用途 |
| --- | --- |
| `backup` | 创建备份 |
| `list` | 列出快照 |
| `detail <选择>` | 查看快照详情 |
| `verify <选择>` | 校验快照 |
| `rollback [-y] <选择>` | 回滚存档 |
| `delete [-y] <选择>` | 删除快照 |
| `status` | 查看服务状态 |
| `config server\|backend` | 查看配置 |

`-w <目录>` 用于指定存档，`-c <目录>` 用于手动指定游戏根目录。

`-y` 可跳过回滚或删除操作的确认。

独立工作站与 CLI 共享游戏内的快照格式、命名规则、配置及备份仓库。

## 配置与备份位置

以下路径相对于游戏根目录。

| 路径 | 用途 |
| --- | --- |
| `config/barusikozako/server.yml` | 备份周期、捕获策略及运行配置 |
| `config/barusikozako/barusikozako_backendzstd.yml` | Zstd 压缩、备份模式及轮换策略 |
| `config/barusikozako/api.yml` | 第三方集成授权 |
| `config/barusikozako/progress.yml` | 游戏内任务进度展示 |
| `config/barusikozako/world.gitignore` | 世界文件排除规则 |
| `barusikozako/<存档名>/<后端>/` | 备份仓库 |

在 `server.yml` 中设置 `schedule.intervalSeconds` 可启用定时备份，设置为 `0` 则关闭。

独立运行时，游戏根目录默认从存档位置推导，并优先使用游戏配置。游戏配置不存在时，使用 `<游戏根>/standalone/`。

## 开发与扩展

BaruSikoZako 提供可替换的存储后端与公开集成接口。

- **后端 SPI：** 实现并注册自定义存储后端。
- **集成 API：** 查询快照、发起备份、校验、取消任务及订阅任务结果。
- **生命周期钩子：** 在备份、校验、删除与回滚等操作的前后执行自定义逻辑。
- **跨加载器集成：** 支持通过 Java ServiceLoader 注册集成与生命周期钩子，同时保留加载器原生集成方式。

## 安全与授权

集成授权通过 `api.yml` 配置，默认关闭。

关闭授权检查时，所有被发现的第三方集成均获得完整权限。启用授权检查后，可以限制允许接入的集成，并单独授予恢复与删除权限。

请仅安装可信来源的模组及扩展，并谨慎配置 WebUI 的网络访问权限。

本项目的授权机制不是用于隔离不可信 Java 代码的安全沙箱。

## 外部系统交互

- 读取、写入、校验及恢复存档、配置与备份文件。
- 使用 `zstd-jni` 原生库进行压缩与解压。
- 提供独立桌面 GUI、CLI 及图形化安装功能。
- WebUI 涉及 HTTP 网络通信。
- 第三方集成可通过公开 API 调用备份框架。

## 开源许可证

本项目采用 MPL-2.0 许可证。

欢迎贡献代码、开发第三方后端与集成，以及在遵守许可证的前提下修改和重新分发本项目。

[更新日志](CHANGELOG.md) · [API 更新日志](CHANGELOG.API.md) · [问题反馈](https://github.com/ZCT-Studio/BaruSikoZako/issues) · [许可证](LICENSE)

</details>

## About

BaruSikoZako (ZakoBak) is an extensible backup framework for Minecraft: Java Edition, designed to make backups and rollbacks simple both inside and outside the game.

Unlike traditional backup mods that directly copy and compress world files, BaruSikoZako works with Minecraft's world-saving mechanisms through multiple Mixin injection points to capture consistent world views and perform subsequent backup processing asynchronously.

## Features

- **Online backups:** Create backups without stopping the game or server.
- **Consistency-aware capture:** Coordinate world saves, detect file changes, and choose between strict and relaxed consistency modes.
- **Asynchronous processing:** Perform time-consuming copying and compression in background tasks.
- **Multiple backup modes:** Full, incremental and differential backups with the bundled Zstd backend.
- **Scheduled backups:** Create backups manually or automatically.
- **Full verification:** Check snapshot and archive integrity before restoration.
- **World rollback:** Restore worlds before loading and notify players after restoration.
- **File exclusions:** Support for `world.gitignore`, `local.gitignore`, and optional `.gitignore` and `.pteroignore` rules.
- **Multiple interfaces:** In-game commands, desktop GUI, Web UI and CLI.
- **Standalone operation:** Manage stopped worlds without launching Minecraft.
- **Remote deployment:** Manage accessible worlds and backup repositories on another machine.
- **Extensible architecture:** Third-party storage backends, integration APIs and lifecycle hooks.

## Consistency and reliability

The backup framework coordinates with Minecraft's world-saving mechanisms and checks file state and content integrity during capture.

`capture.consistency` provides two modes:

- `strict`: The default mode. Requires strict capture consistency and rejects captures that cannot satisfy its requirements.
- `relaxed`: Allows complete files captured at different points in time when strict capture cannot reliably complete.

The consistency level is recorded in snapshot metadata. Restoring a relaxed snapshot displays a warning.

Snapshots are published atomically under a repository lock. Rollback requests are persisted and applied before the world loads. A failed rollback prevents the world from opening.

## Compatibility

- Fabric
- Quilt (through Fabric compatibility)
- Forge
- NeoForge

Supports Minecraft 1.20.1, 1.21.1, 1.21.10, 1.21.11, 26.1.2, 26.2 and 26.3. Available Minecraft and loader combinations depend on the actual release artifacts.

| Minecraft version | Java version |
| --- | --- |
| 1.20.1 | Java 17 |
| 1.21.x | Java 21 |
| 26.x | Java 25 |

Supports dedicated servers and singleplayer. No client-side installation is required for multiplayer.

**C2ME:** Online backups have been tested in a server environment running C2ME. Compatibility may vary between version combinations.

## Download and installation

- [GitHub Releases](https://github.com/ZCT-Studio/BaruSikoZako/releases)
- [GitHub build artifacts](https://github.com/ZCT-Studio/BaruSikoZako/actions/workflows/release.yml)

Download the release matching your Minecraft version and loader, then place the JAR in the server's or client instance's `mods` directory.

Distributions supporting graphical installation can also be launched by double-clicking the JAR or running it with `java -jar`.

The standard distribution includes the Zstd backend. Install the matching `barusikozako_api` only if another mod uses the integration API.

## Quick start

1. Run `/barusikozako backup` to create a backup.
2. Run `/barusikozako list` to list snapshots.
3. Run `/barusikozako verify <UTC timestamp>` to verify a snapshot.
4. Run `/barusikozako rollback <UTC timestamp>` to request a rollback.
5. Restart the dedicated server, or leave and reopen the singleplayer world to complete restoration.

Run server commands as an administrator or from the server console. Omit the leading `/` when using the console.

### Common commands

| Command | Purpose |
| --- | --- |
| `/barusikozako backup` | Create a backup |
| `/barusikozako full` | Create a full backup |
| `/barusikozako list` | List snapshots |
| `/barusikozako detail <selector>` | Show snapshot details |
| `/barusikozako verify <selector>` | Verify a snapshot |
| `/barusikozako rollback <selector>` | Roll back a world |
| `/barusikozako delete <selector>` | Delete a snapshot |
| `/barusikozako task` | List recent tasks |
| `/barusikozako cancel <time>` | Cancel a task |
| `/barusikozako status` | Show service status |
| `/barusikozako config` | Show configuration |
| `/barusikozako version` | Show version and integration information |

Select snapshots using a UTC timestamp, `fromuuid <uuid>`, or `fullname <timestamp--uuid>`.

Select tasks using a timestamp or `fromid <task-id>`.

## Ways to run

### In-game commands

Manage backups, snapshots, tasks and configuration through `/barusikozako` while Minecraft is running.

### Desktop GUI

Double-click the mod JAR or run:

```sh
java -jar mod.jar
```

Manage stopped worlds through a standalone desktop workstation without launching Minecraft.

### Web UI

Manage backups, snapshots and related tasks through a web browser without joining the game.

Network exposure depends on the runtime mode and deployment configuration.

### CLI

Manage stopped worlds from the command line, suitable for automation scripts, scheduled tasks and headless servers.

```sh
java -jar mod.jar [options] <command> [args]
```

Examples:

```sh
java -jar mod.jar -w world backup
java -jar mod.jar -w world list
java -jar mod.jar -w world status
```

| Command | Purpose |
| --- | --- |
| `backup` | Create a backup |
| `list` | List snapshots |
| `detail <selector>` | Show snapshot details |
| `verify <selector>` | Verify a snapshot |
| `rollback [-y] <selector>` | Roll back a world |
| `delete [-y] <selector>` | Delete a snapshot |
| `status` | Show service status |
| `config server\|backend` | Show configuration |

Use `-w <directory>` to specify the world and `-c <directory>` to override the game root.

The `-y` option skips confirmation for rollback and deletion.

The standalone workstation and CLI share the same snapshot format, naming rules, configuration and backup repositories as the in-game commands.

## Configuration and backup location

The following paths are relative to the game root.

| Path | Purpose |
| --- | --- |
| `config/barusikozako/server.yml` | Backup intervals, capture policies and runtime settings |
| `config/barusikozako/barusikozako_backendzstd.yml` | Zstd compression, backup modes and rollover policies |
| `config/barusikozako/api.yml` | Third-party integration authorisation |
| `config/barusikozako/progress.yml` | In-game task progress display |
| `config/barusikozako/world.gitignore` | World file exclusion rules |
| `barusikozako/<world>/<backend>/` | Backup repository |

Set `schedule.intervalSeconds` in `server.yml` to enable scheduled backups. Use `0` to disable them.

In standalone mode, the game root is automatically derived from the world location. Existing game configuration takes priority, with `<game root>/standalone/` used as a fallback.

## Development and extensibility

BaruSikoZako provides pluggable storage backends and public integration interfaces.

- **Backend SPI:** Implement and register custom storage backends.
- **Integration API:** Query snapshots, start backups, verify snapshots, cancel tasks and subscribe to task results.
- **Lifecycle hooks:** Execute custom logic before and after backup, verification, deletion and rollback operations.
- **Cross-loader integration:** Register integrations and lifecycle hooks through Java ServiceLoader, alongside loader-specific integration mechanisms.

## Security and authorisation

Integration authorisation is configured through `api.yml` and is disabled by default.

When authorisation checks are disabled, all discovered third-party integrations receive full rights. Enabling authorisation allows administrators to restrict integrations and separately grant restoration and deletion permissions.

Only install trusted mods and extensions, and configure Web UI network access carefully.

Integration authorisation is not a security sandbox for untrusted Java code.

## External system interactions

- Reads, writes, verifies and restores world files, configuration and backup repositories.
- Uses the `zstd-jni` native library for compression and decompression.
- Provides standalone desktop GUI, CLI and graphical installation functionality.
- Uses HTTP networking for the Web UI.
- Exposes backup framework functionality to third-party integrations through public APIs.

## License

This project is licensed under the Mozilla Public License 2.0 (MPL-2.0).

Contributions, third-party backends, integrations, modifications and redistribution are welcome under the terms of the licence.

[Changelog](CHANGELOG.md) · [API Changelog](CHANGELOG.API.md) · [Report an issue](https://github.com/ZCT-Studio/BaruSikoZako/issues) · [License](LICENSE)