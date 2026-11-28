<p align="center"><img src="https://raw.githubusercontent.com/ZCT-Studio/BaruSikoZako/refs/heads/main/resource/.public/banner.jpg" alt="BaruSikoZako 项目横幅 / Project banner" width="100%"></p>

**A simple backup MOD.**

<details>
<summary>简体中文</summary>

BaruSikoZako（杂鱼备份，简称 ZakoBak）是一个面向 Minecraft Java 版世界的可扩展备份框架，旨在让游戏内外的备份与回滚操作更加简单。

与直接复制并压缩存档文件的传统备份方式不同，本模组通过多个 Mixin 注入点与 Minecraft 世界保存机制协作，获取具有一致性的存档视图，并将后续备份处理交由后台任务执行，以减少备份操作对正常游戏的影响。

支持游戏内指令、桌面 GUI、WebUI 与独立 CLI，可用于在线服务器、离线存档及异地部署。

### 可扩展的备份框架

- **可替换后端。** 存储后端通过后端 SPI 注册。内置的 `ZakoBak Zstd` 后端支持全量、增量与差异快照。
- **集成 API。** 可选的 `barusikozako_api` 允许其他模组查询快照、发起备份、校验、取消任务并订阅结果。
- **生命周期钩子。** 模组可在备份、校验、删除和回滚的前后执行代码。前置钩子失败会阻止操作，后置钩子会收到任务结果。
- **集成授权。** 可通过配置限制允许接入备份框架的第三方集成，并单独控制恢复与删除等敏感操作的权限。授权检查默认关闭。
- **统一仓库。** 游戏内指令、桌面工作站、WebUI 与 CLI 共享备份框架及快照格式。

### 在线一致性捕获

- 捕获时与 Minecraft 世界保存机制协作，协调保存并等待已提交的写入，将后续复制、压缩等操作交由后台任务执行，并通过 BossBar 显示任务进度。
- 捕获过程中检查文件状态及内容完整性。发生变化的文件会根据配置重试，无法满足一致性要求时拒绝捕获。
- `capture.consistency` 提供 `strict`（默认，要求严格的一致性捕获）与 `relaxed`（允许完整复制的文件来自不同时间点）。一致性等级会记录在快照元数据中，恢复宽松快照前会发出警告。
- 快照在仓库锁内原子发布，失败的备份任务不会覆盖已有快照。恢复前可运行完整校验。
- 回滚请求会持久化，并在世界加载前执行。回滚失败时会阻止世界打开。
- 可用 `world.gitignore` 或各目录下的 `local.gitignore` 排除文件；配置中还可选择解析 `.gitignore` 与 `.pteroignore`。

### 自动备份与存储

- 支持手动备份与定时自动备份。
- 可在 `config/barusikozako/server.yml` 中配置自动备份周期、捕获策略及其他运行参数。
- 内置 Zstd 后端支持配置压缩级别、压缩线程数及快照轮换策略。
- 快照默认保存在实例根目录下的 `barusikozako/<存档名>/<后端>/`。
- 备份数据保存在本地，用户可以自行管理、迁移或归档备份仓库。

### 多种操作方式

- **游戏内指令：** 通过 `/barusikozako` 管理备份、校验、回滚、任务、状态与配置。
- **桌面 GUI：** 双击模组 JAR 或运行 `java -jar`，打开独立的桌面工作站，无需启动 Minecraft 即可管理已停止的存档。
- **CLI：** 通过 `java -jar 模组.jar -w <存档> <命令>` 操作，适合自动化脚本、计划任务及无图形界面的服务器环境。

### 独立运行与异地部署

BaruSikoZako 不仅可以作为 Minecraft 模组运行，也可以作为独立的备份管理工具使用。

独立运行模式无需启动 Minecraft，可用于本地已停止的存档、挂载的存储卷，或迁移至其他机器的存档及备份仓库。

### 兼容性与要求

- 支持 Minecraft 1.20.1、1.21.1、1.21.10、1.21.11、26.1.2、26.2 和 26.3。
- 支持 Fabric、Quilt（通过 Fabric 兼容层）、Forge 与 NeoForge，具体支持范围以版本页提供的构建为准。
- Minecraft 1.20.1 使用 Java 17，1.21.x 使用 Java 21，26.x 使用 Java 25。
- 支持独立服务器与单人游戏。多人游戏仅需服务端安装，联机玩家无需安装本模组。
- **C2ME：** 已在启用 C2ME 的服务器环境中进行在线备份测试。不同 Minecraft、C2ME 与本模组版本组合的兼容性可能存在差异。

### 安装

**手动安装**

1. 在版本页下载与你的 Minecraft 版本及模组加载器匹配的 JAR。
2. 将 JAR 放入服务器或客户端实例的 `mods` 目录。
3. 默认发行版已内置 Zstd 后端，无需额外安装。
4. 仅当其他模组使用集成 API 时，才需要安装对应版本的 `barusikozako_api`。

**通过安装器安装**

提供 Universal 通用发行包及图形化安装方式，方便用户为不同 Minecraft 版本及模组加载器安装本模组。

双击对应 JAR 或通过 `java -jar` 启动，并按照界面提示操作。

### 外部系统交互

- 本模组会读取、写入、校验及恢复 Minecraft 存档、配置文件与备份仓库。
- 内置 Zstd 后端使用 `zstd-jni` 原生库执行压缩与解压。
- JAR 支持独立运行，并提供桌面 GUI、CLI 及安装功能。
- WebUI 涉及 HTTP 网络通信，实际网络访问范围取决于部署与配置。
- 第三方集成可通过公开 API 与生命周期钩子调用备份框架提供的功能，其权限可通过集成授权配置进行限制。

请仅向可信用户开放备份管理界面，并谨慎授予第三方集成恢复与删除权限。

### 开源与扩展

本项目采用 Mozilla Public License 2.0（MPL-2.0）许可证。

欢迎开发第三方存储后端、集成模组及管理工具，也欢迎在遵守许可证的前提下修改和重新分发本项目。

</details>

BaruSikoZako (ZakoBak) is an extensible backup framework for Minecraft: Java Edition, designed to make backups and rollbacks simple both inside and outside the game.

Unlike traditional backup mods that directly copy and compress world files, BaruSikoZako works with Minecraft's world-saving mechanisms through multiple Mixin injection points to capture consistent world views, while delegating subsequent backup processing to asynchronous tasks to minimise the impact on gameplay.

Manage your backups through in-game commands, a desktop GUI, a Web UI or a standalone CLI, across live servers, stopped worlds and remote deployments.

## An extensible backup framework

- **Pluggable backends.** Storage backends register through a backend SPI. The bundled `ZakoBak Zstd` backend supports full, incremental and differential snapshots.
- **Integration API.** The optional `barusikozako_api` JAR allows other mods to query snapshots, start backups, verify snapshots, cancel tasks and subscribe to results.
- **Lifecycle hooks.** Mods can execute code before and after backup, verification, deletion and rollback. A failing `before` hook prevents the operation, while `after` hooks receive the task result.
- **Integration authorisation.** Configurable authorisation restricts which third-party integrations may access the framework, with additional permissions for sensitive operations such as restoration and deletion. Authorisation checks are disabled by default.
- **Shared repositories.** In-game commands, the desktop workstation, the Web UI and the CLI share the same backup framework and snapshot format.

## Online capture and verification

- Capture coordinates with Minecraft's world-saving mechanisms and waits for submitted writes. Subsequent copying and compression are handled asynchronously, with task progress displayed through a BossBar.
- File state and content integrity are checked during capture. Changed files are retried according to the configuration, and captures that cannot satisfy the consistency requirements are rejected.
- `capture.consistency` provides `strict` (default; requires strict capture consistency) and `relaxed` (allows complete files captured at different points in time). The consistency level is recorded in snapshot metadata, and restoring a relaxed snapshot displays a warning.
- Snapshots are published atomically under a repository lock. Failed backup tasks do not overwrite existing snapshots. Full verification is available before restoration.
- Rollback requests are persisted and applied before the world loads. A failed rollback prevents the world from opening.
- Exclude files with `world.gitignore` or per-directory `local.gitignore` files. Optional `.gitignore` and `.pteroignore` parsing can be enabled in the configuration.

## Automatic backups and storage

- Supports both manual and scheduled backups.
- Configure backup intervals, capture policies and other runtime settings in `config/barusikozako/server.yml`.
- The bundled Zstd backend provides configurable compression levels, compression threads and snapshot rollover policies.
- Snapshots are stored under `barusikozako/<world>/<backend>/` by default, relative to the instance directory.
- Backup data is stored locally. Users can manage, migrate or archive their backup repositories independently.

## Multiple ways to manage backups

- **In-game commands:** manage backups, verification, rollback, tasks, status and configuration through `/barusikozako`.
- **Desktop GUI:** double-click the mod JAR or run it with `java -jar` to open a standalone desktop workstation for stopped worlds, without launching Minecraft.
- **CLI:** run `java -jar mod.jar -w <world> <command>` for automation scripts, scheduled tasks and headless server environments.

## Standalone operation and remote deployment

BaruSikoZako is not limited to running as a Minecraft mod. It can also operate as a standalone backup management tool.

Standalone operation does not require a running Minecraft instance. It can work with stopped local worlds, mounted storage volumes, or worlds and backup repositories transferred to another machine.

## Compatibility and requirements

- Supports Minecraft 1.20.1, 1.21.1, 1.21.10, 1.21.11, 26.1.2, 26.2 and 26.3.
- Supports Fabric, Quilt (through Fabric's compatibility layer), Forge and NeoForge. Availability depends on the builds listed on the Versions page.
- Java 17 for Minecraft 1.20.1, Java 21 for 1.21.x, and Java 25 for 26.x.
- Supports dedicated servers and singleplayer. No client-side installation is required for multiplayer.
- **C2ME:** online backups have been tested in a server environment running C2ME. Compatibility may vary between different combinations of Minecraft, C2ME and BaruSikoZako versions.

## Installation

**Manual installation**

1. Download the JAR matching your Minecraft version and mod loader.
2. Place it in the server's or client instance's `mods` directory.
3. The standard distribution includes the Zstd backend. No additional backend installation is required.
4. Install the matching `barusikozako_api` only if another mod uses the integration API.

**Graphical installation**

A Universal distribution and graphical installer are available to simplify installation across supported Minecraft versions and mod loaders.

Double-click the appropriate JAR or launch it using `java -jar`, then follow the on-screen instructions.

## External system interactions

- The mod reads, writes, verifies and restores Minecraft world files, configuration files and backup repositories.
- The bundled Zstd backend uses the `zstd-jni` native library for compression and decompression.
- The JAR supports standalone execution, including a desktop GUI, CLI and installation functionality.
- The Web UI uses HTTP networking. Its network exposure depends on the deployment and configuration.
- Third-party integrations may access framework functionality through the public API and lifecycle hooks. Their permissions can be restricted through integration authorisation.

Only expose backup management interfaces to trusted users, and grant restoration and deletion permissions to third-party integrations with care.

## Open source and extensibility

This project is licensed under the Mozilla Public License 2.0 (MPL-2.0).

Third-party storage backends, integration mods and management tools are welcome. Modification and redistribution are permitted under the terms of the licence.

---

[User guide / 使用说明](https://github.com/ZCT-Studio/BaruSikoZako/blob/main/README.md) · [Report an issue / 问题反馈](https://github.com/ZCT-Studio/BaruSikoZako/issues/new?title=%5BMod%5D%7C)
