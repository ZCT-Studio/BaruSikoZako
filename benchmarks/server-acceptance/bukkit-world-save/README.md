# Bukkit World.save() 故障对照

本探针在测试世界的 PDC 写入标记，直接调用公用 `World.save()`。每轮使用 Windows NTFS 和隔离 Git worktree 小地图。先在正常保存中写入 41，再锁定 `level.dat` 阻止替换，在同一 API 下尝试写入 77。实服退出后核对磁盘 SHA-256，再启动实服读回标记。探针 JAR 独立于产品构建。

保存失败成立需要同一轮同时观察到 API 正常返回、实际文件替换异常、旧文件摘要不变及重启读回 41。错误匹配限定在保存命令处理期间，排除随后关服产生的异常记录。CraftBukkit、Spigot、Paper 和 Purpur 的 1.20.1、1.21.1、1.21.10 共 12 组均复现。详见[原始结果](results.json)。

## 复跑

使用官方服务端构建准备缓存目录，包含 `server.jar` 和已接受的 `eula.txt`。CraftBukkit 和 Spigot 由官方 BuildTools 构建。以下命令需要已经由 Paper 启动产生的 Bukkit API 和运行库目录。Java 25 编译器输出 Java 17 字节码。1.20.1 实服用 Java 17，1.21.1 及 1.21.10 实服用 Java 21。

```powershell
$jdk = 'C:/Program Files/Java/jdk-25/bin'
$src = 'benchmarks/server-acceptance/bukkit-world-save'
$build = '.gradle/acceptance-build/bukkit-world-save'
$libraries = '<Paper 安装目录>/libraries'
$classpath = (Get-ChildItem $libraries -Recurse -Filter '*.jar' | ForEach-Object FullName) -join ';'
New-Item -ItemType Directory -Force "$build/classes" | Out-Null
& "$jdk/javac.exe" --release 17 -encoding UTF-8 -cp $classpath -d "$build/classes" "$src/WorldSaveProbe.java"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& "$jdk/jar.exe" --create --file "$build/probe.jar" -C "$build/classes" . -C $src plugin.yml
if ($LASTEXITCODE) { exit $LASTEXITCODE }
python benchmarks/server-acceptance/bukkit-small.py --platform paper --minecraft 1.21.10 --port 26430 `
  --base '<服务端缓存>' --server '<新测试目录>' --java '<Java 可执行文件>' `
  --fixture-repo '<本地测试存档 Git 仓库>' --fixture-revision '<基线提交>' `
  --world-save-probe "$build/probe.jar" --preview '<杂鱼预览 JAR>'
```

单次运行写出 `world-save-result.json` 及三份服务器日志 `seed.log`、`save-fault.log` 和 `reopen.log`。本探针只覆盖 `level.dat` 替换。它不能证明区块、POI、实体、玩家或 SavedData 已排空，也不构成备份支持。

## 可选的启动观察探针

该探针只用于测试 1.21.10 的指定官方构建。它按类文件 SHA-256 绑定观察点，在 JVM 内记录 `level.dat` 替换的重试结果和 `scoreboard.dat` 的 SavedData 异常分支，保留原版执行和原始发行 JAR。未审核的类不绑定，缺少观察信号的故障试验失败。安装杂鱼预览无需安装本探针。

四个 1.21.10 服务端各通过两条独立故障试验。正常保存和重启未触发失败标记，磁盘拒绝写入时两类观察点均报告失败，原文件摘要和重启标记保持在基线。早期探针先后遇到局部变量校验和 Paper/Purpur 隔离类加载限制。最终观察点放在重试返回后，观察方法由独立的 JDK-only 探针 JAR 提供给启动类加载器。 [观察试验原始结果](observer-results.json)

用 Gradle 依赖缓存中已核验的 ASM 9.10.1 编译观察探针。正式产物无需该依赖。

```powershell
$asm = '<已核验的 ASM 9.10.1 JAR>'
$expected = 'ED825D10AB1399C8C0CB669E688CF0C8C82629B4C8399B58352B68E92CA10FCB'
if ((Get-FileHash $asm).Hash -ne $expected) { throw 'Unexpected ASM JAR digest' }
Copy-Item $asm "$build/asm-9.10.1.jar"
New-Item -ItemType Directory -Force "$build/agent-classes","$build/hook-classes" | Out-Null
& "$jdk/javac.exe" --release 17 -encoding UTF-8 -cp $asm -d "$build/agent-classes" "$src/SaveObservationAgent.java"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& "$jdk/javac.exe" --release 17 -encoding UTF-8 -d "$build/hook-classes" "$src/SaveHooks.java"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& "$jdk/jar.exe" --create --file "$build/save-hooks.jar" -C "$build/hook-classes" .
if ($LASTEXITCODE) { exit $LASTEXITCODE }
@('Premain-Class: barusikozako.probe.SaveObservationAgent', 'Class-Path: asm-9.10.1.jar', 'Boot-Class-Path: save-hooks.jar') |
  Set-Content "$build/agent.mf" -Encoding ascii
& "$jdk/jar.exe" --create --file "$build/save-agent.jar" --manifest "$build/agent.mf" -C "$build/agent-classes" .
if ($LASTEXITCODE) { exit $LASTEXITCODE }
```

在前一条复跑命令添加 `--save-agent "$build/save-agent.jar"`。脚本会在测试 JVM 增加 `-javaagent`。把 `save-agent.jar`、`save-hooks.jar` 和 `asm-9.10.1.jar` 放在同一目录，测试插件仍单独安装。保留 `--world-save-probe` 测试世界元数据。省略它可测试计分板写入。两条命令应分别使用新实例和独立端口。
