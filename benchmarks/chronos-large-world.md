# BaruSikoZako 与 Chronos 对照基准

状态：进行中。静态压缩首轮完成。在线备份、连续备份及游戏性能尚待执行，不用于判断产品胜负。

## 共同输入

MapMC 的 Manhattan Community Board 5 Java 世界，公开下载用于测试和构建。原始 ZIP 为 81,776,286 字节，SHA-256 为 `85d7a7148c4e56728aa32ad99207b615b4434f1bf369327dd2da516a4f9b9c2f`。解压后 135,206,565 字节，包含 20 个 ZIP 条目。该世界是地图生成数据，不能代表长期运行的生存服。 [来源](https://github.com/xspring1982/mapmc-worlds)

静态测试将全部普通文件按路径排序装入同一份 135,239,680 字节的 TAR，SHA-256 `c1041782dc7da4a41e04df2d39a57df27ba9ec9c793bd746103de5be6c471`。两种算法读取完全相同的 TAR。Windows x64，Intel Core i5-13420H，内存 16,891,371,520 字节，Java 25。六轮交替执行，每轮生成单独文件，未清理文件系统缓存。

| 压缩方式 | 参数 | 压缩后字节 | 耗时 P50 | 耗时 P95 |
| --- | --- | ---: | ---: | ---: |
| Zstd | zstd-jni 1.5.7-28，等级 3 | 80,486,381 | 577.5 ms | 731 ms |
| ZIP | JDK Deflate，等级 6，TAR 作为一个 ZIP 条目 | 81,787,866 | 4,547 ms | 5,020 ms |

P95 使用最近秩法，六次样本取最大值。十二个文件均独立解压并与输入 TAR 的 SHA-256 对比一致。此测试对比压缩算法及容器，Chronos 实际 ZIP 按文件组织，因此其结果不能直接替代产品实服测量。压缩时长包括读取和写入，缓存及测试先后顺序影响结果。逐轮数据见 [static-compression.csv](static-compression.csv)，可执行代码见 [StaticCompression.java](StaticCompression.java) 与 [VerifyStatic.java](VerifyStatic.java)。

## 在线备份

待执行。将同一份世界先在 Minecraft 26.3 中完成一次版本升级并安全停服，再复制为各产品初始世界。对齐负载并分别验证恢复及 SHA-256。Chronos 关闭裁剪与黑名单，记录实际覆盖范围。

## 连续备份

待执行。每个产品执行 10 至 20 代，记录变化负载、每代耗时、文件系统实际分配字节与各恢复点的完整性。Chronos 的独立归档和 BaruSikoZako 的增量链分别记录恢复依赖。

## 游戏性能

待执行。记录在线真实客户端、正常空服与原版暂停空服下的 MSPT P95/P99、CPU、磁盘物理 I/O、采样窗口和基线。当前不对这些指标作推断。
