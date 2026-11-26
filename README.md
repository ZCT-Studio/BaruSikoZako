<p align="center"><img src="resource/.public/banner.jpg" alt="BaruSikoZako 项目横幅 / Project banner" width="100%"></p>

<details>
<summary>简体中文</summary>

## 简介

BaruSikoZako（杂鱼备份） 为 Minecraft Java 版保存世界备份。更新整合包前可以手动留一份快照，平时也可以定时备份。需要回滚时，可先校验快照，再让游戏在下次打开世界前恢复。

## 功能

- 全量备份保存完整世界。增量备份记录上一次备份后发生的变化，差异备份记录相对于指定全量快照的变化。
- 默认版使用 Zstd 压缩，将快照保存在本地。
- 可手动或定时备份，列出快照并校验归档。
- 可用 `world.gitignore` 和存档子目录中的 `local.gitignore` 排除文件。

## 下载与安装

在项目平台的版本列表选择下载项，也可以从 GitHub 的成功构建中下载对应加载器和 Minecraft 版本的产物。 [GitHub 构建产物](https://github.com/ZCT-Studio/BaruSikoZako/actions/workflows/release.yml)

1. 选择与你的 Minecraft 版本和加载器匹配的文件。可用组合以下载项为准。Minecraft 1.20.1 使用 Java 17，1.21.x 使用 Java 21，26.x 使用 Java 25。
2. **独立服务器**将 JAR 放入服务器的 `mods` 目录。联机玩家无需在客户端安装本模组。**单人游戏**将 JAR 放入客户端实例的 `mods` 目录，并在允许使用指令的世界中操作。
3. 首次安装选默认版，内含 Zstd。选 `_L` 版时需另装兼容的备份后端。其他模组调用公开接口时，才需要加装匹配的 `barusikozako_api`。

使用 1.0.2 主模组、API 和 Zstd 时，可调用 `BackupService.snapshotDirectory(snapshotRef)` 异步查询快照当前的本地绝对路径，或调用 `snapshotFolderName(snapshotRef)` 只取文件夹名。备份成功后，可从 `TaskResult.BackupCommitted.snapshot()` 获取参数。快照不存在或没有本地目录时返回空值。迁移或删除后需要重新查询。

## 第一次备份和恢复

1. 运行 `/barusikozako backup`，等备份完成后用 `/barusikozako list` 找到快照 ID。
2. 运行 `/barusikozako verify <UTC 时间>`，确认快照通过完整校验。
3. 想回滚时，运行 `/barusikozako rollback <UTC 时间>`。独立服务器需要重启，单人游戏需要退出并重新进入存档。恢复会在世界加载前替换当前存档。回滚后第一次开服期间，每位玩家首次进入时会收到通知，包含存档名和恢复点的 UTC 备份时间。

`restore`、`verify` 和 `delete` 均可用 Tab 补全时间。使用快照 UUID 时，在命令后加 `fromuuid <uuid>`。也可用 `fullname <时间--UUID>` 粘贴完整快照目录名。运行 `/barusikozako task` 列出近期任务，再用 `task <时间>` 或 `cancel <时间>`，也可加 `fromid <任务 ID>` 直接选择。

服务端命令可由管理员或服务器控制台执行。在控制台输入命令时省略开头的 `/`。

### 其他常用命令

| 命令 | 用途 |
| --- | --- |
| `/barusikozako status` | 查看备份服务是否可用 |
| `/barusikozako full` | 使用 Zstd 创建一次全量备份 |
| `/barusikozako task <任务 ID>` | 查看备份或校验任务的状态 |

## 配置与备份位置

| 路径 | 用途 |
| --- | --- |
| `config/barusikozako/server.yml` | 在 `schedule.intervalSeconds` 中设置备份间隔。填 `0` 可关闭定时备份。 |
| `config/barusikozako/barusikozako_backendzstd.yml` | 设置压缩、备份模式和全量轮换。 |
| `config/barusikozako/world.gitignore` | 为世界备份设置全局文件排除规则。 |
| `barusikozako/<存档名>/<后端>/` | 快照独立于世界目录存放。Zstd 的 `snapshots/` 下以 `2026-11-23T06-19-24.645272600Z--<uuid>` 格式命名，旧 UUID 目录会在下一次备份写入前迁移。 |

修改 YAML 配置后，重启服务器或客户端以应用设置。

[更新记录](CHANGELOG.md) · [问题反馈](https://github.com/ZCT-Studio/BaruSikoZako/issues) · [MPL-2.0 许可证](LICENSE)

</details>

## About

BaruSikoZako(ZakoBak) backs up Minecraft: Java Edition worlds. Take a snapshot before updating a modpack, schedule regular backups, and verify the snapshot you want to restore before opening the world again.

## Features

- Full backups capture the entire world. Incremental backups store changes since the previous backup. Differential backups store changes since a selected full backup.
- The standard build compresses snapshots with Zstd and keeps them on the same machine.
- Run backups on demand or on a schedule, list snapshots, and verify archives.
- Exclude files through `world.gitignore` and `local.gitignore` files inside the world.

## Download and install

Select a file from the project's download page, or download the artifact for your Minecraft version and loader from a successful GitHub build. [GitHub build artifacts](https://github.com/ZCT-Studio/BaruSikoZako/actions/workflows/release.yml)

1. Select a file for your Minecraft version and loader. Use Java 17 for Minecraft 1.20.1, Java 21 for 1.21.x, or Java 25 for 26.x.
2. **Dedicated server:** Place the JAR in the server's `mods` folder. Players can join without this mod installed on their clients. **Singleplayer:** Place the JAR in your client instance's `mods` folder and enable commands in the world.
3. Start with the standard build, which includes Zstd. The `_L` variant needs a separately installed backend. Add the matching `barusikozako_api` only if another mod uses the integration API.

With version 1.0.2 of the main mod, API, and Zstd backend, call `BackupService.snapshotDirectory(snapshotRef)` for the snapshot’s current absolute local path or `snapshotFolderName(snapshotRef)` for just the folder name. After a successful backup, get the reference from `TaskResult.BackupCommitted.snapshot()`. The result is empty if the snapshot or local directory is unavailable. Query again after a migration or deletion.

## Your first backup and restore

1. Run `/barusikozako backup`. Once it completes, run `/barusikozako list` to find the snapshot ID.
2. Run `/barusikozako verify <UTC timestamp>` and wait for verification to pass.
3. To roll back, run `/barusikozako rollback <UTC timestamp>`. Restart a dedicated server, or leave and reopen the world in singleplayer. Recovery replaces the active save before the world loads. On their first join during the first session after rollback, each player receives a notice with the world name and the backup time in UTC.

Use Tab to complete timestamps for `restore`, `verify`, and `delete`. Add `fromuuid <uuid>` to select by snapshot UUID, or `fullname <timestamp--uuid>` to paste the complete snapshot folder name. Use `/barusikozako task` to list recent tasks, then use `task <time>` or `cancel <time>`. Both accept `fromid <task-id>` for direct selection.

Run server commands as an administrator or from the console. Omit the leading `/` in the console.

### Other useful commands

| Command | Purpose |
| --- | --- |
| `/barusikozako status` | Check if the backup service is available |
| `/barusikozako full` | Create a full backup with Zstd |
| `/barusikozako task <task ID>` | Check the progress of a backup or verification task |

## Configuration and backup location

| Path | Purpose |
| --- | --- |
| `config/barusikozako/server.yml` | Set the backup interval in `schedule.intervalSeconds`. Use `0` to disable scheduled backups. |
| `config/barusikozako/barusikozako_backendzstd.yml` | Configure compression, backup mode, and full-backup rotation. |
| `config/barusikozako/world.gitignore` | Define file exclusions for world backups. |
| `barusikozako/<world>/<backend>/` | Snapshots are stored separately from the world. Zstd uses names like `2026-11-23T06-19-24.645272600Z--<uuid>` under `snapshots/` and migrates UUID-only directories before the next backup write. |

Restart the server or client after editing the YAML settings.

[Changelog](CHANGELOG.md) · [MPL-2.0 License](LICENSE)
