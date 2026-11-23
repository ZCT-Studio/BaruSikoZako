<p align="center"><img src="https://raw.githubusercontent.com/ZCT-Studio/BaruSikoZako/refs/heads/main/resource/.public/banner.jpg" alt="BaruSikoZako 项目横幅 / Project banner" width="100%"></p>

> BaruSikoZako creates and verifies world backups on Minecraft Java Edition servers. Keep snapshots on a schedule, make one before updating your modpack, and choose a verified recovery point when you need to roll the world back.

## Features

- Create full, incremental, or differential backups with the bundled Zstd backend.
- Run backups manually or set a schedule in `config/barusikozako/server.yml`.
- List and verify snapshots. By default, backups are stored locally in `barusikozako/<world>/<backend>/`.
- Exclude files with `world.gitignore` and per-directory `local.gitignore` rules.

## Installation

Choose a file that matches your Minecraft version and loader, then put the standard JAR in the server's `mods` folder and use the Java runtime required by the file. Check the version listing for available Fabric, Quilt, Forge, and NeoForge builds. Players can join without installing the mod on their clients.

The standard JAR includes the Zstd backend. If you choose the `_L` build, install a compatible backend separately. Add the matching `barusikozako_api` only if another mod uses the integration API.

Singleplayer backups and restores have been tested on Forge (1.20.1, 1.21.10, 1.21.11, 26.1.2 and 26.2), NeoForge 1.21.1, Fabric and Quilt 1.21.11, and Fabric, Quilt, Forge and NeoForge 26.3. Install the matching JAR in your client instance's `mods` folder. Use the backup and verification commands in a world with commands enabled. To restore in singleplayer, queue a restore, exit the world and reopen it. The request survives a client restart.

## Quick start

1. Run `/barusikozako backup` as an administrator or use the server console.
2. Run `/barusikozako list` to find a snapshot ID and `/barusikozako verify <snapshot ID>` to check its contents.
3. To roll back, run `/barusikozako restore <snapshot ID>` and restart the server. The restore runs before the world loads.

[Documentation](https://github.com/ZCT-Studio/BaruSikoZako#readme) · [Report an issue](https://github.com/ZCT-Studio/BaruSikoZako/issues)

<details>
<summary>简体中文</summary>

## 简介

BaruSikoZako 为 Minecraft Java 版服务端提供世界备份。你可以定时保存快照，更新整合包前手动备份，或在需要回滚时选择并校验恢复点。

- 内置 Zstd 后端支持全量、增量和差异备份。
- 在 `config/barusikozako/server.yml` 中设置定时备份。快照默认保存在服务端的 `barusikozako/<world>/<backend>/`。
- 支持使用 `world.gitignore` 和各目录下的 `local.gitignore` 排除文件。

## 安装和使用

单人备份与回滚已在 Forge 1.20.1／1.21.10／1.21.11／26.1.2／26.2、NeoForge 1.21.1、Fabric／Quilt 1.21.11，以及 26.3 的 Fabric、Quilt、Forge、NeoForge 验证。将对应 JAR 放入客户端实例的 `mods` 目录，并在允许指令的世界里使用。单人回滚请先执行恢复指令，再退出世界并重新进入。重启客户端会保留请求。

1. 按 Minecraft 版本和加载器选择下载文件。请在版本页确认 Fabric、Quilt、Forge 或 NeoForge 的对应产物。
2. 将默认版 JAR 放入服务端的 `mods` 目录，使用所下载文件要求的 Java 启动。默认版自带 Zstd，玩家客户端无需安装。选择 `_L` 版时需另装兼容后端，只有其他模组调用公开接口时才需要安装对应的 `barusikozako_api`。
3. 管理员可执行 `/barusikozako backup` 创建备份，执行 `/barusikozako list` 查看快照 ID，再用 `/barusikozako verify <快照 ID>` 校验。
4. 需要恢复时，执行 `/barusikozako restore <快照 ID>` 并重启服务器。恢复会在世界加载前执行。

</details>
