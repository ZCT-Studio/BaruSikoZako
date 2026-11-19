# Minecraft 备份项目对照

## 官方版本与运行入口

固定 Minecraft 26.3、Fabric Loader 0.20.1、Java 25、Windows x64 NTFS。版本、文件 SHA-512、发布元数据及实际 JAR 声明保存在 [competitors-metadata.json](competitors-metadata.json)。仅从官方公开版本下载，未修改第三方 JAR。Textile Backup 的当前 Fabric 版本不覆盖 26.3，排除在这个测试组外。

| 项目 | 固定版本与依赖 | 备份与恢复语义 |
| --- | --- | --- |
| FastBack | 0.35.0+26.3.0，Fabric API、权限 API；上游要求本机 Git 与 Git LFS | Git 历史中的首次快照及后续增量。公开恢复命令复制到新目录，专用服务端需要停服手动切换。测量含 Git 对象、索引和 LFS 对象。 [版本](https://modrinth.com/mod/fastback/version/0.35.0%2B26.3.0) · [恢复文档](https://pcal43.github.io/fastback/usage.html) |
| Just Enough Backups | 1.2.0.10+26.3，JAR 要求 Fabric API >=0.160.5+26.3 | 全量、相对最近快照的 partial、相对全量的 differential。恢复异步准备后停服，重启时应用。 [项目](https://modrinth.com/mod/justenoughbackups-jeb) · [配置](https://jeb.frandm.dev/configuration.html) |
| Chronos Backups | 1.1.3+26.x+fabric，Fabric API | ZIP 或目录。默认裁剪区块，并按黑名单过滤。公开文档未确认同等完整性校验及自动恢复；须在独立目录解包并核对覆盖范围后再纳入恢复比较。 [项目](https://modrinth.com/mod/chronos-backups) |
| Textile Backup | 最新 Fabric 稳定版本 3.1.3-1.21，Fabric API 和 Cloth Config | 独立压缩归档，恢复需重启。在 26.3 组不适用。 [项目](https://modrinth.com/mod/textile_backup) |

实机准入探测使用 Git 2.51.0.windows.1、Git LFS 3.7.0、Fabric API 0.166.2+26.3。同一停服世界分别复制到独立目录。每组一次，控制台探测仅用于确认启动和命令注册。原始输出见 [competitors-startup.json](competitors-startup.json)。

## 复跑

将元数据中标为 primary 的 Fabric 26.3 发布 JAR 和 Fabric API JAR 下载到 .gradle/competitors。脚本会重新校验 SHA-512。传入已停服的独立 Fabric 冒烟目录，其中包含已接受的 EULA、已缓存的服务端依赖和基准世界。

```powershell
python -B benchmarks/competitor-smoke.py $stoppedFixture $java25
```

启动探测未测量备份或恢复性能。正式对照应从相同停服世界恢复各组初始状态，按相同顺序重放客户端与区块编辑负载。将生产者的提交回执与实际可读取的恢复点绑定，每个点离线恢复后比较相对路径集合和逐文件 SHA-256。裁剪默认配置保留为单独实验组，只有完整恢复范围等价的结果可比较体积。使用 Windows 卷或物理设备计数记录磁盘 I/O，并与进程 I/O 分开。运行前固定压缩、保留、后台上传、启动 JVM 配置及恢复流程。每个场景分别记录重复次数、P50/P95、失败率与原始样本。空服测试区分正常 tick 和确认原版已暂停。

当前没有同负载备份、恢复、保留和故障数据，因此没有速度或空间排名。待验证的可借鉴方向包括 JEB 将恢复准备与停服切换分离、FastBack 显式暴露回收与保留，以及 Chronos 明确区分数据裁剪带来的体积收益。
