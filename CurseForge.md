# BaruSikoZako

Back up your Minecraft: Java Edition world before a modpack update or a big building session. BaruSikoZako can take compressed snapshots manually or on a schedule, verify a backup, and restore the world before it next loads.

## Features

- Full, incremental, and differential backups with the bundled Zstd backend.
- Local snapshots under `barusikozako/<world>/<backend>/` in the server or client instance.
- Scheduled backups through `config/barusikozako/server.yml`.
- File exclusions through `world.gitignore` and directory-specific `local.gitignore` files.

## Installation

1. Under **Files**, choose the build for your Minecraft version and Fabric, Quilt, Forge, or NeoForge loader. Use Java 17 for Minecraft 1.20.1, Java 21 for 1.21.x, or Java 25 for 26.x.
2. **Dedicated server:** Place the JAR in the server's `mods` folder. Players can join without this mod installed on their clients. **Singleplayer:** Place the JAR in your client instance's `mods` folder and enable commands in the world.
3. Start with the standard build, which includes Zstd. The `_L` variant needs a separately installed backend. Add the matching `barusikozako_api` only if another mod uses the integration API.

## Back up and restore

1. Run `/barusikozako backup`. Once it completes, run `/barusikozako list` to find the snapshot ID.
2. Run `/barusikozako verify <snapshot ID>` and wait for verification to pass.
3. To roll back, run `/barusikozako restore <snapshot ID>`. Restart a dedicated server, or leave and reopen the world in singleplayer. Recovery replaces the active save before the world loads.

Run server commands as an administrator or from the console. Omit the leading `/` in the console.

---

## 简体中文

更新整合包或大规模改造世界前，可以用 BaruSikoZako 为 Minecraft Java 版世界留一份快照。默认版内置 Zstd，支持全量、增量和差异备份。快照保存在实例根目录下的 `barusikozako/<存档名>/<后端>/`。需要定时备份时，编辑 `config/barusikozako/server.yml`。可通过 `world.gitignore` 和各目录下的 `local.gitignore` 排除文件。

### 安装

1. 在 **Files** 中选择与你的 Minecraft 版本及 Fabric、Quilt、Forge 或 NeoForge 加载器匹配的文件。Minecraft 1.20.1 使用 Java 17，1.21.x 使用 Java 21，26.x 使用 Java 25。
2. **独立服务器**将 JAR 放入服务器的 `mods` 目录。联机玩家无需在客户端安装本模组。**单人游戏**将 JAR 放入客户端实例的 `mods` 目录，并在允许使用指令的世界中操作。
3. 首次安装选默认版，内含 Zstd。选 `_L` 版时需另装兼容的备份后端。其他模组调用公开接口时，才需要加装匹配的 `barusikozako_api`。

### 备份和恢复

1. 运行 `/barusikozako backup`，等备份完成后用 `/barusikozako list` 找到快照 ID。
2. 运行 `/barusikozako verify <快照 ID>`，确认快照通过完整校验。
3. 想回滚时，运行 `/barusikozako restore <快照 ID>`。独立服务器需要重启，单人游戏需要退出并重新进入存档。恢复会在世界加载前替换当前存档。

服务端命令可由管理员或服务器控制台执行。在控制台输入命令时省略开头的 `/`。

## Links / 相关链接

[User guide / 使用说明](https://github.com/ZCT-Studio/BaruSikoZako#readme) · [Report an issue / 问题反馈](https://github.com/ZCT-Studio/BaruSikoZako/issues)
