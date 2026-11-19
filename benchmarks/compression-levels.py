# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio
import argparse,csv,gzip,hashlib,json,os,pathlib,shutil,subprocess,sys,time,statistics
sys.path.insert(0,str(pathlib.Path('docs/benchmarks/fabric-26.3-install').resolve()))
from windows_metrics import Metrics
p=argparse.ArgumentParser()
p.add_argument('source',type=pathlib.Path)
p.add_argument('--timeout',type=int,default=900)
p.add_argument('--memory-mib',type=int,default=2048)
args=p.parse_args()
work=pathlib.Path('.gradle/competitors/levels').resolve();work.mkdir(parents=True,exist_ok=True)
tar=work/'input.tar'
if not tar.exists():
 print('Decompressing input gzip',flush=True)
 h=hashlib.sha256()
 with gzip.open(args.source,'rb') as src,tar.open('xb') as out:
  while chunk:=src.read(1024*1024): out.write(chunk);h.update(chunk)
 (work/'input.sha256').write_text(h.hexdigest(),encoding='ascii')
digest=(work/'input.sha256').read_text().strip()
jars=list((pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni/1.5.7-28').rglob('zstd-jni-1.5.7-28.jar'))
java=pathlib.Path('C:/Program Files/Java/jdk-25/bin/java.exe')
source=pathlib.Path('benchmarks/CompressionLevels.java').resolve()
raw=pathlib.Path('benchmarks/compression-levels-raw.json')
rows=json.loads(raw.read_text(encoding='utf-8'))['samples'] if raw.exists() else []
combinations=[('zstd',3),('zip',1),('zstd',9),('zip',6),('zip',9)]
def execute(action,mode,level,target):
 cmd=[str(java),'--enable-native-access=ALL-UNNAMED','-Xmx128m','-XX:MaxDirectMemorySize=64m','-cp',str(jars[0]),str(source),action,mode,str(level),str(tar),str(target)]
 proc=subprocess.Popen(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8',errors='replace')
 started=time.monotonic(); peak=0;reason=None
 while proc.poll() is None:
  try: peak=max(peak,Metrics.sample(proc).peak_rss)
  except OSError: pass
  if peak>args.memory_mib*1048576: reason='memory_limit';proc.kill();break
  if time.monotonic()-started>args.timeout: reason='timeout';proc.kill();break
  time.sleep(.25)
 out,err=proc.communicate()
 if reason or proc.returncode: return {'status':reason or 'error','error':err[-500:],'peak_bytes':peak}
 values=dict(x.split('=',1) for x in out.strip().split())
 return {'status':'ok','peak_bytes':peak,**values}
for repeat in range(6):
 order=combinations if repeat%2==0 else list(reversed(combinations))
 for mode,level in order:
  if any(r['mode']==mode and r['level']==level and r['round']==repeat+1 and r.get('verified') for r in rows): continue
  target=work/('sample.zip' if mode=='zip' else 'sample.zst')
  row={'mode':mode,'level':level,'round':repeat+1}
  c=execute('compress',mode,level,target);row['compress']=c
  if c['status']=='ok':
   v=execute('decompress',mode,level,target);row['decompress']=v
   row['verified']=v.get('sha256')==digest
  else: row['verified']=False
  rows.append(row)
  pathlib.Path('benchmarks/compression-levels-raw.json').write_text(json.dumps({'source_bytes':args.source.stat().st_size,'tar_bytes':tar.stat().st_size,'tar_sha256':digest,'memory_limit_mib':args.memory_mib,'timeout_seconds':args.timeout,'samples':rows},indent=2)+'\n',encoding='utf-8')
  if target.exists(): target.unlink()
  print(mode,level,repeat+1,c['status'],row['verified'],flush=True)
