# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio
import json,statistics,math,pathlib
data=json.loads(pathlib.Path('benchmarks/compression-levels-raw.json').read_text(encoding='utf-8'))
rows=data['samples']
lines=['# 压缩等级基准','',f"共同 TAR {data['tar_bytes']:,} 字节，SHA-256 `{data['tar_sha256']}`。每次为独立 JVM，工作集限制 2 GiB，单次操作限时 900 秒。解压摘要逐轮核对。",'','| 算法 | 等级 | 有效轮次 | 压缩字节 | 压缩 P50 / P95 ms | 解压 P50 / P95 ms | 压缩峰值工作集 MiB |','| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
def quant(v):
 v=sorted(v);return f'{statistics.median(v):.0f} / {v[math.ceil(.95*len(v))-1]:.0f}'
for mode,level in [('zstd',3),('zstd',9),('zstd',15),('zstd',19),('zstd',22),('zip',1),('zip',6),('zip',9)]:
 rs=[r for r in rows if r['mode']==mode and r['level']==level and r.get('verified')]
 if not rs: lines.append(f'| {mode} | {level} | 0 | 待测 | 待测 | 待测 | 待测 |');continue
 status=f'{len(rs)} / 6'+('（进行中）' if len(rs)<6 else '')
 lines.append(f"| {mode} | {level} | {status} | {int(rs[0]['compress']['bytes']):,} | {quant([int(r['compress']['ms']) for r in rs])} | {quant([int(r['decompress']['ms']) for r in rs])} | {max(r['compress']['peak_bytes'] for r in rs)/1048576:.1f} |")
lines+=['','Windows x64，Intel i5-13420H，约 16 GiB 内存，Java 25，zstd-jni 1.5.7-28。ZIP 使用 JDK Deflate，将 TAR 放入单个 ZIP 条目。P95 使用最近秩法。工作集为进程峰值，包含 JVM、原生库与压缩缓冲，不等于堆内存。操作系统缓存未清空。','', '各等级完成六轮前的分位数仅供观察。高等级按用户要求后测。失败和待补试验保留在原始数据。测试仅评估算法。','', '[原始数据](compression-levels-raw.json) · [执行脚本](compression-levels.py) · [Java 测试程序](CompressionLevels.java)']
pathlib.Path('benchmarks/compression-levels.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
