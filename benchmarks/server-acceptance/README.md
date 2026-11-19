# 全平台实服验收

先用小地图验证全量、增量、完整校验和回滚，再使用具有代表性的大地图补齐实服证据。每个 Minecraft 与平台组合独立开服，版本由实际运行日志确认。

平台验收统一跳过上游 Alpha 构建，排除 Minecraft 1.21.9、26.1、26.1.1 的所有平台，以及 Folia 1.20.1、NeoForge 1.20.1、SpongeForge 和 SpongeNeo。26.1 系列仅保留 26.1.2。

- [兼容矩阵](matrix.md) 和 [机器可读证据](matrix.json) 按组合记录状态。大地图和完整故障矩阵是独立发布门。上游可用、预览加载与备份能力有各自的状态。
- FML26 的 Forge 和 NeoForge 分别记录。开发构建的证据包括工作区 HEAD 与实际 JAR 的 SHA-256；当前工作区有待提交改动。
- [小地图脚本](small-world.py) 创建本地测试 Git 仓库的独立 worktree。在世界计分板标记为 41 时运行全量，改为 77 并运行增量。直接核对第二个提交记录引用全量基线，分别完整校验，再改成 99。重启必须在 Minecraft 打开世界前报告恢复提交，且实际读回 77。
- [故障保护脚本](fault-guards.py) 使用已通过小地图的实例，在后台复制开始后取消请求，并独立复现 NTFS 拒绝写入 `scoreboard.dat`。之前已提交记录的摘要必须一致。它只覆盖两个故障点。
- [旧版插件对照脚本](bukkit-small.py) 单独记录 Bukkit 预览加载和计分板写入故障。加 `--world-save-probe` 可运行[直接调用 Bukkit 保存的对照](bukkit-world-save/README.md)。省略 `--preview` 可运行纯上游对照。
- 大地图使用测试 Git 仓库的固定提交签出。源存档 DataVersion 4903 对应 26.2。26.3 路径先在自己 worktree 内由官方服务端升级；26.1 系列及更旧版本需要各自的匹配地图。真实客户端入口使用原版客户端和 Mojang 元数据，服务端绑定回环，只放行本次试验账号。

## 复跑

先用官方安装器准备独立运行时缓存，保留各自的官方安装元数据及已接受的 EULA。脚本只复用安装器输出、运行库和启动文件。每个实例的地图均由 Minecraft 生成或由 Git 签出。

```powershell
$targets = @('fabric-26.3','quilt-26.3')
$java = 'C:/Program Files/Java/jdk-25/bin/java.exe'
$seed = '<本地测试存档 Git 仓库>'
$revision = '<固定基线提交>'
$runtime = '.gradle'

& ./benchmarks/server-acceptance/build-installed.ps1 -RuntimeCache $runtime -Targets $targets
python benchmarks/server-acceptance/run-installed.py --runtime-cache $runtime --java $java `
  --fixture-repo $seed --fixture-revision $revision --output .gradle/acceptance-runs/small-repeat @targets
python benchmarks/server-acceptance/catalog.py --output .gradle/acceptance-report `
  --runs .gradle/acceptance-runs/small-repeat
```

已准备的缓存目录布局为 `loader26-smoke/<26.3 loader>`、`plugin26/<platform>-<旧 26.x>`、`fml-range/<forge 或 neoforge>-<旧 26.x>` 和 `pre26-runtime/<platform>-<1.x>`。`run-installed.py` 保存每一组完整命令、退出状态证据和日志。单实例的 `small-world.py --help` 也可指定任意独立安装路径。使用 `--existing-world` 时同批仅接受同一个游戏版本。真实客户端需另外提供 `--client-fixture`、`--client-cache` 和 `--client-version-dir`。滚动 tick 百分位来自游戏自身的 `tick query`。1.21.10 和 1.21.11 可另用 `--paused-empty` 验证原版空服暂停，需要在日志里观察到两次实际使用空服捕获入口。

验收默认省略保存协调耗时阈值，使用产品默认值 0。可以用 `--main-thread-budget 1` 单独复现显式启用阈值时的拒绝，或用 `--main-thread-budget 0` 验证显式禁用。150 ms 拒绝样本与默认配置试验分别保留。

Fabric 26.3 的小地图对照中，缺省和显式 0 均完成全量、增量、完整校验和离线恢复。配置 1 ms 的一组在实际协调耗时 24 ms 后拒绝备份，仓库没有新增提交。复跑指令和日志在 `.gradle/acceptance-runs/save-limit-modes`。

## NeoForge 26.1 热修复版本的历史合包实验

以 26.1.2 构建为样本，仅在测试 JAR 的本体、API 和嵌套 Zstd 的 TOML 内将 Minecraft 范围改为 `[26.1,26.1.2]`，加载器下界设为 `26.1.0.19-beta`，保持所有类文件原样。实服在进入世界前即发生以下结果：

| 实际运行组合 | 合包样本 | 原因 |
| --- | --- | --- |
| 26.1 / NeoForge 26.1.0.19-beta | FAIL | MixinExtras 0.5.3 将数组型 `@Redirect.at` 当作单个 `AnnotationNode` 读取，抛出 `ClassCastException` |
| 26.1.1 / NeoForge 26.1.1.15-beta | FAIL | 同一启动回滚钩子和相同异常 |
| 26.1.2 / NeoForge 26.1.2.127 | PASS | 对照通过小地图全量、增量、完整校验和离线回滚 |

类文件对照显示本体只有 `DedicatedStartupMixin.class` 不同，API 和独立 Zstd 项目类逐字节相同。测试实例在 `.gradle/acceptance-runs/neo-merged-test`，每组的 `seed.log` 保存启动异常。正式开发构建沿用按目标编译的启动回滚钩子。

反方向以 **26.1 构建**为样本。原样安装到 26.1.1 或 26.1.2 时，本体、API 和嵌套 Zstd 的 `[26.1]` 声明均触发 NeoForge 依赖检查。对照样本仅将三处 Minecraft 范围改为 `[26.1,26.1.2]`，加载器下界 `26.1.0.19-beta` 和所有类文件保持原样。

| 实际游戏与加载器 | 原样安装 26.1 包 | 元数据实验包 |
| --- | --- | --- |
| 26.1 / NeoForge 26.1.0.19-beta | 既有构建已通过 | 小地图全量、增量、完整校验、启动前回滚和重启通过 |
| 26.1.1 / NeoForge 26.1.1.15-beta | 启动拒绝 | 小地图全量、增量、完整校验、启动前回滚和重启通过 |
| 26.1.2 / NeoForge 26.1.2.127 | 启动拒绝 | 小地图全量、增量、完整校验、启动前回滚和重启通过 |

26.1.2 实测可以执行 26.1 产物中使用单个 `@At` 的启动回滚钩子。反方向的数组型注解在旧运行时失败。反向实验的产物和实服记录位于 `.gradle/acceptance-build/neoforge-26.1-forward` 和 `.gradle/acceptance-runs/neo-26.1-forward`。

## CI 分层

- PR 在托管 runner 运行共享模块和恢复回归，以及验收脚本的语法检查。
- 维护者从默认分支手动执行指定组合。自托管 Windows runner 需要标签 `baru-acceptance`，在仓库变量中配置 `ZAKO_RUNTIME_CACHE`、`ZAKO_FIXTURE_GIT`、`ZAKO_FIXTURE_REVISION` 及 `ZAKO_JAVA25`。作业重用正式运行时缓存，独立测试目标有各自的实例目录。
- 候选版模式先遍历全部现有安装目标，并执行 `--release-check`。当前自动化只完整接入小地图流程。任一适用组合缺少大地图往返证据或存在非 PASS 项，门禁返回失败。真实客户端、大地图和全故障路径仍需纳入候选作业的自动调度。

## 仍待解决的适配

- 1.20.1、1.21.1、1.21.10、1.21.11 有 Java 25 的 Fabric／Quilt 和 Forge 开发入口。NeoForge 1.20.1 已排除。Bukkit 系和 Sponge 的上游构建存在性与项目验收相互独立。详见[旧版适配](../../private/agents/docs/legacy-server.md)。
- 26.x 当前在线捕获按文件稳定性检查制备暂存视图。同步保存协调耗时继续记录，默认不限时。准备阶段的超时和写入失败依然阻止捕获。恢复并读回测试标记的证据只覆盖本次数据及保存路径。
- Paper / Purpur 预览已观察到写入失败后 future 正常完成。正式插件须验证区块、玩家、实体、POI 及共享数据的写入结果与隔离能力。Folia 还需要跨区域的所有权和保存完成证据。CraftBukkit、Spigot 和三个 Sponge 实现要分别验收存储和启动行为。
- 插件离线恢复可评估由外部启动引导器独占受管理的世界根。引导器先查锁和未决事务，恢复成功后才启动未经修改的官方 JAR，失败即退出。需要实测竞争启动、目录切换崩溃后恢复，以及直接绕开引导器的部署限制。这个入口只能解决恢复阶段，在线捕获仍需独立的保存证明。

Forge 旧版使用官方安装器的输出。将各版本安装到 `pre26-runtime/forge-<Minecraft>` 后，可将目标如 `forge-1.20.1` 直接传给上面的构建和小地图命令。已排除的 Forge 1.21.9 停服诊断记录曾使用小地图脚本的 `--single-start --paused-empty`，在首次启动完成备份和校验后继续验证停服与回滚。停服超时会保留此前已验证的阶段和失败记录。

## CraftBukkit 与 Spigot 26.3

使用上游 BuildTools #201 和官方 26.3 描述 #4664 执行 `java -jar BuildTools.jar --nogui --rev 26.3 --compile CRAFTBUKKIT,SPIGOT`。两个输出均在隔离工作目录、Java 25 和本地 Git worktree 小地图上独立启动。安装同一份 Bukkit 兼容预览，`barusikozako status` 报告在线捕获关闭，`barusikozako backup` 与 `barusikozako restore` 明确返回 `UNSUPPORTED`。启动日志显示三维度采用 `world`、`world_nether` 和 `world_the_end` 三个目录，与此前 Paper 三维度处于同一个根目录的观察不同。输出 JAR 的 SHA-256 及本地日志对应路径见矩阵 JSON。 [上游构建工具](https://hub.spigotmc.org/jenkins/job/BuildTools/) · [官方 26.3 版本描述](https://hub.spigotmc.org/versions/26.3.json)
