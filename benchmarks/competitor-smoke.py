# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio
"""Boot each pinned public release with a fresh copy of the same stopped world."""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import tempfile
import threading
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("seed", type=Path)
parser.add_argument("java", type=Path)
parser.add_argument("--work", type=Path, default=Path(".gradle/competitors"))
parser.add_argument("--output", type=Path, default=Path(".gradle/competitors/startup.json"))
args = parser.parse_args()
metadata = json.loads(Path(__file__).with_name("competitors-metadata.json").read_text(encoding="utf-8"))
if (args.seed / ".barusikozako-smoke").read_text(encoding="ascii") != "isolated test\n":
    raise ValueError("Use a stopped, isolated Fabric smoke fixture")
args.work.mkdir(parents=True, exist_ok=True)
commands = {"fastback": ["backup help"], "justenoughbackups-jeb": ["jeb list"],
            "chronos-backups": ["chronos"]}
results = []
for project in metadata["projects"]:
    slug = project["project"]
    release = project["fabric_26_3"]
    if release is None:
        results.append({"project": slug, "status": "no_release_for_fabric_26_3"})
        continue
    work = Path(tempfile.mkdtemp(prefix=slug + "-", dir=args.work))
    for name in ("fabric-server-launch.jar", "server.jar", "eula.txt"):
        shutil.copy2(args.seed / name, work)
    for name in ("libraries", ".fabric", "world"):
        shutil.copytree(args.seed / name, work / name, ignore=shutil.ignore_patterns("session.lock"))
    mods = work / "mods"
    mods.mkdir()
    for record in ([release, metadata["fabric_api"]] if "fabric-api" in metadata["jar_declared_dependencies"][slug] else [release]):
        file = next(item for item in record["files"] if item["primary"])
        source = args.work / file["filename"]
        if hashlib.sha512(source.read_bytes()).hexdigest() != file["hashes"]["sha512"]:
            raise ValueError("Pinned jar SHA-512 differs: " + source.name)
        shutil.copy2(source, mods)
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    (work / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nlevel-name=world\npause-when-empty-seconds=0\n", encoding="ascii")
    result = {"project": slug, "release": release["version_number"], "status": "startup_unverified",
              "game": "26.3", "loader": "Fabric", "scope": "startup_and_console_probe"}
    results.append(result)
    with (work / "console.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen([str(args.java.resolve()), "-Xmx2G", "-Dfile.encoding=UTF-8",
                                    "-jar", "fabric-server-launch.jar", "nogui"], cwd=work,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace")
        output = queue.Queue()
        def drain():
            for line in process.stdout:
                log.write(line)
                log.flush()
                output.put(line)
            output.put(None)
        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        try:
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                line = output.get(timeout=max(0.01, deadline-time.monotonic()))
                if line is None:
                    break
                if "Done (" in line and 'For help, type "help"' in line:
                    for command in [*commands[slug], "stop"]:
                        process.stdin.write(command + "\n")
                    process.stdin.flush()
                    result["status"] = "booted"
                    break
            result["exit_code"] = process.wait(timeout=45)
        except (queue.Empty, subprocess.TimeoutExpired):
            result["status"] = "timeout"
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            reader.join(timeout=5)
    output_lines = (work / "console.log").read_text(encoding="utf-8").splitlines()
    result["console_excerpt"] = [line.split("]: ", 1)[-1] for line in output_lines
                                 if "System chat:" in line or "Done (" in line]
    print(slug, result["status"], flush=True)
args.output.write_text(json.dumps(results, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")

