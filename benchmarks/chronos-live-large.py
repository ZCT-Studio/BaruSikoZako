# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio
"""Compare two isolated 26.3 servers with a real local client."""
import json
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, str(Path('docs/benchmarks/fabric-26.3-install').resolve()))
import real_client
from windows_metrics import Metrics

work = Path('.gradle/competitors').resolve()
work.mkdir(parents=True, exist_ok=True)
root = Path(tempfile.mkdtemp(prefix='live-large-', dir=work))
source = Path('.gradle/competitors/large-seed/world').resolve()
fixture = Path('.gradle/alpha-smoke/server-ua9o98be').resolve()
loom = Path.home() / '.gradle/caches/fabric-loom/26.3'
java = Path('C:/Program Files/Java/jdk-25/bin/java.exe')
cache = Path('D:/Projects/.minecraft_dev')
metadata = json.loads(Path('benchmarks/competitors-metadata.json').read_text(encoding='utf-8'))
force_save = os.environ.get('BARU_FORCE_SAVE') == '1'
baru_only = os.environ.get('BARU_ONLY') == '1'
activity = os.environ.get('BARU_ACTIVITY') == '1'
player_exit = os.environ.get('BARU_PLAYER_EXIT') == '1'
fault = os.environ.get('BARU_FAULT', '')
read_rate = os.environ.get('BARU_READ_RATE', '0')
root.mkdir(parents=True, exist_ok=True)

def make_server(name, world):
    path = root / name
    path.mkdir()
    for item in ('libraries', '.fabric'):
        shutil.copytree(fixture / item, path / item)
    for item in ('fabric-server-launch.jar', 'server.jar', 'eula.txt'):
        shutil.copy2(fixture / item, path / item)
    shutil.copytree(world, path / 'world', ignore=shutil.ignore_patterns('session.lock'))
    (path / 'mods').mkdir()
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    (path / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-name=world\n'
        'white-list=true\nview-distance=2\nsimulation-distance=2\nenable-query=false\nenable-rcon=false\n', encoding='ascii')
    (path / 'whitelist.json').write_text(json.dumps([{'uuid': str(real_client.profile_id()), 'name': real_client.NAME}]), encoding='ascii')
    return path, port

class Server:
    def __init__(self, path):
        self.queue = queue.Queue()
        self.log = (path / 'console.log').open('w', encoding='utf-8')
        self.process = subprocess.Popen([str(java), '--enable-native-access=ALL-UNNAMED', '-Xms512m', '-Xmx2G',
            '-Dfile.encoding=UTF-8', '-Dfabric.installer.server.gameJar=server.jar', '-jar',
            'fabric-server-launch.jar', 'nogui'], cwd=path, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
        def read():
            for line in self.process.stdout:
                self.log.write(line)
                self.log.flush()
                self.queue.put(line.strip())
            self.queue.put(None)
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
    def command(self, text):
        self.process.stdin.write(text + '\n')
        self.process.stdin.flush()
    def wait(self, choices, timeout):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(str(choices))
            line = self.queue.get(timeout=remaining)
            if line is None:
                raise RuntimeError('Server exited before ' + str(choices))
            if any(text.lower() in line.lower() for text in choices):
                return line
    def stop(self):
        if self.process.poll() is None:
            self.command('stop')
            try:
                self.process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.reader.join(timeout=5)
        self.log.close()

def install(path, product):
    if product == 'chronos':
        release = next(x for x in metadata['projects'] if x['project'] == 'chronos-backups')['fabric_26_3']
        for artifact in (release, metadata['fabric_api']):
            file = next(x for x in artifact['files'] if x['primary'])
            shutil.copy2(Path('.gradle/competitors') / file['filename'], path / 'mods')
        (path / 'config').mkdir(exist_ok=True)
        (path / 'config/chronos.toml').write_text('pruneChunks = false\nscheduleBackups = false\ncopyBlacklist = []\ncompressionMethod = "zip"\n', encoding='utf-8')
    else:
        shutil.copy2('platform-fabric/build/libs/barusikozako-fabric-26.3-development-0.1.0-dev.jar', path / 'mods')
        (path / 'config/barusikozako').mkdir(parents=True)
        (path / 'config/barusikozako/server.properties').write_text(
            'schema=1\nverify.contentBytes=107374182400\nverify.archiveBytes=128849018880\n'
            'verify.entries=1000000\nquery.manifestBytes=134217728\ncapture.enabled=true\n'
            f'capture.pauseMillis=150\ncapture.preparationMillis=60000\nbackup.readBytesPerSecond={read_rate}\n'
            'zstd.level=3\nzstd.compressionThreads=1\n', encoding='utf-8')

base, _ = make_server('normalized', source)
server = Server(base)
try:
    server.wait(['Done ('], 240)
finally:
    server.stop()

results = []
for product in (('baru',) if baru_only else ('chronos', 'baru')):
    path, port = make_server(product, base / 'world')
    install(path, product)
    server = Server(path)
    client = None
    result = {'product': product, 'game': '26.3', 'player': 'real client online', 'status': 'starting'}
    results.append(result)
    try:
        server.wait(['Done ('], 240)
        if product == 'baru':
            server.wait(['BaruSikoZako services ready'], 60)
        client_log = (path / 'client.log').open('w', encoding='utf-8')
        client = real_client.launch(java, path, loom / 'mojang_minecraft_info.json', cache,
                                    loom / 'minecraft-client.jar', port, client_log)
        server.wait([real_client.NAME + ' joined the game'], 180)
        time.sleep(15)
        if activity:
            server.command('forceload add 192 192')
            server.wait(['force loaded'], 30)
        server.command('tick query')
        result['baseline_tick_query'] = server.wait(['Percentiles:'], 20)
        before = Metrics.sample(server.process)
        start = time.monotonic()
        server.command('chronos backup' if product == 'chronos' else 'barusikozako backup full')
        queries = []
        next_query = start + 5
        next_edit = start + 1
        next_save = start + 3
        unload_sent = False
        player_exited = False
        capture_started = False
        task_id = None
        fault_sent = False
        stopped_metrics = None
        save_sent = False
        outcome = None
        while time.monotonic() - start < 900:
            now = time.monotonic()
            if not player_exited and client.poll() is not None:
                raise RuntimeError('Real client disconnected during backup')
            if fault and not fault_sent and capture_started and task_id and now >= start + 3:
                if fault == 'cancel':
                    server.command(f'barusikozako cancel "{task_id}"')
                elif fault == 'stop':
                    stopped_metrics = Metrics.sample(server.process)
                    server.command('stop')
                else:
                    raise ValueError('Unknown fault')
                result['fault_sent'] = fault
                fault_sent = True
            if player_exit and not player_exited and now >= start + 3:
                client.terminate()
                client.wait(timeout=30)
                player_exited = True
                result['player_exit_sent'] = True
            if activity and now >= next_edit:
                block = 'stone' if int(now - start) % 4 < 2 else 'gold_block'
                server.command(f'execute at {real_client.NAME} run setblock ~1 ~ ~ minecraft:{block}')
                next_edit = now + 1
            if activity and now >= next_save:
                server.command('save-all')
                next_save = now + 5
            if activity and not unload_sent and now >= start + 5:
                server.command('forceload remove 192 192')
                unload_sent = True
                result['unload_requested'] = True
            if force_save and not save_sent and now >= start + 3:
                server.command('save-all flush')
                save_sent = True
                result['forced_save_sent'] = True
            if now >= next_query:
                server.command('tick query')
                next_query = now + 5
            try:
                line = server.queue.get(timeout=.25)
            except queue.Empty:
                continue
            if line is None:
                if fault == 'stop' and fault_sent:
                    result['status'] = 'stopped_during_capture'
                    outcome = 'server stopped during capture'
                    break
                raise RuntimeError('Server exited during backup')
            if 'Requested task ' in line:
                match = re.search(r'Requested task ([0-9a-f-]{36}:\d+)', line)
                if match:
                    task_id = match.group(1)
            if 'Best-effort background capture started' in line:
                capture_started = True
            if 'Percentiles:' in line:
                queries.append(line)
            if any(x in line.lower() for x in ('backup committed ', 'backup completed in ',
                                               'backup operation failed', 'request rejected', 'task cancelled')):
                outcome = line
                break
        after = stopped_metrics if stopped_metrics is not None else Metrics.sample(server.process)
        result.update({'duration_seconds': round(time.monotonic() - start, 3),
            'cpu_seconds': (after.cpu_ms - before.cpu_ms) / 1000,
            'process_read_bytes': after.read_bytes - before.read_bytes,
            'process_write_bytes': after.write_bytes - before.write_bytes,
            'jvm_lifetime_peak_working_set_bytes': after.peak_rss,
            'tick_queries_during': queries, 'outcome': outcome,
            'status': ('stopped_during_capture' if stopped_metrics else 'cancelled' if fault == 'cancel' and outcome and 'cancelled' in outcome.lower()
                       else 'committed' if outcome and ('committed ' in outcome.lower() or 'completed in ' in outcome.lower()) else 'failed_or_timeout')})
        if stopped_metrics is None:
            server.command('tick query')
            result['after_tick_query'] = server.wait(['Percentiles:'], 20)
        store = path / ('backups' if product == 'chronos' else 'config/barusikozako/repository')
        # Chronos removes its temporary copy asynchronously after publishing. Count
        # only final archives to avoid racing that cleanup.
        final_files = (store / 'world').glob('*.zip') if product == 'chronos' else (store / 'snapshots').rglob('*')
        result['archive_logical_bytes'] = sum(x.stat().st_size for x in final_files if x.is_file())
        if product == 'baru' and result['status'] == 'committed':
            match = re.search(r'Backup committed ([0-9a-f-]{36})', outcome)
            server.command('barusikozako verify ' + match.group(1))
            result['verification'] = server.wait(['Full verification passed', 'Backup operation failed'], 900)
    except Exception as error:
        if result.get('status') != 'committed':
            result['status'] = 'error'
        result['error'] = str(error)
    finally:
        if client and client.poll() is None:
            client.terminate()
            client.wait(timeout=30)
        if client:
            client_log.close()
        server.stop()
        output = (('benchmarks/best-effort-fault-raw.json' if fault else
                   'benchmarks/best-effort-logout-raw.json' if player_exit else
                   'benchmarks/best-effort-activity-raw.json' if activity else
                   'benchmarks/best-effort-live-raw.json') if baru_only else 'benchmarks/chronos-live-large-raw.json')
        output_path = Path(output)
        previous = json.loads(output_path.read_text(encoding='utf-8')) if baru_only and output_path.exists() else []
        output_path.write_text(json.dumps(previous + results, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        print(product, json.dumps(result, ensure_ascii=False), flush=True)
