# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio
"""Independently parse, safely extract and hash a committed full snapshot."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('snapshot', type=Path)
parser.add_argument('--work', type=Path, default=Path('.gradle/competitors'))
parser.add_argument('--output', type=Path, default=Path('benchmarks/best-effort-independent-check.json'))
parser.add_argument('--java', type=Path, default=Path('C:/Program Files/Java/jdk-25/bin/java.exe'))
args = parser.parse_args()
snapshot = args.snapshot.resolve()
args.work.mkdir(parents=True, exist_ok=True)
destination = Path(tempfile.mkdtemp(prefix='independent-restore-', dir=args.work)).resolve()
entries = [json.loads(line) for line in (snapshot / 'manifest.jsonl').read_text(encoding='utf-8').splitlines()][1:]
expected = {}
for item in entries:
    parts = item['path']
    if not parts or any(part in ('', '.', '..') or any(c in part for c in '/\\:') for part in parts):
        raise ValueError('Unsafe manifest path')
    path = destination.joinpath(*parts)
    if not path.resolve().is_relative_to(destination) or path in expected:
        raise ValueError('Escaping or duplicate manifest path')
    if item['type'] not in ('file', 'directory'):
        raise ValueError('Unsupported manifest entry')
    expected[path] = item

cache = Path.home() / '.gradle/caches/modules-2/files-2.1/com.github.luben/zstd-jni/1.5.7-28'
jars = list(cache.rglob('zstd-jni-1.5.7-28.jar'))
if not jars:
    raise FileNotFoundError('Build-verified zstd-jni jar not found')
decoder = Path('benchmarks/DecodeZstd.java').resolve()
command = [str(args.java), '--enable-native-access=ALL-UNNAMED', '-cp', str(jars[0]),
           str(decoder), str(snapshot / 'data.tar.zst')]
started = time.monotonic()
process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
seen = set()
total = 0
file_count = 0
try:
    with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
        for member in archive:
            parts = PurePosixPath(member.name).parts
            if not parts or any(part in ('', '.', '..', '/') or any(c in part for c in '\\:') for part in parts):
                raise ValueError('Unsafe TAR path')
            path = destination.joinpath(*parts)
            if path in seen or path not in expected or not path.resolve().is_relative_to(destination):
                raise ValueError('Unexpected, duplicate or escaping TAR entry')
            item = expected[path]
            if member.isdir() and item['type'] == 'directory':
                path.mkdir(parents=True, exist_ok=True)
            elif member.isfile() and item['type'] == 'file' and member.size == item['size']:
                path.parent.mkdir(parents=True, exist_ok=True)
                digest = hashlib.sha256()
                source = archive.extractfile(member)
                if source is None:
                    raise ValueError('TAR file has no payload')
                with source, path.open('xb') as target:
                    while chunk := source.read(1024 * 1024):
                        digest.update(chunk)
                        target.write(chunk)
                if digest.hexdigest() != item['sha256'] or path.stat().st_size != member.size:
                    raise ValueError('Extracted file digest or length differs')
                total += member.size
                file_count += 1
            else:
                raise ValueError('Unsupported TAR type or size')
            seen.add(path)
    while process.stdout.read(64 * 1024):
        pass
except BaseException:
    process.kill()
    process.wait()
    raise
finally:
    process.stdout.close()
stderr = process.stderr.read()
if process.wait() != 0:
    raise RuntimeError('Zstd decoder failed: ' + stderr.decode('utf-8', errors='replace')[-1000:])
if seen != set(expected):
    raise ValueError('TAR did not contain every manifest entry')
result = {'tool': 'Python tarfile and hashlib with zstd-jni decoder', 'snapshot_id': snapshot.name,
          'files': file_count, 'logical_bytes': total, 'sha256_match': True,
          'extract_and_hash_seconds': round(time.monotonic() - started, 3)}
args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
print(json.dumps(result), flush=True)
