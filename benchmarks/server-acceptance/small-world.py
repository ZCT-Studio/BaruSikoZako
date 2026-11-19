# SPDX-License-Identifier: MPL-2.0
# Copyright (c) 2026 ZCT Studio

"""Full and incremental console round trip on an isolated server."""

import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
from pathlib import Path
import queue
import re
import shutil
import subprocess
import sys
import threading
import time


def git(directory, *args):
    return subprocess.check_output(["git", "-C", str(directory), *args], text=True).strip()


def trial(server, command, label, actions):
    with (server / f"{label}.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        lines = queue.Queue()

        def pump():
            with process.stdout:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    lines.put(re.sub(r"\x1b\[[0-9;]*m", "", line).rstrip())
            lines.put(None)

        def expect(pattern, seconds=180):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                try:
                    line = lines.get(timeout=max(0, deadline - time.monotonic()))
                except queue.Empty:
                    break
                if line is None:
                    raise RuntimeError(f"{label} exited before {pattern}. See {log.name}")
                if match := re.search(pattern, line):
                    print(server.name, label, line, flush=True)
                    return match
            raise TimeoutError(f"{label}: {pattern}. See {log.name}")

        input_lock = threading.Lock()

        def send(command):
            with input_lock:
                process.stdin.write(command + "\n")
                process.stdin.flush()

        reader = threading.Thread(target=pump)
        reader.start()
        try:
            actions(expect, send)
            send("stop")
            if process.wait(timeout=120) != 0:
                raise RuntimeError(f"{label}: exit {process.returncode}. See {log.name}")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            reader.join()
            process.stdin.close()


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    sys.stdout.reconfigure(errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("server", "java", "base", "product", "api", "fixture-repo"):
        parser.add_argument(f"--{arg}", required=True, type=lambda path: Path(path).resolve())
    parser.add_argument("--backend", type=lambda path: Path(path).resolve(),
                        help="Install a separately packaged backend with the no-backend host.")
    parser.add_argument("--fixture-revision", required=True)
    parser.add_argument("--use-existing-world", action="store_true")
    parser.add_argument("--paused-empty", action="store_true",
                        help="Wait for vanilla empty-server suspension before testing backup.")
    parser.add_argument("--single-start", action="store_true",
                        help="Seed and exercise backups in one server lifetime. Useful when investigating shutdown.")
    parser.add_argument("--platform", required=True, choices=("fabric", "quilt", "forge", "neoforge"))
    parser.add_argument("--loader", required=True)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--main-thread-budget", type=int,
                        help="Override the save coordination threshold in milliseconds. Defaults to the server setting.")
    parser.add_argument("--launcher", required=True, help="Server launcher filename or relative @argument-file path.")
    parser.add_argument("--client-fixture", type=lambda path: Path(path).resolve(),
                        help="Location of the genuine Windows client launcher helper.")
    parser.add_argument("--client-cache", type=lambda path: Path(path).resolve(),
                        help="Local Mojang asset and library cache.")
    parser.add_argument("--client-version-dir", type=lambda path: Path(path).resolve(),
                        help="Directory with the matching original client JAR and Mojang metadata.")
    parser.add_argument("--client-warmup-seconds", type=int, default=15)
    args = parser.parse_args()
    client_options = (args.client_fixture, args.client_cache, args.client_version_dir)
    if any(client_options) and (not all(client_options) or args.minecraft not in ("1.21.11", "26.2", "26.3")):
        parser.error("Provide all client paths and select Minecraft 1.21.11, 26.2 or 26.3.")
    if args.client_warmup_seconds < 0:
        parser.error("Client warm-up must be zero or positive.")
    if args.paused_empty and (args.client_fixture or args.minecraft in ("1.20.1", "1.21.1")):
        parser.error("Empty-server suspension requires a version with vanilla suspension and no test client.")
    if args.single_start and args.use_existing_world:
        parser.error("Single-start trials require a new small world.")
    server, base = args.server, args.base
    if server.exists():
        parser.error("Server destination must be new.")
    if not re.search(r"(?m)^eula=true\s*$", (base / "eula.txt").read_text(encoding="utf-8")):
        parser.error("Runtime source must have existing EULA acceptance.")
    server.mkdir(parents=True)
    (server / "mods").mkdir()
    # Only reuse installation caches and launch artifacts. The save always comes from Git or Minecraft.
    for name in ("libraries", ".fabric", ".cache", "versions"):
        if (base / name).is_dir():
            shutil.copytree(base / name, server / name)
    for file in base.iterdir():
        if file.is_file() and (file.name in ("eula.txt", "server.jar", "quilt-server-launcher.properties",
                                             args.launcher) or file.name.endswith("-shim.jar")):
            shutil.copyfile(file, server / file.name)
    for file in (args.product, args.api, args.backend):
        if file is not None:
            shutil.copyfile(file, server / "mods" / file.name)

    fixture = server / "fixture"
    detach = ("--detach",) if args.use_existing_world else ("--no-checkout", "--detach")
    git(args.fixture_repo, "worktree", "add", *detach, str(fixture), args.fixture_revision)
    if not args.use_existing_world:
        git(fixture, "read-tree", "--empty")
    elif not (fixture / "world/level.dat").is_file():
        raise RuntimeError("Requested fixture revision has no world/level.dat")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\n"
        "white-list=true\nlevel-name=fixture/world\nlevel-seed=2612026\nview-distance=2\nsimulation-distance=2\n"
        + ("pause-when-empty-seconds=1\n" if args.paused_empty else ""),
        encoding="utf-8",
    )
    launcher = None
    if args.client_fixture:
        module_spec = importlib.util.spec_from_file_location("real_client", args.client_fixture.resolve())
        launcher = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(launcher)
    whitelist = [{"uuid": str(launcher.profile_id()), "name": launcher.NAME}] if launcher else []
    (server / "whitelist.json").write_text(json.dumps(whitelist) + "\n", encoding="utf-8")
    config = server / "config/barusikozako"
    config.mkdir(parents=True)
    capture_config = "capture.enabled: true\ncapture.preparationMillis: 60000\n"
    if args.main_thread_budget is not None:
        capture_config += f"capture.mainThreadBudgetMillis: {args.main_thread_budget}\n"
    (config / "server.yml").write_text(capture_config, encoding="utf-8")
    loader_pattern = {
        "fabric": r"Loading Minecraft {game} with Fabric Loader {loader}(?:\s|$)",
        "quilt": r"Loading Minecraft {game} with Quilt Loader {loader}(?:\s|$)",
        "forge": r"Forge mod loading, version {loader}, for MC {game}(?:\s|$)",
        "neoforge": r"NeoForge mod loading, version {loader}, for MC {game}(?:\s|$)",
    }[args.platform].format(game=re.escape(args.minecraft), loader=re.escape(args.loader))
    launcher_args = [args.launcher] if args.launcher.startswith("@") else ["-jar", args.launcher]
    launch = [str(args.java), "-Dfile.encoding=UTF-8", "-Xmx1536m" if args.use_existing_world else "-Xmx768m",
              *(["-Dterminal.jline=false", "-Dterminal.ansi=false"] if args.platform == "forge" else []),
              *launcher_args, "nogui"]
    result = {"platform": args.platform, "minecraft": args.minecraft, "loader": args.loader,
              "projectHead": git(Path.cwd(), "rev-parse", "HEAD"),
              "worktreeDirty": bool(git(Path.cwd(), "status", "--porcelain")),
              "productSha256": sha256(server / "mods" / args.product.name),
              "apiSha256": sha256(server / "mods" / args.api.name),
              "map": "git-world" if args.use_existing_world else "small",
              "mainThreadConfiguredLimitMillis": args.main_thread_budget,
              "stages": {}}
    stages = result["stages"]
    if args.backend is not None:
        result["backendSha256"] = sha256(server / "mods" / args.backend.name)

    @contextmanager
    def connected_client(expect, send):
        if launcher is None:
            yield None
            return
        with (server / "client.log").open("w", encoding="utf-8") as log:
            client = launcher.launch(args.java, server, args.client_version_dir / "mojang_minecraft_info.json",
                                     args.client_cache, args.client_version_dir / "minecraft-client.jar", args.port, log,
                                     expected_version=args.minecraft)
            try:
                joined = expect(re.escape(launcher.NAME) + r" (joined the game|lost connection:.*)$", 220)
                if joined.group(1) != "joined the game":
                    raise RuntimeError(joined.string)
                # Keep join-time chunk loading separate from the steady-state test.
                result["clientWarmupSeconds"] = args.client_warmup_seconds
                time.sleep(args.client_warmup_seconds)
                if client.poll() is not None:
                    raise RuntimeError("Client exited during the warm-up")
                send("tick query")
                result["onlineTickBaseline"] = expect(r"Percentiles:").string
                result["clientType"] = "Mojang client"
                yield launcher.NAME
                if client.poll() is not None:
                    raise RuntimeError("Client exited during the backup trial")
                send("list")
                result["clientAfterBackups"] = expect(r"There are 1 of .* " + re.escape(launcher.NAME)).string
            finally:
                if client.poll() is None:
                    client.terminate()
                    try:
                        client.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        client.kill()
                        client.wait()

    def ready(expect):
        expect(loader_pattern)
        expect(r"Starting minecraft server version " + re.escape(args.minecraft) + r"(?:\s|$)")
        expect(r"Done \([\d.]+s\)")
        stages["START"] = "PASS"
        expect("BaruSikoZako services ready")
        stages["LOAD"] = "PASS"

    def seed(expect, send):
        ready(expect)
        seed_score(expect, send)

    def seed_score(expect, send):
        send("scoreboard objectives add zako_accept dummy")
        expect("Created new objective")
        set_score(expect, send, 41)

    def set_score(expect, send, value):
        send(f"scoreboard players set ProbeMarker zako_accept {value}")
        expect(rf"Set .*ProbeMarker to {value}$")

    def take(expect, send, mode):
        finished = threading.Event()

        def sample_ticks():
            while not finished.wait(4):
                send("tick query")

        sampler = threading.Thread(target=sample_ticks)
        if args.client_fixture:
            sampler.start()
        try:
            send("barusikozako " + mode)
            log = expect(r"Backup committed ([a-f0-9-]{36}).*?(\d+) ms.*?(\d+) archive bytes"
                         r"|Backup operation failed|Request rejected:|Task cancelled\.|Commit outcome is uncertain", 300)
            if log.group(1) is None:
                raise RuntimeError(log.string)
        finally:
            finished.set()
            if args.client_fixture:
                sampler.join()
        snapshot = log.group(1)
        record = config / "repository/snapshots" / snapshot / "commit"
        lines = record.read_text(encoding="utf-8").splitlines()
        result[mode] = {"id": snapshot, "archiveBytes": int(log.group(3)),
                        "taskMillis": int(log.group(2)), "commitSha256": sha256(record)}
        return lines

    def verify(expect, send, mode):
        snapshot = result[mode]["id"]
        send("barusikozako verify " + snapshot)
        log = expect(r"Full verification passed for " + snapshot + r" \((\d+) entries, (\d+) bytes\)", 180)
        result[mode].update(verifiedEntries=int(log.group(1)), verifiedBytes=int(log.group(2)))

    def backup(expect, send):
        ready(expect)
        if args.single_start:
            seed_score(expect, send)
        if args.paused_empty:
            result["emptyPauseObserved"] = expect(r"Server empty for \d+ seconds, pausing", 30).string
        with connected_client(expect, send) as player:
            take(expect, send, "full")
            if player:
                send(f"execute at {player} run setblock ~2 ~ ~ minecraft:gold_block")
                edit = expect(r"Changed the block at \(?(-?\d+), (-?\d+), (-?\d+)\)?")
                result["onlineEdit"] = [int(value) for value in edit.groups()]
            set_score(expect, send, 77)
            delta = take(expect, send, "incremental")
        if result.get("clientAfterBackups"):
            stages["ONLINE"] = "PASS"
        if "incremental" not in delta or result["full"]["id"] not in delta:
            raise RuntimeError("Second point did not commit as an incremental of the full point")
        if args.paused_empty:
            log = (server / "backup.log").read_text(encoding="utf-8", errors="replace")
            result["emptyPauseCaptures"] = len(re.findall(r"capture prepared: .*edge EMPTY_PAUSE", log))
            if result["emptyPauseCaptures"] != 2:
                raise RuntimeError("Expected both backups to use the empty-server capture edge")
        stages["BACKUP"] = "PASS"
        verify(expect, send, "full")
        verify(expect, send, "incremental")
        stages["VERIFY"] = "PASS"
        set_score(expect, send, 99)
        send("barusikozako restore " + result["incremental"]["id"])
        expect("Restore queued for " + result["incremental"]["id"])

    def restored(expect, send):
        # Loader logs can precede the recovery hook, so use ordered records from the on-disk log below.
        expect("Offline restore committed for " + result["incremental"]["id"])
        stages["RESTORE"] = "PASS"
        expect(r"Starting minecraft server version " + re.escape(args.minecraft) + r"(?:\s|$)")
        expect(r"Done \([\d.]+s\)")
        expect("BaruSikoZako services ready")
        send("scoreboard players get ProbeMarker zako_accept")
        expect(r"ProbeMarker has 77")
        if "onlineEdit" in result:
            x, y, z = result["onlineEdit"]
            send(f"forceload add {x} {z}")
            expect(r"force loaded|already marked for force loading")
            for _ in range(12):
                send(f"execute if block {x} {y} {z} minecraft:gold_block")
                check = expect(r"Test passed|Test failed|That position is not loaded")
                if check.group() == "Test passed":
                    result["restoredOnlineBlock"] = True
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Recovered world does not contain the online edit")
        stages["RESTART"] = "PASS"

    phase = "seed"
    try:
        if not args.single_start:
            trial(server, launch, phase, seed)
            git(fixture, "add", "-f", "--", "world")
            result["fixtureTree"] = git(fixture, "write-tree")
            result["worldBytes"] = sum(file.stat().st_size for file in (fixture / "world").rglob("*") if file.is_file())
        phase = "backup"
        trial(server, launch, phase, backup)
        phase = "restore"
        trial(server, launch, phase, restored)
        result["restoredScore"] = 77
        if not args.single_start:
            git(fixture, "restore", "--worktree", "--", "world")
    except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
        result["failure"] = {"phase": phase, "detail": str(failure)}
        if isinstance(failure, subprocess.TimeoutExpired):
            result["failure"]["reason"] = "SHUTDOWN_TIMEOUT"
        raise
    finally:
        log_file = server / "backup.log"
        log_text = log_file.read_text(encoding="utf-8", errors="replace") if log_file.is_file() else ""
        result["saveSamples"] = [dict(mainThreadMillis=int(save.replace(",", "")), copyMillis=int(copy.replace(",", "")))
                                 for save, copy in re.findall(
                                     r"capture prepared: save ([\d,]+) ms, background copy ([\d,]+) ms", log_text)]
        result["mainThreadBudgetRejectionsMillis"] = [
            int(elapsed) for elapsed in re.findall(r"World save preparation took (\d+) ms on the game thread", log_text)]
        if args.client_fixture:
            result["tickQueryWindows"] = [line for line in log_text.splitlines() if "Percentiles:" in line]
        (server / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
