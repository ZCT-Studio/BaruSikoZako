import pathlib, shutil, subprocess, queue, threading, time, tempfile, zipfile, json, hashlib, re
root=pathlib.Path('.gradle/competitors').resolve()
seed=pathlib.Path('.gradle/alpha-smoke/server-2ekivk7o').resolve()
meta=json.loads(pathlib.Path('benchmarks/competitors-metadata.json').read_text(encoding='utf-8'))
java='C:/Program Files/Java/jdk-25/bin/java.exe'
results=[]
for product in ('chronos','baru'):
 work=pathlib.Path(tempfile.mkdtemp(prefix='compare-'+product+'-',dir=root))
 for n in ('fabric-server-launch.jar','server.jar','eula.txt'): shutil.copy2(seed/n,work)
 for n in ('libraries','.fabric','world'): shutil.copytree(seed/n,work/n,ignore=shutil.ignore_patterns('session.lock'))
 mods=work/'mods';mods.mkdir()
 if product=='chronos':
  p=next(p for p in meta['projects'] if p['project']=='chronos-backups')
  for record in (p['fabric_26_3'],meta['fabric_api']):
   f=next(f for f in record['files'] if f['primary']);shutil.copy2(root/f['filename'],mods)
  (work/'config').mkdir()
  (work/'config/chronos.toml').write_text('pruneChunks = false\nscheduleBackups = false\ncopyBlacklist = []\ncompressionMethod = "zip"\n',encoding='utf-8')
 else:
  shutil.copy2('platform-fabric/build/libs/barusikozako-fabric-26.3-development-0.1.0-dev.jar',mods)
  (work/'config/barusikozako').mkdir(parents=True)
  (work/'config/barusikozako/server.properties').write_text('schema=1\nverify.contentBytes=2147483648\nverify.archiveBytes=3221225472\nverify.entries=100000\nquery.manifestBytes=134217728\ncapture.enabled=true\ncapture.pauseMillis=150\n',encoding='utf-8')
 (work/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25579\nonline-mode=false\npause-when-empty-seconds=0\n',encoding='ascii')
 q=queue.Queue()
 proc=subprocess.Popen([java,'--enable-native-access=ALL-UNNAMED','-Xmx2G','-Dfile.encoding=UTF-8','-jar','fabric-server-launch.jar','nogui'],cwd=work,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
 lines=[]
 def read():
  for line in proc.stdout: lines.append(line);q.put(line)
 threading.Thread(target=read,daemon=True).start()
 def wait(pattern,seconds=120):
  until=time.monotonic()+seconds
  while time.monotonic()<until:
   line=q.get(timeout=max(.1,until-time.monotonic()))
   if pattern.lower() in line.lower(): return line
  raise TimeoutError(pattern)
 try:
  wait('Done (')
  time.sleep(3)
  start=time.monotonic()
  proc.stdin.write(('chronos backup' if product=='chronos' else 'barusikozako backup full')+'\n');proc.stdin.flush()
  if product=='chronos': receipt=wait('backup complete',120)
  else: receipt=wait('Backup committed ',120)
  elapsed=time.monotonic()-start
  proc.stdin.write('stop\n');proc.stdin.flush();proc.wait(timeout=60)
  files=[p for p in (work/('backups' if product=='chronos' else 'config/barusikozako/repository')).rglob('*') if p.is_file()]
  result={'product':product,'seconds':elapsed,'receipt':receipt.strip(),'bytes':sum(p.stat().st_size for p in files),'files':[str(p.relative_to(work)) for p in files],'fixture':str(work)}
  print(json.dumps(result,ensure_ascii=False),flush=True);results.append(result)
 except Exception as e:
  print(product,repr(e),flush=True)
 finally:
  if proc.poll() is None: proc.kill();proc.wait()
  (work/'comparison.log').write_text(''.join(lines),encoding='utf-8')
pathlib.Path('benchmarks/chronos-comparison-raw.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')

