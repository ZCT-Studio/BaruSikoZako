# 世界备份排除规则

世界备份启动后，从 `config/barusikozako/world.gitignore` 读取 UTF-8 规则。新文件不存在时先尝试重命名原来的 `save.gitignore`，都不存在时生成只有注释的模板。已存在的 `world.gitignore` 优先。当前只备份世界，游戏根目录不会参与扫描。

世界内的 `local.gitignore` 从已捕获的读取视图取得。每份文件的规则以所在目录为基准，只作用于该目录下的条目。全局规则先判定，同文件内最后一条匹配规则优先，较深目录的局部规则可覆盖较浅层的普通规则。父目录被排除后，位于该目录里的规则和子项例外不会打开它。[Gitignore 规范](https://git-scm.com/docs/gitignore#_pattern_format)

```gitignore
# config/barusikozako/world.gitignore
/mod-cache/
*.tmp
!important.tmp
```

已注册的模组排除优先于普通规则，用户不能通过 `!` 重新包含命中条目。一次备份使用同一份已登记规则和捕获视图，任务途中注册或撤销仅影响后续任务。实际参与匹配的规则、来源及快照 ID 会记入服务器日志，执行备份的玩家收到带来源的规则提示，较长的列表会提示查阅完整日志。

## 调用 API

已绑定的公共 `BackupService` 可调用 `registerWorldExclusion("*.tmp")`，来源取自绑定的集成 ID。关闭返回的 `AutoCloseable` 可注销，关闭绑定或主机也会解除其注册。同一来源和规则的重复注册会被拒绝。模组登记仅接受一个正向排除模式。

不直接依赖 API 时，可反射调用模组的 `io.github.zct_studio.barusikozako.NativeEntrypoints.registerBackupExclusion(String scope, String source, String pattern)` 或 Agent 的 `io.github.zct_studio.barusikozako.agent.AgentRuntime` 同名方法。传入 `WORLD`、模组标识和规则，调用方持有并关闭返回的句柄。Bukkit 插件可从插件管理器获取 `barusikozako` 桥，再调用 `registerWorldExclusion(Plugin owner, String pattern)`，桥会在插件停用时注销。

## 归档与恢复

新生产快照使用 `.worlds/<存档名>/<相对路径>`。模组端按当前打开的世界选择仓库。Agent 在线捕获当前原版世界，启动器的离线备份可覆盖受管理的 `worlds/` 容器及其中分离的多世界目录。各世界的全局规则独立匹配。单纯查看、校验和暂存提取旧快照仍沿用后端格式，平台的回滚入口要求新的世界命名空间。

离线回滚完整校验后才切换目录。排除文件不会自动混入恢复后的活动世界，恢复前的完整世界（包括被排除文件）由原有事务保存在回滚副本中，日志记录位置。世界规则若排除启动所需的 `level.dat`，该快照会被启动前恢复检查拒绝。集成客户端未提供启动前回滚入口。
## 构建和对照测试

`:engine:gitignore` 保留上游 JGit 的匹配类及其直接依赖，通过源码裁剪和包重定位避免引入 JGit 其他功能、SLF4J 及国际化资源。相比在整个应用里启用 Shadow `minimize()`，独立源码子集的输入和改动可直接审查。Core 将该组件的类和声明纳入自身 JAR，后端 SPI 和公共业务 API 不暴露 JGit 类型。

```powershell
.\gradlew.bat :engine:gitignore:check :engine:core:check :backends:zstd:check
.\gradlew.bat :engine:gitignore:gitIgnoreCompatibility '-PcomparisonJgitClasspath=<JGit JAR>;<SLF4J API JAR>'
```

对照测试调用 `git check-ignore --no-index`，需将 Git 加入 PATH。测试范围是单文件规则的路径匹配，包括目录继承。项目只读取显式的两份配置文件，Git 工作树中的嵌套配置、跟踪状态和全局排除规则不参与备份。

升级前核对 [JGit 上游源码](https://github.com/eclipse-jgit/jgit/tree/v7.8.1.202611041320-r)，同时运行 [Gitignore 规范](https://git-scm.com/docs/gitignore) 的对照测试。发行包中的 `META-INF/barusikozako/gitignore/` 保留原始许可证、版权及源码坐标。

