# BaruSikoZako

## About

BaruSikoZako adds world backups to Minecraft Java Edition servers. Use it to keep regular recovery points or make a manual backup before changing mods, updating the server, or working on the world.

## Features

- **Full, incremental, and differential backups** with Zstd compression and local snapshot storage.
- **Manual and scheduled backups** from the server console or administrator commands, with scheduling in `config/barusikozako/server.yml`.
- **Snapshot verification and recovery**. Check the contents of an archive and queue a restore for the next server startup, before the world loads.
- **Configurable exclusions** using `world.gitignore` and per-directory `local.gitignore` rules.

## Installation

1. In **Files**, select the build for your Minecraft version and loader. Look for the matching Fabric, Quilt, Forge, or NeoForge file.
2. Put the standard JAR in your server's `mods` folder and start the server with Java 25. The standard build includes the Zstd backend. Players do not need to install the mod on their clients.
3. Run `/barusikozako backup` from the server console or as an administrator. Run `/barusikozako list` to find the resulting snapshot ID.

Choose the `_L` variant if you plan to install a compatible backend separately. Add the matching standalone API only if another mod uses the public integration API.

## Common commands

- `/barusikozako backup` starts a backup using the configured mode.
- `/barusikozako verify <snapshot ID>` verifies the selected snapshot.
- `/barusikozako restore <snapshot ID>` queues recovery. Restart the server to apply it.

Snapshots are stored in `config/barusikozako/repository/` by default.

---

## 简体中文

BaruSikoZako 为 Minecraft Java 版服务器提供世界备份。你可以定时保留恢复点，也可以在更新服务器、更换模组或改动世界之前手动备份。

- **备份与校验**支持 Zstd 压缩的全量、增量和差异备份。快照默认保存在服务端的 `config/barusikozako/repository/`。
- **定时与排除**可在 `config/barusikozako/server.yml` 中配置定时备份，并通过 `world.gitignore` 和各目录下的 `local.gitignore` 排除文件。
- **安装**时，在 **Files** 中选择与你的 Minecraft 版本及 Fabric、Quilt、Forge 或 NeoForge 加载器匹配的文件。将默认版 JAR 放入服务器的 `mods` 目录，并使用 Java 25 启动。默认版已内置 Zstd，玩家客户端无需安装。选择 `_L` 版时需另装后端，外部模组调用公开接口时才需加装独立 API。
- **使用**时，管理员可执行 `/barusikozako backup` 开始备份，执行 `/barusikozako list` 查看快照 ID，再用 `/barusikozako verify <快照 ID>` 校验。执行 `/barusikozako restore <快照 ID>` 后重启，恢复会在世界加载前执行。

## Links / 相关链接

[Documentation / 项目文档](https://github.com/ZCT-Studio/BaruSikoZako#readme) · [Issue tracker / 问题反馈](https://github.com/ZCT-Studio/BaruSikoZako/issues)
